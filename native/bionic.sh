#!/bin/sh
# Trough's native store, built a second time against bionic (Android's C library), so Java can
# load it in-process (System.load) and the same oracles can run as Android programs.
# docs/BIONIC.md. Inputs, fetched and verified by tools/bionic-deps.sh:
#   BIONIC_SYSROOT  Termux's ndk-sysroot (headers, crt objects): <dir>/usr/include, <dir>/usr/lib
#   SQLITE_SRC      directory with Debian's sqlite3.c/sqlite3.h amalgamation
#   BIONIC_INCLUDE  extra headers (Khronos Vulkan) for the presenter
#   native/bionic.sh <native-src-dir> <out-dir>      (CLANG defaults to clang-19)
# Outputs: bin/android-core-* (bionic oracle executables; bin/ so the APK marks them executable),
# lib/libtrough-android.so (the JNI library). Not in outputs.list: the phone root cannot rebuild
# them yet (no clang there), so build.native does not compare them.
# PT_INTERP stays /system/bin/linker64; set_interp.py and tfix leave bionic programs alone.
set -eu
S=$1 O=$2
CLANG=${CLANG:-clang-19}
SYS=${BIONIC_SYSROOT:?BIONIC_SYSROOT}
SQL=${SQLITE_SRC:?SQLITE_SRC}
INC=${BIONIC_INCLUDE:?BIONIC_INCLUDE}
W=$(mktemp -d)
trap 'rm -rf "$W"' EXIT
mkdir -p "$O/bin" "$O/lib" "$W/stub"
# libgcc supplies compiler builtins only (no libc dependency); atomics stay inline.
GCCLIB=$(dirname "$(gcc -print-libgcc-file-name)")
T="--target=aarch64-linux-android28 --sysroot=$SYS -B$SYS/usr/lib -mno-outline-atomics"
# Link stubs: an empty library per soname gives DT_NEEDED; Android's linker resolves symbols
# at load time from the device's own libraries.
echo 'void __trough_stub(void) {}' > "$W/stub/stub.c"
for l in c m dl log android vulkan; do
    $CLANG $T -fuse-ld=lld -shared -nostdlib -o "$W/stub/lib$l.so" "$W/stub/stub.c" -Wl,-soname,lib$l.so
done
L="-fuse-ld=lld -L$W/stub -L$SYS/usr/lib -L$GCCLIB -Wl,--unresolved-symbols=ignore-in-object-files -Wl,--build-id=none"
CORE="-std=c11 -O2 -Wall -Wextra -Werror -fPIC -ffile-prefix-map=$S=native -DNDEBUG"
SQLF="-O2 -fPIC -DNDEBUG -DSQLITE_THREADSAFE=1 -DSQLITE_DEFAULT_WAL_SYNCHRONOUS=2 -DSQLITE_OMIT_LOAD_EXTENSION -ffile-prefix-map=$SQL=sqlite"
$CLANG $T $SQLF -c "$SQL/sqlite3.c" -o "$W/sqlite3.o"
set -- "$W/sqlite3.o"
for part in store document stream task workspace draft text keeper; do
    $CLANG $T $CORE -I"$SQL" -c "$S/store/$part.c" -o "$W/$part.o"
    set -- "$@" "$W/$part.o"
done
for scenario in store documents streams tasks workspace drafts text keeper; do
    $CLANG $T $CORE -fPIE -pie -I"$S/store" -I"$SQL" "$S/scenarios/$scenario.c" "$@" $L -lm -ldl -lc -o "$O/bin/android-core-$scenario"
done
$CLANG $T $CORE -shared -I"$S/store" -I"$S/core" -isystem "$INC" -I"$S/present" "$S/android/jni.c" "$S/android/present.c" "$S/android/derive.c" \
    "$S/core/world.c" "$S/core/derive.c" "$S/core/blobs.c" "$S/core/exec.c" "$S/present/scene.c" "$S/present/oracle.c" "$@" $L -lm -ldl -llog -landroid -lvulkan -lc -Wl,-soname,libtrough-android.so \
    -o "$O/lib/libtrough-android.so"
