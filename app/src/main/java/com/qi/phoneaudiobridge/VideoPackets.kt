package com.qi.phoneaudiobridge

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Camera-relay wire layer (wave 4, plan §B.2): VIDEO_DATA fragmentation
 * and reassembly. Pure logic — no Android imports — so the JVM harness
 * (tools/TestVideo.kt) pins the exact byte layout and loss behaviour;
 * VideoPackets.cs mirrors this file semantics-for-semantics.
 *
 * A VIDEO_DATA payload is a 10-byte subheader + up to [MAX_FRAG_DATA]
 * bytes of one H.264 access unit:
 *
 *   offset 0, 4 B: frameId (per stream, starts at 0, +1 per frame)
 *   offset 4, 2 B: fragIndex (0-based)
 *   offset 6, 2 B: fragCount
 *   offset 8, 1 B: flags — bit0 keyframe, bit1 last frag of the frame
 *   offset 9, 1 B: codec (0 = h264; the protocol header's audio codec
 *                  bits are NOT used for video types)
 *
 * The whole datagram (16-byte protocol header included) never exceeds
 * [MAX_DATAGRAM_BYTES], staying clear of path-MTU fragmentation and far
 * inside the existing 4096-byte receive buffer. The frame's capture
 * timestamp rides the protocol header's timestamp field.
 */
object VideoPackets {
    const val MAX_DATAGRAM_BYTES = 1200
    const val SUBHEADER_SIZE = 10
    const val MAX_FRAG_DATA = MAX_DATAGRAM_BYTES - Protocol.HEADER_SIZE - SUBHEADER_SIZE // 1174

    const val FLAG_KEYFRAME = 0x01
    const val FLAG_FRAME_END = 0x02

    /** fragCount sanity bound: 2048 frags x 1174 B ~ 2.4 MB per AU. */
    const val MAX_FRAGS = 2048

    const val CODEC_H264 = 0

    data class FragHeader(
        val frameId: Int,
        val fragIndex: Int,
        val fragCount: Int,
        val keyframe: Boolean,
        val frameEnd: Boolean,
        val codec: Int
    )

    /** A parsed fragment: subheader + the AU bytes it carries. */
    data class Frag(val header: FragHeader, val data: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Frag && header == other.header && data.contentEquals(other.data)
        override fun hashCode(): Int = 31 * header.hashCode() + data.contentHashCode()
    }

    fun packSubheader(
        frameId: Int, fragIndex: Int, fragCount: Int,
        keyframe: Boolean, frameEnd: Boolean, codec: Int = CODEC_H264
    ): ByteArray {
        val out = ByteArray(SUBHEADER_SIZE)
        packSubheaderInto(out, 0, frameId, fragIndex, fragCount, keyframe, frameEnd, codec)
        return out
    }

    fun packSubheaderInto(
        out: ByteArray, offset: Int,
        frameId: Int, fragIndex: Int, fragCount: Int,
        keyframe: Boolean, frameEnd: Boolean, codec: Int = CODEC_H264
    ) {
        val bb = ByteBuffer.wrap(out, offset, SUBHEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(frameId)
        bb.putShort(fragIndex.toShort())
        bb.putShort(fragCount.toShort())
        var flags = 0
        if (keyframe) flags = flags or FLAG_KEYFRAME
        if (frameEnd) flags = flags or FLAG_FRAME_END
        bb.put(flags.toByte())
        bb.put(codec.toByte())
    }

    fun unpackSubheader(data: ByteArray, offset: Int = 0): FragHeader? {
        if (data.size - offset < SUBHEADER_SIZE) return null
        val bb = ByteBuffer.wrap(data, offset, SUBHEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        val frameId = bb.int
        val fragIndex = bb.short.toInt() and 0xFFFF
        val fragCount = bb.short.toInt() and 0xFFFF
        val flags = bb.get().toInt() and 0xFF
        val codec = bb.get().toInt() and 0xFF
        return FragHeader(
            frameId, fragIndex, fragCount,
            keyframe = flags and FLAG_KEYFRAME != 0,
            frameEnd = flags and FLAG_FRAME_END != 0,
            codec = codec
        )
    }

    /** Parse one VIDEO_DATA payload (after the 16-byte protocol header). */
    fun parseFrag(payload: ByteArray): Frag? {
        val h = unpackSubheader(payload) ?: return null
        return Frag(h, payload.copyOfRange(SUBHEADER_SIZE, payload.size))
    }

    /**
     * Split one access unit into VIDEO_DATA payloads (10-byte subheader
     * + chunk), WITHOUT the 16-byte protocol header — the form a sender
     * that packs its own headers (AudioEngine.sendTo) needs.
     */
    fun splitPayloads(frameId: Int, au: ByteArray, keyframe: Boolean): List<ByteArray> {
        require(au.isNotEmpty()) { "cannot packetize an empty access unit" }
        val fragCount = (au.size + MAX_FRAG_DATA - 1) / MAX_FRAG_DATA
        require(fragCount <= MAX_FRAGS) { "access unit too large: ${au.size} B" }
        return (0 until fragCount).map { i ->
            val off = i * MAX_FRAG_DATA
            val len = minOf(MAX_FRAG_DATA, au.size - off)
            val payload = ByteArray(SUBHEADER_SIZE + len)
            packSubheaderInto(payload, 0, frameId, i, fragCount, keyframe, i == fragCount - 1)
            System.arraycopy(au, off, payload, SUBHEADER_SIZE, len)
            payload
        }
    }

    /**
     * Split one access unit into full protocol datagrams with consecutive
     * header seqs starting at [firstSeq]; every packet of the frame
     * carries the frame's capture [timestampUs] in the header.
     */
    fun packetize(frameId: Int, au: ByteArray, keyframe: Boolean, timestampUs: Long, firstSeq: Int): List<ByteArray> {
        return splitPayloads(frameId, au, keyframe).mapIndexed { i, payload ->
            Protocol.pack(Protocol.TYPE_VIDEO_DATA, 0, firstSeq + i, timestampUs, payload)
        }
    }
}

/**
 * In-order reassembler for one video stream (plan §B.2): fragments are
 * collected per frameId; a frame is emitted only when complete AND all
 * earlier frames have been emitted or dropped. A frame that stays
 * incomplete for [deadlineMs] — or never arrives at all while later
 * frames complete — is dropped whole (real-time wins over completeness)
 * and raises [keyframeNeeded] so the caller can ask the phone for an
 * IDR. The emission base is the first frameId seen after construction /
 * [reset]: production resets on every VIDEO_CONFIG and the phone starts
 * each stream at frameId 0 with a keyframe, so pre-base frames cannot
 * occur on the wire.
 *
 * Not thread-safe: the owner (one video thread per stream) serializes.
 * The clock is injected ([nowMs] parameters) so tests drive time.
 */
class VideoReassembler(
    private val deadlineMs: Long = 200,
    private val maxInFlight: Int = 4,
    private val maxAuBytes: Int = 2_000_000
) {
    var droppedFrames = 0
        private set
    var completedFrames = 0
        private set
    var keyframeNeeded = false
        private set

    val inFlightFrames: Int get() = partials.size

    private class Partial(val fragCount: Int, val firstSeenMs: Long) {
        val frags = arrayOfNulls<ByteArray>(fragCount)
        var received = 0
        var bytes = 0
    }

    private val partials = HashMap<Int, Partial>()
    private val completed = HashMap<Int, ByteArray>()
    private var nextExpected: Int? = null
    private var gapSinceMs = -1L

    fun clearKeyframeNeeded() {
        keyframeNeeded = false
    }

    fun reset() {
        partials.clear()
        completed.clear()
        nextExpected = null
        gapSinceMs = -1L
        droppedFrames = 0
        completedFrames = 0
        keyframeNeeded = false
    }

    /**
     * Offer one fragment. Returns any access units that became emittable
     * (in frameId order) as a result — usually empty or one AU.
     */
    fun offer(
        frameId: Int, fragIndex: Int, fragCount: Int, keyframe: Boolean,
        data: ByteArray, nowMs: Long
    ): List<ByteArray> {
        val out = advance(nowMs)
        if (fragCount <= 0 || fragCount > VideoPackets.MAX_FRAGS) return out
        if (fragIndex < 0 || fragIndex >= fragCount) return out
        val ne = nextExpected
        if (ne == null) {
            nextExpected = frameId
        } else if (frameId < ne) {
            return out // late / duplicate of an emitted or dropped frame
        }
        if (!completed.containsKey(frameId)) {
            val p = partials.getOrPut(frameId) { Partial(fragCount, nowMs) }
            if (p.fragCount != fragCount) return out // inconsistent fragment: ignore
            if (p.frags[fragIndex] == null) {
                if (p.bytes + data.size > maxAuBytes) return out // poisoned frame: let the deadline reap it
                p.frags[fragIndex] = data
                p.received++
                p.bytes += data.size
                if (p.received == p.fragCount) {
                    partials.remove(frameId)
                    val au = ByteArray(p.bytes)
                    var off = 0
                    for (f in p.frags) {
                        System.arraycopy(f!!, 0, au, off, f.size)
                        off += f.size
                    }
                    completed[frameId] = au
                }
            }
            evictOverflow()
        }
        out += advance(nowMs)
        noteGap(nowMs)
        return out
    }

    /** Drive the loss deadlines without a new fragment. */
    fun expire(nowMs: Long): List<ByteArray> {
        val out = advance(nowMs)
        noteGap(nowMs)
        return out
    }

    /** Emit what's emittable; drop a blocking frame whose deadline passed. */
    private fun advance(nowMs: Long): MutableList<ByteArray> {
        val out = ArrayList<ByteArray>()
        while (true) {
            val ne = nextExpected ?: break
            val done = completed.remove(ne)
            if (done != null) {
                out += done
                completedFrames++
                nextExpected = ne + 1
                gapSinceMs = -1L
                continue
            }
            val p = partials[ne]
            if (p != null) {
                if (nowMs - p.firstSeenMs > deadlineMs) {
                    partials.remove(ne)
                    noteDrop()
                    nextExpected = ne + 1
                    gapSinceMs = -1L
                    continue
                }
                break // blocking frame still within its deadline
            }
            // No trace of frame ne: a whole-frame gap, but only a gap if
            // later frames are already known — otherwise nothing to do.
            val hasHigher = partials.keys.any { it > ne } || completed.keys.any { it > ne }
            if (!hasHigher) break
            if (gapSinceMs < 0) {
                gapSinceMs = nowMs
                break
            }
            if (nowMs - gapSinceMs > deadlineMs) {
                noteDrop()
                nextExpected = ne + 1
                gapSinceMs = -1L
                continue
            }
            break
        }
        // Stale non-blocking partials are reaped too: when their turn
        // came they would already be past the deadline.
        val stale = partials.entries
            .filter { it.key != nextExpected && nowMs - it.value.firstSeenMs > deadlineMs }
            .map { it.key }
        for (id in stale) {
            partials.remove(id)
            noteDrop()
        }
        return out
    }

    private fun noteGap(nowMs: Long) {
        val ne = nextExpected ?: return
        if (gapSinceMs >= 0) return
        if (partials.containsKey(ne) || completed.containsKey(ne)) return
        if (partials.keys.any { it > ne } || completed.keys.any { it > ne }) gapSinceMs = nowMs
    }

    private fun noteDrop() {
        droppedFrames++
        keyframeNeeded = true
    }

    /** Bound memory: too many incomplete frames evicts the oldest one. */
    private fun evictOverflow() {
        while (partials.size > maxInFlight) {
            val oldest = partials.keys.minOrNull() ?: break
            partials.remove(oldest)
            noteDrop()
            if (nextExpected == oldest) {
                nextExpected = (partials.keys + completed.keys).minOrNull() ?: (oldest + 1)
                gapSinceMs = -1L
            }
        }
    }
}
