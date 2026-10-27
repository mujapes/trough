#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
W=$(mktemp -d)
trap 'rm -rf "$W"' EXIT
H="$ROOT/hot/src/com/trough/hot"
javac -d "$W/classes" "$H/Json.java" "$H/ScenarioProtocol.java" "$H/ScenarioProcess.java" "$ROOT/tests/jvm/ScenarioProtocolTest.java"
java -cp "$W/classes" com.trough.hot.ScenarioProtocolTest "$W"
