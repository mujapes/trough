#!/bin/bash
# Trough builds itself: compile + run C and Java with the root's own toolchain, under the
# simulated phone filter, with a cleared environment. Run after tools/root.sh.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; R=/data/data/t.t; D=$R/d
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
gcc -O2 -o "$W/sim" "$ROOT/tests/seccomp_sim.c"
ENV="env -i HOME=$R/home TMPDIR=$W PATH=$D/usr/bin"
F=0
cp "$ROOT/native/threadtest/threadtest.c" "$W/t.c"
printf 'class Hello{public static void main(String[] a){System.out.println("java ok");}}\n' > "$W/Hello.java"
cd "$W"
$ENV "$W/sim" "$D/usr/bin/gcc" -O2 t.c -o t -lpthread && "$W/sim" ./t | grep -q '"ok":true' && echo "ok    gcc: build + run threaded C" || { echo "FAIL  gcc"; F=$((F+1)); }
$ENV "$W/sim" "$D/usr/bin/javac" Hello.java && $ENV "$W/sim" "$D/usr/bin/java" Hello | grep -q "java ok" && echo "ok    javac + java" || { echo "FAIL  java"; F=$((F+1)); }
echo "== $F failed"; exit $F
