/* Drives every entry point execmap interposes; prints one line per call. Run with and without
 * the preload by tests/execmap.sh. Paths under /usr/libexec/trough-test do not exist on the
 * test machine; the copies under the test root do. */
#define _GNU_SOURCE
#include <spawn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stddef.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>
extern char **environ;
#define T "/usr/libexec/trough-test"

static void run(const char *name, int how) {
    fflush(stdout);
    pid_t p = fork();
    if (p == 0) {
        char *av[] = {(char *)name, "arg1", NULL};
        char *env[] = {"PATH=" T "/bin", "MARK=env-ok", NULL};
        switch (how) {
            case 0: execve(T "/bin/hello", av, environ); break;
            case 1: execv(T "/bin/script", av); break;            /* #! interpreter mapped too */
            case 2: setenv("PATH", "/nonexistent:" T "/bin", 1); execvp("hello", av); break;
            case 3: execl(T "/bin/hello", name, "arg1", (char *)NULL); break;
            case 4: setenv("PATH", T "/bin", 1); execlp("script", name, "arg1", (char *)NULL); break;
            case 5: execle(T "/bin/printenv-mark", "x", (char *)NULL, env); break;
            case 6: setenv("PATH", T "/bin", 1); execvp("noshebang", av); break;   /* ENOEXEC -> sh */
            case 7: execv(T "/bin/argscript", av); break;        /* #!interp with one argument */
        }
        printf("%s: FAILED %m\n", name);
        fflush(stdout);
        _exit(127);
    }
    int st;
    waitpid(p, &st, 0);
}

int main(void) {
    run("execve", 0); run("execv+shebang", 1); run("execvp", 2); run("execl", 3);
    run("execlp+shebang", 4); run("execle", 5); run("execvp-noshebang", 6); run("shebang-arg", 7);
    char *av[] = {"posix_spawn", "arg1", NULL};
    pid_t pid;
    int r = posix_spawn(&pid, T "/bin/script", NULL, NULL, av, environ);
    if (r == 0) { int st; waitpid(pid, &st, 0); } else printf("posix_spawn: FAILED %s\n", strerror(r));
    setenv("PATH", T "/bin", 1);
    fflush(stdout);
    char *avp[] = {"posix_spawnp", "arg1", NULL};
    r = posix_spawnp(&pid, "hello", NULL, NULL, avp, environ);
    if (r == 0) { int st; waitpid(pid, &st, 0); } else printf("posix_spawnp: FAILED %s\n", strerror(r));
    fflush(stdout);
    int s = system("echo system-ok $0");
    printf("system status %d\n", WIFEXITED(s) ? WEXITSTATUS(s) : -1);
    FILE *f = popen("echo popen-ok", "r");
    char line[128] = "";
    if (f) { if (!fgets(line, sizeof line, f)) line[0] = 0; printf("popen read: %s", line); printf("pclose %d\n", pclose(f)); }
    else printf("popen: FAILED %m\n");
    /* Android denies apps hard links: from here on linkat is EACCES, as there. */
    struct sock_filter flt[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_linkat, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EACCES),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog prog = {4, flt};
    prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0);
    prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog);
    const char *dir = getenv("LINKDIR");
    char a[512], b[512], c2[512];
    snprintf(a, sizeof a, "%s/status", dir); snprintf(b, sizeof b, "%s/status-old", dir); snprintf(c2, sizeof c2, "%s/status-at", dir);
    int fd = open(a, O_WRONLY | O_CREAT | O_TRUNC, 0640);
    if (write(fd, "Package: x\n", 11) != 11) printf("write: FAILED\n");
    close(fd);
    if (link(a, b) == 0) {
        struct stat st; char got[32] = ""; int g = open(b, O_RDONLY); ssize_t n = read(g, got, sizeof got - 1); close(g);
        stat(b, &st);
        printf("link: copied %s mode %o\n", n == 11 && !strncmp(got, "Package: x\n", 11) ? "same-content" : "WRONG", st.st_mode & 0777);
    } else printf("link: FAILED %m\n");
    if (link(a, b) != 0 && errno == EEXIST) printf("link existing: EEXIST\n"); else printf("link existing: WRONG\n");
    if (linkat(AT_FDCWD, a, AT_FDCWD, c2, 0) == 0) printf("linkat: copied\n"); else printf("linkat: FAILED %m\n");
    return 0;
}
