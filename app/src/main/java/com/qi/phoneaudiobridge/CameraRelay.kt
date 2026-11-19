package com.qi.phoneaudiobridge

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import org.json.JSONObject
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Camera relay capture + H.264 encode (wave 4, plan §B.1) — camera2
 * feeding a MediaCodec AVC encoder's input Surface directly (no
 * CameraX: its AARs cannot enter the manual aapt2/kotlinc pipeline).
 *
 * The encoded access units go to [Listener.onEncodedFrame]; the
 * AudioEngine packetizes and sends them on its own video thread, so
 * nothing in here ever touches the audio path. Frames are delivered
 * sensor-raw: rotation/mirroring are reported in the config JSON
 * (from [CameraCharacteristics]) and applied on the PC side, never
 * baked in here.
 *
 * Encoder per the plan's parameter table: 720p30 @ 3 Mbps VBR by
 * default (1080p @ 8 Mbps on request), 1 s I-frame interval plus
 * on-demand IDR via [requestKeyframe], High Profile 4.0 when the
 * encoder accepts it, hardware encoders only (the OMX.google software
 * encoder is never selected — it would burn the CPU the audio path
 * needs and still miss real-time).
 */
class CameraRelay(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        /** Stream parameters are final; [configJson] is the VIDEO_CONFIG
         * body (its "csd" field already base64'd) and [csdAnnexB] the raw
         * SPS+PPS stream. Always delivered before the first frame. */
        fun onConfig(configJson: JSONObject, csdAnnexB: ByteArray)
        /** One encoded access unit. [captureUs] is in the
         * System.nanoTime()/1000 monotonic domain (the MediaCodec
         * presentation timestamp for Surface input is the camera
         * capture timestamp) — the same domain Protocol.nowUs() uses,
         * which is what A/V sync on the PC will be computed in. */
        fun onEncodedFrame(au: ByteArray, keyframe: Boolean, captureUs: Long)
        fun onError(reason: String)
    }

    @Volatile private var running = false
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var codec: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var outputThread: Thread? = null
    @Volatile private var configDelivered = false

    fun start(quality: String) {
        if (running) return
        running = true
        configDelivered = false
        val t = HandlerThread("sr-camera")
        t.start()
        cameraThread = t
        cameraHandler = Handler(t.looper)
        cameraHandler?.post { openCameraAndEncoder(quality) }
    }

    fun stop() {
        if (!running) return
        running = false
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { camera?.close() } catch (_: Exception) {}
        camera = null
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        try { encoderSurface?.release() } catch (_: Exception) {}
        encoderSurface = null
        try { cameraThread?.quitSafely() } catch (_: Exception) {}
        cameraThread = null
        cameraHandler = null
    }

    /** Ask the encoder for an immediate IDR (sync frame). The caller
     * (AudioEngine) rate-limits this to >= 500 ms per the plan. */
    fun requestKeyframe() {
        val c = codec ?: return
        try {
            val p = Bundle()
            p.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            c.setParameters(p)
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission") // the engine checks CAMERA before start()
    private fun openCameraAndEncoder(quality: String) {
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val (cameraId, chars) = pickCamera(cm)
                ?: run { listener.onError("no camera available"); stop(); return }
            val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
            val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val target = if (quality == "1080p") Size(1920, 1080) else Size(1280, 720)
            val size = pickSize(map?.getOutputSizes(MediaCodec::class.java) ?: emptyArray(), target)
                ?: run { listener.onError("no encoder output size"); stop(); return }

            val encoderInfo = pickEncoder()
                ?: run { listener.onError("no hardware AVC encoder"); stop(); return }
            val created = createEncoder(encoderInfo.name, size, quality)
                ?: run { listener.onError("encoder configure failed"); stop(); return }
            codec = created
            encoderSurface = created.createInputSurface()
            created.start()
            startOutputPump(created, size, sensorOrientation, facing)

            cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    startCaptureSession(device)
                }
                override fun onDisconnected(device: CameraDevice) {
                    device.close(); if (camera === device) camera = null
                }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close(); if (camera === device) camera = null
                    listener.onError("camera error $error")
                    stop()
                }
            }, cameraHandler)
        } catch (e: Exception) {
            Log.w(TAG, "camera relay start failed", e)
            listener.onError("start failed: ${e.message}")
            stop()
        }
    }

    private fun startCaptureSession(device: CameraDevice) {
        val surface = encoderSurface ?: return
        try {
            @Suppress("DEPRECATION")
            device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    try {
                        val req = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        req.addTarget(surface)
                        // plan §B.1: lock AE to a fixed 30 fps target so the
                        // encoder sees a steady cadence (no fps ramping).
                        pickFpsRange(device)?.let { req.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                        s.setRepeatingRequest(req.build(), null, cameraHandler)
                    } catch (e: Exception) {
                        listener.onError("capture start failed: ${e.message}")
                        stop()
                    }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    listener.onError("capture session configure failed")
                    stop()
                }
            }, cameraHandler)
        } catch (e: Exception) {
            listener.onError("capture session failed: ${e.message}")
            stop()
        }
    }

    private fun startOutputPump(mc: MediaCodec, size: Size, sensorOrientation: Int, facing: Int) {
        val t = Thread({
            val info = MediaCodec.BufferInfo()
            while (running) {
                val idx = try { mc.dequeueOutputBuffer(info, 10_000) } catch (_: Exception) { break }
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        deliverConfig(mc.outputFormat, size, sensorOrientation, facing)
                    }
                    idx >= 0 -> {
                        try {
                            val buf = mc.getOutputBuffer(idx)
                            if (buf != null && info.size > 0 &&
                                (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                val au = ByteArray(info.size)
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                buf.get(au)
                                val ptsUs = if (info.presentationTimeUs > 0) info.presentationTimeUs
                                            else System.nanoTime() / 1000
                                listener.onEncodedFrame(
                                    au,
                                    (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0,
                                    ptsUs)
                            }
                        } catch (_: Exception) {
                        } finally {
                            try { mc.releaseOutputBuffer(idx, false) } catch (_: Exception) {}
                        }
                    }
                }
            }
        }, "sr-encoder-out")
        t.isDaemon = true
        outputThread = t
        t.start()
    }

    private fun deliverConfig(format: MediaFormat, size: Size, sensorOrientation: Int, facing: Int) {
        if (configDelivered) return
        val csd = extractCsd(format) ?: return
        configDelivered = true
        val front = facing == CameraCharacteristics.LENS_FACING_FRONT
        val json = JSONObject()
            .put("codec", "h264")
            .put("width", size.width)
            .put("height", size.height)
            .put("fps", TARGET_FPS)
            .put("rotation", sensorOrientation)
            .put("mirrored", front)
            .put("lensFacing", if (front) "front" else "back")
            .put("csd", Base64.encodeToString(csd, Base64.NO_WRAP))
        listener.onConfig(json, csd)
    }

    /** SPS+PPS from the encoder's output format, as one Annex-B stream
     * (start codes ensured — most encoders include them, some don't). */
    private fun extractCsd(format: MediaFormat): ByteArray? {
        val sps = format.getByteBuffer("csd-0") ?: return null
        val pps = format.getByteBuffer("csd-1") ?: return null
        fun annexB(src: ByteBuffer): ByteArray {
            val b = src.duplicate()
            val raw = ByteArray(b.remaining())
            b.get(raw)
            return if (raw.size >= 4 && raw[0].toInt() == 0 && raw[1].toInt() == 0 &&
                (raw[2].toInt() == 1 || (raw[2].toInt() == 0 && raw[3].toInt() == 1))) raw
            else byteArrayOf(0, 0, 0, 1) + raw
        }
        return annexB(sps) + annexB(pps)
    }

    private fun pickCamera(cm: CameraManager): Pair<String, CameraCharacteristics>? {
        var fallback: Pair<String, CameraCharacteristics>? = null
        for (id in cm.cameraIdList) {
            val chars = try { cm.getCameraCharacteristics(id) } catch (_: Exception) { continue }
            if (fallback == null) fallback = id to chars
            if (chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                return id to chars
            }
        }
        return fallback
    }

    /** Closest supported encoder size to [target]: prefer the same
     * aspect family, then the nearest pixel count. The CONFIG reports
     * the size actually picked — never the requested one. */
    private fun pickSize(sizes: Array<Size>, target: Size): Size? {
        if (sizes.isEmpty()) return null
        val targetArea = target.width * target.height
        val targetAspect = target.width.toDouble() / target.height
        return sizes.minWithOrNull(
            compareBy<Size> { abs(it.width.toDouble() / it.height - targetAspect) > 0.05 }
                .thenBy { abs(it.width * it.height - targetArea) }
        )
    }

    private fun pickFpsRange(device: CameraDevice): Range<Int>? {
        val chars = try {
            (context.getSystemService(Context.CAMERA_SERVICE) as CameraManager)
                .getCameraCharacteristics(device.id)
        } catch (_: Exception) { return null }
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: return null
        return ranges.firstOrNull { it.upper == TARGET_FPS && it.lower == TARGET_FPS }
            ?: ranges.filter { it.upper == TARGET_FPS }.maxByOrNull { it.lower }
            ?: ranges.maxByOrNull { it.upper }
    }

    /** Hardware AVC encoders only; the OMX.google.* software encoder is
     * explicitly skipped (plan §B.1 hardware-encode requirement). */
    private fun pickEncoder(): MediaCodecInfo? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        var fallback: MediaCodecInfo? = null
        for (info in list.codecInfos) {
            if (!info.isEncoder) continue
            if (MediaFormat.MIMETYPE_VIDEO_AVC !in info.supportedTypes) continue
            if (info.name.startsWith("OMX.google.")) continue
            if (Build.VERSION.SDK_INT >= 29 && info.isHardwareAccelerated) return info
            if (fallback == null) fallback = info
        }
        return fallback
    }

    private fun createEncoder(name: String, size: Size, quality: String): MediaCodec? {
        val bitrate = if (quality == "1080p") 8_000_000 else 3_000_000
        fun buildFormat(withProfile: Boolean): MediaFormat {
            val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.width, size.height)
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            f.setInteger(MediaFormat.KEY_FRAME_RATE, TARGET_FPS)
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            if (Build.VERSION.SDK_INT >= 21) {
                f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }
            if (withProfile && Build.VERSION.SDK_INT >= 21) {
                f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                f.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4)
            }
            // lowest-latency hints; unknown keys are ignored by encoders
            // that don't know them, and configure() is retried without
            // the extras if it throws.
            try { f.setInteger(MediaFormat.KEY_PRIORITY, 0) } catch (_: Exception) {}
            try { f.setInteger("low-latency", 1) } catch (_: Exception) {}
            return f
        }
        val mc = try { MediaCodec.createByCodecName(name) } catch (_: Exception) { return null }
        for (format in listOf(buildFormat(true), buildFormat(false))) {
            try {
                mc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                return mc
            } catch (_: Exception) {
                try { mc.reset() } catch (_: Exception) {}
            }
        }
        try { mc.release() } catch (_: Exception) {}
        return null
    }

    companion object {
        private const val TAG = "CameraRelay"
        private const val TARGET_FPS = 30
    }
}
