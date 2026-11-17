#!/usr/bin/env bash
# 手机音频桥 手工构建脚本（无 Gradle）：沿用万物收集箱/AuriMemo 验证过的路线。
# aapt2 -> kotlinc（仅 Android 框架 + kotlin-stdlib，无 AndroidX）-> d8 -> zipalign -> apksigner
set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-$HOME/tools/jdk17}"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="$HOME/android-sdk"
BT="$SDK/build-tools/34.0.0"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
KOTLINC="$HOME/tools/kotlinc/bin/kotlinc"
WORK="build-manual/work"

rm -rf "$WORK"
mkdir -p "$WORK/gen" "$WORK/dex"

echo "== 1/5 资源编译与链接 =="
"$BT/aapt2" compile --dir app/src/main/res -o "$WORK/res.zip"
"$BT/aapt2" link -o "$WORK/base.apk" -I "$ANDROID_JAR" \
    --manifest app/src/main/AndroidManifest.xml \
    --java "$WORK/gen" \
    --min-sdk-version 26 --target-sdk-version 34 \
    "$WORK/res.zip"

echo "== 2/5 Kotlin 编译 =="
find app/src/main/java -name '*.kt' > "$WORK/sources.txt"
"$KOTLINC" -cp "$ANDROID_JAR:app/libs/zxing-core-3.5.3.jar" -jvm-target 17 -no-reflect \
    -d "$WORK/app.jar" \
    @"$WORK/sources.txt" \
    $(find "$WORK/gen" -name '*.java')

echo "== 2.5/5 javac 编译 R 生成代码 =="
mkdir -p "$WORK/javac-out"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 8 -target 8 -nowarn \
    -cp "$WORK/app.jar:$ANDROID_JAR" \
    -d "$WORK/javac-out" \
    $(find "$WORK/gen" -name '*.java')
"$JAVA_HOME/bin/jar" cf "$WORK/java-gen.jar" -C "$WORK/javac-out" .

echo "== 3/5 DEX =="
STDLIB="$HOME/tools/kotlinc/lib/kotlin-stdlib.jar"
"$BT/d8" --min-api 26 --lib "$ANDROID_JAR" --output "$WORK/dex" \
    "$WORK/app.jar" "$WORK/java-gen.jar" "$STDLIB" app/libs/zxing-core-3.5.3.jar

echo "== 4/5 组装 APK =="
cp "$WORK/base.apk" "$WORK/unsigned.apk"
(cd "$WORK/dex" && zip -q "../unsigned.apk" classes.dex)
"$BT/zipalign" -f -p 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"

echo "== 5/5 签名 =="
mkdir -p build-manual
if [ ! -f build-manual/debug.keystore ]; then
    "$JAVA_HOME/bin/keytool" -genkeypair -keystore build-manual/debug.keystore \
        -storepass android -keypass android -alias androiddebugkey \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Phone Audio Bridge Debug,O=QI,C=CN"
fi
"$BT/apksigner" sign --ks build-manual/debug.keystore \
    --ks-pass pass:android --key-pass pass:android \
    --out SoundRelay-Android-v2.2.3.apk "$WORK/aligned.apk"
echo "DONE: SoundRelay-Android-v2.2.3.apk"
