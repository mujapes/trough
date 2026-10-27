#!/bin/bash
# trough-apt on the phone's view of the filesystem (tests/phonefs.sh: no /bin /usr /etc /tmp,
# simulated app seccomp filter, ordinary uid): Debian's signatures verified by the root's gpgv
# via APT::Key::GPGVCommand, packages with maintainer scripts installed (#!/bin/sh -> execmap),
# tfix after dpkg, the installed programs run. Plus the negative control: a wrong keyring must
# make apt refuse the index. Tests the shipped root.zip, unpacked as DebRoot does (replaces d/).
# Network: deb.debian.org.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
D=/data/data/t.t/d; P="$ROOT/tests/phonefs.sh"; F=0
ok() { if [ "$1" = 0 ]; then echo "ok    $2"; else echo "FAIL  $2"; F=$((F+1)); fi; }
# What ships: the root zip unpacked the way the phone does it, not the VM's build tree.
ZIP="${ROOT_ZIP:-$HOME/.trough/root.zip}"
"$ROOT/tools/unpack_root.sh" "$ZIP" "$D"
gcc -O2 -shared -fPIC -o /data/data/t.t/l/libexecmap.so "$ROOT/native/execmap/execmap.c" -ldl
SRC=$D/etc/apt/sources.list.d/debian.sources
cp "$SRC" "$SRC.good"
sed -i 's|debian-archive-keyring.gpg|debian-archive-removed-keys.gpg|' "$SRC"
rm -rf $D/var/lib/apt/lists/*
out=$("$P" $D/usr/local/bin/apt-get update 2>&1); rc=$?
mv "$SRC.good" "$SRC"
[ $rc != 0 ] && echo "$out" | grep -q "is not signed" && [ "$(ls $D/var/lib/apt/lists | grep -c Packages)" = 0 ]
ok $? "negative control: wrong keyring -> not signed, no index kept"
"$P" $D/usr/local/bin/apt-get update >/dev/null 2>&1; ok $? "apt-get update: InRelease verified by the root's gpgv"
[ "$(ls $D/var/lib/apt/lists | grep -c Packages)" -ge 3 ]; ok $? "verified indexes kept"
out=$("$P" $D/usr/local/bin/apt-get install -y less jq 2>&1); rc=$?
[ $rc = 0 ]; ok $? "apt-get install less jq (maintainer scripts via #!/bin/sh)"
echo "$out" | grep -qE 'Setting up less' ; ok $? "less configured (postinst ran)"
[ "$(readlink $D/etc/alternatives/pager)" = "$D/usr/bin/less" ]; ok $? "update-alternatives ran, link re-rooted by tfix"
[ "$("$P" $D/usr/bin/sh -c 'echo "{\"a\":[1,2]}" | jq -c .a' 2>&1)" = "[1,2]" ]; ok $? "installed jq runs (PT_INTERP -> texec)"
"$P" $D/usr/bin/sh -c 'less --version' 2>&1 | grep -q '^less '; ok $? "installed less runs"
"$P" $D/usr/bin/dpkg-query --admindir=$D/var/lib/dpkg -W -f='${Status}' jq 2>&1 | grep -q 'install ok installed'; ok $? "dpkg database: jq installed"
out=$("$P" $D/usr/local/bin/apt-get remove -y jq 2>&1); [ $? = 0 ] && [ ! -e $D/usr/bin/jq ]; ok $? "apt-get remove jq"
echo "== $F failed"; exit $F
