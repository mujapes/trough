/* Reproduce Android's app seccomp behaviour in the VM: trap (SIGSYS) the syscalls the Pixel
 * 7a's filter trapped (native/probe, 10-27), then exec the command. The filter survives
 * execve, as Android's does. Usage: seccomp_sim <cmd> [args...] */
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stddef.h>
#include <stdio.h>
#include <sys/prctl.h>
#include <unistd.h>

int main(int argc, char **argv) {
    struct sock_filter f[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 99 /* set_robust_list */, 3, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 293 /* rseq */, 2, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 449 /* futex_waitv */, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
    };
    struct sock_fprog prog = { sizeof f / sizeof f[0], f };
    if (argc < 2 || prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) || prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog)) {
        perror("seccomp_sim"); return 2;
    }
    execv(argv[1], argv + 1);
    perror("execv"); return 127;
}
