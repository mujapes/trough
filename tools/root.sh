#!/bin/bash
# Assemble Trough's Debian root (d/) from a package list, reproducibly, and pack it for the
# phone. Packages are imported from this machine's apt-verified install: exactly the files
# `dpkg -L` lists, so no download and nothing unverified. Then: GCC specs, glibc link names,
# tfix (PT_INTERP -> texec, re-rooted symlinks, refuse dangling links).
#   tools/root.sh <out.zip> [stage=/data/data/t.t/d]
# The zip holds regular files; empty dirs, symlinks and exec bits travel as dirs.txt / links.txt /
# exec.txt because Android's ZipInputStream creates none of them.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(realpath -m "${1:?usage: root.sh <out.zip> [stage]}")"
D="${2:-/data/data/t.t/d}"
R=/data/data/t.t

# What Trough builds itself with (DESIGN.md "Self-hosting toolchain"). libc6 is NOT imported:
# Trough ships its own patched glibc in l/.
# git: files/src is a repo (Self.java merges each bundle seed into the agent's edits). Its perl
# scripts (add -i, send-email, svn) are left out with perl.
# apt + dpkg + gpgv: trough-apt, verifying Debian's signatures with gpgv via the supported
# APT::Key::GPGVCommand (apt 3.0.3 acquire-item.cc: set -> the gpgv method, not /usr/bin/sqv).
WANT="gcc-14 libc6-dev linux-libc-dev libcrypt-dev openjdk-21-jdk-headless git bash apt dpkg gpgv debian-archive-keyring
      coreutils dash debianutils diffutils findutils grep gzip sed hostname"   # Debian's Essential set: dpkg and maintainer scripts assume it
EXCLUDE='^(libc6|libc-bin|gcc-14-base|debconf|perl-base|install-info|rpcsvc-proto|java-common|adduser|passwd|base-passwd|libpam.*|login|util-linux|mount|systemd.*|dbus.*|ca-certificates|base-files|perl|perl-modules-.*|libperl.*|liberror-perl|git-man)$'
ALL=$(python3 "$ROOT/tools/dpkg_closure.py" $WANT)
PKGS=$(echo "$ALL" | grep -vE "$EXCLUDE")
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
[ -e "$D/bin" ] || ln -s usr/bin "$D/bin"      # merged /usr, as Debian: execmap maps /bin/sh -> d/bin/sh
[ -e "$D/sbin" ] || ln -s usr/sbin "$D/sbin"
# Trough keeps no ld.so cache: texec puts the library dirs on LD_LIBRARY_PATH for every program.
# dpkg insists on finding an ldconfig, so it gets one that says so and succeeds.
mkdir -p "$D/usr/sbin"
printf '#!%s/usr/bin/sh\n# Trough: no ld.so.cache by design (texec sets LD_LIBRARY_PATH); nothing to rebuild.\nexit 0\n' "$D" > "$D/usr/sbin/ldconfig"
chmod +x "$D/usr/sbin/ldconfig"
# tfix, native (no Python on the phone), and apt/apt-get/dpkg wrappers that run it after every
# package operation: apt's own hooks chdir to /tmp first, which Android lacks.
mkdir -p "$D/usr/libexec/trough" "$D/usr/local/bin"
gcc -O2 -Wall -o "$D/usr/libexec/trough/tfix" "$ROOT/native/tfix/tfix.c"
for t in apt apt-get dpkg; do
    if [ $t = dpkg ]; then real="$D/usr/bin/dpkg --instdir=$D --admindir=$D/var/lib/dpkg --log=$D/var/log/dpkg.log --force-not-root --force-script-chrootless --force-bad-path"
    else real="$D/usr/bin/$t"; fi
    cat > "$D/usr/local/bin/$t" <<WRAP
#!$D/usr/bin/sh
# Trough: the real $t, then tfix on the root (PT_INTERP -> texec, absolute links re-rooted).
$real "\$@"
rc=\$?
$D/usr/libexec/trough/tfix $D >&2 || echo "trough: tfix reported problems (above)" >&2
exit \$rc
WRAP
    chmod +x "$D/usr/local/bin/$t"
done
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

# Alternatives (made by maintainer scripts, so in no dpkg -L list): recreate the host's choice
# -- master link -> /etc/alternatives/<name> -> target -- for every target that is in d/.
mkdir -p "$D/etc/alternatives"
update-alternatives --get-selections | while read -r name _ _; do
    target=$(readlink "/etc/alternatives/$name") || continue
    [ -e "$D$target" ] || [ -L "$D$target" ] || continue
    ln -sfn "$target" "$D/etc/alternatives/$name"
    link=$(update-alternatives --query "$name" | sed -n 's/^Link: //p')
    [ -n "$link" ] && { mkdir -p "$D$(dirname "$link")"; ln -sfn "/etc/alternatives/$name" "$D$link"; }
done

# dpkg's database: every package of the closure is recorded as installed, including the
# excluded ones (libc6 is Trough's own glibc in l/; adduser, passwd, systemd... mean nothing in
# an app), so apt sees a consistent system. Files lists only for what is really in d/.
mkdir -p "$D/var/lib/dpkg/info" "$D/var/lib/dpkg/updates" "$D/var/lib/dpkg/triggers" \
    "$D/var/lib/apt/lists/partial" "$D/var/cache/apt/archives/partial" "$D/var/log/apt" \
    "$D/etc/apt/apt.conf.d" "$D/etc/apt/preferences.d" "$D/etc/apt/sources.list.d" "$D/etc/apt/trusted.gpg.d"
: > "$D/var/lib/dpkg/available"
for p in $ALL; do dpkg-query -s "$p" 2>/dev/null && echo; done > "$D/var/lib/dpkg/status"
for p in $ALL; do   # dpkg's own records, by glob: multiarch ones are named <pkg>:<arch>.*
    in_root=0; echo "$PKGS" | grep -qx "$p" && in_root=1
    for f in /var/lib/dpkg/info/"$p".list /var/lib/dpkg/info/"$p":*.list; do
        [ -f "$f" ] || continue
        b=$(basename "$f" .list)
        if [ $in_root = 1 ]; then cp "$f" "$D/var/lib/dpkg/info/"; else : > "$D/var/lib/dpkg/info/$b.list"; fi
        if [ $in_root = 1 ] && [ -f "/var/lib/dpkg/info/$b.md5sums" ]; then cp "/var/lib/dpkg/info/$b.md5sums" "$D/var/lib/dpkg/info/"
        else : > "$D/var/lib/dpkg/info/$b.md5sums"; fi
    done
done
# apt rooted in d/ (APT_CONFIG points here, Root.env). dpkg as a normal user into d/.
cat > "$D/etc/apt/apt.conf" <<CONF
Dir "$D/";
Dir::Bin::Methods "$D/usr/lib/apt/methods/";
Dir::Bin::dpkg "$D/usr/bin/dpkg";
APT::Key::GPGVCommand "$D/usr/bin/gpgv";
Acquire::https::CaInfo "$D/etc/ssl/certs/ca-certificates.crt";
Dir::dpkg::cputable "$D/usr/share/dpkg/cputable";
Dir::dpkg::tupletable "$D/usr/share/dpkg/tupletable";
Dir::dpkg::triplettable "$D/usr/share/dpkg/triplettable";
DPkg::Path "$D/usr/sbin:$D/usr/bin:/system/bin";
DPkg::Options { "--instdir=$D"; "--admindir=$D/var/lib/dpkg"; "--log=$D/var/log/dpkg.log"; "--force-not-root"; "--force-script-chrootless"; "--force-bad-path"; };
CONF
cat > "$D/etc/apt/sources.list.d/debian.sources" <<SRC
Types: deb
URIs: https://deb.debian.org/debian
Suites: trixie trixie-updates
Components: main
Signed-By: $D/usr/share/keyrings/debian-archive-keyring.gpg

Types: deb
URIs: https://deb.debian.org/debian-security
Suites: trixie-security
Components: main
Signed-By: $D/usr/share/keyrings/debian-archive-keyring.gpg
SRC

python3 "$ROOT/tools/tfix.py" "$D" >&2   # l/ exists here (tests/run.sh) and on the phone (Root)

(cd "$D" && find . -type d -empty -printf '%P\n' > dirs.txt \
    && find . -type l -printf '%P\t%l\n' > links.txt \
    && find . -type f -perm -u+x ! -name '*.so*' -printf '%P\n' > exec.txt)
rm -f "$OUT"; (cd "$D" && find . -type f -print0 | xargs -0 zip -q -X "$OUT")
echo "root.sh: $(du -sh "$D" | cut -f1) -> $(du -h "$OUT" | cut -f1) ($(wc -l < "$D/links.txt") links, $(wc -l < "$D/exec.txt") executables)" >&2
