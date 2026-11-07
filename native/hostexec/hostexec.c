/* hostexec: runs Android's own programs for processes under texec's path filter
 * (docs/FILESYSTEM.md "Layer 2"). The filter survives exec, so a bionic program (toybox, getprop,
 * am) exec'd from a filtered process would trap on its first path syscall with no handler and die.
 * Trough starts this broker without the filter; the glibc mapper's execve (trough-path.c) sends the
 * exec here instead, when it is filtered and the target uses Android's linker. Prior art:
 * flatpak-spawn --host.
 *
 * Protocol, one exec per connection on a unix stream socket in Trough's private data dir:
 *   client -> header { u32 magic, u32 argc, u32 envc, u32 bytes } with SCM_RIGHTS fds 0, 1, 2,
 *             then `bytes` of NUL-terminated strings: path, cwd, argv[argc], envp[envc]
 *   broker -> i32 wait status once the program exits
 * The client then exits with that status, so for its parent the exec looks ordinary. If the
 * client goes away first (killed), the broker kills the program. Only this uid may connect.
 *
 * Usage: hostexec <socket path>
 */
#define _GNU_SOURCE
#include <errno.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>

#define MAGIC 0x74786563u   /* "texc" */
#define MAX_BYTES (4u << 20)

static int read_full(int fd, void *b, size_t n) {
    for (size_t off = 0; off < n; ) {
        ssize_t r = read(fd, (char *)b + off, n - off);
        if (r <= 0) { if (r < 0 && errno == EINTR) continue; return -1; }
        off += (size_t)r;
    }
    return 0;
}

static void serve(int c) {
    struct ucred cred; socklen_t cl = sizeof cred;
    if (getsockopt(c, SOL_SOCKET, SO_PEERCRED, &cred, &cl) || cred.uid != getuid()) return;
    uint32_t h[4];
    char ctl[CMSG_SPACE(3 * sizeof(int))];
    struct iovec iov = { h, sizeof h };
    struct msghdr m = { .msg_iov = &iov, .msg_iovlen = 1, .msg_control = ctl, .msg_controllen = sizeof ctl };
    ssize_t r = recvmsg(c, &m, MSG_CMSG_CLOEXEC | MSG_WAITALL);
    int fds[3] = { -1, -1, -1 };
    struct cmsghdr *cm = CMSG_FIRSTHDR(&m);
    if (cm && cm->cmsg_type == SCM_RIGHTS && cm->cmsg_len == CMSG_LEN(3 * sizeof(int)))
        memcpy(fds, CMSG_DATA(cm), sizeof fds);
    if (r != sizeof h || h[0] != MAGIC || h[3] > MAX_BYTES || fds[2] < 0) return;
    char *blob = malloc(h[3] + 1);
    if (!blob || read_full(c, blob, h[3])) return;
    blob[h[3]] = 0;
    char **argv = calloc(h[1] + 1, sizeof *argv), **envp = calloc(h[2] + 1, sizeof *envp);
    if (!argv || !envp) return;
    char *p = blob, *end = blob + h[3];
    const char *path = p; p += strlen(p) + 1;
    const char *cwd = p < end ? p : "/"; p += strlen(p) + 1;
    for (uint32_t i = 0; i < h[1] && p < end; i++) { argv[i] = p; p += strlen(p) + 1; }
    for (uint32_t i = 0; i < h[2] && p < end; i++) { envp[i] = p; p += strlen(p) + 1; }

    pid_t pid = fork();
    if (pid == 0) {
        for (int i = 0; i < 3; i++) dup2(fds[i], i);   /* dup2 clears CLOEXEC on 0..2 */
        signal(SIGPIPE, SIG_DFL);
        if (chdir(cwd)) { /* like the original process, carry on from wherever we are */ }
        execve(path, argv, envp);
        _exit(127);
    }
    for (int i = 0; i < 3; i++) close(fds[i]);
    int status = 127 << 8;
    if (pid > 0) {
        for (;;) {   /* program exits, or the client hangs up (killed): then kill the program */
            struct pollfd pf = { c, POLLIN | POLLRDHUP, 0 };
            int pr = poll(&pf, 1, 200);
            if (waitpid(pid, &status, WNOHANG) == pid) break;
            if (pr > 0 && (pf.revents & (POLLHUP | POLLRDHUP | POLLERR))) {
                kill(pid, SIGKILL); waitpid(pid, &status, 0); return;
            }
        }
    }
    int32_t s = status;
    if (write(c, &s, sizeof s) != sizeof s) { /* client gone */ }
}

int main(int argc, char **argv) {
    if (argc != 2) { fprintf(stderr, "usage: hostexec <socket path>\n"); return 2; }
    struct sockaddr_un a = { .sun_family = AF_UNIX };
    if (strlen(argv[1]) >= sizeof a.sun_path) { fprintf(stderr, "hostexec: socket path too long\n"); return 2; }
    strcpy(a.sun_path, argv[1]);
    int s = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    unlink(argv[1]);
    umask(077);   /* only this uid; SO_PEERCRED checks again */
    if (s < 0 || bind(s, (struct sockaddr *)&a, sizeof a) || listen(s, 16)) { perror("hostexec"); return 1; }
    signal(SIGPIPE, SIG_IGN);
    signal(SIGCHLD, SIG_DFL);
    for (;;) {
        int c = accept4(s, 0, 0, SOCK_CLOEXEC);
        if (c < 0) { if (errno == EINTR) continue; perror("hostexec: accept"); return 1; }
        pid_t h = fork();   /* one handler per exec: a long-running program blocks no one */
        if (h == 0) { close(s); serve(c); _exit(0); }
        close(c);
        while (waitpid(-1, 0, WNOHANG) > 0) {}
    }
}
