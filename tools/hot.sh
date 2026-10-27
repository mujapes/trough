#!/bin/bash
# Build, sign and publish a hot bundle: the phone picks it up within a second, no install.
#   hot/src/**/*.java -> classes.dex, native/* -> bin/, + manifest.json, signed with
#   ~/.trough/hot-key.pem (never committed; its public half is app/assets/hot-pubkey.der).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID_JAR="${ANDROID_JAR:-$HOME/android-sdk/platforms/android-35/android.jar}"
D8_JAR="${D8_JAR:-$HOME/android-sdk/build-tools/35.0.0/lib/d8.jar}"
CC_ARM64="${CC_ARM64:-$( [ "$(uname -m)" = aarch64 ] && echo gcc || echo aarch64-linux-gnu-gcc )}"
KEY="${HOT_KEY:-$HOME/.trough/hot-key.pem}"
PUBLISH="${PUBLISH:-/mnt/shared/Download/trough/hot}"
ENTRY="${ENTRY:-com.trough.hot.Main}"

B="$(mktemp -d)"; trap 'rm -rf "$B"' EXIT
mkdir -p "$B/z/bin" "$B/obj" "$B/dex"

"$CC_ARM64" -O2 -static -o "$B/z/bin/bench" "$ROOT/native/bench/bench.c"
"$CC_ARM64" -O2 -static -nostdlib -ffreestanding -fno-builtin -fno-stack-protector \
    -o "$B/z/bin/probe" "$ROOT/native/probe/probe.c"

find "$ROOT/hot/src" -name '*.java' > "$B/sources.txt"
if ! javac -nowarn -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -classpath "$ANDROID_JAR" \
    -d "$B/obj" @"$B/sources.txt" > "$B/javac.log" 2>&1; then
    grep -v 'warning: \[options\]' "$B/javac.log" >&2; echo "javac failed" >&2; exit 1
fi
find "$B/obj" -name '*.class' > "$B/classes.txt"
java -Xmx384m -cp "$D8_JAR" com.android.tools.r8.D8 --output "$B/dex" --lib "$ANDROID_JAR" \
    --min-api 28 @"$B/classes.txt"
cp "$B/dex/classes.dex" "$B/z/"
printf '{"entry":"%s","built_at":%s,"git":"%s"}\n' "$ENTRY" "$(date +%s)" \
    "$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo dev)" > "$B/z/manifest.json"
(cd "$B/z" && zip -q -X -r "$B/bundle.zip" .)
openssl dgst -sha256 -sign "$KEY" -out "$B/bundle.sig" "$B/bundle.zip"

# Signature first, then the zip, each by rename: a half-written pair just fails verification
# and is retried on the next poll.
mkdir -p "$PUBLISH"
cp "$B/bundle.sig" "$PUBLISH/.bundle.sig.tmp" && mv -f "$PUBLISH/.bundle.sig.tmp" "$PUBLISH/bundle.sig"
cp "$B/bundle.zip" "$PUBLISH/.bundle.zip.tmp" && mv -f "$PUBLISH/.bundle.zip.tmp" "$PUBLISH/bundle.zip"
echo "published $(sha256sum "$PUBLISH/bundle.zip" | cut -c1-8) ($(stat -c %s "$PUBLISH/bundle.zip") bytes) -> $PUBLISH"
