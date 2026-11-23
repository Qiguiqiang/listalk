import com.qi.phoneaudiobridge.CaptureWatchdog
import com.qi.phoneaudiobridge.FocusPolicy
import com.qi.phoneaudiobridge.FocusPolicy.FocusEvent

/**
 * JVM tests for the SHIPPED per-direction pause policy and capture
 * watchdog (board item #3: "another app plays sound -> the PC stops
 * receiving the phone mic").
 *
 * The shipped bug these tests pin down: the wave-1 call pause was an
 * audio-focus listener that mapped ANY focus loss to one shared flag
 * gating BOTH directions, and after a permanent AUDIOFOCUS_LOSS the
 * matching GAIN is never delivered, so the flag latched until the
 * bridge was restarted — any music/video app permanently killed the
 * mic uplink. The policy now decides the two directions independently:
 * the uplink is gated ONLY by a real call state, media focus moves
 * only the downlink, and a permanent loss schedules bounded focus
 * re-requests instead of latching.
 *
 * CaptureWatchdog pins the capture-hardening decisions: sustained
 * near-zero frames (a silenced/dead recorder) trigger a bounded
 * number of recorder rebuilds, and a streak of read() errors does the
 * same without a hot rebuild storm.
 */
fun main() {
    var checks = 0
    fun expect(cond: Boolean, label: String) {
        checks++
        check(cond) { "FAILED: $label" }
        println("FOCUS ok: $label")
    }

    // ================= FocusPolicy: direction decoupling =================
    val p = FocusPolicy()
    expect(!p.uplinkPaused && !p.downlinkPaused && !p.downlinkDucked, "policy starts fully active")

    // THE regression: a media app taking durable focus must not pause the uplink.
    p.onFocusEvent(FocusEvent.LOSS, 1_000)
    expect(p.downlinkPaused, "permanent focus LOSS pauses the downlink")
    expect(!p.uplinkPaused, "permanent focus LOSS does NOT pause the uplink (board #3 regression)")

    // Permanent loss: bounded re-request schedule 2s/5s/15s/45s, then stop.
    expect(!p.shouldReRequestFocus(2_999), "no re-request before the first delay elapses")
    expect(p.shouldReRequestFocus(3_000), "first re-request due at +2s")
    p.onFocusReRequestPerformed(3_000)
    expect(!p.shouldReRequestFocus(7_999) && p.shouldReRequestFocus(8_000), "second re-request at +5s")
    p.onFocusReRequestPerformed(8_000)
    expect(!p.shouldReRequestFocus(22_999) && p.shouldReRequestFocus(23_000), "third re-request at +15s")
    p.onFocusReRequestPerformed(23_000)
    expect(!p.shouldReRequestFocus(67_999) && p.shouldReRequestFocus(68_000), "fourth re-request at +45s")
    p.onFocusReRequestPerformed(68_000)
    expect(!p.shouldReRequestFocus(10_000_000), "re-requests stop after the bounded attempts (no latch, no fight)")

    // GAIN clears everything, including the schedule.
    p.onFocusEvent(FocusEvent.GAIN, 70_000)
    expect(!p.downlinkPaused && !p.shouldReRequestFocus(10_000_000), "GAIN resumes downlink and clears the schedule")

    // Transient loss: pause, no re-request (the platform returns GAIN itself).
    val t = FocusPolicy()
    t.onFocusEvent(FocusEvent.LOSS_TRANSIENT, 0)
    expect(t.downlinkPaused && !t.uplinkPaused, "transient loss pauses downlink only")
    expect(!t.shouldReRequestFocus(60_000), "transient loss never schedules a re-request")
    t.onFocusEvent(FocusEvent.GAIN, 5_000)
    expect(!t.downlinkPaused, "GAIN after transient loss resumes the downlink")

    // Duck: volume down, not a pause; a duck arriving over a transient
    // pause must not un-pause.
    val d = FocusPolicy()
    d.onFocusEvent(FocusEvent.LOSS_TRANSIENT_CAN_DUCK, 0)
    expect(d.downlinkDucked && !d.downlinkPaused && !d.uplinkPaused, "CAN_DUCK ducks the downlink only")
    d.onFocusEvent(FocusEvent.GAIN, 1_000)
    expect(!d.downlinkDucked, "GAIN clears the duck")
    val d2 = FocusPolicy()
    d2.onFocusEvent(FocusEvent.LOSS_TRANSIENT, 0)
    d2.onFocusEvent(FocusEvent.LOSS_TRANSIENT_CAN_DUCK, 100)
    expect(d2.downlinkPaused && !d2.downlinkDucked, "CAN_DUCK over a transient pause keeps the pause")

    // Call gate: the ONLY thing that pauses the uplink; fully
    // independent of the focus/downlink state in both directions.
    val c = FocusPolicy()
    c.setInCall(true)
    expect(c.uplinkPaused && !c.downlinkPaused, "call state pauses the uplink only")
    c.onFocusEvent(FocusEvent.LOSS, 0)
    expect(c.uplinkPaused && c.downlinkPaused, "call + focus loss pause both, independently")
    c.setInCall(false)
    expect(!c.uplinkPaused && c.downlinkPaused, "call end resumes the uplink while downlink stays focus-paused")
    c.onFocusEvent(FocusEvent.GAIN, 100)
    expect(!c.downlinkPaused, "focus GAIN then resumes the downlink")

    // reset() returns to the pristine state (engine stop()).
    c.onFocusEvent(FocusEvent.LOSS, 0)
    c.setInCall(true)
    c.reset()
    expect(!c.uplinkPaused && !c.downlinkPaused && !c.shouldReRequestFocus(10_000_000), "reset clears call, downlink and schedule")

    // ================= CaptureWatchdog: silence rebuilds =================
    val w = CaptureWatchdog()
    // Healthy frames never trigger anything.
    for (i in 0 until 200) {
        expect(!w.onFrame(0.5f, i * 100L, inhibited = false), "healthy frame $i triggers no rebuild")
    }
    // Sustained silence: rebuild exactly at the trigger duration.
    val w2 = CaptureWatchdog()
    expect(!w2.onFrame(0.0f, 0, inhibited = false), "first silent frame only starts the clock")
    expect(!w2.onFrame(0.0f, CaptureWatchdog.SILENCE_TRIGGER_MS - 1, inhibited = false), "silence just under the trigger does not rebuild")
    expect(w2.onFrame(0.0f, CaptureWatchdog.SILENCE_TRIGGER_MS, inhibited = false), "sustained silence at the trigger rebuilds the recorder")
    expect(w2.rebuildCount == 1, "silence rebuild counted")
    // Interval bound: a fresh silence window right after a rebuild is ignored.
    w2.onRecorderStarted(CaptureWatchdog.SILENCE_TRIGGER_MS)
    expect(!w2.onFrame(0.0f, 9_000, inhibited = false), "post-rebuild silence window restarts")
    expect(!w2.onFrame(0.0f, 17_000, inhibited = false), "silence inside the min rebuild interval does not rebuild")
    // After the interval, sustained silence rebuilds again.
    expect(!w2.onFrame(0.0f, 100_000, inhibited = false), "later silence window starts")
    expect(w2.onFrame(0.0f, 108_000, inhibited = false), "silence after the interval rebuilds again")
    expect(w2.rebuildCount == 2, "second silence rebuild counted")
    // Budget: third rebuild allowed, fourth refused.
    expect(!w2.onFrame(0.0f, 200_000, inhibited = false), "third silence window starts")
    expect(w2.onFrame(0.0f, 208_000, inhibited = false), "third rebuild within budget")
    expect(!w2.onFrame(0.0f, 300_000, inhibited = false), "fourth silence window starts")
    expect(!w2.onFrame(0.0f, 308_000, inhibited = false), "rebuild budget exhausted: no fourth rebuild")

    // A healthy frame resets the silence clock.
    val w3 = CaptureWatchdog()
    w3.onFrame(0.0f, 0, inhibited = false)
    w3.onFrame(0.0f, 7_000, inhibited = false)
    w3.onFrame(0.5f, 7_500, inhibited = false)
    expect(!w3.onFrame(0.0f, 8_000, inhibited = false), "healthy frame reset the clock: new silence window starts")
    expect(!w3.onFrame(0.0f, 15_500, inhibited = false), "7.5s into the new window is not enough")
    expect(w3.onFrame(0.0f, 16_000, inhibited = false), "a full new silence window after the healthy frame rebuilds")

    // Inhibited (recorder known-silenced / in-call) frames never count.
    val w4 = CaptureWatchdog()
    w4.onFrame(0.0f, 0, inhibited = false)
    expect(!w4.onFrame(0.0f, 7_000, inhibited = true), "inhibited frame does not trigger")
    expect(!w4.onFrame(0.0f, 8_000, inhibited = false), "inhibit reset the clock: silence window restarts")
    expect(!w4.onFrame(0.0f, 15_000, inhibited = false), "7s of counted silence after inhibit is not enough")
    expect(w4.onFrame(0.0f, 16_000, inhibited = false), "full counted silence window after inhibit rebuilds")

    // ================= CaptureWatchdog: read-error rebuilds =================
    val e = CaptureWatchdog()
    repeat(4) { expect(!e.onReadError(0), "read error ${it + 1} below threshold") }
    expect(e.onReadError(0), "fifth consecutive read error rebuilds")
    repeat(5) { expect(!e.onReadError(100), "read errors inside the rate limit do not rebuild (storm guard)") }
    expect(e.onReadError(2_100), "read error after the rate-limit interval rebuilds (streak kept)")
    e.onReadSuccess()
    repeat(4) { expect(!e.onReadError(5_000), "success reset the streak: error ${it + 1} below threshold") }
    expect(e.onReadError(5_000), "fifth error after a success rebuilds again")
    // onRecorderStarted also resets the streak.
    val e2 = CaptureWatchdog()
    repeat(4) { e2.onReadError(0) }
    e2.onRecorderStarted(0)
    repeat(4) { expect(!e2.onReadError(0), "recorder restart reset the streak: error ${it + 1} below threshold") }

    // A recorder reporting itself STOPPED is definitively dead: it
    // rebuilds at once (no streak), but under the same rate limit, so
    // a stack that stops every new recorder cannot storm rebuilds.
    val s = CaptureWatchdog()
    expect(s.onReadError(0, recorderStopped = true), "stopped recorder rebuilds immediately")
    expect(!s.onReadError(100, recorderStopped = true), "stopped rebuild is rate-limited (storm guard)")
    expect(s.onReadError(2_100, recorderStopped = true), "stopped rebuild allowed again after the interval")

    println("FOCUS ALL OK ($checks checks)")
}
