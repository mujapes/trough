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
gcc -O2 -Wall -Wextra -shared -fPIC -DTROUGH_ROOT="\"$R\"" -DTROUGH_SH="\"$T/bin/sh\"" \
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
F=0
with="$(LD_PRELOAD="$W/libexecmap.so" "$W/drive" 2>&1)"
without="$("$W/drive" 2>&1)"
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
n=$(echo "$with" | grep -c FAILED)
if [ "$n" = 0 ]; then echo "ok    no failures with the preload"; else echo "FAIL  $n failures with the preload"; echo "$with" | grep FAILED | sed 's/^/      /'; F=$((F+1)); fi
n=$(echo "$without" | grep -cE 'FAILED|status 127|pclose 32512')
if [ "$n" -ge 10 ]; then echo "ok    negative control: without the preload $n calls fail"; else echo "FAIL  negative control: only $n failures without the preload"; echo "$without" | sed 's/^/      /'; F=$((F+1)); fi
# Existing paths are never touched: /bin/sh here stays /bin/sh.
out="$(LD_PRELOAD="$W/libexecmap.so" sh -c 'echo $0' 2>&1)"; [ "$out" = sh ] && echo "ok    existing paths untouched" || { echo "FAIL  existing paths"; F=$((F+1)); }
echo "== $F failed"; exit $F
