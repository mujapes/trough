#!/bin/sh
# Derivation graph against a memo-free reference (docs/DERIVE.md). No device IO.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
W=$(mktemp -d)
trap 'rm -rf "$W"' EXIT
${CC:-cc} -std=c11 -O2 -g -Wall -Wextra -Werror -pedantic ${CORE_CFLAGS:-} -I"$ROOT/native/core" \
    "$ROOT/native/core/world.c" "$ROOT/native/core/derive.c" "$ROOT/native/scenarios/derive.c" -o "$W/oracle"
"$W/oracle" "$@"
