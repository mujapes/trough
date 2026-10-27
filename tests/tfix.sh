#!/bin/bash
# Differential oracle: native/tfix (C, runs on the phone) == tools/tfix.py (the reference) on
# identical trees: same bytes in every file, same link targets, same summary, same dangling set.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
gcc -O2 -Wall -Wextra -o "$W/tfix" "$ROOT/native/tfix/tfix.c" || exit 1
T="$W/tree"; mkdir -p "$T/bin" "$T/lib" "$T/etc"
cp /usr/bin/ls /usr/bin/true "$T/bin/"                                   # dynamic ELFs
printf 'int main(void){return 0;}\n' > "$W/s.c" && gcc -static -o "$T/bin/static" "$W/s.c"   # no PT_INTERP
cp "$(ldd /usr/bin/ls | awk '/libc.so/{print $3}')" "$T/lib/"            # shared lib with PT_INTERP
printf '#!/bin/sh\necho hi\n' > "$T/bin/script"                           # not ELF
cp /usr/bin/true "$T/bin/fixed" && python3 "$ROOT/tools/set_interp.py" "$T/bin/fixed" >/dev/null   # already texec
ln -s /etc/hostname "$T/etc/abs-missing"                                  # abs, re-rooted, dangles under /R
ln -s /bin/ls "$T/etc/abs-ls"
ln -s ../bin/ls "$T/etc/rel"                                              # relative: untouched
ln -s /data/data/t.t/x "$T/etc/trough"                                    # already Trough's
mkdir -p "$T/R/bin" && cp /usr/bin/true "$T/R/bin/ls"
ln -s "$T/R/bin/ls" "$T/etc/abs-present"                                  # abs under nothing special
cp -a "$T" "$W/py"; cp -a "$T" "$W/c"
python3 "$ROOT/tools/tfix.py" "$W/py" --root /R > "$W/py.out"; py=$?
"$W/tfix" "$W/c" --root /R > "$W/c.out"; c=$?
F=0
ok() { if [ "$1" = 0 ]; then echo "ok    $2"; else echo "FAIL  $2"; F=$((F+1)); fi; }
[ $py = $c ]; ok $? "same exit status ($py)"
[ "$(head -1 "$W/py.out" | sed "s|$W/py|T|")" = "$(head -1 "$W/c.out" | sed "s|$W/c|T|")" ]; ok $? "same summary: $(head -1 "$W/c.out" | sed "s|$W/c|T|")"
diff <(tail -n +2 "$W/py.out" | sed "s|$W/py|T|g" | sort) <(tail -n +2 "$W/c.out" | sed "s|$W/c|T|g" | sort) >/dev/null; ok $? "same dangling set ($(grep -c ' -> ' "$W/c.out"))"
diff <(cd "$W/py" && find . -type f -exec sha256sum {} + | sort) <(cd "$W/c" && find . -type f -exec sha256sum {} + | sort) >/dev/null; ok $? "byte-identical files"
diff <(cd "$W/py" && find . -type l -printf '%p %l\n' | sed "s|$W/py|T|g" | sort) <(cd "$W/c" && find . -type l -printf '%p %l\n' | sed "s|$W/c|T|g" | sort) >/dev/null; ok $? "identical link targets"
# Negative control: the comparison must notice a difference.
printf x >> "$W/c/bin/true"
! diff <(cd "$W/py" && find . -type f -exec sha256sum {} + | sort) <(cd "$W/c" && find . -type f -exec sha256sum {} + | sort) >/dev/null; ok $? "negative control: a 1-byte change is detected"
readelf -l "$W/c/bin/ls" | grep -q 'interpreter: /data/data/t.t/x'; ok $? "ls now uses texec"
echo "== $F failed"; exit $F
