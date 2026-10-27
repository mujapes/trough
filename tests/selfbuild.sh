#!/bin/bash
# Trough rebuilds itself: hot/build.sh compiles hot/src to a dex with the root's own javac and
# java (d8), each under the simulated phone filter with a cleared environment, exactly as
# Self.java runs it on the phone. The shell stays outside the filter: in the VM /bin/sh is stock
# glibc, on the phone it is bionic's /system/bin/sh. Run after tools/root.sh.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; R=/data/data/t.t; D=$R/d
AJ="${ANDROID_JAR:-$HOME/android-sdk/platforms/android-35/android.jar}"
D8="${D8_JAR:-$HOME/android-sdk/build-tools/35.0.0/lib/d8.jar}"
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
gcc -O2 -o "$W/sim" "$ROOT/tests/seccomp_sim.c"
mkdir -p "$W/path"
for b in javac java; do
    printf '#!/bin/sh\nexec env -i HOME=%s/home TMPDIR=%s PATH=%s/usr/bin %s/sim %s/usr/bin/%s "$@"\n' \
        "$R" "$W" "$D" "$W" "$D" "$b" > "$W/path/$b"; chmod +x "$W/path/$b"
done
build() { env -i TMPDIR="$W" PATH="$W/path:/usr/bin:/bin" sh "$ROOT/hot/build.sh" "$1" "$2" "$AJ" "$D8" >"$W/log" 2>&1; }
F=0
t0=$(date +%s%N)
if build "$ROOT/hot/src" "$W/out" && [ -s "$W/out/classes.dex" ] && head -c 4 "$W/out/classes.dex" | grep -q '^dex'; then
    echo "ok    hot/src -> classes.dex under the filter ($(( ($(date +%s%N) - t0) / 1000000 )) ms, $(stat -c %s "$W/out/classes.dex") bytes)"
else echo "FAIL  self build"; tail -5 "$W/log"; F=$((F+1)); fi
# Negative control: a broken tree must fail and leave no dex, or the pass above proves nothing.
cp -r "$ROOT/hot/src" "$W/bad" && echo 'class Broken { int x = ; }' > "$W/bad/Broken.java"
if build "$W/bad" "$W/badout" || [ -e "$W/badout/classes.dex" ]; then echo "FAIL  broken source built"; F=$((F+1));
else echo "ok    broken source fails, no dex"; fi
echo "== $F failed"; exit $F
