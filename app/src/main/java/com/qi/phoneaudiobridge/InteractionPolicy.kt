package com.qi.phoneaudiobridge

/**
 * Pure interaction policy for the Android UI (wave 3, plan
 * work-v2/android-interaction-and-camera-plan.md Part A). Everything in
 * this file is Android-free so the gesture/density rules are JVM-testable
 * (tools/TestInteraction.kt) instead of depending on a real phone:
 *
 *  - the single-overlay stack the three back channels share (A.1.1),
 *  - the FB-12 device-list density form rules (A.2.2/A.2.3),
 *  - the pull-to-refresh eligibility gate (A.1.4/A.1.9),
 *  - the pairing-confirm verification watch behind the B-05 fix.
 */
object InteractionPolicy {

    /** The five home overlays. At most one is visible at any time. */
    enum class Overlay { SETTINGS, DISCOVER, SCAN, CODE, CONFIRM }

    /**
     * The overlay "stack" (A.1.1): by design it holds only the currently
     * open overlay — opening one replaces whatever was open. All back
     * channels (on-screen button, edge swipe, system Back) close exactly
     * [back]'s return value; a null return means "at home", where Back
     * backgrounds the task instead of finishing the Activity (A.1.3).
     */
    class OverlayStack {
        var current: Overlay? = null
            private set

        fun show(o: Overlay) { current = o }
        fun clear() { current = null }

        /** Consume a Back press: returns the overlay to close, or null at home. */
        fun back(): Overlay? {
            val c = current
            current = null
            return c
        }
    }

    // ---------------- FB-12 density (A.2.2 / A.2.3) ----------------

    enum class DeviceForm { CHIP, CARD }

    data class FormInput(
        val connected: Boolean,
        val connecting: Boolean,
        val pairingTarget: Boolean,
        val manualExpanded: Boolean
    )

    /**
     * State-driven forms always win: connecting / pairing / connected
     * devices render as full cards and a connected card can never be
     * collapsed by manual state. A paired device in none of those states
     * is a compact chip unless the user manually expanded it.
     */
    fun formFor(input: FormInput): DeviceForm =
        if (input.connected || input.connecting || input.pairingTarget || input.manualExpanded)
            DeviceForm.CARD else DeviceForm.CHIP

    /**
     * Disconnect auto-collapse (A.2.3): a device that was connected on
     * the previous poll and is not connected now loses any manual
     * expansion, so it falls back to a chip on its own.
     */
    fun pruneManualExpanded(
        manual: Set<String>,
        wasConnected: Set<String>,
        nowConnected: Set<String>
    ): Set<String> = manual - (wasConnected - nowConnected)

    /** Sort key for one compact-group row. */
    data class CompactKey(val pinned: Boolean, val online: Boolean, val lastSeenMs: Long)

    /** Compact group order (A.2.2): pinned first, online before offline,
     *  most recently seen first inside a tier. */
    val COMPACT_ORDER: Comparator<CompactKey> =
        compareByDescending<CompactKey> { it.pinned }
            .thenByDescending { it.online }
            .thenByDescending { it.lastSeenMs }

    // ---------------- pull-to-refresh (A.1.4 / A.1.9) ----------------

    const val PULL_THRESHOLD_DP = 64

    /**
     * Pull-to-refresh engages only on the home page (no overlay), with
     * the engine running and nothing connected — while streaming,
     * guard-paused or inside the reconnect window it stays disabled so a
     * stray pull can never compete with a live session (A.1.9). The
     * refresh action itself only restarts the discovery layer; this gate
     * is the second line of defence.
     */
    fun pullRefreshAllowed(
        engineRunning: Boolean,
        anyConnected: Boolean,
        overlayOpen: Boolean,
        refreshing: Boolean
    ): Boolean = engineRunning && !anyConnected && !overlayOpen && !refreshing

    // ---------------- pairing-confirm verification (B-05) ----------------

    /** How long the session must demonstrably stay up after the user
     *  taps 确认配对 before the pairing counts as succeeded. */
    const val VERIFY_OK_MS = 2000L

    /** A re-resolve blip shorter than this right after confirming is
     *  treated as noise, not failure. */
    const val VERIFY_RESOLVE_GRACE_MS = 1000L

    enum class VerifyOutcome { PENDING, OK, FAILED }

    /**
     * The watch behind the confirm overlay's error bar (audit R-A6):
     * before this, tapping 确认配对 closed the overlay optimistically
     * and a pairing that then failed had no visible surface at all.
     * Now the tap only starts a verification window — the session
     * dropping, vanishing, or going dead-air (re-resolving past the
     * grace) inside the window is a FAILED the user can see and retry
     * from; surviving the whole window is OK.
     */
    fun verifyOutcome(
        sessionExists: Boolean,
        connected: Boolean,
        reResolving: Boolean,
        elapsedMs: Long
    ): VerifyOutcome = when {
        !sessionExists || !connected -> VerifyOutcome.FAILED
        reResolving && elapsedMs >= VERIFY_RESOLVE_GRACE_MS -> VerifyOutcome.FAILED
        elapsedMs >= VERIFY_OK_MS -> VerifyOutcome.OK
        else -> VerifyOutcome.PENDING
    }
}
