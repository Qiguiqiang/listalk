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
    app/src/main/java/com/qi/phoneaudiobridge/SessionRecovery.kt \
    tools/TestMain.kt
"$JAVA_HOME/bin/java" -cp "tools/jvm-test.jar:$HOME/tools/kotlinc/lib/kotlin-stdlib.jar" TestMainKt
# mesh-recovery core tests (v2.2 defect A)
"$KOTLINC" -jvm-target 17 -no-reflect -d tools/recovery-test.jar \
    app/src/main/java/com/qi/phoneaudiobridge/SessionRecovery.kt \
    tools/TestRecovery.kt
"$JAVA_HOME/bin/java" -cp "tools/recovery-test.jar:$HOME/tools/kotlinc/lib/kotlin-stdlib.jar" TestRecoveryKt
