# Owned subprocess execution

`native/runtime/process` supplies the process-lifetime mechanism for trusted scenario and
build owners. It runs in a dedicated single-threaded supervisor with no existing children;
it must not be called inside the Android application process. This is not a sandbox or
capability grant. The owner supplies an absolute executable, arguments, environment and I/O.

`tp_run` starts a process group, establishes subreaper adoption and a monotonic deadline,
and collects the leader's exit/signal separately from timeout, cancellation and exec failure.
Commands start with default signal actions and unblocked signals: Android's inherited signal
state otherwise changes ordinary command behaviour. The supervisor preserves its own caller's
signal settings. TERM, INT, HUP and direct parent death cancel the owned work.

Cleanup also runs after a successful leader exit. A compiler or test cannot abandon a child
which keeps pipes or other resources alive. The leader is held unreaped with `waitid(WNOWAIT)`
until the last group signal, reserving its PID/process-group identity. The supervisor then
repeatedly enumerates children, kills live children, and reaps them. Orphans which created a
new session are adopted and handled by subsequent passes. Enumeration first uses proc's
children list, falling back to visible `/proc` entries on Android. Every candidate must pass
a kernel `waitid(P_PID)` ownership check before signalling; a number in `/proc` is not proof
of ownership. No thread or signal handler reaps between that check and the signal.

Only `waitpid` returning ECHILD establishes `cleanup_complete`. Cleanup has its own bounded
deadline. An incomplete cleanup, unavailable required mechanism or exec failure cannot become
a successful scenario. SIGKILL of the supervisor, OS termination of the whole app, unavailable
proc visibility and uninterruptible kernel work remain external recovery/failure boundaries;
this mechanism does not promise survival through them or contain hostile same-UID code.

`bin/trough-run <milliseconds> <fresh-private-report> -- <absolute-program> [args...]` writes
a bounded JSON report in a file created exclusively with mode 0600. Child stdout cannot be
mistaken for supervisor evidence. Exit 124 represents timeout, 125 a supervision/report failure,
and signal outcomes use 128+signal; the report distinguishes a child's coincident exit code.
The file is ephemeral evidence read after exit, not a durable task journal.

`ScenarioProcess` uses this owner for all native registry scenarios. Its outer watchdog leaves
room for native cleanup; Java interruption first sends TERM and allows bounded cleanup before
forcing exit. Output is continuously drained with a 64 KiB retention limit. Missing/malformed
reports, wrapper/report disagreement, incomplete cleanup, output truncation and incomplete
readers fail the evidence. Failed/interrupted delivery retains the scenario protocol's existing
no-blind-replay semantics.

The native oracle executes actual process trees, including new sessions, ignored TERM,
leader success, deadlines, all three cancellation signals, direct owner death and unrelated
siblings. It asserts every recorded descendant no longer exists, exact reaping counts and
ECHILD, and runs both enumeration strategies. It also reproduces an inherited blocked/ignored
USR1 signal. `tests/core_processes.sh` runs this on Linux; `core.processes` runs the same code
through signed Android control. `tests/scenarios.sh` covers Java interruption, pipes retained
by descendants, output limits and missing reports.

Kernel contracts: [waitid and WNOWAIT](https://man7.org/linux/man-pages/man2/waitid.2.html),
[subreaper adoption](https://man7.org/linux/man-pages/man2/PR_SET_CHILD_SUBREAPER.2const.html),
and [process-group signals](https://man7.org/linux/man-pages/man2/kill.2.html).
