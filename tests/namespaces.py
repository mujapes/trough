#!/usr/bin/env python3
"""Oracle for nsprobe: distinguish a denied call from seccomp trapping that call.

Run on arm64 Linux. Namespace creation may be restricted by the test environment;
ordinary clone must work. Trap only chroot so the probe can start with stock glibc.
No root, downloads, /data writes, or host mounts required.
"""
import json
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix="trough-ns-oracle-") as tmp:
    w = Path(tmp)
    subprocess.run(["gcc", "-O2", "-Wall", "-Wextra", "-Werror", "-o", str(w / "probe"),
                    str(ROOT / "native/nsprobe/nsprobe.c")], check=True)
    (w / "filter.c").write_text(r'''
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stddef.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <unistd.h>
int main(int argc, char **argv) {
    struct sock_filter f[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_chroot, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog prog = {sizeof f / sizeof f[0], f};
    if (argc != 2 || prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) ||
        prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog)) return 2;
    execl(argv[1], argv[1], (char *)0);
    return 3;
}
''')
    subprocess.run(["gcc", "-O2", "-Wall", "-Wextra", "-Werror", "-o", str(w / "filter"),
                    str(w / "filter.c")], check=True)
    for filtered in (False, True):
        cmd = [str(w / "probe")]
        if filtered:
            cmd.insert(0, str(w / "filter"))
        p = subprocess.run(cmd, cwd=w, check=True, capture_output=True, text=True, timeout=10)
        rows = [json.loads(line) for line in p.stdout.splitlines()]
        assert len(rows) == 8, rows
        by_name = {r["test"]: r for r in rows}
        assert len(by_name) == 8
        assert by_name["clone_control"] == dict(test="clone_control", rc=0, errno=0, seccomp=False)
        chroot = by_name["chroot"]
        if filtered:
            assert chroot == dict(test="chroot", rc=-1, errno=38, seccomp=True), chroot
        else:
            assert chroot["seccomp"] is False, chroot
        print("ok   " + ("SIGSYS is distinct from a kernel error" if filtered else "native probe and clone control"))
