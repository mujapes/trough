#define _POSIX_C_SOURCE 200809L
#include "process.h"
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* A private result file separates supervisor evidence from arbitrary child stdout.
 * The caller supplies a fresh path in its owned scenario directory. */
int main(int argc, char **argv) {
    if (argc < 5 || strcmp(argv[3], "--")) { fprintf(stderr, "usage: trough-run <timeout-ms> <new-report-path> -- <absolute-program> [args...]\n"); return 125; }
    char *end; errno = 0; unsigned long timeout = strtoul(argv[1], &end, 10);
    if (errno || !*argv[1] || *end || !timeout || timeout > 600000) return 125;
    int fd = open(argv[2], O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
    if (fd < 0) { perror("supervisor report"); return 125; }
    tp_options options = {timeout, 100, 2000}; tp_result result;
    int rc = tp_run(argv + 4, &options, &result);
    char report[768];
    int size = snprintf(report, sizeof report,
        "{\"version\":1,\"pid\":%d,\"exit\":%d,\"signal\":%d,\"exec_errno\":%d,\"error\":%d,"
        "\"timed_out\":%s,\"cancelled_signal\":%d,\"cleanup_complete\":%s,\"reaped\":%u,\"proc_scan\":%s,\"elapsed_ms\":%llu}\n",
        result.pid, result.exit_code, result.term_signal, result.exec_errno, result.error,
        result.timed_out ? "true" : "false", result.cancelled_signal, result.cleanup_complete ? "true" : "false",
        result.reaped, result.proc_scan ? "true" : "false", (unsigned long long)result.elapsed_ms);
    ssize_t written = write(fd, report, (size_t)size); int closed = close(fd);
    if (written != size || closed || rc || !result.cleanup_complete) return 125;
    if (result.timed_out) return 124;
    if (result.cancelled_signal) return 128 + result.cancelled_signal;
    if (result.term_signal) return 128 + result.term_signal;
    return result.exit_code;
}
