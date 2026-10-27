#!/bin/bash
# Assemble Trough's Debian root (d/) from a package list, reproducibly, and pack it for the
# phone. Packages are imported from this machine's apt-verified install: exactly the files
# `dpkg -L` lists, so no download and nothing unverified. Then: GCC specs, glibc link names,
# tfix (PT_INTERP -> texec, re-rooted symlinks, refuse dangling links).
#   tools/root.sh <out.zip> [stage=/data/data/t.t/d]
# The zip holds regular files; symlinks and exec bits travel as links.txt / exec.txt because
# Android's ZipInputStream can create neither.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(realpath -m "${1:?usage: root.sh <out.zip> [stage]}")"
D="${2:-/data/data/t.t/d}"
R=/data/data/t.t

# What Trough builds itself with (DESIGN.md "Self-hosting toolchain"). libc6 is NOT imported:
# Trough ships its own patched glibc in l/.
WANT="gcc-14 libc6-dev linux-libc-dev libcrypt-dev openjdk-21-jdk-headless"
EXCLUDE='^(libc6|libc-bin|gcc-14-base|debconf|dpkg|perl-base|tar|install-info|rpcsvc-proto|java-common|adduser|passwd|base-passwd|libpam.*|login|util-linux|mount|systemd.*|dbus.*|ca-certificates)$'
PKGS=$(apt-cache depends --recurse --no-recommends --no-suggests --no-conflicts --no-breaks \
    --no-replaces --no-enhances --installed $WANT 2>/dev/null | grep -vE '^\s|^<' | sort -u | grep -vE "$EXCLUDE")
echo "root.sh: $(echo "$PKGS" | wc -l) packages" >&2

rm -rf "$D" && mkdir -p "$D"
LIST="$(mktemp)"; trap 'rm -f "$LIST"' EXIT
for p in $PKGS; do dpkg -L "$p" 2>/dev/null; done | sort -u | while read -r f; do
    if [ -L "$f" ] || [ -f "$f" ]; then echo "$f"; fi
done > "$LIST"
# --ignore-failed-read: a few conffiles are root-only (/etc/default/cacerts); none are needed.
(cd / && tar -cf - --no-recursion --ignore-failed-read -T "$LIST" 2>/dev/null) | tar -C "$D" -xf -
# Generated at install time by maintainer scripts, so not in any dpkg -L list.
mkdir -p "$D/etc/ssl/certs/java" "$D/etc/ssl/private" && cp -L /etc/ssl/certs/java/cacerts "$D/etc/ssl/certs/java/"
cp -L /etc/ssl/certs/ca-certificates.crt "$D/etc/ssl/certs/"
[ -e "$D/lib" ] || ln -s usr/lib "$D/lib"
ln -sf gcc-14 "$D/usr/bin/gcc"; ln -sf gcc-14 "$D/usr/bin/cc"
for b in java javac jar; do ln -sf ../lib/jvm/java-21-openjdk-arm64/bin/$b "$D/usr/bin/$b"; done

# GCC: link through texec, headers/startfiles from d/ (tools/gcc_specs.py).
gcc -dumpspecs | python3 "$ROOT/tools/gcc_specs.py" > "$D/usr/lib/gcc/aarch64-linux-gnu/14/specs"
# glibc's development link names resolve to Trough's patched glibc in l/.
L="$D/usr/lib/aarch64-linux-gnu"
for n in ld-linux-aarch64.so.1 libc.so.6 libm.so.6 librt.so.1 libpthread.so.0 libdl.so.2; do
    [ -e "$L/$n" ] || ln -sf "$R/l/$n" "$L/$n"
done
ln -sf "$R/l/ld-linux-aarch64.so.1" "$D/usr/lib/ld-linux-aarch64.so.1"

python3 "$ROOT/tools/tfix.py" "$D" >&2   # l/ exists here (tests/run.sh) and on the phone (Root)

(cd "$D" && find . -type l -printf '%P\t%l\n' > links.txt \
    && find . -type f -perm -u+x ! -name '*.so*' -printf '%P\n' > exec.txt)
rm -f "$OUT"; (cd "$D" && find . -type f -print0 | xargs -0 zip -q -X "$OUT")
echo "root.sh: $(du -sh "$D" | cut -f1) -> $(du -h "$OUT" | cut -f1) ($(wc -l < "$D/links.txt") links, $(wc -l < "$D/exec.txt") executables)" >&2
