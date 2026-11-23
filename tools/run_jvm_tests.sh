#!/usr/bin/env bash
# JVM harness: compiles the SHIPPED pure-Kotlin sources (Protocol.kt +
# EchoCanceller.kt — no Android imports) together with tools/TestMain.kt
# and runs the header round-trip, the tokenHash/verifyCode derivations and
# the NLMS echo-cancellation measurement (synthetic speech + delayed /
# attenuated playback echo, ERLE printed in dB).
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-$HOME/tools/jdk17}"
export PATH="$JAVA_HOME/bin:$PATH"
KOTLINC="$HOME/tools/kotlinc/bin/kotlinc"
"$KOTLINC" -jvm-target 17 -no-reflect -d tools/jvm-test.jar \
    app/src/main/java/com/qi/phoneaudiobridge/Protocol.kt \
    app/src/main/java/com/qi/phoneaudiobridge/EchoCanceller.kt \
    app/src/main/java/com/qi/phoneaudiobridge/EchoProcessor.kt \
    app/src/main/java/com/qi/phoneaudiobridge/LoopGuard.kt \
    app/src/main/java/com/qi/phoneaudiobridge/PlayoutWatchdog.kt \
    app/src/main/java/com/qi/phoneaudiobridge/SessionRecovery.kt \
    tools/TestMain.kt
"$JAVA_HOME/bin/java" -cp "tools/jvm-test.jar:$HOME/tools/kotlinc/lib/kotlin-stdlib.jar" TestMainKt
# mesh-recovery core tests (v2.2 defect A)
"$KOTLINC" -jvm-target 17 -no-reflect -d tools/recovery-test.jar \
    app/src/main/java/com/qi/phoneaudiobridge/SessionRecovery.kt \
    tools/TestRecovery.kt
"$JAVA_HOME/bin/java" -cp "tools/recovery-test.jar:$HOME/tools/kotlinc/lib/kotlin-stdlib.jar" TestRecoveryKt
# cross-subnet sweep core tests (v2.2.3 FB-6)
"$KOTLINC" -jvm-target 17 -no-reflect -d tools/sweep-test.jar \
    app/src/main/java/com/qi/phoneaudiobridge/SubnetSweep.kt \
    tools/TestSweep.kt
"$JAVA_HOME/bin/java" -cp "tools/sweep-test.jar:$HOME/tools/kotlinc/lib/kotlin-stdlib.jar" TestSweepKt
# discovery diagnostics core tests (hardening §5.4): per-channel counters,
# paired-probe schedule, plain-language verdict selection.
"$KOTLINC" -jvm-target 17 -no-reflect -d tools/diag-test.jar \
    app/src/main/java/com/qi/phoneaudiobridge/DiscoveryDiag.kt \
    tools/TestDiag.kt
"$JAVA_HOME/bin/java" -cp "tools/diag-test.jar:$HOME/tools/kotlinc/lib/kotlin-stdlib.jar" TestDiagKt
# PC-side loop detector scenarios (v2.2.3 FB-8): the Kotlin port of
# LoopDetector.cs (tools/PcLoopDetector.kt) driven through music+AEC
# residual, a genuine electric loop, and the probe-resume failsafe.
"$KOTLINC" -jvm-target 17 -no-reflect -d tools/looppc-test.jar \
    app/src/main/java/com/qi/phoneaudiobridge/EchoCanceller.kt \
    app/src/main/java/com/qi/phoneaudiobridge/EchoProcessor.kt \
    tools/PcLoopDetector.kt \
    tools/TestLoopPc.kt
"$JAVA_HOME/bin/java" -cp "tools/looppc-test.jar:$HOME/tools/kotlinc/lib/kotlin-stdlib.jar" TestLoopPcKt
# volume mapping + SET_VOLUME packet tests (volume-stream fix, design
# work-v2/android-volume-streams.md §3.4): TYPE_SET_VOLUME allocation and
# the percent<->gain mapping both ends convert volume through.
"$KOTLINC" -jvm-target 17 -no-reflect -d tools/volume-test.jar \
    app/src/main/java/com/qi/phoneaudiobridge/Protocol.kt \
    tools/TestVolume.kt
"$JAVA_HOME/bin/java" -cp "tools/volume-test.jar:$HOME/tools/kotlinc/lib/kotlin-stdlib.jar" TestVolumeKt
# video packet layer (wave 4, plan §B.2): types 12-15, the 10-byte
# fragmentation subheader, packetize/depacketize round-trip and the
# reassembler's in-order / 200 ms-loss / keyframe-needed behaviour.
"$KOTLINC" -jvm-target 17 -no-reflect -d tools/video-test.jar \
    app/src/main/java/com/qi/phoneaudiobridge/Protocol.kt \
    app/src/main/java/com/qi/phoneaudiobridge/VideoPackets.kt \
    tools/TestVideo.kt
"$JAVA_HOME/bin/java" -cp "tools/video-test.jar:$HOME/tools/kotlinc/lib/kotlin-stdlib.jar" TestVideoKt
# interaction policy (wave 3, plan Part A): the overlay stack behind the
# three back channels, FB-12 density form rules + disconnect pruning +
# compact ordering, pull-to-refresh eligibility, and the pairing-confirm
# verification watch (B-05).
"$KOTLINC" -jvm-target 17 -no-reflect -d tools/interaction-test.jar \
    app/src/main/java/com/qi/phoneaudiobridge/InteractionPolicy.kt \
    tools/TestInteraction.kt
"$JAVA_HOME/bin/java" -cp "tools/interaction-test.jar:$HOME/tools/kotlinc/lib/kotlin-stdlib.jar" TestInteractionKt
# focus policy + capture watchdog (board item #3): per-direction pause
# decoupling — the uplink is gated ONLY by a real call state, media
# focus moves only the downlink, a permanent focus loss schedules
# bounded re-requests instead of latching — plus the capture rebuild
# decisions for silenced / read-erroring recorders.
"$KOTLINC" -jvm-target 17 -no-reflect -d tools/focus-test.jar \
    app/src/main/java/com/qi/phoneaudiobridge/FocusPolicy.kt \
    app/src/main/java/com/qi/phoneaudiobridge/CaptureWatchdog.kt \
    tools/TestFocus.kt
"$JAVA_HOME/bin/java" -cp "tools/focus-test.jar:$HOME/tools/kotlinc/lib/kotlin-stdlib.jar" TestFocusKt
