#!/bin/sh
# Trough's native store, built a second time against bionic (Android's C library), so Java can
# load it in-process (System.load) and the same oracles can run as Android programs.
# docs/BIONIC.md. Inputs, fetched and verified by tools/bionic-deps.sh:
#   BIONIC_SYSROOT  Termux's ndk-sysroot (headers, crt objects): <dir>/usr/include, <dir>/usr/lib
#   SQLITE_SRC      directory with Debian's sqlite3.c/sqlite3.h amalgamation
#   BIONIC_INCLUDE  extra headers (Khronos Vulkan; the NDK's ICU4C headers)
#   HARFBUZZ_SRC    HarfBuzz's src/ (harfbuzz.cc and headers), for native shaping
#   HB_CACHE        optional: a directory caching the HarfBuzz object by source and flag hash
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
HB=${HARFBUZZ_SRC:?HARFBUZZ_SRC}
FT=${FREETYPE_SRC:?FREETYPE_SRC}
PNG=${PNG_SRC:?PNG_SRC}
CLANGXX=${CLANGXX:-$(echo "$CLANG" | sed 's/clang/clang++/')}
W=$(mktemp -d)
trap 'rm -rf "$W"' EXIT
mkdir -p "$O/bin" "$O/lib" "$W/stub"
# libgcc supplies compiler builtins only (no libc dependency); atomics stay inline.
GCCLIB=$(dirname "$(gcc -print-libgcc-file-name)")
T="--target=aarch64-linux-android28 --sysroot=$SYS -B$SYS/usr/lib -mno-outline-atomics"
# Link stubs: an empty library per soname gives DT_NEEDED; Android's linker resolves symbols
# at load time from the device's own libraries.
echo 'void __trough_stub(void) {}' > "$W/stub/stub.c"
for l in c m dl log android vulkan icu z; do
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
# HarfBuzz as C++ without its runtime (no exceptions, RTTI or libc++): it needs only libc.
# Native text needs API 31 (AFontMatcher is 29, the NDK's libicu 31); Trough's text already did.
T31=$(echo "$T" | sed 's/android28/android31/')
HBFLAGS="-std=c++17 -O2 -fPIC -fvisibility=hidden -fno-exceptions -fno-rtti -fno-threadsafe-statics -DHAVE_PTHREAD -DNDEBUG"
HBF="$HBFLAGS -ffile-prefix-map=$HB=harfbuzz"
# Cache by compiler, flags and input bytes, never by the temporary bundle/sysroot paths.
HBKEY=$( (cd "$HB" && find . -type f \( -name '*.cc' -o -name '*.hh' -o -name '*.h' \) | LC_ALL=C sort | xargs sha256sum
    cd "$SYS/usr/include" && find . -type f | LC_ALL=C sort | xargs sha256sum
    echo "$CLANGXX --target=aarch64-linux-android31 -mno-outline-atomics $HBFLAGS"
    $CLANGXX --version) | sha256sum | cut -c1-16)
if [ -n "${HB_CACHE:-}" ] && [ -f "$HB_CACHE/hb-$HBKEY.o" ]; then cp "$HB_CACHE/hb-$HBKEY.o" "$W/hb.o"
else
    $CLANGXX $T31 $HBF -c "$HB/harfbuzz.cc" -o "$W/hb.o"
    if [ -n "${HB_CACHE:-}" ]; then mkdir -p "$HB_CACHE" && cp "$W/hb.o" "$HB_CACHE/hb-$HBKEY.o"; fi
fi
# FreeType provides the same hinted advances used by Android's Skia. Compile its default
# modules with libpng for bitmap strikes; libpng uses Android's public libz.
FT_OBJS=""
FTKEY=$( (cd "$FT" && find include src -type f | LC_ALL=C sort | xargs sha256sum
    cd "$PNG" && find . -type f | LC_ALL=C sort | xargs sha256sum
    cd "$SYS/usr/include" && find . -type f | LC_ALL=C sort | xargs sha256sum
    echo "$CLANG --target=aarch64-linux-android31 -mno-outline-atomics -O2 -fPIC -fvisibility=hidden -DFT2_BUILD_LIBRARY -DFT_CONFIG_OPTION_USE_PNG -DPNG_ARM_NEON_OPT=0 -DNDEBUG"
    $CLANG --version) | sha256sum | cut -c1-16)
cp "$PNG/scripts/pnglibconf.h.prebuilt" "$W/pnglibconf.h"
PNG_OBJS=""
for unit in png pngerror pngget pngmem pngpread pngread pngrio pngrtran pngrutil pngset pngtrans pngwio pngwrite pngwtran pngwutil; do
    obj="$W/$unit.o"
    cached="${HB_CACHE:-$W}/png-$FTKEY-$unit.o"
    if [ -f "$cached" ]; then cp "$cached" "$obj"
    else
        $CLANG $T31 -O2 -fPIC -fvisibility=hidden -DPNG_ARM_NEON_OPT=0 -DNDEBUG -ffile-prefix-map="$PNG"=libpng -I"$PNG" -I"$W" -c "$PNG/$unit.c" -o "$obj"
        if [ -n "${HB_CACHE:-}" ]; then
            mkdir -p "$HB_CACHE"
            cp "$obj" "$cached.tmp.$$" && mv "$cached.tmp.$$" "$cached"
        fi
    fi
    PNG_OBJS="$PNG_OBJS $obj"
done
for unit in base/ftbase base/ftinit base/ftsystem base/ftdebug base/ftmm base/ftglyph base/ftbbox base/ftbitmap autofit/autofit truetype/truetype type1/type1 cff/cff cid/type1cid pfr/pfr type42/type42 winfonts/winfnt pcf/pcf bdf/bdf psaux/psaux psnames/psnames pshinter/pshinter sfnt/sfnt smooth/smooth raster/raster sdf/sdf svg/svg gzip/ftgzip lzw/ftlzw; do
    obj="$W/ft-$(basename "$unit").o"
    cached="${HB_CACHE:-$W}/ft-$FTKEY-$(basename "$unit").o"
    if [ -f "$cached" ]; then cp "$cached" "$obj"
    else
        $CLANG $T31 -O2 -fPIC -fvisibility=hidden -DFT2_BUILD_LIBRARY -DFT_CONFIG_OPTION_USE_PNG -DNDEBUG -ffile-prefix-map="$FT"=freetype -I"$FT/include" -I"$PNG" -I"$W" -c "$FT/src/$unit.c" -o "$obj"
        if [ -n "${HB_CACHE:-}" ]; then
            mkdir -p "$HB_CACHE"
            cp "$obj" "$cached.tmp.$$" && mv "$cached.tmp.$$" "$cached"
        fi
    fi
    FT_OBJS="$FT_OBJS $obj"
done
$CLANG $T31 $CORE -DUCONFIG_USE_LOCAL -isystem "$INC" -isystem "$HB" -isystem "$FT/include" -c "$S/text/shape.c" -o "$W/shape.o"
$CLANG $T31 $CORE -DUCONFIG_USE_LOCAL -isystem "$INC" -c "$S/text/measure.c" -o "$W/measure.o"
$CLANG $T31 $CORE -DUCONFIG_USE_LOCAL -isystem "$INC" -c "$S/text/bidi.c" -o "$W/bidi.o"
$CLANG $T $CORE -ffp-contract=off -c "$S/text/breaks.c" -o "$W/breaks.o"
$CLANG $T31 $CORE -ffp-contract=off -DUCONFIG_USE_LOCAL -isystem "$INC" -c "$S/text/words.c" -o "$W/words.o"
$CLANG $T31 $CORE -ffp-contract=off -c "$S/text/paragraph.c" -o "$W/paragraph.o"
$CLANG $T31 $CORE -DUCONFIG_USE_LOCAL -isystem "$INC" -c "$S/text/visual.c" -o "$W/visual.o"
$CLANG $T $CORE -ffp-contract=off -c "$S/text/layout.c" -o "$W/layout.o"
$CLANG $T $CORE -shared -I"$S/store" -I"$S/core" -isystem "$INC" -I"$S/present" "$S/android/jni.c" "$S/android/present.c" "$S/android/derive.c" "$S/android/text.c" "$S/text/ownership.c" -I"$S/text" "$W/shape.o" "$W/measure.o" "$W/bidi.o" "$W/breaks.o" "$W/words.o" "$W/paragraph.o" "$W/visual.o" "$W/layout.o" "$W/hb.o" \
    "$S/core/world.c" "$S/core/derive.c" "$S/core/blobs.c" "$S/core/exec.c" "$S/core/resources.c" "$S/core/atlas.c" "$S/core/order.c" "$S/present/scene.c" "$S/present/oracle.c" $FT_OBJS $PNG_OBJS "$@" $L -lm -ldl -llog -landroid -lvulkan -licu -lz -lc -Wl,-soname,libtrough-android.so \
    -o "$O/lib/libtrough-android.so"
# Public Android API symbols are resolved on the device, but bundled engines must be complete.
if nm -D --undefined-only "$O/lib/libtrough-android.so" | awk '$NF ~ /^(FT_|hb_|png_)/ { print; bad=1 } END { exit !bad }'; then
    echo "bionic.sh: unresolved bundled font-engine symbols" >&2
    exit 1
fi
