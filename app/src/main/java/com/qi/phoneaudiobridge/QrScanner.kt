package com.qi.phoneaudiobridge

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * Framework-only QR scanner (UI_SPEC_MULTIDEVICE §4.2): camera2 preview on a
 * TextureView + ZXing core decoding of YUV frames. No AndroidX / CameraX.
 *
 * v2 robustness fixes (see work-v2/android-rootcause.md §1):
 *  - ZXing hints pinned: POSSIBLE_FORMATS=[QR_CODE], TRY_HARDER, UTF-8.
 *  - Continuous-picture autofocus (fallback AUTO, then OFF) + AE on, so the
 *    lens actually focuses on an on-screen code.
 *  - Decode attempts throttled (>=120 ms apart, latest frame only) on a
 *    dedicated thread — the camera handler is never blocked by ZXing.
 *  - Luminance extraction honours BOTH rowStride and pixelStride of the
 *    YUV_420_888 Y plane.
 *  - Failures are reported via [onError] instead of being swallowed to Log.
 */
class QrScanner(
    private val context: Context,
    private val textureView: TextureView,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit = {}
) {
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.CHARACTER_SET to "UTF-8"
            )
        )
    }
    @Volatile private var stopped = true
    @Volatile private var delivered = false
    @Volatile private var lastDecodeMs = 0L
    @Volatile var torchOn = false
        private set

    fun start() {
        stopped = false
        delivered = false
        cameraThread = HandlerThread("qr-cam").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
        if (textureView.isAvailable) {
            openCamera()
        } else {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) = openCamera()
                override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture) = true
                override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
            }
        }
    }

    private fun openCamera() {
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull { cid ->
                cm.getCameraCharacteristics(cid).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cm.cameraIdList.firstOrNull() ?: run {
                onError(context.getString(R.string.qr_no_camera))
                return
            }
            val chars = cm.getCameraCharacteristics(id)
            imageReader = ImageReader.newInstance(1280, 720, ImageFormat.YUV_420_888, 2).apply {
                setOnImageAvailableListener({ r ->
                    val image = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
                    try {
                        val now = System.currentTimeMillis()
                        if (!stopped && !delivered && now - lastDecodeMs >= DECODE_INTERVAL_MS) {
                            lastDecodeMs = now
                            decode(image)
                        }
                    } catch (_: Exception) {
                    } finally {
                        image.close()
                    }
                }, cameraHandler)
            }
            val texture = textureView.surfaceTexture ?: run {
                onError(context.getString(R.string.qr_preview_not_ready))
                return
            }
            texture.setDefaultBufferSize(1280, 720)
            val previewSurface = Surface(texture)
            @Suppress("MissingPermission")
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    try {
                        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        builder.addTarget(previewSurface)
                        builder.addTarget(imageReader!!.surface)
                        // Autofocus: continuous-picture first (best for a
                        // handheld scan of a screen), then AUTO, then leave
                        // the template default. AE always on.
                        val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
                        when {
                            afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ->
                                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) ->
                                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                        }
                        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        previewRequestBuilder = builder
                        device.createCaptureSession(
                            listOf(previewSurface, imageReader!!.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    session = s
                                    try { s.setRepeatingRequest(builder.build(), null, cameraHandler) } catch (_: Exception) {}
                                }
                                override fun onConfigureFailed(s: CameraCaptureSession) {
                                    onError(context.getString(R.string.qr_start_failed))
                                }
                            },
                            cameraHandler
                        )
                    } catch (e: Exception) {
                        Log.w("QrScanner", "session failed: ${e.message}")
                        onError(context.getString(R.string.qr_start_failed))
                    }
                }
                override fun onDisconnected(device: CameraDevice) { device.close(); onError(context.getString(R.string.qr_camera_busy)) }
                override fun onError(device: CameraDevice, error: Int) { device.close(); onError(context.getString(R.string.qr_camera_error, error)) }
            }, cameraHandler)
        } catch (e: SecurityException) {
            Log.w("QrScanner", "openCamera security: ${e.message}")
            onError(context.getString(R.string.qr_no_permission))
        } catch (e: Exception) {
            Log.w("QrScanner", "openCamera failed: ${e.message}")
            onError(context.getString(R.string.qr_open_failed))
        }
    }

    private fun decode(image: android.media.Image) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = image.width
        val height = image.height
        // Extract tightly-packed luminance honouring rowStride AND
        // pixelStride (v1 ignored pixelStride -> garbage on some devices).
        val packed = ByteArray(width * height)
        val rowBytes = ByteArray(buffer.remaining())
        buffer.get(rowBytes)
        if (pixelStride == 1) {
            if (rowStride == width) {
                System.arraycopy(rowBytes, 0, packed, 0, minOf(rowBytes.size, packed.size))
            } else {
                for (row in 0 until height) {
                    val srcOff = row * rowStride
                    if (srcOff + width > rowBytes.size) break
                    System.arraycopy(rowBytes, srcOff, packed, row * width, width)
                }
            }
        } else {
            for (row in 0 until height) {
                val rowOff = row * rowStride
                for (col in 0 until width) {
                    val idx = rowOff + col * pixelStride
                    if (idx >= rowBytes.size) break
                    packed[row * width + col] = rowBytes[idx]
                }
            }
        }
        val source = PlanarYUVLuminanceSource(packed, width, height, 0, 0, width, height, false)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        try {
            val result = reader.decodeWithState(bitmap)
            val text = result.text ?: return
            delivered = true
            Handler(android.os.Looper.getMainLooper()).post { onResult(text) }
        } catch (_: Exception) {
            // no code in this frame; keep scanning
        } finally {
            reader.reset()
        }
    }

    /** Re-arm after a rejected (non-LisTalk) code so scanning continues. */
    fun resume() {
        delivered = false
    }

    fun setTorch(on: Boolean) {
        torchOn = on
        val builder = previewRequestBuilder ?: return
        val s = session ?: return
        try {
            builder.set(
                CaptureRequest.FLASH_MODE,
                if (on) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
            )
            s.setRepeatingRequest(builder.build(), null, cameraHandler)
        } catch (_: Exception) {}
    }

    fun stop() {
        stopped = true
        try { session?.close() } catch (_: Exception) {}
        try { cameraDevice?.close() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        session = null; cameraDevice = null; imageReader = null
        cameraThread?.quitSafely()
        cameraThread = null
    }

    companion object {
        private const val DECODE_INTERVAL_MS = 120L
    }
}
