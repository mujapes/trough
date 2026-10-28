#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
W=$(mktemp -d)
trap 'rm -rf "$W"' EXIT
H="$ROOT/hot/src/com/trough/hot"
${CC:-cc} -std=c11 -O2 -Wall -Wextra -Werror "$ROOT/native/runtime/process.c" "$ROOT/native/runtime/run.c" -o "$W/trough-run"
javac -d "$W/classes" "$H/Json.java" "$H/ScenarioProtocol.java" "$H/ScenarioProcess.java" "$H/NativeReproduction.java" \
    "$ROOT/tests/jvm/ScenarioProtocolTest.java" "$ROOT/tests/jvm/NativeReproductionTest.java"
java -cp "$W/classes" com.trough.hot.ScenarioProtocolTest "$W"
java -cp "$W/classes" com.trough.hot.NativeReproductionTest "$W"
