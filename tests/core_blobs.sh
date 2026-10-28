#!/bin/sh
# Interned blob values through the graph and executor (docs/BLOBS.md). No device IO.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
W=$(mktemp -d)
trap 'rm -rf "$W"' EXIT
${CC:-cc} -std=c11 -O2 -g -Wall -Wextra -Werror -pedantic ${CORE_CFLAGS:-} -I"$ROOT/native/core" \
    "$ROOT/native/core/world.c" "$ROOT/native/core/derive.c" "$ROOT/native/core/blobs.c" "$ROOT/native/core/exec.c" \
    "$ROOT/native/scenarios/blobs.c" -pthread -o "$W/oracle"
"$W/oracle" "$@"
# Again with a 4-bit hash: equal-hash, different-bytes objects must stay distinct.
${CC:-cc} -std=c11 -O2 -g -Wall -Wextra -Werror -pedantic ${CORE_CFLAGS:-} -DBL_WEAK_HASH -I"$ROOT/native/core" \
    "$ROOT/native/core/world.c" "$ROOT/native/core/derive.c" "$ROOT/native/core/blobs.c" "$ROOT/native/core/exec.c" \
    "$ROOT/native/scenarios/blobs.c" -pthread -o "$W/weak"
"$W/weak" "$@"
