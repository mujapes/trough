#!/bin/sh
# Inputs for native/bionic.sh, fetched and verified (docs/BIONIC.md). Prints the two exports.
#   BIONIC_SYSROOT: Termux's ndk-sysroot r30 (Apache-2.0/BSD NDK headers and crt objects),
#                   pinned by SHA-256 from Termux's package index.
#   SQLITE_SRC:     Debian's sqlite3 3.46.1-7+deb13u2 source with Debian's patches applied, the
#                   same release the root's libsqlite3 comes from, amalgamated by its own Makefile.
# Needs: curl, dpkg-dev (dpkg-source), tclsh, make, cc; clang-19 and lld-19 for the build itself.
set -eu
C=${TROUGH_CACHE:-$HOME/.cache/trough-sysroot}
mkdir -p "$C"
SYSROOT_DEB=ndk-sysroot_30_aarch64.deb
SYSROOT_SHA=73502f9b04fce210c430c83bf9ae2b8e332ba5c319f3eb94a61ba5cef090f787
SQLITE_DSC=sqlite3_3.46.1-7+deb13u2.dsc
SQLITE_C_SHA=9dc06fa808506de2b43498ad60043075ba9ad0cb5ff7978704f61a4fca53aede
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
    # apt verifies the source package against the signed Debian archive; dpkg-source checks
    # every tarball against the .dsc's checksums.
    [ -f "$SQLITE_DSC" ] || apt-get source --download-only sqlite3=3.46.1-7+deb13u2 >&2
    rm -rf src && dpkg-source --no-check -x "$SQLITE_DSC" src >&2
    mkdir -p "$A" && cd "$A" && ../configure --disable-tcl >/dev/null && make sqlite3.c >/dev/null
fi
echo "$SQLITE_C_SHA  $A/sqlite3.c" | sha256sum -c - >&2
echo "$SQLITE_H_SHA  $A/sqlite3.h" | sha256sum -c - >&2
echo "BIONIC_SYSROOT=$C/x/data/data/com.termux/files"
echo "SQLITE_SRC=$A"
