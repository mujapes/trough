#!/usr/bin/env python3
"""Rewrite syscalls Android's app seccomp filter traps into an inline -ENOSYS.

glibc calls set_robust_list (and rseq) in EVERY new thread. A thread that blocks all signals
-- node's libuv pool does -- is killed by the kernel on a trapped syscall instead of reaching
texec's SIGSYS handler. So for the glibc Trough ships, the instruction is changed instead:

    mov w8, #99   ...   svc #0      ->      mov w8, #99   ...   movn x0, #37   (x0 = -38)

Every `svc #0` in an executable segment whose syscall number is set by a movz to x8/w8 within
the preceding 8 instructions (with no branch in between) is checked against TRAPPED.

Usage: patch_svc.py <elf> [<elf>...]   (in place; prints what it changed)
"""
import struct
import sys

TRAPPED = {99: "set_robust_list", 293: "rseq", 449: "futex_waitv"}  # native/probe, Pixel 7a 10-27
SVC0 = 0xD4000001
MOVN_X0_37 = 0x92800000 | (37 << 5)          # movn x0, #37  -> x0 = ~37 = -ENOSYS
LOOKBACK = 8


def movz_x8(word):
    """Immediate if word is `movz w8|x8, #imm` (no shift), else None."""
    if (word & 0x7FE0001F) == 0x52800008:   # sf bit masked: matches both w8 and x8, hw=0
        return (word >> 5) & 0xFFFF
    return None


def writes_x8(word):
    return (word & 0x1F) == 8 and not is_branch(word)


def is_branch(word):
    return (word & 0x7C000000) == 0x14000000 or (word & 0xFE000000) == 0x54000000 \
        or (word & 0xFE000000) == 0xD6000000 or (word & 0x7E000000) == 0x34000000 \
        or (word & 0x7E000000) == 0x36000000


def exec_ranges(data):
    if data[:4] != b"\x7fELF" or data[4] != 2:
        raise ValueError("not a 64-bit ELF")
    phoff, = struct.unpack_from("<Q", data, 0x20)
    phentsize, phnum = struct.unpack_from("<HH", data, 0x36)
    for i in range(phnum):
        p_type, p_flags, p_offset, _, _, p_filesz = struct.unpack_from("<IIQQQQ", data, phoff + i * phentsize)
        if p_type == 1 and p_flags & 1:
            yield p_offset, p_offset + p_filesz


def patch(path):
    data = bytearray(open(path, "rb").read())
    changed = []
    for start, end in exec_ranges(data):
        for off in range(start & ~3, end - 3, 4):
            if struct.unpack_from("<I", data, off)[0] != SVC0:
                continue
            for back in range(1, LOOKBACK + 1):
                prev_off = off - 4 * back
                if prev_off < start:
                    break
                w = struct.unpack_from("<I", data, prev_off)[0]
                if is_branch(w):
                    break
                imm = movz_x8(w)
                if imm is not None:
                    if imm in TRAPPED:
                        struct.pack_into("<I", data, off, MOVN_X0_37)
                        changed.append((off, TRAPPED[imm]))
                    break
                if writes_x8(w):
                    break  # x8 set some other way: leave it
    if changed:
        open(path, "wb").write(data)
    return changed


if __name__ == "__main__":
    for p in sys.argv[1:]:
        c = patch(p)
        names = {}
        for _, n in c:
            names[n] = names.get(n, 0) + 1
        print(f"{p}: {len(c)} svc sites -> -ENOSYS {names}")
