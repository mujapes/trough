#define _GNU_SOURCE
#include "process.h"
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static volatile sig_atomic_t cancelled;
static void cancel_handler(int signal_number) { cancelled = signal_number; }
static uint64_t milliseconds(void) {
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now)) return 0;
    return (uint64_t)now.tv_sec * 1000 + (uint64_t)now.tv_nsec / 1000000;
}
static void tick(void) { struct timespec delay = {0, 5000000}; nanosleep(&delay, NULL); }
static void child_status(tp_result *r, int status) {
    if (WIFEXITED(status)) r->exit_code = WEXITSTATUS(status);
    else if (WIFSIGNALED(status)) r->term_signal = WTERMSIG(status);
}

/* Merely observing a numeric PID is not ownership. waitid checks that it is our unreaped
 * child. No other thread/handler reaps, so its PID cannot be reused between this check and
 * kill, even if it exits during the interval. Never signal arbitrary /proc entries. */
static int kill_owned(pid_t pid) {
    siginfo_t info = {0}; int rc;
    do { rc = waitid(P_PID, (id_t)pid, &info, WEXITED | WNOHANG | WNOWAIT); } while (rc && errno == EINTR);
    if (rc) return errno == ECHILD ? 0 : -1;
    if (info.si_pid) return 0; /* already a zombie, held until our reap pass */
    return kill(pid, SIGKILL) && errno != ESRCH ? -1 : 0;
}
static int kill_children(tp_result *r) {
#ifndef TP_FORCE_PROC_SCAN
    char path[96]; snprintf(path, sizeof path, "/proc/self/task/%ld/children", (long)getpid());
    FILE *children = fopen(path, "r");
    if (children) {
        long pid; unsigned count = 0; int rc = 0, parsed;
        while ((parsed = fscanf(children, "%ld", &pid)) == 1) {
            if (++count > 16384 || pid <= 1 || pid > INT_MAX) { errno = EOVERFLOW; rc = -1; break; }
            if (kill_owned((pid_t)pid)) { rc = -1; break; }
        }
        if (!rc && (ferror(children) || parsed != EOF)) { errno = EIO; rc = -1; }
        int saved = errno; fclose(children); errno = saved;
        return rc;
    }
#endif
    /* Some Android proc configurations omit children. Directory visibility is only a hint:
     * the same kernel child check above is mandatory for every candidate. */
    DIR *proc = opendir("/proc");
    if (!proc) return -1;
    r->proc_scan = 1;
    unsigned count = 0; int rc = 0;
    errno = 0;
    for (struct dirent *entry; (entry = readdir(proc));) {
        char *end; errno = 0; long pid = strtol(entry->d_name, &end, 10);
        if (!*entry->d_name || *end || errno || pid <= 1 || pid > INT_MAX) continue;
        if (++count > 16384) { errno = EOVERFLOW; rc = -1; break; }
        if (kill_owned((pid_t)pid)) { rc = -1; break; }
        errno = 0;
    }
    if (!rc && errno) rc = -1;
    int saved = errno; closedir(proc); errno = saved;
    return rc;
}

int tp_run(char *const argv[], const tp_options *options, tp_result *result) {
    if (!result) { errno = EINVAL; return -1; }
    memset(result, 0, sizeof *result); result->pid = result->exit_code = -1;
    if (!argv || !argv[0] || argv[0][0] != '/' || !options || !options->timeout_ms || options->timeout_ms > 600000 ||
        options->grace_ms > 5000 || !options->cleanup_ms || options->cleanup_ms > 5000) {
        result->error = EINVAL; return -1;
    }
    /* This supervisor owns all its children, not someone else's application process. */
    siginfo_t existing = {0};
    if (!waitid(P_ALL, 0, &existing, WEXITED | WNOHANG | WNOWAIT) || errno != ECHILD) {
        result->error = EBUSY; return -1;
    }
    const int signals[] = {SIGTERM, SIGINT, SIGHUP, SIGCHLD};
    struct sigaction old_actions[4], action = {0};
    sigset_t old_mask, active_mask, blocked;
    int installed = 0, mask_saved = 0, subreaper = 0, death_signal = 0, have_prctl = 0, pipefd[2] = {-1, -1};
    uint64_t started = milliseconds(); pid_t parent = getppid(), child = -1;
    if (!started) { result->error = errno ? errno : EIO; return -1; }
    cancelled = 0; sigemptyset(&blocked);
    for (unsigned i = 0; i < 3; i++) sigaddset(&blocked, signals[i]);
    if (sigprocmask(SIG_BLOCK, &blocked, &old_mask)) goto setup_error;
    mask_saved = 1; active_mask = old_mask;
    for (unsigned i = 0; i < 4; i++) sigdelset(&active_mask, signals[i]);
    sigemptyset(&action.sa_mask);
    for (unsigned i = 0; i < 4; i++) {
        action.sa_handler = i == 3 ? SIG_DFL : cancel_handler;
        if (sigaction(signals[i], &action, &old_actions[i])) goto setup_error;
        installed++;
    }
    if (prctl(PR_GET_CHILD_SUBREAPER, &subreaper, 0L, 0L, 0L) || prctl(PR_GET_PDEATHSIG, &death_signal, 0L, 0L, 0L)) goto setup_error;
    have_prctl = 1;
    if (prctl(PR_SET_CHILD_SUBREAPER, 1L, 0L, 0L, 0L) || prctl(PR_SET_PDEATHSIG, SIGTERM, 0L, 0L, 0L)) goto setup_error;
    if (getppid() != parent) cancelled = SIGTERM;
    if (sigprocmask(SIG_SETMASK, &active_mask, NULL)) goto setup_error;
    if (cancelled) { result->cancelled_signal = cancelled; result->cleanup_complete = 1; goto done; }
    if (pipe2(pipefd, O_CLOEXEC | O_NONBLOCK)) goto setup_error;
    pid_t supervisor = getpid();
    child = fork();
    if (child < 0) goto setup_error;
    if (!child) {
        close(pipefd[0]);
        int failed = 0;
        if (setpgid(0, 0) || prctl(PR_SET_PDEATHSIG, SIGKILL, 0L, 0L, 0L)) failed = errno;
        if (getppid() != supervisor) failed = ECANCELED;
        action.sa_handler = SIG_DFL;
        /* Android can leave unrelated signals blocked or ignored. Commands start with
         * defaults and an empty mask. glibc reserves signals 32/33 and rejects their
         * sigaction with EINVAL; they cannot be changed through its public interface. */
        for (int sig = 1; sig < NSIG; sig++) {
            if (sig == SIGKILL || sig == SIGSTOP) continue;
            if (sigaction(sig, &action, NULL) && errno != EINVAL && !failed) failed = errno;
        }
        sigset_t child_mask; sigemptyset(&child_mask);
        if (sigprocmask(SIG_SETMASK, &child_mask, NULL) && !failed) failed = errno;
        if (!failed) { execv(argv[0], argv); failed = errno; }
        ssize_t written;
        do { written = write(pipefd[1], &failed, sizeof failed); } while (written < 0 && errno == EINTR);
        /* This is one atomic pipe record. A failed report must still fail the command. */
        _exit(written == sizeof failed ? 127 : 126);
    }
    close(pipefd[1]); pipefd[1] = -1; result->pid = (int)child;
    if (setpgid(child, child) && errno != EACCES && errno != ESRCH) result->error = errno;
    uint64_t deadline = started + options->timeout_ms;
    while (!result->error) {
        siginfo_t info = {0};
        if (waitid(P_PID, (id_t)child, &info, WEXITED | WNOHANG | WNOWAIT)) {
            if (errno == EINTR) continue;
            result->error = errno; break;
        }
        if (cancelled) { result->cancelled_signal = cancelled; break; }
        if (info.si_pid) break;
        uint64_t now = milliseconds();
        if (!now) { result->error = errno ? errno : EIO; break; }
        if (now >= deadline) { result->timed_out = 1; break; }
        tick();
    }
    if (result->timed_out || result->cancelled_signal || result->error) {
        (void)kill(-child, SIGTERM); (void)kill(child, SIGTERM);
        uint64_t grace = milliseconds() + options->grace_ms;
        for (;;) {
            uint64_t now = milliseconds();
            if (!now || now >= grace) break;
            tick();
        }
    }
    /* The leader is still unreaped, reserving this process-group identity through the last
     * group signal. Never signal that group again after the subsequent reap pass. */
    (void)kill(-child, SIGKILL); (void)kill(child, SIGKILL);
    uint64_t cleanup = milliseconds() + options->cleanup_ms;
    int last_scan_error = 0;
    for (;;) {
        if (kill_children(result)) last_scan_error = errno;
        int status; pid_t pid;
        while ((pid = waitpid(-1, &status, WNOHANG)) > 0) {
            result->reaped++;
            if (pid == child) child_status(result, status);
        }
        if (pid < 0 && errno == ECHILD) { result->cleanup_complete = 1; break; }
        if (pid < 0 && errno != EINTR) { if (!result->error) result->error = errno; break; }
        uint64_t now = milliseconds();
        if (!now || now >= cleanup) { if (!result->error) result->error = last_scan_error ? last_scan_error : ETIMEDOUT; break; }
        tick();
    }
    int failure = 0;
    if (read(pipefd[0], &failure, sizeof failure) == sizeof failure) result->exec_errno = failure;
    goto done;
setup_error:
    result->error = errno ? errno : EIO;
done:
    if (pipefd[0] >= 0) close(pipefd[0]);
    if (pipefd[1] >= 0) close(pipefd[1]);
    if (have_prctl) {
        if (prctl(PR_SET_CHILD_SUBREAPER, (long)subreaper, 0L, 0L, 0L) && !result->error) result->error = errno;
        if (prctl(PR_SET_PDEATHSIG, (long)death_signal, 0L, 0L, 0L) && !result->error) result->error = errno;
    }
    for (int i = installed - 1; i >= 0; i--) sigaction(signals[i], &old_actions[i], NULL);
    if (mask_saved) sigprocmask(SIG_SETMASK, &old_mask, NULL);
    uint64_t ended = milliseconds(); result->elapsed_ms = ended >= started ? ended - started : 0;
    return result->error ? -1 : 0;
}
