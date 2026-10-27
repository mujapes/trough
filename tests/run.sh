#!/bin/bash
# The texec/glibc oracle: Android's app seccomp filter and the /data/data/t.t layout,
# reproduced on any arm64 Linux box (the Forge VM, GitHub's arm64 runner). Every check here
# passed on the Pixel 7a or reproduces something that failed there. Needs sudo once to create
# /data/data/t.t. Exit status = number of failed checks.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
R=/data/data/t.t
GLIBC_DIR="${GLIBC_DIR:-/lib/aarch64-linux-gnu}"
OUT="$(mktemp -d)"; trap 'rm -rf "$OUT"' EXIT
FAILS=0
check() {  # check <name> <expected-exit> <cmd...>
    local name="$1" want="$2"; shift 2
    local got out
    out="$("$@" 2>&1 </dev/null)"; got=$?
    if [ "$got" = "$want" ]; then echo "ok    $name"; else echo "FAIL  $name (exit $got, want $want)"; echo "$out" | tail -3 | sed 's/^/      /'; FAILS=$((FAILS+1)); fi
}
check_out() {  # check_out <name> <regex> <cmd...>
    local name="$1" re="$2"; shift 2
    local out; out="$("$@" 2>&1 </dev/null)"
    if echo "$out" | grep -qE "$re"; then echo "ok    $name"; else echo "FAIL  $name (no /$re/)"; echo "$out" | tail -3 | sed 's/^/      /'; FAILS=$((FAILS+1)); fi
}

[ -w "$R" ] || { sudo mkdir -p "$R" && sudo chown "$(id -u)" "$R"; }
mkdir -p "$R/l" "$R/bin" "$R/home" "$R/cache"
FREE="-O2 -static -nostdlib -ffreestanding -fno-builtin -fno-stack-protector"
gcc -O2 -o "$OUT/sim" "$ROOT/tests/seccomp_sim.c"
gcc $FREE -o "$OUT/probe" "$ROOT/native/probe/probe.c"
gcc $FREE -Wl,-Ttext-segment=0x6f00000000 -o "$R/x" "$ROOT/native/texec/texec.c"
gcc -O2 -static -o "$OUT/bench" "$ROOT/native/bench/bench.c"
for l in ld-linux-aarch64.so.1 libc.so.6 libm.so.6 librt.so.1 libpthread.so.0 libdl.so.2; do cp "$GLIBC_DIR/$l" "$R/l/"; done
python3 "$ROOT/tools/patch_svc.py" "$R/l/"* >/dev/null
gcc -O2 -shared -fPIC -o "$R/l/libexecmap.so" "$ROOT/native/execmap/execmap.c" -ldl   # preloaded by texec, as on the phone
python3 "$ROOT/tools/patch_paths.py" "$R/l/libc.so.6" >/dev/null
# Android has no nsswitch.conf, so glibc falls back to plain DNS via resolv.conf. Hide this
# box's nsswitch.conf from the test copy, or it would resolve through systemd-resolved and
# never exercise the redirected resolv.conf (a hollow pass, seen 10-27).
python3 - "$R/l/libc.so.6" <<'EOF'
import sys
p = sys.argv[1]; d = open(p, 'rb').read()
open(p, 'wb').write(d.replace(b"/etc/nsswitch.conf\0", b"/nonexistent/nsswi\0"))
EOF
printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > "$R/r"
for t in threadtest dnstest; do
    gcc -O2 -o "$R/bin/$t" "$ROOT/native/threadtest/$t.c" && python3 "$ROOT/tools/set_interp.py" "$R/bin/$t" >/dev/null
done

echo "== the simulated filter matches the phone"
check_out "probe sees exactly the phone's trapped set" \
    '"set_robust_list":"SIGSYS","rseq":"SIGSYS".*"futex_waitv_einval":"SIGSYS"' "$OUT/sim" "$OUT/probe"
check "stock static glibc dies, as on the phone (159)" 159 "$OUT/sim" "$OUT/bench"

echo "== texec + patched glibc"
check "static glibc via texec launcher" 0 "$OUT/sim" "$R/x" "$OUT/bench"
check_out "threadtest exec'd directly (kernel loads texec)" '"ok":true' "$OUT/sim" "$R/bin/threadtest"
check_out "dnstest exec'd directly" '"rc":0' "$OUT/sim" "$R/bin/dnstest"
check_out "dnstest via python subprocess, env cleared, fds closed" '"rc":0' \
    "$OUT/sim" "$R/x" /usr/bin/python3 -c "import subprocess; print(subprocess.run(['$R/bin/dnstest'], env={}, capture_output=True, text=True).stdout)"

echo "== negative controls (each must fail, or the checks above prove nothing)"
mv "$R/r" "$R/r.off"
check "no resolv.conf -> DNS fails" 1 "$OUT/sim" "$R/bin/dnstest"
mv "$R/r.off" "$R/r"
# texec always puts its own lib dir first, so the control needs a texec built against an
# entirely unpatched glibc.
mkdir -p "$OUT/stock"
cp "$GLIBC_DIR/ld-linux-aarch64.so.1" "$GLIBC_DIR/libc.so.6" "$OUT/stock/"
gcc $FREE -Wl,-Ttext-segment=0x6f00000000 -DTROUGH_LIB="\"$OUT/stock\"" \
    -DTROUGH_LD="\"$OUT/stock/ld-linux-aarch64.so.1\"" -o "$OUT/texec-stock" "$ROOT/native/texec/texec.c"
check "unpatched glibc -> signal-blocking threads killed (159)" 159 "$OUT/sim" "$OUT/texec-stock" "$R/bin/threadtest"

CLAUDE_BIN="${CLAUDE_BIN:-$(readlink -f "$HOME/.local/bin/claude" 2>/dev/null)}"
if [ -n "$CLAUDE_BIN" ] && [ -f "$CLAUDE_BIN" ]; then
    echo "== claude ($CLAUDE_BIN)"
    cp "$CLAUDE_BIN" "$R/bin/claude" && python3 "$ROOT/tools/set_interp.py" "$R/bin/claude" >/dev/null
    check_out "claude --version" 'Claude Code' "$OUT/sim" "$R/bin/claude" --version
    check_out "claude -p, fake key: reaches the API and is refused" 'Invalid API key' \
        env -i HOME="$R/home" TMPDIR="$R/cache" ANTHROPIC_API_KEY=sk-ant-trough-invalid \
        timeout 90 "$OUT/sim" "$R/bin/claude" -p "say ok" --model haiku
fi
echo "== $FAILS failed"
exit $FAILS
