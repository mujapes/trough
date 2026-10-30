#!/bin/sh
# Inputs for native/bionic.sh, fetched and verified (docs/BIONIC.md). Prints the two exports.
#   BIONIC_SYSROOT: Termux's ndk-sysroot r30 (Apache-2.0/BSD NDK headers and crt objects),
#                   pinned by SHA-256 from Termux's package index.
#   BIONIC_INCLUDE: Khronos Vulkan headers from Debian's libvulkan-dev 1.4.309.0-1 (the .deb,
#                   pinned by SHA-256), so they travel with the sources.
#   SQLITE_SRC:     Debian's sqlite3 3.46.1-7+deb13u2 source with Debian's patches applied, the
#                   same release the root's libsqlite3 comes from, amalgamated by its own Makefile.
#   HARFBUZZ_SRC:   HarfBuzz 10.2.0 (MIT), the release AOSP main ships, from Debian's pool
#                   (pinned by SHA-256); built as C++ without its runtime (docs/NATIVE_TEXT.md).
#   ICU headers:    the NDK's ICU4C headers (libicu, API 31+) from AOSP external/icu at a pinned
#                   commit, checked by a hash over the extracted files; in BIONIC_INCLUDE.
# Needs: curl, dpkg-dev (dpkg-source), tclsh, make, cc; clang and lld (19) for the build itself.
set -eu
C=${TROUGH_CACHE:-$HOME/.cache/trough-sysroot}
mkdir -p "$C"
SYSROOT_DEB=ndk-sysroot_30_aarch64.deb
SYSROOT_SHA=73502f9b04fce210c430c83bf9ae2b8e332ba5c319f3eb94a61ba5cef090f787
SQLITE_DSC=sqlite3_3.46.1-7+deb13u2.dsc
SQLITE_DSC_SHA=46e1eec171e11108fc436e45309b635b601029726b6ffd3bf435bb474e511638
DEBIAN=https://deb.debian.org/debian/pool/main/s/sqlite3
SQLITE_C_SHA=9dc06fa808506de2b43498ad60043075ba9ad0cb5ff7978704f61a4fca53aede
VULKAN_CORE_SHA=39f358bb99f7be5206524d119de4a5b5ab5c515c329c2857ff395d37e6387b4c
SQLITE_H_SHA=f319f664239fdd3154721a70b7cf37fa1475703c6a4deaf7605511851c1edb17

if [ ! -d "$C/x/data/data/com.termux/files/usr/include" ]; then
    mkdir -p "$C/deb" && cd "$C/deb"
    [ -f "$SYSROOT_DEB" ] || curl -fsSLO "https://packages-cf.termux.dev/apt/termux-main/pool/main/n/ndk-sysroot/$SYSROOT_DEB"
    echo "$SYSROOT_SHA  $SYSROOT_DEB" | sha256sum -c - >&2
    rm -rf "$C/x" && dpkg-deb -x "$SYSROOT_DEB" "$C/x"
fi
A="$C/sqlite/src/bld"
if [ ! -f "$A/sqlite3.c" ]; then
    mkdir -p "$C/sqlite" && cd "$C/sqlite"
    # The .dsc is pinned here; dpkg-source checks every tarball against the .dsc's checksums.
    # Plain downloads (not apt-get source) so CI runners on other distributions can verify too.
    for f in "$SQLITE_DSC" sqlite3_3.46.1.orig.tar.xz sqlite3_3.46.1.orig-www.tar.xz sqlite3_3.46.1-7+deb13u2.debian.tar.xz; do
        [ -f "$f" ] || curl -fsSLO "$DEBIAN/$f"
    done
    echo "$SQLITE_DSC_SHA  $SQLITE_DSC" | sha256sum -c - >&2
    rm -rf src && dpkg-source --no-check -x "$SQLITE_DSC" src >&2
    mkdir -p "$A" && cd "$A" && ../configure --disable-tcl >/dev/null && make sqlite3.c >/dev/null
fi
echo "$SQLITE_C_SHA  $A/sqlite3.c" | sha256sum -c - >&2
echo "$SQLITE_H_SHA  $A/sqlite3.h" | sha256sum -c - >&2
VULKAN_DEB=libvulkan-dev_1.4.309.0-1_arm64.deb
VULKAN_DEB_SHA=3244f0422d3c5cf14f5d13bc48a0cae7786d94ea0ceff22414c094388666e70d
if [ ! -f "$C/include/vulkan/vulkan_core.h" ]; then
    mkdir -p "$C/deb" && cd "$C/deb"
    [ -f "$VULKAN_DEB" ] || curl -fsSLO "https://deb.debian.org/debian/pool/main/v/vulkan-loader/$VULKAN_DEB"
    echo "$VULKAN_DEB_SHA  $VULKAN_DEB" | sha256sum -c - >&2
    rm -rf "$C/vk" "$C/include" && dpkg-deb -x "$VULKAN_DEB" "$C/vk" && mkdir -p "$C/include"
    cp -r "$C/vk/usr/include/vulkan" "$C/vk/usr/include/vk_video" "$C/include/"
fi
echo "$VULKAN_CORE_SHA  $C/include/vulkan/vulkan_core.h" | sha256sum -c - >&2
HB_TAR=harfbuzz_10.2.0.orig.tar.xz
HB_SHA=620e3468faec2ea8685d32c46a58469b850ef63040b3565cde05959825b48227
if [ ! -f "$C/harfbuzz-10.2.0/src/harfbuzz.cc" ]; then
    mkdir -p "$C/deb" && cd "$C/deb"
    [ -f "$HB_TAR" ] || curl -fsSLO "https://deb.debian.org/debian/pool/main/h/harfbuzz/$HB_TAR"
    echo "$HB_SHA  $HB_TAR" | sha256sum -c - >&2
    rm -rf "$C/harfbuzz-10.2.0" && tar xJf "$HB_TAR" -C "$C"
fi
ICU_COMMIT=474ba9832e41cc4ee44b20a9ee07ec6d573ce1a4
ICU_TREE_SHA=1c2fbce4d20c94214b01df38076609c146d375287c2417ec348eaf57f7c3097c
icu_tree() { (cd "$C/icu" && find . -type f | LC_ALL=C sort | xargs sha256sum | sha256sum | cut -d' ' -f1); }
if [ ! -f "$C/icu/unicode/ubidi.h" ] || [ "$(icu_tree)" != "$ICU_TREE_SHA" ]; then
    rm -rf "$C/icu" && mkdir -p "$C/icu"
    curl -fsSL "https://android.googlesource.com/platform/external/icu/+archive/$ICU_COMMIT/libicu/ndk_headers.tar.gz" | tar xz -C "$C/icu"
fi
[ "$(icu_tree)" = "$ICU_TREE_SHA" ] || { echo "ICU headers: tree hash mismatch" >&2; exit 1; }
rm -rf "$C/include/unicode" && cp -r "$C/icu/unicode" "$C/icu/uconfig_local.h" "$C/include/"
echo "BIONIC_INCLUDE=$C/include"
echo "HARFBUZZ_SRC=$C/harfbuzz-10.2.0/src"
echo "BIONIC_SYSROOT=$C/x/data/data/com.termux/files"
echo "SQLITE_SRC=$A"
