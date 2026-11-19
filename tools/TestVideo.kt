import com.qi.phoneaudiobridge.Protocol
import com.qi.phoneaudiobridge.VideoPackets
import com.qi.phoneaudiobridge.VideoReassembler

/**
 * JVM tests for the Wave-4 camera-relay wire layer (design:
 * work-v2/android-interaction-and-camera-plan.md §B.2): packet types
 * 12–15, the 10-byte VIDEO_DATA fragmentation subheader, packetize /
 * depacketize round-trip, and the reassembler's in-order delivery,
 * 200 ms loss deadline and keyframe-needed signalling. The C# mirror
 * (VideoPackets.cs) is pinned to the same golden subheader bytes by the
 * SmokeTest video stage; the type numbers are pinned across Kotlin /
 * PC Python / C# by tools/check_protocol_consistency.py.
 */
fun main() {
    var checks = 0
    fun expect(cond: Boolean, label: String) {
        checks++
        check(cond) { "FAILED: $label" }
        println("VIDEO ok: $label")
    }

    // ---- packet type allocation (plan §B.2) ----
    expect(Protocol.TYPE_VIDEO_CONFIG == 12, "TYPE_VIDEO_CONFIG == 12")
    expect(Protocol.TYPE_VIDEO_DATA == 13, "TYPE_VIDEO_DATA == 13")
    expect(Protocol.TYPE_VIDEO_CTRL == 14, "TYPE_VIDEO_CTRL == 14")
    expect(Protocol.TYPE_VIDEO_KEYFRAME_REQ == 15, "TYPE_VIDEO_KEYFRAME_REQ == 15")
    val allTypes = listOf(
        Protocol.TYPE_AUDIO, Protocol.TYPE_DISCOVER, Protocol.TYPE_DISCOVER_RESP,
        Protocol.TYPE_HELLO, Protocol.TYPE_HELLO_ACK, Protocol.TYPE_PING, Protocol.TYPE_PONG,
        Protocol.TYPE_RATE_REQ, Protocol.TYPE_RATE_ACK, Protocol.TYPE_BYE,
        Protocol.TYPE_SET_VOLUME, Protocol.TYPE_BEACON,
        Protocol.TYPE_VIDEO_CONFIG, Protocol.TYPE_VIDEO_DATA,
        Protocol.TYPE_VIDEO_CTRL, Protocol.TYPE_VIDEO_KEYFRAME_REQ
    )
    expect(allTypes.distinct().size == allTypes.size, "all 16 packet type constants are distinct")
    expect(Protocol.PROTO_VERSION == 2, "PROTO_VERSION still 2 (video negotiates via caps, never a version bump)")

    // ---- size budget (plan §B.2: datagram total <= 1200 B) ----
    expect(VideoPackets.MAX_DATAGRAM_BYTES == 1200, "MAX_DATAGRAM_BYTES == 1200")
    expect(VideoPackets.SUBHEADER_SIZE == 10, "SUBHEADER_SIZE == 10")
    expect(VideoPackets.MAX_FRAG_DATA == 1200 - 16 - 10, "MAX_FRAG_DATA == 1174 (1200 - header - subheader)")

    // ---- subheader golden bytes (C# mirror must produce identical bytes) ----
    val golden = VideoPackets.packSubheader(
        frameId = 0x01020304, fragIndex = 5, fragCount = 9,
        keyframe = true, frameEnd = true, codec = 0
    )
    expect(
        golden.contentEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x00, 0x05, 0x00, 0x09, 0x03, 0x00)),
        "subheader golden bytes: frameId|fragIndex|fragCount|flags(key|end)=0x03|codec=0"
    )
    val gh = VideoPackets.unpackSubheader(golden)!!
    expect(
        gh.frameId == 0x01020304 && gh.fragIndex == 5 && gh.fragCount == 9 && gh.keyframe && gh.frameEnd && gh.codec == 0,
        "subheader round-trip"
    )
    expect(VideoPackets.unpackSubheader(ByteArray(9)) == null, "subheader shorter than 10 B rejected")
    val pflags = VideoPackets.packSubheader(7, 0, 1, keyframe = false, frameEnd = true, codec = 0)
    expect(pflags[8].toInt() == 0x02, "P-frame last frag: flags == 0x02 (end only)")

    // ---- packetize: sizes, headers, seq continuity ----
    fun au(size: Int, seed: Int) = ByteArray(size) { ((it * 31 + seed) and 0xFF).toByte() }
    val big = au(5000, 7)
    val pkts = VideoPackets.packetize(frameId = 42, au = big, keyframe = true, timestampUs = 987654321L, firstSeq = 1000)
    expect(pkts.size == 5, "5000 B AU -> 5 frags (4x1174 + 304)")
    expect(pkts.all { it.size <= 1200 }, "every datagram <= 1200 B total")
    expect(pkts.map { it.size }.sum() == 5000 + 5 * (16 + 10), "datagram bytes = AU + 5x(header+subheader)")
    var seqOk = true
    var hdrOk = true
    pkts.forEachIndexed { i, pkt ->
        val h = Protocol.unpack(pkt, pkt.size)!!
        if (h.type != Protocol.TYPE_VIDEO_DATA || h.seq != 1000 + i || h.timestampUs != 987654321L) seqOk = false
        val fh = VideoPackets.unpackSubheader(pkt, Protocol.HEADER_SIZE)!!
        if (fh.frameId != 42 || fh.fragIndex != i || fh.fragCount != 5 || !fh.keyframe) hdrOk = false
        if (fh.frameEnd != (i == 4)) hdrOk = false
    }
    expect(seqOk, "VIDEO_DATA headers: type 13, seq consecutive from firstSeq, capture ts preserved")
    expect(hdrOk, "frag subheaders: frameId/fragIndex/fragCount/keyframe/end flags correct")

    val small = au(100, 3)
    val one = VideoPackets.packetize(frameId = 1, au = small, keyframe = false, timestampUs = 5L, firstSeq = 0)
    expect(one.size == 1 && one[0].size == 16 + 10 + 100, "100 B AU -> single 126 B datagram")

    val threw = try {
        VideoPackets.packetize(1, ByteArray(0), false, 0L, 0); false
    } catch (_: IllegalArgumentException) { true }
    expect(threw, "empty AU rejected by packetize")

    // ---- reassembler helpers ----
    fun fragsOf(frameId: Int, data: ByteArray, keyframe: Boolean): List<VideoPackets.Frag> {
        val ps = VideoPackets.packetize(frameId, data, keyframe, 0L, 0)
        return ps.map { p ->
            val fh = VideoPackets.unpackSubheader(p, Protocol.HEADER_SIZE)!!
            VideoPackets.Frag(fh, p.copyOfRange(Protocol.HEADER_SIZE + 10, p.size))
        }
    }
    fun feed(r: VideoReassembler, f: VideoPackets.Frag, nowMs: Long): List<ByteArray> =
        r.offer(f.header.frameId, f.header.fragIndex, f.header.fragCount, f.header.keyframe, f.data, nowMs)

    // ---- round-trip in order ----
    run {
        val r = VideoReassembler()
        val fs = fragsOf(10, big, keyframe = true)
        var emitted: List<ByteArray> = emptyList()
        fs.forEachIndexed { i, f ->
            val out = feed(r, f, 1000L + i)
            if (i < fs.size - 1) expect(out.isEmpty(), "round-trip: nothing emitted before last frag (${i + 1}/${fs.size})")
            else emitted = out
        }
        expect(emitted.size == 1 && emitted[0].contentEquals(big), "round-trip: complete AU emitted byte-identical")
        expect(r.completedFrames == 1 && r.droppedFrames == 0 && !r.keyframeNeeded, "round-trip: counters clean")
    }

    // ---- round-trip with shuffled frags ----
    run {
        val r = VideoReassembler()
        val fs = fragsOf(3, big, keyframe = false)
        val shuffled = listOf(fs[2], fs[0], fs[4], fs[1], fs[3])
        var emitted: List<ByteArray> = emptyList()
        shuffled.forEach { f -> emitted = emitted + feed(r, f, 2000L) }
        expect(emitted.size == 1 && emitted[0].contentEquals(big), "shuffled frags still reassemble byte-identical")
    }

    // ---- duplicate frag is harmless ----
    run {
        val r = VideoReassembler()
        val fs = fragsOf(1, small, keyframe = true)
        val first = feed(r, fs[0], 0L)
        expect(first.size == 1 && first[0].contentEquals(small), "single-frag frame emits on arrival")
        val out = feed(r, fs[0], 1L)
        expect(out.isEmpty() && r.completedFrames == 1, "duplicate of an already-emitted frame ignored")
    }

    // ---- out-of-order frames: later frame waits for the earlier one ----
    // (the emission base is the first frameId seen after a reset — in
    // production the PC resets on VIDEO_CONFIG and the phone starts each
    // stream at frameId 0 with a keyframe, plan §B.2.)
    run {
        val r = VideoReassembler()
        val base = fragsOf(19, au(120, 5), keyframe = true)
        expect(feed(r, base[0], 2999L).size == 1, "base frame 19 emits, nextExpected becomes 20")
        val a = au(300, 11); val b = au(300, 22)
        val fa = fragsOf(20, a, keyframe = true); val fb = fragsOf(21, b, keyframe = false)
        var out = feed(r, fb[0], 3000L)
        expect(out.isEmpty(), "frame 21 complete but held: frame 20 still missing")
        out = feed(r, fa[0], 3001L)
        expect(out.size == 2 && out[0].contentEquals(a) && out[1].contentEquals(b), "frame 20 arrival releases 20 then 21 in order")
    }

    // ---- loss: missing frag + 200 ms deadline drops the frame, stream continues ----
    run {
        val r = VideoReassembler()
        val a = au(2500, 33); val b = au(200, 44)
        val fa = fragsOf(30, a, keyframe = true)   // 3 frags
        val fb = fragsOf(31, b, keyframe = false)  // 1 frag
        feed(r, fa[0], 4000L); feed(r, fa[2], 4000L) // frag 1 lost
        var out = feed(r, fb[0], 4001L)
        expect(out.isEmpty(), "frame 31 held behind incomplete frame 30")
        out = r.expire(4000L + 199)
        expect(out.isEmpty() && r.droppedFrames == 0, "frame 30 survives at +199 ms (deadline is 200 ms)")
        out = r.expire(4000L + 201)
        expect(out.size == 1 && out[0].contentEquals(b), "frame 30 dropped at deadline; frame 31 released")
        expect(r.droppedFrames == 1, "droppedFrames == 1 after deadline drop")
        expect(r.keyframeNeeded, "keyframeNeeded raised after a dropped frame")
        r.clearKeyframeNeeded()
        expect(!r.keyframeNeeded, "clearKeyframeNeeded resets the flag")
        // a late frag of the dropped frame must not resurrect it
        val late = feed(r, fa[1], 4300L)
        expect(late.isEmpty() && r.completedFrames == 1, "late frag of dropped frame ignored")
    }

    // ---- whole frame lost (no frags at all): gap also expires ----
    run {
        val r = VideoReassembler()
        val base = fragsOf(39, au(120, 6), keyframe = true)
        feed(r, base[0], 4999L) // establishes nextExpected = 40
        val b = au(200, 55)
        val fb = fragsOf(41, b, keyframe = false)
        feed(r, fb[0], 5000L) // frame 40 never arrives; the gap is noted now
        var out = r.expire(5000L + 199)
        expect(out.isEmpty(), "gap frame 40: frame 41 still held at +199 ms")
        out = r.expire(5000L + 201)
        expect(out.size == 1 && out[0].contentEquals(b) && r.droppedFrames == 1, "gap expires: frame 40 counted dropped, 41 released")
    }

    // ---- inconsistent fragCount is rejected ----
    run {
        val r = VideoReassembler()
        val a = au(2500, 66)
        val fa = fragsOf(50, a, keyframe = true)
        feed(r, fa[0], 6000L)
        val bogus = VideoPackets.Frag(
            VideoPackets.FragHeader(50, 1, 99, keyframe = true, frameEnd = false, codec = 0),
            ByteArray(100)
        )
        feed(r, bogus, 6001L)
        val out = feed(r, fa[1], 6002L) + feed(r, fa[2], 6003L)
        expect(out.size == 1 && out[0].contentEquals(a), "frag with mismatched fragCount ignored; frame still completes")
    }

    // ---- absurd fragCount rejected outright ----
    run {
        val r = VideoReassembler()
        val bogus = VideoPackets.Frag(
            VideoPackets.FragHeader(60, 0, 60000, keyframe = true, frameEnd = false, codec = 0),
            ByteArray(10)
        )
        val out = feed(r, bogus, 7000L)
        expect(out.isEmpty() && r.inFlightFrames == 0, "fragCount 60000 (> MAX_FRAGS) rejected, nothing buffered")
    }

    // ---- in-flight bound: oldest incomplete frame evicted ----
    run {
        val r = VideoReassembler(maxInFlight = 2)
        val a = au(2500, 77)
        val f1 = fragsOf(70, a, keyframe = true)
        val f2 = fragsOf(71, a, keyframe = false)
        val f3 = fragsOf(72, a, keyframe = false)
        feed(r, f1[0], 8000L)             // frame 70 incomplete (blocking)
        feed(r, f2[0], 8001L)             // frame 71 incomplete
        feed(r, f3[0], 8002L)             // exceeds bound -> frame 70 evicted (it blocks emission)
        expect(r.droppedFrames == 1, "in-flight bound evicts the oldest (blocking) frame")
        val out = feed(r, f2[1], 8003L) + feed(r, f2[2], 8004L) + feed(r, f3[1], 8005L) + feed(r, f3[2], 8006L)
        expect(out.size == 2, "frames 71 and 72 complete and emit after the eviction")
    }

    println("VIDEO ALL OK ($checks checks)")
}
