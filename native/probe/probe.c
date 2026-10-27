/* Which syscalls does Android's app seccomp filter trap? (no libc: libc startup is the suspect)
 *
 * The 10-27 bench died with SIGSYS (exit 159) before printing anything: something static
 * glibc calls at startup is not on the app allowlist. Android installs the filter with
 * SECCOMP_RET_TRAP, so a SIGSYS handler sees si_syscall and the call simply returns.
 * This tries every syscall a static glibc binary made at startup (strace in the VM) and
 * prints one JSON object: {"name": "ok" | "SIGSYS" , ...}.
 *
 * Build: gcc -O2 -static -nostdlib -ffreestanding -fno-builtin -fno-stack-protector -o probe probe.c
 */
typedef unsigned long u64;
typedef long i64;

static i64 sys6(i64 n, i64 a, i64 b, i64 c, i64 d, i64 e, i64 f) {
    register i64 x8 __asm__("x8") = n;
    register i64 x0 __asm__("x0") = a;
    register i64 x1 __asm__("x1") = b;
    register i64 x2 __asm__("x2") = c;
    register i64 x3 __asm__("x3") = d;
    register i64 x4 __asm__("x4") = e;
    register i64 x5 __asm__("x5") = f;
    __asm__ volatile("svc 0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5) : "memory");
    return x0;
}
#define S(n, a, b, c, d) sys6(n, (i64)(a), (i64)(b), (i64)(c), (i64)(d), 0, 0)

enum { NR_write = 64, NR_exit_group = 94, NR_rt_sigaction = 134, NR_set_tid_address = 96,
       NR_set_robust_list = 99, NR_rseq = 293, NR_prlimit64 = 261, NR_readlinkat = 78,
       NR_getrandom = 278, NR_ioctl = 29, NR_brk = 214, NR_mprotect = 226,
       NR_sched_setaffinity = 122, NR_openat = 56, NR_close = 57, NR_fstat = 80, NR_read = 63,
       NR_clone3 = 435, NR_faccessat2 = 439, NR_statx = 291, NR_futex_waitv = 449 };

static volatile int trapped;

struct ksigaction { void (*handler)(int, void *, void *); u64 flags; void (*restorer)(void); u64 mask; };

static void on_sigsys(int sig, void *info, void *ctx) { (void)sig; (void)info; (void)ctx; trapped = 1; }

static u64 len(const char *s) { u64 n = 0; while (s[n]) n++; return n; }
static void out(const char *s) { S(NR_write, 1, s, len(s), 0); }

static char buf[256];
static u64 robust[3];
static unsigned rseq_area[8] __attribute__((aligned(32)));
static int tid;

static void probe(const char *name, i64 nr, i64 a, i64 b, i64 c, i64 d) {
    static int first = 1;
    trapped = 0;
    sys6(nr, a, b, c, d, 0, 0);
    out(first ? "\"" : ",\""); first = 0;
    out(name);
    out(trapped ? "\":\"SIGSYS\"" : "\":\"ok\"");
}

void _start(void) {
    struct ksigaction sa = { on_sigsys, 0x4 /* SA_SIGINFO */, 0, 0 };
    S(NR_rt_sigaction, 31 /* SIGSYS */, &sa, 0, 8);
    u64 cpu = 1;
    out("{");
    probe("set_tid_address", NR_set_tid_address, (i64)&tid, 0, 0, 0);
    probe("set_robust_list", NR_set_robust_list, (i64)robust, 24, 0, 0);
    probe("rseq", NR_rseq, (i64)rseq_area, 32, 0, 0x53053053);
    probe("rseq_unregister", NR_rseq, (i64)rseq_area, 32, 1, 0x53053053);
    probe("prlimit64", NR_prlimit64, 0, 3 /* RLIMIT_STACK */, 0, (i64)buf);
    probe("readlinkat", NR_readlinkat, -100, (i64)"/proc/self/exe", (i64)buf, sizeof buf);
    probe("getrandom", NR_getrandom, (i64)buf, 8, 0, 0);
    probe("ioctl_tcgets", NR_ioctl, 1, 0x5401, (i64)buf, 0);
    probe("brk", NR_brk, 0, 0, 0, 0);
    probe("mprotect", NR_mprotect, (i64)buf & ~4095L, 4096, 3, 0);
    probe("sched_setaffinity", NR_sched_setaffinity, 0, sizeof cpu, (i64)&cpu, 0);
    probe("openat", NR_openat, -100, (i64)"/proc/self/stat", 0, 0);
    probe("fstat", NR_fstat, 1, (i64)buf, 0, 0);
    probe("read_bad_fd", NR_read, -1, (i64)buf, 1, 0);
    probe("clone3_einval", NR_clone3, 0, 0, 0, 0);
    probe("faccessat2", NR_faccessat2, -100, (i64)"/", 0, 0);
    probe("statx", NR_statx, -100, (i64)"/", 0, 0x7ff);
    probe("futex_waitv_einval", NR_futex_waitv, 0, 0, 0, 0);
    out("}\n");
    S(NR_exit_group, 0, 0, 0, 0);
    for (;;) {}
}
