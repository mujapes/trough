#!/bin/sh
# Native draft state across a real hot replacement (docs/KEEPER.md "Retirement"): publish a
# bundle, type into the app's draft store without flushing, publish a different bundle, and
# require the successor to reopen the store and find every edit. Needs the phone loop
# (tools/loop.sh's transport); publishes twice.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
S="$ROOT/tools/scenario.py"
OUT=$(mktemp -d); trap 'rm -rf "$OUT"' EXIT
NO_SCENARIOS=1 "$ROOT/tools/loop.sh" >/dev/null
python3 "$S" android.retire.setup --timeout 60 --out "$OUT/setup.json"
# A second, different bundle: the marker changes the bundle hash and nothing else.
RETIRE_MARK=$(date +%s%N) NO_SCENARIOS=1 "$ROOT/tools/loop.sh" >/dev/null
python3 "$S" android.retire.check --timeout 60 --out "$OUT/check.json"
python3 - "$OUT/check.json" <<'PY'
import json, sys
r = json.load(open(sys.argv[1])); o = r.get('outcome', r)
print(json.dumps(o)); sys.exit(0 if o.get('ok') else 1)
PY
