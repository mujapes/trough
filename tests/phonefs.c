/* tests/phonefs.sh's helper: the phone's view of the filesystem, for VM tests only.
 * One process, no exec until the end (an exec as a non-root uid drops the namespace's
 * capabilities): new user + mount namespace mapping this uid to itself, a tmpfs / holding only
 * /data/data/t.t, /proc and /dev (bind mounts), chroot, then exec argv[1..] as this same uid.
 * Trough never does this: it is how the VM imitates a phone that has no /bin, /usr, /etc, /tmp. */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <sched.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stddef.h>
#include <sys/mount.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/stat.h>
#include <unistd.h>

static void die(const char *what) { fprintf(stderr, "phonefs: %s: %s\n", what, strerror(errno)); exit(125); }

static void put(const char *path, const char *text) {
    int fd = open(path, O_WRONLY);
    if (fd < 0 || write(fd, text, strlen(text)) != (ssize_t)strlen(text)) die(path);
    close(fd);
}

/* The phone's answers where this namespace would differ:
 *  - chown to another user is EPERM there (dpkg tolerates it as a non-root user); here an
 *    unmapped uid gives EINVAL: fchownat/fchown with a uid or gid other than ours (or -1) -> EPERM;
 *  - Android's policy denies apps hard links: linkat -> EACCES (dpkg's status backup, 10-27). */
static void phone_policy(uid_t u, gid_t g) {
#define ARG(n) offsetof(struct seccomp_data, args[n])
#define CHECK(arg, want, skip) \
    BPF_STMT(BPF_LD | BPF_W | BPF_ABS, ARG(arg)), \
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 0xffffffffu, skip, 0), \
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, want, skip - 1, 0), \
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM)
    struct sock_filter f[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_linkat, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EACCES),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_fchownat, 0, 9),
        CHECK(2, u, 2),
        CHECK(3, g, 2),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_fchown, 0, 8),
        CHECK(1, u, 2),
        CHECK(2, g, 2),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog prog = {sizeof f / sizeof *f, f};
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0 || prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog) != 0) die("seccomp");
}

int main(int argc, char **argv) {
    if (argc < 2) { fprintf(stderr, "usage: phonefs <cmd> [args...]\n"); return 125; }
    uid_t u = getuid();
    gid_t g = getgid();
    if (unshare(CLONE_NEWUSER | CLONE_NEWNS) != 0) die("unshare");
    char map[64];
    snprintf(map, sizeof map, "%u %u 1\n", u, u);
    put("/proc/self/uid_map", map);
    put("/proc/self/setgroups", "deny");
    snprintf(map, sizeof map, "%u %u 1\n", g, g);
    put("/proc/self/gid_map", map);
    if (mount(NULL, "/", NULL, MS_REC | MS_PRIVATE, NULL) != 0) die("make / private");
    const char *n = "/data/data/t.t/cache/phonefs/root";
    mkdir(n, 0755);
    if (mount("none", n, "tmpfs", 0, NULL) != 0) die("tmpfs");
    char p[256];
    const char *binds[] = {"/data/data/t.t", "/proc", "/dev"};
    for (int i = 0; i < 3; i++) {
        snprintf(p, sizeof p, "%s%s", n, binds[i]);
        char cmd[300];
        snprintf(cmd, sizeof cmd, "%s", p);
        for (char *s = cmd + strlen(n) + 1; *s; s++) if (*s == '/') { *s = 0; mkdir(cmd, 0755); *s = '/'; }
        mkdir(p, 0755);
        if (mount(binds[i], p, NULL, MS_BIND | MS_REC, NULL) != 0) die(binds[i]);
    }
    if (chroot(n) != 0) die("chroot");
    if (chdir("/data/data/t.t") != 0) die("chdir");
    phone_policy(u, g);
    execv(argv[1], argv + 1);
    die(argv[1]);
}
