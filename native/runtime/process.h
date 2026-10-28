#ifndef TROUGH_PROCESS_H
#define TROUGH_PROCESS_H
#include <stdint.h>

/* Linux/Android owned-process execution. Call ONLY in a dedicated, single-threaded
 * supervisor process with no pre-existing children. This is a mechanism for a trusted
 * effect/scenario owner, not capability validation or an untrusted-code sandbox.
 * The child gets a fresh process group, default signal actions and unblocked signals.
 * Subreaper adoption plus kernel-checked child
 * enumeration also cleans descendants which created a new session/group.
 * TERM/INT/HUP and parent death cancel execution; timeout uses CLOCK_MONOTONIC.
 * SIGKILL of the supervisor/whole Android process remains an external recovery boundary. */
typedef struct {
    uint64_t timeout_ms;            /* 1..600000, excluding bounded cleanup */
    uint64_t grace_ms;              /* 0..5000 before escalation on cancellation */
    uint64_t cleanup_ms;            /* 1..5000 for adoption and reaping */
} tp_options;
typedef struct {
    int pid, exit_code, term_signal, exec_errno, error;
    int timed_out, cancelled_signal, cleanup_complete;
    unsigned reaped, proc_scan;
    uint64_t elapsed_ms;
} tp_result;
/* argv[0] is an absolute executable path; argv is NULL terminated. Environment and stdio
 * are supplied by the owner. Return 0 means supervision succeeded, NOT that child exit=0.
 * Failure never claims cleanup complete without waitpid's ECHILD proof. */
int tp_run(char *const argv[], const tp_options *, tp_result *);
#endif
