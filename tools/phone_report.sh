#!/bin/bash
# Wait for the phone's next boot report and summarise it. Reports are named
# status-<epoch ms>.json (Main.statusFile); the VM's view of shared storage has stale mtimes, so
# this keys on the name: the first report newer than <since-ms> (default: now) that reached
# step "done" (or the latest step after --timeout seconds).
#   tools/phone_report.sh [since-ms] [--timeout 1800]
set -uo pipefail
DIR="${TROUGH_SHARED:-/mnt/shared/Download/trough}"
SINCE="${1:-$(($(date +%s) * 1000))}"; T=1800
[ "${2:-}" = --timeout ] && T="$3"
end=$(($(date +%s) + T)); f=""
while [ "$(date +%s)" -lt "$end" ]; do
    f=$(ls "$DIR" | sed -n 's/^status-\([0-9]*\)\.json$/\1/p' | awk -v s="$SINCE" '$1 > s' | sort -n | tail -1)
    if [ -n "$f" ] && python3 -c "import json,sys;sys.exit(0 if json.load(open('$DIR/status-$f.json')).get('step')=='done' else 1)" 2>/dev/null; then break; fi
    sleep 10
done
[ -z "$f" ] && { echo "no report since $SINCE"; exit 1; }
python3 - "$DIR/status-$f.json" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
g = lambda *k: (lambda v: v)(__import__('functools').reduce(lambda o, x: (o or {}).get(x) if isinstance(o, dict) else None, k, d))
print(sys.argv[1].rsplit('/', 1)[1], "step", d.get("step"), "| running", d.get("running"), "| seed", d.get("seed"))
print("root:", d.get("root_install"), "| files:", d.get("root_files"), "| packages current:", g("root_packages", "current"), "ok:", g("root_packages", "ok"))
errs = {k: str(v)[:160] for k, v in d.items() if k.endswith("_error")}
print("errors:", errs or "none")
print("selftest:", {k: v.get("exit") for k, v in (d.get("selftest") or {}).items()})
print("self_edit:", g("self_edit", "ok"), "| seed_merge:", g("seed_merge", "ok"), "| native_repro:", g("native_repro", "ok"),
      "| apt:", g("apt", "ok"), "jq_before:", g("apt", "jq_before"), "| codex refused:", g("codex", "refused"),
      "| providers:", [v.get("refused") for v in (d.get("providers") or {}).values()])
PY
