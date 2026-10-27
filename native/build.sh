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
