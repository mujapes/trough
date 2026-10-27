#!/bin/bash
# The phone's filesystem, in the VM: run a command under the seccomp simulator in a private mount
# namespace whose / holds only what an Android app sees of Trough -- /data/data/t.t, /proc, /dev
# -- with no /bin, /usr, /etc or /tmp. A hard-coded host path then fails here exactly as it
# would on the phone, instead of silently working (the sqv misreading, 10-27).
# VM test harness only: Trough itself never uses namespaces (the phone has none to give).
#   tests/phonefs.sh <cmd> [args...]     (cmd is a path under /data/data/t.t; env is cleared)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
R=/data/data/t.t
mkdir -p "$R/cache/phonefs"
[ "$R/cache/phonefs/sim" -nt "$ROOT/tests/seccomp_sim.c" ] || gcc -O2 -static -o "$R/cache/phonefs/sim" "$ROOT/tests/seccomp_sim.c"
gcc -O2 -Wall -o "$R/cache/phonefs/enter" "$ROOT/tests/phonefs.c"
exec "$R/cache/phonefs/enter" "$R/cache/phonefs/sim" "$R/d/usr/bin/env" -i HOME=$R/home TMPDIR=$R/cache \
    PATH=$R/d/usr/local/bin:$R/d/usr/bin:$R/d/usr/sbin APT_CONFIG=$R/d/etc/apt/apt.conf "$@"
