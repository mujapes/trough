/* execmap: stock Debian programs exec stock absolute paths -- maintainer scripts start with
 * #!/bin/sh, apt runs /bin/sh -c, scripts say #!/usr/bin/perl or #!/usr/bin/env python3 -- and
 * Android has none of /bin, /usr or /sbin. texec preloads this into every glibc program it
 * starts (LD_PRELOAD, only if this file exists), so their exec calls find Trough's root.
 *
 * Scope is deliberately narrow, two behaviours:
 *  1. exec mapping: the exec family, posix_spawn(p), system and popen/pclose (below). The
 *     reverse direction too: a glibc program exec'ing an Android platform binary (realpath
 *     under /system, /apex, ...; e.g. toybox mountpoint from openjdk's postinst) gets an
 *     environment without LD_LIBRARY_PATH/LD_PRELOAD, which bionic's linker would follow
 *     into the root's glibc and fail ("libc.so has bad ELF magic");
 *     Debian maintainer scripts (dpkg sets DPKG_MAINTSCRIPT_NAME only for them) also get
 *     Debian's own fakechroot preloaded with the root as its base, so their absolute /etc,
 *     /var, /usr paths land in the root (openjdk's postinst: mkdir /etc/.java). This is the
 *     mechanism mmdebstrap's fakechroot mode uses for unprivileged installs; no namespace exists
 *     here. Host paths (/data, /proc, /system, ...) are excluded. Scripts only: ordinary
 *     programs keep the zero-translation fast path;
 *  2. hard links: Android's policy denies apps link(2); dpkg backs up its status file with link()
 *     (atomic-file.c) and packages carry hard-linked files. link/linkat that fail with EACCES or
 *     EPERM on a regular file make a copy with the same mode instead -- a correct stand-in for
 *     backups and packaged duplicates, and the only answer Android leaves.
 * No open/stat/chdir path rewriting -- everything else uses the paths dpkg and tfix laid out.
 * The rule: an absolute program or #! interpreter under /bin /sbin /lib /usr that exists under
 * the root (/data/data/t.t/d) runs from the root, even when the device has the same path:
 * Android's /bin is a symlink to /system/bin, whose bionic toybox sh cannot run under glibc's
 * LD_LIBRARY_PATH (found 2026-10-27: dpkg's preinst hit "libc.so has bad ELF magic"). Paths the
 * root lacks are never touched, so on a normal Linux box (no root) this library changes nothing.
 *
 * Build: gcc -O2 -shared -fPIC -o libexecmap.so execmap.c -ldl
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <signal.h>
#include <spawn.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

#ifndef TROUGH_ROOT
#define TROUGH_ROOT "/data/data/t.t/d"
#endif
#ifndef TROUGH_SH   /* tests/execmap.sh points this at a shell path the VM lacks */
#define TROUGH_SH "/bin/sh"
#endif

#ifndef TROUGH_LINKER    /* prefix of a bionic program's PT_INTERP; tests use a stand-in */
#define TROUGH_LINKER "/system/bin/linker"
#endif
#ifndef TROUGH_PLATFORM   /* tests/execmap.sh points this at a stand-in platform directory */
#define TROUGH_PLATFORM "/system/", "/system_ext/", "/apex/", "/vendor/", "/product/", "/odm/"
#endif

#ifndef TROUGH_FAKECHROOT   /* shipped in the bundle's lib/, installed beside texec */
#define TROUGH_FAKECHROOT "/data/data/t.t/l/libfakechroot.so"
#endif
#ifndef TROUGH_HOST_PATHS
#define TROUGH_HOST_PATHS "/data:/proc:/dev:/sys:/system:/system_ext:/apex:/vendor:/product:/odm:/storage:/sdcard:/linkerconfig"
#endif

extern char **environ;

static const char *env_get(char *const envp[], const char *key) {
    size_t n = strlen(key);
    for (int i = 0; envp && envp[i]; i++)
        if (!strncmp(envp[i], key, n) && envp[i][n] == '=') return envp[i] + n + 1;
    return NULL;
}

/* A maintainer script's environment plus fakechroot; NULL when not a script or already set.
 * The strings are leaked on a successful exec (the image is replaced) and freed by free_env. */
static char **script_env(char *const envp[]) {
    if (!env_get(envp, "DPKG_MAINTSCRIPT_NAME") || env_get(envp, "FAKECHROOT_BASE") ||
        access(TROUGH_FAKECHROOT, R_OK)) return NULL;
    int n = 0;
    while (envp[n]) n++;
    char **v = malloc(sizeof(char *) * (n + 4));
    if (!v) return NULL;
    const char *pre = env_get(envp, "LD_PRELOAD");
    int k = 0;
    for (int i = 0; i < n; i++) if (strncmp(envp[i], "LD_PRELOAD=", 11)) v[k++] = envp[i];
    if (asprintf(&v[k], "LD_PRELOAD=%s%s%s", pre && *pre ? pre : "", pre && *pre ? ":" : "", TROUGH_FAKECHROOT) < 0) { free(v); return NULL; }
    v[++k] = "FAKECHROOT_BASE=" TROUGH_ROOT;
    v[++k] = "FAKECHROOT_EXCLUDE_PATH=" TROUGH_HOST_PATHS;
    v[++k] = NULL;
    return v;
}

static void free_env(char **v, char *const original[]) {
    if (!v) return;
    for (int i = 0; v[i]; i++)
        if (!strncmp(v[i], "LD_PRELOAD=", 11)) {
            int shared = 0;
            for (int j = 0; original && original[j]; j++) shared |= original[j] == v[i];
            if (!shared) free(v[i]);
        }
    free(v);
}

/* libc's own entry points, past any later preload. fakechroot's execve re-adds its saved loader
 * variables (a platform binary must not receive them) and its realpath answers in chroot terms. */
static void *libc_symbol(const char *name) {
    static void *libc;
    if (!libc) libc = dlopen("libc.so.6", RTLD_NOW | RTLD_NOLOAD);
    void *f = libc ? dlsym(libc, name) : NULL;
    return f ? f : dlsym(RTLD_NEXT, name);
}

/* 1 if the ELF at path names Android's linker as its interpreter: a bionic program anywhere
 * (e.g. Trough's own bionic builds in the bundle), not only under the platform directories. */
static int bionic_elf(const char *path) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    unsigned char eh[64];
    int found = 0;
    if (pread(fd, eh, sizeof eh, 0) == (ssize_t)sizeof eh && !memcmp(eh, "\177ELF", 4) && eh[4] == 2) {
        uint64_t phoff; uint16_t phentsize, phnum;
        memcpy(&phoff, eh + 0x20, 8); memcpy(&phentsize, eh + 0x36, 2); memcpy(&phnum, eh + 0x38, 2);
        for (unsigned i = 0; i < phnum && phentsize >= 56; i++) {
            unsigned char ph[56];
            if (pread(fd, ph, sizeof ph, (off_t)(phoff + (uint64_t)i * phentsize)) != (ssize_t)sizeof ph) break;
            uint32_t type; uint64_t offset;
            memcpy(&type, ph, 4); memcpy(&offset, ph + 8, 8);
            if (type != 3) continue;   /* PT_INTERP */
            char interp[sizeof TROUGH_LINKER] = {0};
            size_t n = sizeof TROUGH_LINKER - 1;
            found = pread(fd, interp, n, (off_t)offset) == (ssize_t)n && !memcmp(interp, TROUGH_LINKER, n);
            break;
        }
    }
    close(fd);
    return found;
}

/* envp minus the glibc loader variables when exe is a platform (bionic) binary; else NULL. */
static char **platform_env(const char *exe, char *const envp[]) {
    static const char *const platform[] = {TROUGH_PLATFORM};
    char real[PATH_MAX];
    char *(*resolve)(const char *, char *) = (char *(*)(const char *, char *))libc_symbol("realpath");
    if (!exe || !envp || !resolve || !resolve(exe, real)) return NULL;
    unsigned i = 0;
    while (i < sizeof platform / sizeof *platform && strncmp(real, platform[i], strlen(platform[i]))) i++;
    if (i == sizeof platform / sizeof *platform && !bionic_elf(real)) return NULL;
    int n = 0;
    while (envp[n]) n++;
    char **v = malloc(sizeof(char *) * (n + 1));
    if (!v) return NULL;
    int k = 0;
    for (int j = 0; j < n; j++)
        if (strncmp(envp[j], "LD_LIBRARY_PATH=", 16) && strncmp(envp[j], "LD_PRELOAD=", 11)) v[k++] = envp[j];
    v[k] = NULL;
    return v;
}

static int mapped_prefix(const char *p) {
    static const char *const dirs[] = {"/bin/", "/sbin/", "/lib/", "/usr/"};
    for (unsigned i = 0; i < sizeof dirs / sizeof *dirs; i++)
        if (strncmp(p, dirs[i], strlen(dirs[i])) == 0) return 1;
    return 0;
}

/* The path to exec for p: p itself, or its copy under the root when p is missing here. */
static const char *map(const char *p, char *buf) {
    if (!p || p[0] != '/' || !mapped_prefix(p)) return p;
    if (snprintf(buf, PATH_MAX, "%s%s", TROUGH_ROOT, p) >= PATH_MAX) return p;
    return access(buf, F_OK) == 0 ? buf : p;
}

typedef int (*execve_fn)(const char *, char *const[], char *const[]);
typedef int (*spawn_fn)(pid_t *, const char *, const posix_spawn_file_actions_t *,
                        const posix_spawnattr_t *, char *const[], char *const[]);

static execve_fn real_execve(void) {
    static execve_fn f;
    if (!f) f = (execve_fn)dlsym(RTLD_NEXT, "execve");
    return f;
}

static spawn_fn real_spawn(void) {
    static spawn_fn f;
    if (!f) f = (spawn_fn)dlsym(RTLD_NEXT, "posix_spawn");
    return f;
}

/* If path is a #! script whose interpreter must be mapped, fills nargv (caller frees) with
 * [interp, optional-arg, path, argv[1..]] and returns the mapped interpreter; else NULL.
 * The kernel would do the same with the original interpreter, which does not exist here. */
static const char *shebang(const char *path, char *const argv[], char ***nargv, char *ibuf, char *mbuf) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return NULL;
    char line[256];
    ssize_t n = read(fd, line, sizeof line - 1);
    close(fd);
    if (n < 3 || line[0] != '#' || line[1] != '!') return NULL;
    line[n] = 0;
    char *nl = strchr(line, '\n');
    if (!nl) return NULL;
    *nl = 0;
    char *s = line + 2;
    while (*s == ' ' || *s == '\t') s++;
    char *interp = s;
    while (*s && *s != ' ' && *s != '\t') s++;
    char *arg = NULL;
    if (*s) {
        *s++ = 0;
        while (*s == ' ' || *s == '\t') s++;
        if (*s) {   /* Linux passes the rest of the line as ONE argument, trailing blanks cut */
            arg = s;
            char *e = s + strlen(s);
            while (e > s && (e[-1] == ' ' || e[-1] == '\t')) *--e = 0;
        }
    }
    const char *m = map(interp, mbuf);
    if (m == interp) return NULL;
    strncpy(ibuf, m, PATH_MAX - 1);
    ibuf[PATH_MAX - 1] = 0;
    int argc = 0;
    while (argv && argv[argc]) argc++;
    char **v = malloc(sizeof(char *) * (argc + 4));
    if (!v) return NULL;
    int k = 0;
    v[k++] = ibuf;
    if (arg) v[k++] = strdup(arg);
    v[k++] = (char *)path;
    for (int i = 1; i < argc; i++) v[k++] = argv[i];
    v[k] = NULL;
    *nargv = v;
    return ibuf;
}

int execve(const char *path, char *const argv[], char *const envp[]) {
    char buf[PATH_MAX], ibuf[PATH_MAX], mbuf[PATH_MAX];
    const char *p = map(path, buf);
    char **nargv = NULL;
    const char *interp = shebang(p, argv, &nargv, ibuf, mbuf);
    char **script = script_env(envp);
    char *const *env = script ? script : envp;
    char **clean = platform_env(interp ? interp : p, env);
    execve_fn run = clean ? (execve_fn)libc_symbol("execve") : real_execve();
    int r = run(interp ? interp : p, interp ? nargv : argv, clean ? clean : env);
    int e = errno;
    free(nargv);
    free(clean);
    free_env(script, envp);
    errno = e;
    return r;
}

int execv(const char *path, char *const argv[]) { return execve(path, argv, environ); }

/* PATH search done here, so each candidate goes through execve's mapping (glibc's own search
 * calls its internal __execve, which no preload can see). */
int execvpe(const char *file, char *const argv[], char *const envp[]) {
    if (!*file) { errno = ENOENT; return -1; }
    if (strchr(file, '/')) return execve(file, argv, envp);
    const char *path = getenv("PATH");
    if (!path) path = "/bin:/usr/bin";
    int saw_eacces = 0;
    for (const char *d = path;; d++) {
        const char *e = strchrnul(d, ':');
        char cand[PATH_MAX];
        int dl = (int)(e - d);
        if (snprintf(cand, sizeof cand, "%.*s%s%s", dl, d, dl ? "/" : "", file) < (int)sizeof cand) {
            execve(cand, argv, envp);
            if (errno == ENOEXEC) {   /* no #!: run it with sh, as glibc does */
                char cbuf[PATH_MAX];
                const char *script = map(cand, cbuf);
                int argc = 0;
                while (argv[argc]) argc++;
                char **v = malloc(sizeof(char *) * (argc + 2));
                char sbuf[PATH_MAX];
                if (!v) { errno = ENOMEM; return -1; }
                v[0] = "sh";
                v[1] = (char *)script;
                for (int i = 1; i <= argc; i++) v[i + 1] = argv[i];
                real_execve()(map(TROUGH_SH, sbuf), v, envp);
                free(v);
                return -1;
            }
            if (errno == EACCES) saw_eacces = 1;
            else if (errno != ENOENT && errno != ENOTDIR && errno != ELOOP) return -1;
        }
        if (!*e) break;
        d = e;
    }
    errno = saw_eacces ? EACCES : ENOENT;
    return -1;
}

int execvp(const char *file, char *const argv[]) { return execvpe(file, argv, environ); }

static char **collect(const char *arg0, va_list ap, char ***envp_out) {
    int n = 1;
    va_list c;
    va_copy(c, ap);
    while (va_arg(c, char *)) n++;
    va_end(c);
    char **v = malloc(sizeof(char *) * (n + 1));
    if (!v) return NULL;
    v[0] = (char *)arg0;
    for (int i = 1; i < n; i++) v[i] = va_arg(ap, char *);
    v[n] = NULL;
    if (envp_out) { (void)va_arg(ap, char *); *envp_out = va_arg(ap, char **); }
    return v;
}

int execl(const char *path, const char *arg0, ...) {
    va_list ap; va_start(ap, arg0);
    char **v = collect(arg0, ap, NULL);
    va_end(ap);
    if (!v) { errno = ENOMEM; return -1; }
    int r = execve(path, v, environ);
    free(v);
    return r;
}

int execlp(const char *file, const char *arg0, ...) {
    va_list ap; va_start(ap, arg0);
    char **v = collect(arg0, ap, NULL);
    va_end(ap);
    if (!v) { errno = ENOMEM; return -1; }
    int r = execvp(file, v);
    free(v);
    return r;
}

int execle(const char *path, const char *arg0, ...) {
    va_list ap; va_start(ap, arg0);
    char **envp = NULL;
    char **v = collect(arg0, ap, &envp);
    va_end(ap);
    if (!v) { errno = ENOMEM; return -1; }
    int r = execve(path, v, envp);
    free(v);
    return r;
}

int posix_spawn(pid_t *pid, const char *path, const posix_spawn_file_actions_t *fa,
                const posix_spawnattr_t *attr, char *const argv[], char *const envp[]) {
    char buf[PATH_MAX], ibuf[PATH_MAX], mbuf[PATH_MAX];
    const char *p = map(path, buf);
    char **nargv = NULL;
    const char *interp = shebang(p, argv, &nargv, ibuf, mbuf);
    char **script = script_env(envp);
    char *const *env = script ? script : envp;
    char **clean = platform_env(interp ? interp : p, env);
    spawn_fn run = clean ? (spawn_fn)libc_symbol("posix_spawn") : real_spawn();
    int r = run(pid, interp ? interp : p, fa, attr, interp ? nargv : argv, clean ? clean : env);
    free(nargv);
    free(clean);
    free_env(script, envp);
    return r;
}

int posix_spawnp(pid_t *pid, const char *file, const posix_spawn_file_actions_t *fa,
                 const posix_spawnattr_t *attr, char *const argv[], char *const envp[]) {
    if (strchr(file, '/')) return posix_spawn(pid, file, fa, attr, argv, envp);
    const char *path = getenv("PATH");
    if (!path) path = "/bin:/usr/bin";
    for (const char *d = path;; d++) {
        const char *e = strchrnul(d, ':');
        char cand[PATH_MAX];
        int dl = (int)(e - d);
        char mbuf[PATH_MAX];
        if (snprintf(cand, sizeof cand, "%.*s%s%s", dl, d, dl ? "/" : "", file) < (int)sizeof cand
            && access(map(cand, mbuf), X_OK) == 0)
            return posix_spawn(pid, cand, fa, attr, argv, envp);
        if (!*e) break;
        d = e;
    }
    return ENOENT;
}

/* system() and popen() name /bin/sh inside glibc, out of a preload's reach, so they are
 * reimplemented over the mapped posix_spawn -- only when the root has its own /bin/sh. */
static const char *shell(char *buf) { return map(TROUGH_SH, buf); }

int system(const char *cmd) {
    char buf[PATH_MAX];
    const char *sh = shell(buf);
    if (strcmp(sh, TROUGH_SH) == 0) {
        int (*real)(const char *) = (int (*)(const char *))dlsym(RTLD_NEXT, "system");
        return real(cmd);
    }
    if (!cmd) return access(sh, X_OK) == 0;
    struct sigaction ign = {0}, oint, oquit;
    ign.sa_handler = SIG_IGN;
    sigemptyset(&ign.sa_mask);
    sigaction(SIGINT, &ign, &oint);
    sigaction(SIGQUIT, &ign, &oquit);
    sigset_t chld, omask;
    sigemptyset(&chld);
    sigaddset(&chld, SIGCHLD);
    sigprocmask(SIG_BLOCK, &chld, &omask);
    posix_spawnattr_t a;
    posix_spawnattr_init(&a);
    sigset_t def;
    sigemptyset(&def);
    if (oint.sa_handler != SIG_IGN) sigaddset(&def, SIGINT);
    if (oquit.sa_handler != SIG_IGN) sigaddset(&def, SIGQUIT);
    posix_spawnattr_setsigdefault(&a, &def);
    posix_spawnattr_setsigmask(&a, &omask);
    posix_spawnattr_setflags(&a, POSIX_SPAWN_SETSIGDEF | POSIX_SPAWN_SETSIGMASK);
    char *argv[] = {"sh", "-c", (char *)cmd, NULL};
    pid_t pid;
    int status = -1;
    int err = real_spawn()(&pid, sh, NULL, &a, argv, environ);
    posix_spawnattr_destroy(&a);
    if (err == 0) {
        while (waitpid(pid, &status, 0) < 0) if (errno != EINTR) { status = -1; break; }
    } else {
        status = 127 << 8;
    }
    sigaction(SIGINT, &oint, NULL);
    sigaction(SIGQUIT, &oquit, NULL);
    sigprocmask(SIG_SETMASK, &omask, NULL);
    return status;
}

static struct { FILE *f; pid_t pid; } popened[64];

FILE *popen(const char *cmd, const char *mode) {
    char buf[PATH_MAX];
    const char *sh = shell(buf);
    if (strcmp(sh, TROUGH_SH) == 0) {
        FILE *(*real)(const char *, const char *) = (FILE * (*)(const char *, const char *))dlsym(RTLD_NEXT, "popen");
        return real(cmd, mode);
    }
    int reading = mode[0] == 'r';
    if (!reading && mode[0] != 'w') { errno = EINVAL; return NULL; }
    int fd[2];
    if (pipe2(fd, O_CLOEXEC) < 0) return NULL;
    int child = reading ? fd[1] : fd[0], mine = reading ? fd[0] : fd[1];
    posix_spawn_file_actions_t fa;
    posix_spawn_file_actions_init(&fa);
    posix_spawn_file_actions_adddup2(&fa, child, reading ? STDOUT_FILENO : STDIN_FILENO);
    char *argv[] = {"sh", "-c", (char *)cmd, NULL};
    pid_t pid;
    int err = real_spawn()(&pid, sh, &fa, NULL, argv, environ);
    posix_spawn_file_actions_destroy(&fa);
    close(child);
    if (err) { close(mine); errno = err; return NULL; }
    FILE *f = fdopen(mine, reading ? "r" : "w");
    if (!f) { close(mine); return NULL; }
    for (unsigned i = 0; i < sizeof popened / sizeof *popened; i++)
        if (!popened[i].f) { popened[i].f = f; popened[i].pid = pid; return f; }
    return f;   /* table full: pclose still closes it, just cannot report the status */
}

int pclose(FILE *f) {
    for (unsigned i = 0; i < sizeof popened / sizeof *popened; i++) {
        if (popened[i].f != f) continue;
        pid_t pid = popened[i].pid;
        popened[i].f = NULL;
        fclose(f);
        int status;
        while (waitpid(pid, &status, 0) < 0) if (errno != EINTR) return -1;
        return status;
    }
    int (*real)(FILE *) = (int (*)(FILE *))dlsym(RTLD_NEXT, "pclose");
    return real(f);
}

/* --- hard links (Android denies them to apps) --- */

static int copy_instead(int olddirfd, const char *oldpath, int newdirfd, const char *newpath, int follow) {
    struct stat st;
    if (fstatat(olddirfd, oldpath, &st, follow ? 0 : AT_SYMLINK_NOFOLLOW) != 0 || !S_ISREG(st.st_mode)) return -1;
    int in = openat(olddirfd, oldpath, O_RDONLY | O_CLOEXEC);
    if (in < 0) return -1;
    int out = openat(newdirfd, newpath, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, st.st_mode & 07777);
    if (out < 0) { int e = errno; close(in); errno = e; return -1; }
    char buf[65536];
    ssize_t n;
    while ((n = read(in, buf, sizeof buf)) > 0) {
        for (ssize_t off = 0; off < n; ) {
            ssize_t w = write(out, buf + off, n - off);
            if (w < 0) { if (errno == EINTR) continue; goto fail; }
            off += w;
        }
    }
    if (n < 0) goto fail;
    fchmod(out, st.st_mode & 07777);   /* umask may have cut bits at create */
    close(in);
    if (close(out) != 0) { unlinkat(newdirfd, newpath, 0); return -1; }
    return 0;
fail: {
        int e = errno;
        close(in); close(out);
        unlinkat(newdirfd, newpath, 0);
        errno = e;
        return -1;
    }
}

int linkat(int olddirfd, const char *oldpath, int newdirfd, const char *newpath, int flags) {
    int (*real)(int, const char *, int, const char *, int) =
        (int (*)(int, const char *, int, const char *, int))dlsym(RTLD_NEXT, "linkat");
    if (real(olddirfd, oldpath, newdirfd, newpath, flags) == 0) return 0;
    if (errno != EACCES && errno != EPERM) return -1;
    int saved = errno;
    if (copy_instead(olddirfd, oldpath, newdirfd, newpath, flags & AT_SYMLINK_FOLLOW) == 0) return 0;
    if (errno != EEXIST && errno != ENOENT) errno = saved;
    return -1;
}

int link(const char *oldpath, const char *newpath) {
    int (*real)(const char *, const char *) = (int (*)(const char *, const char *))dlsym(RTLD_NEXT, "link");
    if (real(oldpath, newpath) == 0) return 0;
    if (errno != EACCES && errno != EPERM) return -1;
    int saved = errno;
    if (copy_instead(AT_FDCWD, oldpath, AT_FDCWD, newpath, 0) == 0) return 0;
    if (errno != EEXIST && errno != ENOENT) errno = saved;
    return -1;
}
