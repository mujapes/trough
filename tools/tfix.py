#!/usr/bin/env python3
"""After dpkg: point every stock ELF under the Debian root at texec (in place).

Walks the tree and rewrites PT_INTERP /lib/ld-linux-aarch64.so.1 -> /data/data/t.t/x via
set_interp (idempotent; static binaries and shared libraries without PT_INTERP are left).
Usage: tfix.py [root=/data/data/t.t/d]
"""
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from set_interp import set_interp  # noqa: E402

root = sys.argv[1] if len(sys.argv) > 1 else "/data/data/t.t/d"
changed = 0
for dirpath, _, files in os.walk(root):
    for name in files:
        p = os.path.join(dirpath, name)
        if os.path.islink(p) or not os.path.isfile(p):
            continue
        with open(p, "rb") as f:
            if f.read(4) != b"\x7fELF":
                continue
        if "->" in set_interp(p):
            changed += 1
print(f"{root}: {changed} ELF files now use texec")
