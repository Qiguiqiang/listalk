import com.qi.phoneaudiobridge.InteractionPolicy
import com.qi.phoneaudiobridge.InteractionPolicy.DeviceForm
import com.qi.phoneaudiobridge.InteractionPolicy.FormInput
import com.qi.phoneaudiobridge.InteractionPolicy.Overlay
import com.qi.phoneaudiobridge.InteractionPolicy.VerifyOutcome

/**
 * JVM tests for the SHIPPED interaction-policy core (InteractionPolicy.kt):
 * the single-overlay stack behind the three back channels (plan A.1.1–
 * A.1.3), the FB-12 device-list density form rules (A.2.2/A.2.3), the
 * disconnect auto-collapse of manual expansions, the compact-group
 * ordering, the pull-to-refresh eligibility gate (A.1.4/A.1.9), and the
 * pairing-confirm verification watch that makes B-05 failures visible.
 * These rules are pure so the gesture layer's correctness does not depend
 * on a real phone to check.
 */
fun main() {
    var checks = 0
    fun expect(cond: Boolean, label: String) {
        checks++
        check(cond) { "FAILED: $label" }
        println("INTERACT ok: $label")
    }

    // ---- overlay stack: at most one overlay, back closes exactly it ----
    val stack = InteractionPolicy.OverlayStack()
    expect(stack.current == null, "stack starts empty (home)")
    expect(stack.back() == null, "back at home = no overlay to close (caller backgrounds the task)")
    stack.show(Overlay.SETTINGS)
    expect(stack.current == Overlay.SETTINGS, "show(SETTINGS) makes it current")
    stack.show(Overlay.DISCOVER)
    expect(stack.current == Overlay.DISCOVER, "show replaces: still exactly one overlay")
    expect(stack.back() == Overlay.DISCOVER, "back closes the top overlay (DISCOVER)")
    expect(stack.current == null, "stack empty after close")
    stack.show(Overlay.SCAN)
    stack.clear()
    expect(stack.current == null && stack.back() == null, "clear() empties without a close event")

    // ---- FB-12 form rules (A.2.2): state-driven forms beat manual state ----
    fun form(connected: Boolean = false, connecting: Boolean = false,
             pairingTarget: Boolean = false, manualExpanded: Boolean = false) =
        InteractionPolicy.formFor(FormInput(connected, connecting, pairingTarget, manualExpanded))
    expect(form() == DeviceForm.CHIP, "paired, idle (offline or online-unconnected) = CHIP")
    expect(form(manualExpanded = true) == DeviceForm.CARD, "manual expansion = CARD")
    expect(form(connected = true) == DeviceForm.CARD, "connected = CARD")
    expect(form(connecting = true) == DeviceForm.CARD, "connecting = CARD")
    expect(form(pairingTarget = true) == DeviceForm.CARD, "pairing target = CARD")
    expect(form(connected = true, manualExpanded = false) == DeviceForm.CARD,
        "connected card never collapses via manual state")

    // ---- disconnect auto-collapse (A.2.3): connected -> not connected
    // drops the id from the manual set; unrelated ids survive ----
    val pruned = InteractionPolicy.pruneManualExpanded(
        manual = setOf("a", "b", "c"),
        wasConnected = setOf("a", "b"),
        nowConnected = setOf("b"))
    expect(pruned == setOf("b", "c"), "disconnect of 'a' prunes only 'a' (got $pruned)")
    val prunedNone = InteractionPolicy.pruneManualExpanded(
        manual = setOf("a"), wasConnected = setOf("a"), nowConnected = setOf("a"))
    expect(prunedNone == setOf("a"), "still-connected device keeps its manual expansion entry")
    val prunedNew = InteractionPolicy.pruneManualExpanded(
        manual = setOf("a"), wasConnected = emptySet(), nowConnected = setOf("a"))
    expect(prunedNew == setOf("a"), "a fresh connect does not prune anything")

    // ---- compact-group ordering (A.2.2): pinned first, online before
    // offline, most-recently-seen first within a tier ----
    val keys = listOf(
        InteractionPolicy.CompactKey(pinned = false, online = false, lastSeenMs = 900),
        InteractionPolicy.CompactKey(pinned = false, online = true, lastSeenMs = 100),
        InteractionPolicy.CompactKey(pinned = true, online = false, lastSeenMs = 50),
        InteractionPolicy.CompactKey(pinned = false, online = true, lastSeenMs = 500),
        InteractionPolicy.CompactKey(pinned = true, online = true, lastSeenMs = 10))
    val sorted = keys.sortedWith(InteractionPolicy.COMPACT_ORDER)
    expect(sorted.map { it.lastSeenMs } == listOf(10L, 50L, 500L, 100L, 900L),
        "order = pinned(online,offline) > unpinned online recent-first > offline (got ${sorted.map { it.lastSeenMs }})")

    // ---- pull-to-refresh eligibility (A.1.4 + A.1.9 matrix) ----
    expect(InteractionPolicy.pullRefreshAllowed(engineRunning = true, anyConnected = false, overlayOpen = false, refreshing = false),
        "home, engine running, nothing connected = pull allowed")
    expect(!InteractionPolicy.pullRefreshAllowed(engineRunning = true, anyConnected = true, overlayOpen = false, refreshing = false),
        "connected (streaming / guard-paused / reconnecting) = pull disabled")
    expect(!InteractionPolicy.pullRefreshAllowed(engineRunning = true, anyConnected = false, overlayOpen = true, refreshing = false),
        "overlay open = pull disabled")
    expect(!InteractionPolicy.pullRefreshAllowed(engineRunning = true, anyConnected = false, overlayOpen = false, refreshing = true),
        "already refreshing = no re-trigger")
    expect(!InteractionPolicy.pullRefreshAllowed(engineRunning = false, anyConnected = false, overlayOpen = false, refreshing = false),
        "engine off = pull does nothing")
    expect(InteractionPolicy.PULL_THRESHOLD_DP == 64, "pull threshold pinned at 64dp")

    // ---- B-05 verify watch: after 确认配对, the pairing only counts as
    // succeeded once the session demonstrably stays up; a drop, a vanish
    // or dead air inside the window is a visible failure ----
    fun verify(exists: Boolean = true, connected: Boolean = true, reResolving: Boolean = false, elapsedMs: Long) =
        InteractionPolicy.verifyOutcome(exists, connected, reResolving, elapsedMs)
    expect(verify(elapsedMs = 0) == VerifyOutcome.PENDING, "just confirmed = still verifying")
    expect(verify(elapsedMs = 1_999) == VerifyOutcome.PENDING, "inside the window, session up = pending")
    expect(verify(elapsedMs = 2_000) == VerifyOutcome.OK, "session up for the full window = OK")
    expect(verify(connected = false, elapsedMs = 500) == VerifyOutcome.FAILED, "session dropped inside the window = FAILED")
    expect(verify(exists = false, elapsedMs = 500) == VerifyOutcome.FAILED, "session vanished = FAILED")
    expect(verify(reResolving = true, elapsedMs = 1_200) == VerifyOutcome.FAILED, "dead air (re-resolving) past grace = FAILED")
    expect(verify(reResolving = true, elapsedMs = 300) == VerifyOutcome.PENDING, "a brief re-resolve blip inside grace = still pending")
    expect(verify(connected = false, elapsedMs = 5_000) == VerifyOutcome.FAILED, "a late drop never turns into OK")

    println("INTERACTION ALL OK ($checks checks)")
}
