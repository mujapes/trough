#!/usr/bin/env python3
"""Redirect hard-coded paths in Trough's glibc to files Trough owns.

Android has no /etc/resolv.conf, and an app cannot create one, so glibc's resolver would
fall back to 127.0.0.1:53 where nothing listens. Paths are rewritten in place, so a
replacement must be exactly as long as the original:

    /etc/resolv.conf  ->  /data/data/t.t/r

/data/data/t.t is Trough's own data dir (package "t.t", chosen for exactly this length).

Usage: patch_paths.py <elf> [<elf>...]
"""
import sys

PATHS = {b"/etc/resolv.conf": b"/data/data/t.t/r"}


def patch(path):
    data = bytearray(open(path, "rb").read())
    done = []
    for old, new in PATHS.items():
        assert len(old) == len(new), (old, new)
        needle = old + b"\0"
        n = data.count(needle)
        if n:
            data[:] = data.replace(needle, new + b"\0")
            done.append(f"{old.decode()} x{n}")
    if done:
        open(path, "wb").write(data)
    return done


if __name__ == "__main__":
    for p in sys.argv[1:]:
        print(f"{p}: {', '.join(patch(p)) or 'nothing'}")
