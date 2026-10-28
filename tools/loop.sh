#!/bin/sh
# One turn of the VM-orchestrated development loop (docs/AUTONOMOUS_DEVELOPMENT.md):
# publish a signed bundle, wait until the phone reports running exactly that bundle, then run
# every scenario the running build advertises. Signed results land in
# Download/trough/evidence/<bundle sha>/. No user taps; Trough may be visible or behind another app.
#   tools/loop.sh            publish the working tree, then verify it
#   NO_PUBLISH=1 tools/loop.sh   verify whatever is running now
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SHARED="${SHARED:-/mnt/shared/Download/trough}"
WAIT="${WAIT:-300}"
S="$ROOT/tools/scenario.py"

if [ -z "${NO_PUBLISH:-}" ]; then
    "$ROOT/tools/hot.sh" >/dev/null
    want=$(sha256sum "$SHARED/hot/bundle.zip" | cut -d' ' -f1)
else
    want=""
fi

end=$(( $(date +%s) + WAIT ))
while :; do
    obs=$(python3 "$S" app.observe --timeout 30 2>/dev/null || true)
    have=$(printf '%s' "$obs" | python3 -c 'import json,sys
try:
    r = json.load(sys.stdin)
    if r["status"] == "done": print(r["bundle"])
except Exception: pass')  # a request caught by the reload comes back "interrupted": ask again
    if [ -n "$have" ] && { [ -z "$want" ] || [ "$have" = "$want" ]; }; then break; fi
    [ "$(date +%s)" -lt "$end" ] || { echo "phone did not activate ${want:-a bundle} within ${WAIT}s (running: ${have:-nothing})" >&2; exit 1; }
    sleep 5
done

out="$SHARED/evidence/$have"
mkdir -p "$out"
printf '%s' "$obs" > "$out/app.observe.json"
fail=0
for name in $(printf '%s' "$obs" | python3 -c 'import json,sys; print(" ".join(json.load(sys.stdin)["outcome"]["scenarios"]))'); do
    [ "$name" = app.observe ] && continue
    limit=120
    [ "$name" != build.native ] || limit=600
    if python3 "$S" "$name" --timeout "$limit" --out "$out/$name.json" >/dev/null 2>"$out/$name.err"; then
        rm -f "$out/$name.err"; echo "ok   $name"
    else
        fail=1; echo "FAIL $name (see $out)"
    fi
done
echo "bundle $(printf '%.12s' "$have") evidence: $out"
exit $fail
