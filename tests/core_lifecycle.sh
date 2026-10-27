#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
W=$(mktemp -d)
trap 'rm -rf "$W"' EXIT
CC=${CC:-cc}
FLAGS="-std=c11 -O2 -g -Wall -Wextra -Werror -pedantic ${CORE_CFLAGS:-}"
$CC $FLAGS -I"$ROOT/native/core" "$ROOT/native/core/world.c" "$ROOT/native/core/resources.c" \
    "$ROOT/native/scenarios/resources.c" -o "$W/resources"
"$W/resources"
$CC $FLAGS -fPIC -shared -DVERSION=1 "$ROOT/native/scenarios/fixtures/implementation.c" -o "$W/one.so"
$CC $FLAGS -fPIC -shared -DVERSION=2 "$ROOT/native/scenarios/fixtures/implementation.c" -o "$W/two.so"
$CC $FLAGS -I"$ROOT/native/core" "$ROOT/native/core/world.c" "$ROOT/native/core/resources.c" \
    "$ROOT/native/core/modules.c" "$ROOT/native/scenarios/replacement.c" -ldl -o "$W/modules"
"$W/modules" "$W/one.so" "$W/two.so"
