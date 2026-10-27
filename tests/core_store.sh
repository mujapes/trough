#!/bin/sh
# Durable store against its reference model, lifecycle, quota, object and SIGKILL oracles.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
W=$(mktemp -d)
trap 'rm -rf "$W"' EXIT
CC=${CC:-cc}
$CC -std=c11 -O2 -g -Wall -Wextra -Werror -pedantic ${CORE_CFLAGS:-} -I"$ROOT/native/store" \
    "$ROOT/native/store/store.c" "$ROOT/native/scenarios/store.c" -lsqlite3 -o "$W/oracle"
"$W/oracle" "$W/data"
