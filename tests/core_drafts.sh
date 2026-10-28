#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
W=$(mktemp -d)
trap 'rm -rf "$W"' EXIT
${CC:-cc} -std=c11 -O2 -g -Wall -Wextra -Werror -pedantic ${CORE_CFLAGS:-} -I"$ROOT/native/store" \
    "$ROOT/native/store/store.c" "$ROOT/native/store/document.c" "$ROOT/native/store/draft.c" \
    "$ROOT/native/scenarios/drafts.c" -lsqlite3 -pthread -o "$W/oracle"
"$W/oracle" "$W/data"
