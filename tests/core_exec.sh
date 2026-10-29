#!/bin/sh
# Derivation executor: owner thread, workers, cancellation (docs/EXEC.md). No device IO.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
W=$(mktemp -d)
trap 'rm -rf "$W"' EXIT
${CC:-cc} -std=c11 -O2 -g -Wall -Wextra -Werror -pedantic ${CORE_CFLAGS:-} -I"$ROOT/native/core" \
    "$ROOT/native/core/world.c" "$ROOT/native/core/derive.c" "$ROOT/native/core/blobs.c" "$ROOT/native/core/exec.c" "$ROOT/native/core/resources.c" \
    "$ROOT/native/scenarios/exec.c" -pthread -o "$W/oracle"
"$W/oracle" "$@"
