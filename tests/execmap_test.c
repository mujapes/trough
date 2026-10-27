/* Drives every entry point execmap interposes; prints one line per call. Run with and without
 * the preload by tests/execmap.sh. Paths under /usr/libexec/trough-test do not exist on the
 * test machine; the copies under the test root do. */
#define _GNU_SOURCE
#include <spawn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
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
    return 0;
}
