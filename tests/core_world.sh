#!/bin/sh
# Native semantic kernel against deterministic and differential oracles. No model/device IO.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
W=$(mktemp -d)
trap 'rm -rf "$W"' EXIT
CC=${CC:-cc}
$CC -std=c11 -O2 -g -Wall -Wextra -Werror -pedantic ${CORE_CFLAGS:-} \
    -I"$ROOT/native/core" "$ROOT/native/core/world.c" "$ROOT/native/scenarios/publication.c" -o "$W/oracle"
"$W/oracle"
