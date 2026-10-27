#!/usr/bin/env python3
"""Dependency closure of installed packages, from dpkg's own status database.

Depends + Pre-Depends, recursively; for alternatives (a | b) the first installed one; virtual
names resolved through Provides. apt-cache's --installed view drops some of these (base-files'
Pre-Depends on the virtual awk, 10-27), which left Trough's dpkg database inconsistent.

Usage: dpkg_closure.py <package>... [--status /var/lib/dpkg/status]   -> names, one per line
"""
import re
import sys

args = sys.argv[1:]
path = "/var/lib/dpkg/status"
if "--status" in args:
    i = args.index("--status"); path = args[i + 1]; del args[i:i + 2]

pkgs, provides = {}, {}
for stanza in open(path).read().split("\n\n"):
    f, key = {}, None
    for line in stanza.splitlines():
        if line[:1] in (" ", "\t") and key:
            f[key] += " " + line.strip()
        elif ":" in line:
            key, _, v = line.partition(":"); f[key] = v.strip()
    if "installed" not in f.get("Status", "").split():
        continue
    name = f["Package"]
    pkgs[name] = f
    for p in filter(None, (x.strip() for x in f.get("Provides", "").split(","))):
        provides.setdefault(re.split(r"[\s(:]", p)[0], []).append(name)


def resolve(alt):
    n = re.split(r"[\s(:]", alt.strip())[0]
    if n in pkgs: return n
    return next(iter(provides.get(n, [])), None)


seen, todo = set(), [a for a in args]
while todo:
    n = resolve(todo.pop())
    if n is None or n in seen: continue
    seen.add(n)
    for field in ("Depends", "Pre-Depends"):
        for dep in filter(None, (d.strip() for d in pkgs[n].get(field, "").split(","))):
            for alt in dep.split("|"):
                if resolve(alt):
                    todo.append(alt); break
print("\n".join(sorted(seen)))
