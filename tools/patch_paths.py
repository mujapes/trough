#!/usr/bin/env python3
"""Redirect hard-coded paths in Trough's glibc to files Trough owns.

Android has no /etc/resolv.conf, and an app cannot create one, so glibc's resolver would
fall back to 127.0.0.1:53 where nothing listens. Paths are rewritten in place, so a
replacement must be exactly as long as the original:

    /etc/resolv.conf  ->  /data/data/t.t/r

/data/data/t.t is Trough's own data dir (package "t.t", chosen for exactly this length).

Usage: patch_paths.py [--any] <elf> [<elf>...]
  --any  also rewrite occurrences that are not NUL-terminated (Rust &str constants carry their
         length separately; codex has one beside musl's C string). Same length, so still safe.
"""
import sys

PATHS = {b"/etc/resolv.conf": b"/data/data/t.t/r"}


def patch(path, any_end=False):
    data = bytearray(open(path, "rb").read())
    done = []
    for old, new in PATHS.items():
        assert len(old) == len(new), (old, new)
        needle, repl = (old, new) if any_end else (old + b"\0", new + b"\0")
        n = data.count(needle)
        if n:
            data[:] = data.replace(needle, repl)
            done.append(f"{old.decode()} x{n}")
    if done:
        open(path, "wb").write(data)
    return done


if __name__ == "__main__":
    args = sys.argv[1:]
    any_end = "--any" in args
    for p in [a for a in args if a != "--any"]:
        print(f"{p}: {', '.join(patch(p, any_end)) or 'nothing'}")
