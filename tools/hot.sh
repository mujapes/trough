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
mkdir -p "$B/z/bin"

# Native code, by the same script Trough runs on the phone (native/build.sh). Sources ship too,
# so the phone can rebuild them (Self.buildNative) and compare (SelfTest.nativeRepro).
CC="$CC_ARM64" sh "$ROOT/native/build.sh" "$ROOT/native" "$B/z"
python3 "$ROOT/tools/set_interp.py" "$B/z/bin/threadtest" "$B/z/bin/dnstest" "$B/z/bin/nsprobe"
mkdir -p "$B/native" && (cd "$ROOT/native" && cp --parents bench/bench.c probe/probe.c texec/texec.c \
    threadtest/threadtest.c threadtest/dnstest.c nsprobe/nsprobe.c execmap/execmap.c "$B/native/") && cp "$ROOT/native/build.sh" "$B/native/"

# Trough's own glibc: the VM's arm64 copy, with trapped syscalls rewritten to -ENOSYS.
GLIBC_DIR="${GLIBC_DIR:-/lib/aarch64-linux-gnu}"
for l in ld-linux-aarch64.so.1 libc.so.6 libm.so.6 librt.so.1 libpthread.so.0 libdl.so.2; do
    cp "$GLIBC_DIR/$l" "$B/z/lib/"
done
python3 "$ROOT/tools/patch_svc.py" "$B/z/lib/"*
# (lib/libexecmap.so, the exec mapper texec preloads, came from native/build.sh above.)
python3 "$ROOT/tools/patch_paths.py" "$B/z/lib/libc.so.6"

# Payloads: too big for the bundle. Content-addressed in shared storage; their hashes go in
# the signed manifest, so they are verified on the phone as they stream in.
PAYLOAD_DIR="$(dirname "$PUBLISH")/payload"
CLAUDE_BIN="${CLAUDE_BIN:-$(readlink -f "$HOME/.local/bin/claude")}"
PAYLOADS=""
if [ -f "$CLAUDE_BIN" ]; then
    # Interp rewritten in place (patchelf's layout change segfaults Bun's embedded payload).
    cp "$CLAUDE_BIN" "$B/claude" && python3 "$ROOT/tools/set_interp.py" "$B/claude"
    sha=$(sha256sum "$B/claude" | cut -d' ' -f1)
    mkdir -p "$PAYLOAD_DIR"
    [ -f "$PAYLOAD_DIR/$sha" ] || { cp "$B/claude" "$PAYLOAD_DIR/.$sha.tmp" && mv "$PAYLOAD_DIR/.$sha.tmp" "$PAYLOAD_DIR/$sha"; }
    PAYLOADS="\"claude\":\"$sha\""
fi
# Codex: a static musl binary, so no PT_INTERP to point at texec; musl's (and a Rust crate's)
# /etc/resolv.conf rewritten to Trough's, same length (tools/patch_paths.py --any).
CODEX_BIN="${CODEX_BIN:-$(ls /usr/lib/node_modules/@openai/codex/node_modules/@openai/codex-linux-arm64/vendor/aarch64-unknown-linux-musl/bin/codex 2>/dev/null || true)}"
if [ -n "$CODEX_BIN" ] && [ -f "$CODEX_BIN" ]; then
    cp "$CODEX_BIN" "$B/codex" && python3 "$ROOT/tools/patch_paths.py" --any "$B/codex"
    sha=$(sha256sum "$B/codex" | cut -d' ' -f1)
    [ -f "$PAYLOAD_DIR/$sha" ] || { cp "$B/codex" "$PAYLOAD_DIR/.$sha.tmp" && mv "$PAYLOAD_DIR/.$sha.tmp" "$PAYLOAD_DIR/$sha"; }
    PAYLOADS="$PAYLOADS${PAYLOADS:+,}\"codex\":\"$sha\""
    rm -f "$B/codex"
fi
# Trough's Debian root (self-hosting toolchain, tools/root.sh). Rebuilt with ROOTFS=1.
ROOT_ZIP="${ROOT_ZIP:-$HOME/.trough/root.zip}"
if [ "${ROOTFS:-0}" = 1 ]; then "$ROOT/tools/root.sh" "$ROOT_ZIP" >&2; fi
# Trough's own files in d/ + the package list (small, signed): what a phone that already has a
# root converges to (DebRoot.converge); the root zip itself only bootstraps.
if [ -f "${ROOT_ZIP%.zip}files.zip" ]; then mkdir -p "$B/z/rootfiles" && (cd "$B/z/rootfiles" && unzip -q "${ROOT_ZIP%.zip}files.zip"); fi
if [ -f "$ROOT_ZIP" ]; then
    sha=$(sha256sum "$ROOT_ZIP" | cut -d' ' -f1)
    [ -f "$PAYLOAD_DIR/$sha" ] || { cp "$ROOT_ZIP" "$PAYLOAD_DIR/.$sha.tmp" && mv "$PAYLOAD_DIR/.$sha.tmp" "$PAYLOAD_DIR/$sha"; }
    PAYLOADS="$PAYLOADS${PAYLOADS:+,}\"root\":\"$sha\""
fi

# Trough holds its own source and rebuilds itself from it on the phone (hot/.../Self.java), with
# the same script, so the bundle carries src/, build.sh and the two SDK jars build.sh needs.
"$ROOT/hot/build.sh" "$ROOT/hot/src" "$B/java" "$ANDROID_JAR" "$D8_JAR"
cp "$B/java/classes.dex" "$B/z/"
cp -r "$ROOT/hot/src" "$B/z/src" && cp "$ROOT/hot/build.sh" "$B/z/build.sh"
cp -r "$B/native" "$B/z/src/native"   # native sources live in files/src too (Self.buildNative)
for jar in "$ANDROID_JAR" "$D8_JAR"; do
    sha=$(sha256sum "$jar" | cut -d' ' -f1)
    [ -f "$PAYLOAD_DIR/$sha" ] || { cp "$jar" "$PAYLOAD_DIR/.$sha.tmp" && mv "$PAYLOAD_DIR/.$sha.tmp" "$PAYLOAD_DIR/$sha"; }
    PAYLOADS="$PAYLOADS${PAYLOADS:+,}\"$(basename "$jar")\":\"$sha\""
done
printf '{"entry":"%s","built_at":%s,"git":"%s","payloads":{%s}}\n' "$ENTRY" "$(date +%s)" \
    "$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo dev)" "$PAYLOADS" > "$B/z/manifest.json"
(cd "$B/z" && zip -q -X -r "$B/bundle.zip" .)
openssl dgst -sha256 -sign "$KEY" -out "$B/bundle.sig" "$B/bundle.zip"

# Signature first, then the zip, each by rename: a half-written pair just fails verification
# and is retried on the next poll.
mkdir -p "$PUBLISH"
cp "$B/bundle.sig" "$PUBLISH/.bundle.sig.tmp"
mv -f "$PUBLISH/.bundle.sig.tmp" "$PUBLISH/bundle.sig"
cp "$B/bundle.zip" "$PUBLISH/.bundle.zip.tmp"
mv -f "$PUBLISH/.bundle.zip.tmp" "$PUBLISH/bundle.zip"
echo "published $(sha256sum "$PUBLISH/bundle.zip" | cut -c1-8) ($(stat -c %s "$PUBLISH/bundle.zip") bytes) -> $PUBLISH"
