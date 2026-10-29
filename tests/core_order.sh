#!/bin/sh
# The paragraph order tree (docs/ORDER.md) against a flat array. No device IO.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
W=$(mktemp -d)
trap 'rm -rf "$W"' EXIT
${CC:-cc} -std=c11 -O2 -g -Wall -Wextra -Werror -pedantic ${CORE_CFLAGS:-} -I"$ROOT/native/core" \
    "$ROOT/native/core/order.c" "$ROOT/native/scenarios/order.c" -o "$W/oracle"
"$W/oracle" "$@"
