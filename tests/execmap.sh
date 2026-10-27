#!/bin/bash
# execmap oracle: every interposed entry point finds programs and #! interpreters under the
# root when the stock absolute path is missing -- and the same calls FAIL without the preload
# (negative control). Paths under /usr/libexec/trough-test stand in for /bin etc. on Android.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
T=/usr/libexec/trough-test
[ -e "$T" ] && { echo "$T exists here; the test needs it absent"; exit 1; }
R="$W/root"; mkdir -p "$R$T/bin"
mkdir -p "$W/platform/bin"
gcc -O2 -Wall -Wextra -shared -fPIC -DTROUGH_ROOT="\"$R\"" -DTROUGH_SH="\"$T/bin/sh\"" -DTROUGH_PLATFORM="\"$W/platform/\"" \
    -o "$W/libexecmap.so" "$ROOT/native/execmap/execmap.c" -ldl || exit 1
gcc -O2 -Wall -o "$W/drive" "$ROOT/tests/execmap_test.c" || exit 1
printf '#include <stdio.h>\nint main(int c,char**v){printf("hello-ok %%s %%s\\n",v[0],c>1?v[1]:"-");return 0;}\n' > "$W/h.c"
gcc -o "$R$T/bin/hello" "$W/h.c"
cp /usr/bin/dash "$R$T/bin/sh"
printf '#!%s/bin/sh\necho "script-ok $0 $1"\n' "$T" > "$R$T/bin/script"
printf 'echo "noshebang-ok $1"\n' > "$R$T/bin/noshebang"
printf '#!%s/bin/sh -e\necho "shebang-arg-ok $1"\n' "$T" > "$R$T/bin/argscript"
printf '#!%s/bin/sh\necho "printenv-ok $MARK"\n' "$T" > "$R$T/bin/printenv-mark"
chmod +x "$R$T/bin/"*
# A path the device ALSO has (Android's /bin -> /system/bin): the root's copy must win.
mkdir -p "$R/usr/bin" && printf '#!/bin/sh\necho root-first-ok\n' > "$R/usr/bin/true" && chmod +x "$R/usr/bin/true"
F=0
mkdir -p "$W/l1" "$W/l2"
with="$(LINKDIR="$W/l1" LD_PRELOAD="$W/libexecmap.so" "$W/drive" 2>&1)"
without="$(LINKDIR="$W/l2" "$W/drive" 2>&1)"
expect() {  # expect <label> <regex>
    if echo "$with" | grep -qE "$2"; then echo "ok    $1"; else echo "FAIL  $1 (no /$2/)"; F=$((F+1)); fi
}
expect execve '^hello-ok execve arg1$'
expect "execv + mapped #! interpreter" '^script-ok .*/bin/script arg1$'
expect "execvp PATH search" '^hello-ok execvp arg1$'
expect execl '^hello-ok execl arg1$'
expect "execlp + #!" '^script-ok .*/bin/script arg1$'
expect "execle keeps the given env" '^printenv-ok env-ok$'
expect "execvp: no #! -> mapped sh" '^noshebang-ok arg1$'
expect "#! with one argument" '^shebang-arg-ok arg1$'
expect "posix_spawn + #!" '^script-ok .*/bin/script arg1$'
expect posix_spawnp '^hello-ok posix_spawnp arg1$'
expect "system() via mapped sh" '^system-ok sh$'
expect "system() status" '^system status 0$'
expect "popen/pclose via mapped sh" '^popen read: popen-ok$'
expect "pclose status" '^pclose 0$'
expect "link() denied -> copy, same content and mode" '^link: copied same-content mode 640$'
expect "link() onto an existing name -> EEXIST" '^link existing: EEXIST$'
expect "linkat() denied -> copy" '^linkat: copied$'
echo "$without" | grep -q '^link: FAILED Permission denied' && echo "ok    negative control: without the preload link() is EACCES" || { echo "FAIL  link negative control"; F=$((F+1)); }
n=$(echo "$with" | grep -c FAILED)
if [ "$n" = 0 ]; then echo "ok    no failures with the preload"; else echo "FAIL  $n failures with the preload"; echo "$with" | grep FAILED | sed 's/^/      /'; F=$((F+1)); fi
n=$(echo "$without" | grep -cE 'FAILED|status 127|pclose 32512')
if [ "$n" -ge 12 ]; then echo "ok    negative control: without the preload $n calls fail"; else echo "FAIL  negative control: only $n failures without the preload"; echo "$without" | sed 's/^/      /'; F=$((F+1)); fi
out="$(LD_PRELOAD="$W/libexecmap.so" dash -c '/usr/bin/true' 2>&1)"; [ "$out" = root-first-ok ] && echo "ok    root copy wins over a device path of the same name" || { echo "FAIL  root-first ($out)"; F=$((F+1)); }
out="$(dash -c '/usr/bin/true; echo $?' 2>&1)"; [ "$out" = 0 ] && echo "ok    negative control: without the preload the device's copy runs" || { echo "FAIL  root-first negative control"; F=$((F+1)); }
# A platform (bionic) binary must not inherit glibc's loader variables.
printf '#include <stdio.h>\n#include <stdlib.h>\nint main(void){puts(getenv("LD_PRELOAD")||getenv("LD_LIBRARY_PATH")?"platform-env-leak":"platform-env-ok");return 0;}\n' > "$W/p.c"
gcc -o "$W/platform/bin/plat" "$W/p.c"
out="$(LD_LIBRARY_PATH=/x LD_PRELOAD="$W/libexecmap.so" dash -c "$W/platform/bin/plat" 2>&1)"; [ "$out" = platform-env-ok ] && echo "ok    platform binaries get no glibc loader variables" || { echo "FAIL  platform env ($out)"; F=$((F+1)); }
out="$(LD_LIBRARY_PATH=/x dash -c "$W/platform/bin/plat" 2>&1)"; [ "$out" = platform-env-leak ] && echo "ok    negative control: without the preload they leak" || { echo "FAIL  platform env negative control ($out)"; F=$((F+1)); }
# Maintainer scripts (and only they) run under Debian's fakechroot with the root as base.
FC=$(dpkg -L libfakechroot 2>/dev/null | grep '/libfakechroot.so$' | head -1)
if [ -z "$FC" ]; then echo "FAIL  libfakechroot is not installed (apt install libfakechroot; hot.sh ships it)"; F=$((F+1)); else
R2="$W/root2"; mkdir -p "$R2/bin" "$R2/usr/bin"
cp /usr/bin/dash "$R2/bin/sh"; cp /usr/bin/mkdir /usr/bin/touch "$R2/usr/bin/"
gcc -O2 -Wall -Wextra -shared -fPIC -DTROUGH_ROOT="\"$R2\"" -DTROUGH_FAKECHROOT="\"$FC\"" \
    -DTROUGH_HOST_PATHS="\"$W:/proc:/dev:/sys:/lib:/usr/lib\"" -o "$W/fc.so" "$ROOT/native/execmap/execmap.c" -ldl
[ -e /etc/.trough-test ] && { echo "/etc/.trough-test exists here; the test needs it absent"; exit 1; }
printf '#!/bin/sh\nmkdir -p /etc/.trough-test/x && touch /etc/.trough-test/x/f && echo script-ok\n' > "$W/postinst"; chmod +x "$W/postinst"
out="$(DPKG_MAINTSCRIPT_NAME=postinst LD_PRELOAD="$W/fc.so" dash -c "$W/postinst" 2>&1)"
[ "$out" = script-ok ] && [ -f "$R2/etc/.trough-test/x/f" ] && [ ! -e /etc/.trough-test ] && echo "ok    maintainer script's /etc writes land in the root" || { echo "FAIL  maintainer script under fakechroot ($out)"; F=$((F+1)); }
rm -rf "$R2/etc"
out="$(LD_PRELOAD="$W/fc.so" dash -c "$W/postinst" 2>&1)"
[ "$out" != script-ok ] && [ ! -e "$R2/etc" ] && echo "ok    negative control: other programs get no path translation" || { echo "FAIL  translation leaked outside maintainer scripts"; F=$((F+1)); }
fi
# Paths the root lacks are never touched: /bin/sh here stays /bin/sh.
out="$(LD_PRELOAD="$W/libexecmap.so" sh -c 'echo $0' 2>&1)"; [ "$out" = sh ] && echo "ok    paths the root lacks untouched" || { echo "FAIL  existing paths"; F=$((F+1)); }
echo "== $F failed"; exit $F
