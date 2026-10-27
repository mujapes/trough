#!/bin/bash
# Trough's native code rebuilt by Trough's own toolchain is byte-identical to the VM's build:
# native/build.sh with the VM's gcc (+ set_interp.py, as tools/hot.sh) vs the same script with the
# root's gcc inside the phone-filesystem harness (+ native tfix, as the phone). Run after
# tools/root.sh (the root must be unpacked at /data/data/t.t/d).
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
R=/data/data/t.t; F=0
A="$(mktemp -d)"; B=$R/cache/native-repro; trap 'rm -rf "$A" "$B"' EXIT
rm -rf "$B"; mkdir -p "$B/src"
sh "$ROOT/native/build.sh" "$ROOT/native" "$A" || exit 1
python3 "$ROOT/tools/set_interp.py" "$A/bin/threadtest" "$A/bin/dnstest" "$A/bin/nsprobe" "$A/bin/core-publication" "$A/bin/core-resources" "$A/bin/core-replacement" >/dev/null
tar -C "$ROOT/native" -cf - -T "$ROOT/native/sources.list" | tar -C "$B/src" -xf -
"$ROOT/tests/phonefs.sh" $R/d/usr/bin/sh $B/src/build.sh $B/src $B/out > "$A/phone.log" 2>&1
[ $? = 0 ]; r=$?; [ $r = 0 ] && echo "ok    phone-side build (root gcc, phone filesystem, app filter)" || { echo "FAIL  phone-side build"; tail -5 "$A/phone.log"; F=$((F+1)); }
"$ROOT/tests/phonefs.sh" $R/d/usr/libexec/trough/tfix $B/out >/dev/null 2>&1
for f in $(cat "$ROOT/native/outputs.list"); do
    if cmp -s "$A/$f" "$B/out/$f"; then echo "ok    $f byte-identical"; else echo "FAIL  $f differs"; F=$((F+1)); fi
done
echo "== $F failed"; exit $F
