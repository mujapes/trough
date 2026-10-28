#!/usr/bin/env python3
"""Point an ELF's PT_INTERP at texec without moving a single byte.

patchelf --set-interpreter grows .interp and rewrites the layout, which broke claude (a Bun
single-file executable that carries its JS inside the file: segfault, 10-27). Instead the
existing string slot is overwritten in place with a short path that always means texec:

    /lib/ld-linux-aarch64.so.1  ->  /data/data/t.t/x

/data/data/t.t is Trough's own data dir (package "t.t", short enough to fit any interp slot),
and x is texec there. Library lookup is texec's job (Trough's lib dir on LD_LIBRARY_PATH).
An fd-based path (/proc/self/fd/98) was tried first and broke whenever a parent closed all
fds before exec -- Python's subprocess does by default.

Usage: set_interp.py <elf> [<elf>...]
"""
import struct
import sys

NEW = b"/data/data/t.t/x"


def set_interp(path):
    with open(path, "r+b") as f:
        head = f.read(64)
        if head[:4] != b"\x7fELF" or head[4] != 2:
            return "not a 64-bit ELF"
        phoff, = struct.unpack_from("<Q", head, 0x20)
        phentsize, phnum = struct.unpack_from("<HH", head, 0x36)
        f.seek(phoff)
        ph = f.read(phentsize * phnum)
        for i in range(phnum):
            p_type, _, p_offset, _, _, p_filesz = struct.unpack_from("<IIQQQQ", ph, i * phentsize)
            if p_type != 3:
                continue
            f.seek(p_offset)
            old = f.read(p_filesz)
            if old.rstrip(b"\0") == NEW:
                return "already set"
            if old.startswith(b"/system/bin/linker"):
                return "bionic (Android's linker, left alone)"
            if len(NEW) + 1 > p_filesz:
                return f"slot too small ({p_filesz})"
            f.seek(p_offset)
            f.write(NEW + b"\0" * (p_filesz - len(NEW)))
            return f"{old.rstrip(bytes(1)).decode()} -> {NEW.decode()}"
        return "static (no PT_INTERP)"


if __name__ == "__main__":
    for p in sys.argv[1:]:
        print(f"{p}: {set_interp(p)}")
