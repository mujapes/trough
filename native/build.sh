#!/bin/sh
# Trough's native code: sources -> executables and the preload library. One definition, run by
# tools/hot.sh in the VM and by Trough itself on the phone with the root's gcc (SelfTest.native),
# so the two builds can be compared byte for byte.
#   native/build.sh <native-src-dir> <out-dir>      (CC defaults to gcc)
# PT_INTERP of the dynamic ones is the caller's step (set_interp.py in the VM, tfix on the phone).
set -eu
S=$1 O=$2
CC=${CC:-gcc}
mkdir -p "$O/bin" "$O/lib"
FREE="-O2 -static -nostdlib -ffreestanding -fno-builtin -fno-stack-protector"
# Dynamic ones name the stock interpreter (the root's GCC specs would say texec directly): the
# caller rewrites it in place, so the VM's and the phone's builds come out byte-identical.
DYN="-Wl,-dynamic-linker,/lib/ld-linux-aarch64.so.1"
$CC -O2 -static -o "$O/bin/bench" "$S/bench/bench.c"
$CC $FREE -o "$O/bin/probe" "$S/probe/probe.c"
$CC $FREE -Wl,-Ttext-segment=0x6f00000000 -o "$O/bin/texec" "$S/texec/texec.c"
$CC -O2 $DYN -o "$O/bin/threadtest" "$S/threadtest/threadtest.c"
$CC -O2 $DYN -o "$O/bin/dnstest" "$S/threadtest/dnstest.c"
$CC -O2 -Wall -Wextra $DYN -o "$O/bin/nsprobe" "$S/nsprobe/nsprobe.c"
$CC -O2 -Wall -Wextra -shared -fPIC -o "$O/lib/libexecmap.so" "$S/execmap/execmap.c" -ldl

# The same native substrate and scenario executors are built on the VM and on the phone.
CORE="-std=c11 -O2 -Wall -Wextra -Werror -fPIC -ffile-prefix-map=$S=native"
for part in world resources modules derive blobs exec atlas order; do
    $CC $CORE -c "$S/core/$part.c" -o "$O/lib/core-$part.o"
done
${AR:-ar} rcsD "$O/lib/libtroughcore.a" "$O/lib/core-world.o" "$O/lib/core-resources.o" "$O/lib/core-modules.o" "$O/lib/core-derive.o" "$O/lib/core-blobs.o" "$O/lib/core-exec.o" "$O/lib/core-atlas.o" "$O/lib/core-order.o"
rm -f "$O/lib/core-world.o" "$O/lib/core-resources.o" "$O/lib/core-modules.o" "$O/lib/core-derive.o" "$O/lib/core-blobs.o" "$O/lib/core-exec.o" "$O/lib/core-atlas.o" "$O/lib/core-order.o"
for scenario in publication resources replacement derive atlas order; do
    $CC $CORE $DYN -I"$S/core" "$S/scenarios/$scenario.c" "$O/lib/libtroughcore.a" -ldl -o "$O/bin/core-$scenario"
done
for scenario in exec blobs; do
    $CC $CORE $DYN -I"$S/core" "$S/scenarios/$scenario.c" "$O/lib/libtroughcore.a" -pthread -o "$O/bin/core-$scenario"
done
# Durable store: SQLite from the root's own Debian packages (native/packages.list).
set --
for part in store document stream task workspace draft text keeper; do
    $CC $CORE -c "$S/store/$part.c" -o "$O/lib/$part.o"
    set -- "$@" "$O/lib/$part.o"
done
rm -f "$O/lib/libtroughstore.a"
${AR:-ar} rcsD "$O/lib/libtroughstore.a" "$@"
rm -f "$@"
for scenario in store documents streams tasks workspace drafts text keeper; do
    $CC $CORE $DYN -I"$S/store" "$S/scenarios/$scenario.c" "$O/lib/libtroughstore.a" -lsqlite3 -pthread -o "$O/bin/core-$scenario"
done
$CC $CORE -c "$S/runtime/process.c" -o "$O/lib/process.o"
${AR:-ar} rcsD "$O/lib/libtroughprocess.a" "$O/lib/process.o"
rm -f "$O/lib/process.o"
$CC $CORE $DYN "$S/runtime/run.c" "$O/lib/libtroughprocess.a" -o "$O/bin/trough-run"
$CC $CORE $DYN -I"$S/runtime" "$S/scenarios/processes.c" "$O/lib/libtroughprocess.a" -o "$O/bin/core-processes"
mkdir -p "$O/fixtures"
for version in 1 2; do
    $CC $CORE -shared -DVERSION=$version "$S/scenarios/fixtures/implementation.c" -o "$O/fixtures/module-$version.so"
done
cp "$S/outputs.list" "$O/outputs.list"
# Bionic outputs (native/bionic.sh, docs/BIONIC.md) when their verified inputs travel with the
# sources: the bundle carries bionic-inputs/ (tools/hot.sh), so the phone rebuilds these too.
if [ -d "$S/bionic-inputs" ]; then
    X=$(mktemp -d)
    dpkg-deb -x "$S/bionic-inputs/ndk-sysroot.deb" "$X"
    BIONIC_SYSROOT="$X/data/data/com.termux/files" SQLITE_SRC="$S/bionic-inputs" \
        BIONIC_INCLUDE="$S/bionic-inputs/include" sh "$S/bionic.sh" "$S" "$O"
    rm -rf "$X"
    cat "$S/outputs-bionic.list" >> "$O/outputs.list"
fi
