#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
W=$(mktemp -d)
trap 'rm -rf "$W"' EXIT
for mode in normal scan; do
    EXTRA=
    if [ "$mode" = scan ]; then EXTRA=-DTP_FORCE_PROC_SCAN; fi
    ${CC:-cc} -std=c11 -O2 -g -Wall -Wextra -Werror -pedantic ${CORE_CFLAGS:-} $EXTRA \
        -I"$ROOT/native/runtime" "$ROOT/native/runtime/process.c" "$ROOT/native/scenarios/processes.c" -o "$W/oracle"
    "$W/oracle" "$W/data-$mode"
done
