#!/usr/bin/env python3
"""After dpkg: make everything under the Debian root work from /data/data/t.t on the phone.

1. Every stock ELF gets PT_INTERP /data/data/t.t/x (texec), in place (set_interp).
2. Every ABSOLUTE symlink is re-rooted under the Debian root: Debian packages link into /etc
   and /usr freely (the JDK's lib/jvm.cfg -> /etc/java-21-openjdk/jvm-arm64.cfg), and on the
   phone those paths do not exist. A VM test passes anyway because the VM has them -- so
3. links whose re-rooted target is missing are reported: they would dangle on the phone.

Usage: tfix.py [tree=/data/data/t.t/d] [--root /data/data/t.t/d]
"""
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from set_interp import set_interp  # noqa: E402

args = [a for a in sys.argv[1:] if not a.startswith("--")]
tree = args[0] if args else "/data/data/t.t/d"
root = sys.argv[sys.argv.index("--root") + 1] if "--root" in sys.argv else "/data/data/t.t/d"
TROUGH = "/data/data/t.t"

elfs = links = 0
dangling = []
for dirpath, dirnames, files in os.walk(tree):
    for name in files + [d for d in dirnames if os.path.islink(os.path.join(dirpath, d))]:
        p = os.path.join(dirpath, name)
        if os.path.islink(p):
            t = os.readlink(p)
            if t.startswith("/") and not t.startswith(TROUGH + "/"):
                new = root + t
                os.unlink(p)
                os.symlink(new, p)
                links += 1
                t = new
            if t.startswith("/") and not os.path.lexists(t):
                dangling.append(f"{p} -> {t}")
            continue
        if not os.path.isfile(p):
            continue
        with open(p, "rb") as f:
            if f.read(4) != b"\x7fELF":
                continue
        if "->" in set_interp(p):
            elfs += 1
print(f"{tree}: {elfs} ELF files now use texec, {links} absolute links re-rooted under {root}")
if dangling:
    print(f"{len(dangling)} links would dangle on the phone (target missing from the root):")
    for d in dangling[:20]:
        print("  " + d)
    sys.exit(1)
