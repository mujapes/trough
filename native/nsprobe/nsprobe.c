/* Capability oracle: each operation runs in a disposable child, never in the app.
 * Report kernel errno separately from Android's seccomp SIGSYS. No mounts or host
 * files are changed. A successful chroot only changes this child's filesystem root.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <sched.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/wait.h>
#include <sys/syscall.h>
#include <ucontext.h>
#include <unistd.h>

static volatile sig_atomic_t trapped;
static void sigsys(int sig, siginfo_t *info, void *ctx) {
    (void)sig; (void)info;
    trapped = 1;
    ((ucontext_t *)ctx)->uc_mcontext.regs[0] = -ENOSYS;
}

static int probe(const char *name, int flags, int use_clone) {
    pid_t p = fork();
    if (p < 0) { perror("fork"); return 1; }
    if (p == 0) {
        struct sigaction sa = {0};
        sa.sa_sigaction = sigsys;
        sa.sa_flags = SA_SIGINFO;
        sigemptyset(&sa.sa_mask);
        if (sigaction(SIGSYS, &sa, NULL)) _exit(2);
        errno = 0;
        long rc;
        if (use_clone) {
            rc = syscall(SYS_clone, (unsigned long)flags | SIGCHLD, 0, 0, 0, 0);
            if (rc == 0) _exit(0);
            if (rc > 0) {
                int child_status;
                while (waitpid((pid_t)rc, &child_status, 0) < 0)
                    if (errno != EINTR) _exit(3);
                if (!WIFEXITED(child_status) || WEXITSTATUS(child_status)) _exit(4);
                rc = 0;
            }
        } else rc = flags ? unshare(flags) : chroot(".");
        int error = rc ? errno : 0;
        dprintf(1, "{\"test\":\"%s\",\"rc\":%ld,\"errno\":%d,\"seccomp\":%s}\n",
                name, rc, error, trapped ? "true" : "false");
        _exit(0);
    }
    int status;
    while (waitpid(p, &status, 0) < 0) if (errno != EINTR) return 1;
    if (!WIFEXITED(status) || WEXITSTATUS(status)) {
        fprintf(stderr, "%s: child status %d\n", name, status);
        return 1;
    }
    return 0;
}

int main(void) {
    int failures = 0;
    failures += probe("user_namespace", CLONE_NEWUSER, 0);
    failures += probe("mount_namespace", CLONE_NEWNS, 0);
    failures += probe("user_mount_namespace", CLONE_NEWUSER | CLONE_NEWNS, 0);
    failures += probe("clone_control", 0, 1);
    failures += probe("clone_user_namespace", CLONE_NEWUSER, 1);
    failures += probe("clone_mount_namespace", CLONE_NEWNS, 1);
    failures += probe("clone_user_mount_namespace", CLONE_NEWUSER | CLONE_NEWNS, 1);
    failures += probe("chroot", 0, 0);
    return failures != 0;
}
