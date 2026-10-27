#!/bin/bash
# Unpack a root zip exactly as the phone does (hot/.../DebRoot.java): regular files from the zip,
# then dirs.txt (empty dirs), links.txt (symlinks), exec.txt (exec bits). Tests use this so they
# exercise what ships, not the VM's build tree (empty dirs missing from the zip, 10-27).
#   tools/unpack_root.sh <root.zip> <dest>
set -euo pipefail
Z="$1" D="$2"
rm -rf "$D" && mkdir -p "$D"
python3 - "$Z" "$D" <<'PY'
import os, sys, zipfile
z, d = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(z) as f:
    for e in f.infolist():
        if e.is_dir(): os.makedirs(os.path.join(d, e.filename), exist_ok=True); continue
        p = os.path.join(d, e.filename)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with f.open(e) as src, open(p, "wb") as dst: dst.write(src.read())
def lines(n):
    p = os.path.join(d, n)
    return [l for l in open(p).read().split("\n") if l] if os.path.exists(p) else []
for rel in lines("dirs.txt"): os.makedirs(os.path.join(d, rel), exist_ok=True)
for l in lines("links.txt"):
    rel, target = l.split("\t", 1)
    p = os.path.join(d, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    os.symlink(target, p)
for rel in lines("exec.txt"): os.chmod(os.path.join(d, rel), 0o755)
PY
