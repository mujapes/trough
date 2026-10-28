#!/bin/sh
# Atlas residency with fence lifetimes against a shadow GPU (docs/ATLAS.md). No device IO.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
W=$(mktemp -d)
trap 'rm -rf "$W"' EXIT
${CC:-cc} -std=c11 -O2 -g -Wall -Wextra -Werror -pedantic ${CORE_CFLAGS:-} -I"$ROOT/native/core" \
    "$ROOT/native/core/atlas.c" "$ROOT/native/scenarios/atlas.c" -o "$W/oracle"
"$W/oracle" "$@"
