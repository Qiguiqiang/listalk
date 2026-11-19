import com.qi.phoneaudiobridge.Protocol
import kotlin.math.abs

/**
 * JVM tests for the volume-stream fix (design: work-v2/android-volume-streams.md
 * §3.4/§4.6): the TYPE_SET_VOLUME packet number and the single percent<->gain
 * mapping every volume surface converts through (PC slider percent 0..100,
 * phone per-device gain 0..1). The C# end mirrors these functions exactly
 * (Protocol.cs GainForPercent/PercentForGain); the consistency checker pins
 * the packet number across Kotlin / PC Python / C#.
 */
fun main() {
    var checks = 0
    fun expect(cond: Boolean, label: String) {
        checks++
        check(cond) { "FAILED: $label" }
        println("VOLUME ok: $label")
    }
    fun feq(a: Float, b: Float) = abs(a - b) < 1e-6f

    // ---- packet type allocation ----
    expect(Protocol.TYPE_SET_VOLUME == 10, "TYPE_SET_VOLUME == 10 (the free slot below BEACON=11)")
    val types = listOf(
        Protocol.TYPE_AUDIO, Protocol.TYPE_DISCOVER, Protocol.TYPE_DISCOVER_RESP,
        Protocol.TYPE_HELLO, Protocol.TYPE_HELLO_ACK, Protocol.TYPE_PING, Protocol.TYPE_PONG,
        Protocol.TYPE_RATE_REQ, Protocol.TYPE_RATE_ACK, Protocol.TYPE_BYE,
        Protocol.TYPE_SET_VOLUME, Protocol.TYPE_BEACON
    )
    expect(types.distinct().size == types.size, "all packet type constants are distinct")
    expect(Protocol.TYPE_BEACON == 11, "TYPE_BEACON still 11")

    // ---- percent -> gain ----
    expect(feq(Protocol.gainForPercent(0), 0f), "gainForPercent(0) == 0")
    expect(feq(Protocol.gainForPercent(100), 1f), "gainForPercent(100) == 1")
    expect(feq(Protocol.gainForPercent(70), 0.7f), "gainForPercent(70) == 0.7 (the registry default)")
    expect(feq(Protocol.gainForPercent(-10), 0f), "gainForPercent clamps below 0")
    expect(feq(Protocol.gainForPercent(250), 1f), "gainForPercent clamps above 100")

    // ---- gain -> percent ----
    expect(Protocol.percentForGain(0f) == 0, "percentForGain(0) == 0")
    expect(Protocol.percentForGain(1f) == 100, "percentForGain(1) == 100")
    expect(Protocol.percentForGain(0.7f) == 70, "percentForGain(0.7) == 70")
    expect(Protocol.percentForGain(0.5f) == 50, "percentForGain(0.5) == 50")
    expect(Protocol.percentForGain(-0.25f) == 0, "percentForGain clamps below 0")
    expect(Protocol.percentForGain(1.25f) == 100, "percentForGain clamps above 1")

    // ---- round trip over the whole UI scale + monotonicity ----
    var prev = -1f
    var roundTrip = true
    var monotone = true
    for (p in 0..100) {
        val g = Protocol.gainForPercent(p)
        if (Protocol.percentForGain(g) != p) roundTrip = false
        if (g < prev) monotone = false
        prev = g
    }
    expect(roundTrip, "percent->gain->percent is the identity for all 0..100")
    expect(monotone, "gainForPercent is non-decreasing over 0..100")

    // ---- wire round trip: a SET_VOLUME packet survives pack/unpack ----
    val payload = "{\"speakerVolume\":0.7}".toByteArray(Charsets.UTF_8)
    val flags = Protocol.flags(Protocol.CODEC_PCM16, Protocol.RATE_48K)
    val pkt = Protocol.pack(Protocol.TYPE_SET_VOLUME, flags, 7, 123456789L, payload)
    val h = Protocol.unpack(pkt, pkt.size)
    expect(h != null && h.type == Protocol.TYPE_SET_VOLUME, "SET_VOLUME packet unpacks with its type")
    expect(h != null && h.seq == 7 && h.timestampUs == 123456789L, "SET_VOLUME header seq/ts preserved")
    val body = pkt.copyOfRange(Protocol.HEADER_SIZE, pkt.size)
    expect(body.contentEquals(payload), "SET_VOLUME payload bytes preserved verbatim")

    println("VOLUME TESTS ALL OK ($checks checks)")
}
