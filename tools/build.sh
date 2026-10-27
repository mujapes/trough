#!/bin/bash
# Build out/trough.apk with no Gradle: native bench -> aapt2 -> javac -> d8 -> zip -> sign.
# Every tool is overridable so the same script runs in the Forge VM and in CI.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID_JAR="${ANDROID_JAR:-$HOME/android-sdk/platforms/android-35/android.jar}"
D8_JAR="${D8_JAR:-$HOME/android-sdk/build-tools/35.0.0/lib/d8.jar}"
AAPT2="${AAPT2:-aapt2}" ZIPALIGN="${ZIPALIGN:-zipalign}" APKSIGNER="${APKSIGNER:-apksigner}"
CC_ARM64="${CC_ARM64:-$( [ "$(uname -m)" = aarch64 ] && echo gcc || echo aarch64-linux-gnu-gcc )}"
KEYSTORE="${KEYSTORE:-$HOME/.trough/debug.keystore}"
SDK_MIN=28 SDK_TARGET=28   # 28: exec from app storage (DESIGN.md "Android hurdles")

B="$(mktemp -d)"; trap 'rm -rf "$B"' EXIT
mkdir -p "$ROOT/out" "$B/apk/lib/arm64-v8a" "$B/obj" "$B/dex"

# Native: static executables, named lib*.so so Android extracts them executable.
"$CC_ARM64" -O2 -static -o "$B/apk/lib/arm64-v8a/libtroughbench.so" "$ROOT/native/bench/bench.c"

"$AAPT2" compile --dir "$ROOT/app/res" -o "$B/res.zip"
"$AAPT2" link -o "$B/base.apk" -I "${FRAMEWORK_RES:-$ANDROID_JAR}" --manifest "$ROOT/app/AndroidManifest.xml" \
    --min-sdk-version $SDK_MIN --target-sdk-version $SDK_TARGET \
    --version-code "${VERSION_CODE:-$(date +%s)}" --version-name "${VERSION_NAME:-$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo dev)}" \
    --java "$B/gen" "$B/res.zip"

find "$B/gen" "$ROOT/app/src" -name '*.java' > "$B/sources.txt"
rm -f "$ROOT/out/trough.apk"
if ! javac -nowarn -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -classpath "$ANDROID_JAR" \
    -d "$B/obj" @"$B/sources.txt" > "$B/javac.log" 2>&1; then
    grep -v 'warning: \[options\]' "$B/javac.log" >&2; echo "javac failed" >&2; exit 1
fi
find "$B/obj" -name '*.class' > "$B/classes.txt"
[ -s "$B/classes.txt" ] || { echo "javac produced nothing" >&2; exit 1; }
java -Xmx384m -cp "$D8_JAR" com.android.tools.r8.D8 --output "$B/dex" --lib "$ANDROID_JAR" \
    --min-api $SDK_MIN @"$B/classes.txt"

cp "$B/base.apk" "$B/unsigned.apk"
cp "$B/dex/"classes*.dex "$B/apk/"
(cd "$B/apk" && zip -q -r "$B/unsigned.apk" .)
"$ZIPALIGN" -f -p 4 "$B/unsigned.apk" "$B/aligned.apk"
if [ ! -f "$KEYSTORE" ]; then
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
        -alias trough -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Trough Debug" >/dev/null 2>&1
fi
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --out "$ROOT/out/trough.apk" "$B/aligned.apk"
"$APKSIGNER" verify "$ROOT/out/trough.apk"
ls -la "$ROOT/out/trough.apk"
