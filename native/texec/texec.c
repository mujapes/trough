/* texec: run a stock Linux arm64 ELF inside Android's app seccomp filter.
 *
 * Android traps (SECCOMP_RET_TRAP) syscalls bionic does not use. On a Pixel 7a / Android 17
 * native/probe found set_robust_list, rseq and futex_waitv trapped -- and static glibc calls
 * the first two before main, so every stock binary died with SIGSYS (exit 159).
 *
 * execve() resets signal handlers, so a handler cannot be inherited through it. Instead this
 * loader installs a SIGSYS handler that turns any trapped syscall into -ENOSYS (glibc copes
 * with ENOSYS for all three), then maps the target ELF itself -- and its PT_INTERP, for
 * dynamic binaries -- builds a fresh initial stack, and jumps to the entry point. The
 * handler stays installed for the life of the program.
 *
 * Path virtualisation, layer 2 (docs/FILESYSTEM.md): programs that do not run on Trough's glibc
 * (static binaries, Go, musl) get a second seccomp filter that traps their path syscalls -- those
 * without the mapper's mark in x5 -- and this handler maps and re-issues them, with the same
 * mapper glibc uses (native/glibc/trough-path.c). Programs on Trough's glibc never trap.
 *
 * Usage: texec [-a argv0] <program> [args...]   (-a: the program's argv[0], as exec gave it)
 * Env: TROUGH_TRAP=1 forces the path filter, TROUGH_TRAP=0 never installs it.
 * Build: gcc -O2 -static -nostdlib -ffreestanding -fno-builtin -fno-stack-protector \
 *            -Inative/glibc/outside -Inative/glibc \
 *            -Wl,-Ttext-segment=0x6f00000000 -o texec texec.c
 * (linked high so it never overlaps a non-PIE target at 0x400000)
 */
#include "../glibc/trough-path.c"

typedef unsigned long u64;
typedef long i64;
typedef unsigned int u32;
typedef unsigned short u16;

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
#define SYS(n, a, b, c, d, e, f) sys6(n, (i64)(a), (i64)(b), (i64)(c), (i64)(d), (i64)(e), (i64)(f))

enum { NR_openat = 56, NR_close = 57, NR_pread64 = 67, NR_write = 64, NR_exit_group = 94,
       NR_rt_sigaction = 134, NR_munmap = 215, NR_mmap = 222, NR_faccessat = 48 };
enum { PROT_R = 1, PROT_W = 2, PROT_X = 4, MAP_PRIVATE = 2, MAP_FIXED = 0x10, MAP_ANON = 0x20 };
enum { AT_NULL = 0, AT_PHDR = 3, AT_PHENT = 4, AT_PHNUM = 5, AT_PAGESZ = 6, AT_BASE = 7,
       AT_ENTRY = 9, AT_EXECFN = 31 };

typedef struct { unsigned char ident[16]; u16 type, machine; u32 version; u64 entry, phoff, shoff;
                 u32 flags; u16 ehsize, phentsize, phnum, shentsize, shnum, shstrndx; } Ehdr;
typedef struct { u32 type, flags; u64 offset, vaddr, paddr, filesz, memsz, align; } Phdr;
enum { PT_LOAD = 1, PT_INTERP = 3, PT_PHDR = 6, ET_EXEC = 2, ET_DYN = 3 };

/* Kernel arm64 ucontext: sigmask + padding occupy 128 bytes, then a 16-aligned sigcontext. */
struct kucontext {
    u64 uc_flags; void *uc_link;
    struct { void *ss_sp; int ss_flags, pad; u64 ss_size; } uc_stack;
    unsigned char sigmask_and_pad[128];
    struct { u64 fault_address, regs[31], sp, pc, pstate; } __attribute__((aligned(16))) mc;
};

/* The program's own SIGSYS action (Go installs one for every signal). rt_sigaction(SIGSYS) is
   trapped while the path filter is on and recorded here, so the program's handler only sees
   traps that are neither path calls nor Android's. */
static struct ksigaction { void (*h)(int, void *, void *); u64 flags; void (*restorer)(void); u64 mask; } user_sigsys;
static volatile int filter_seen;
enum { NR_rt_sigaction_ = 134, SIGSYS_ = 31 };

static void on_sigsys(int sig, void *info, void *ucv) {
    u64 *x = ((struct kucontext *)ucv)->mc.regs;   /* pc is already past the svc */
    long nr = (long)x[8];
    if (nr == NR_faccessat && x[1] == 0 && x[2] == TROUGH_PROBE) {
        filter_seen = 1;
        x[0] = (u64)-TROUGH_FILTERED;   /* the kernel itself would say EFAULT: callers can tell */
    } else if (__trough_is_path(nr)) {
        long r[7] = { (long)x[0], (long)x[1], (long)x[2], (long)x[3], (long)x[4], (long)x[5], nr };
        x[0] = (u64)trough_path_syscall(r);
    } else if (nr == NR_rt_sigaction_ && x[0] == SIGSYS_) {
        struct ksigaction *act = (struct ksigaction *)x[1], *old = (struct ksigaction *)x[2];
        struct ksigaction prev = user_sigsys;
        if (act) user_sigsys = *act;
        if (old) *old = prev;
        x[0] = 0;
    } else if (nr != 99 && nr != 293 && nr != 449 && (u64)user_sigsys.h > 1 /* not DFL/IGN */) {
        user_sigsys.h(sig, info, ucv);
    } else {
        x[0] = (u64)-ENOSYS;   /* Android's trapped set: set_robust_list, rseq, futex_waitv */
    }
}

/* BPF: trap path syscalls and rt_sigaction(SIGSYS, ...) whose x5 is not TROUGH_MARK. */
struct sock_filter { unsigned short code; unsigned char jt, jf; unsigned int k; };
#define BPF_STMT(c, k) ((struct sock_filter){ (unsigned short)(c), 0, 0, (unsigned int)(k) })
#define BPF_JUMP(c, k, t, f) ((struct sock_filter){ (unsigned short)(c), (t), (f), (unsigned int)(k) })
enum { LD_ABS_W = 0x20, JEQ_K = 0x15, RET_K = 0x06, RET_ALLOW = 0x7fff0000, RET_TRAP = 0x00030000,
       OFF_NR = 0, OFF_ARG0 = 16, OFF_ARG5 = 16 + 5 * 8 };
#define TROUGH_NR(n, nr, mask) nr,
static const unsigned short path_nrs[] = { TROUGH_PATHS(TROUGH_NR, _) };

static void die(const char *a, const char *b);

static void install_filter(void) {
    enum { N = sizeof path_nrs / sizeof path_nrs[0] };
    struct sock_filter f[N + 16];
    int i = 0;
    f[i++] = BPF_STMT(LD_ABS_W, OFF_NR);
    f[i++] = BPF_JUMP(JEQ_K, NR_rt_sigaction_, 0, 4);   /* else: to the reload below */
    f[i++] = BPF_STMT(LD_ABS_W, OFF_ARG0);
    /* SIGSYS: to the mark check, so texec's own sigaction (marked) passes in every program that
       inherits the filter, and the program's own (unmarked) traps to be recorded. */
    f[i++] = BPF_JUMP(JEQ_K, SIGSYS_, (unsigned char)(N + 4), 1);
    f[i++] = BPF_STMT(RET_K, RET_TRAP);
    f[i++] = BPF_STMT(RET_K, RET_ALLOW);
    /* reload nr; each path nr jumps to the mark check below */
    f[i++] = BPF_STMT(LD_ABS_W, OFF_NR);
    for (int k = 0; k < N; k++) {
        int to_check = N - k;          /* instructions left in the list, then ALLOW, then check */
        f[i++] = BPF_JUMP(JEQ_K, path_nrs[k], (unsigned char)to_check, 0);
    }
    f[i++] = BPF_STMT(RET_K, RET_ALLOW);
    f[i++] = BPF_STMT(LD_ABS_W, OFF_ARG5);
    f[i++] = BPF_JUMP(JEQ_K, (unsigned int)TROUGH_MARK, 0, 2);
    f[i++] = BPF_STMT(LD_ABS_W, OFF_ARG5 + 4);
    f[i++] = BPF_JUMP(JEQ_K, (unsigned int)(TROUGH_MARK >> 32), 1, 0);
    f[i++] = BPF_STMT(RET_K, RET_TRAP);
    f[i++] = BPF_STMT(RET_K, RET_ALLOW);
    struct { unsigned short len; struct sock_filter *filter; } prog = { (unsigned short)i, f };
    SYS(167 /* prctl */, 38 /* PR_SET_NO_NEW_PRIVS */, 1, 0, 0, 0, 0);
    if (SYS(167, 22 /* PR_SET_SECCOMP */, 2 /* SECCOMP_MODE_FILTER */, &prog, 0, 0, 0) != 0)
        die("cannot install the path filter", 0);
}

/* Filters stack on every exec, so install only if no ancestor did: an unmarked faccessat on a
   NULL path traps exactly when the filter is already on (the handler notes it). */
static void ensure_filter(void) {
    filter_seen = 0;
    SYS(NR_faccessat, -100, 0, TROUGH_PROBE, 0, 0, 0);
    if (!filter_seen) install_filter();
}

/* GCC emits memset for zero-initialised structs even when freestanding. */
void *memset(void *d, int c, unsigned long n) { unsigned char *p = d; while (n--) *p++ = (unsigned char)c; return d; }
void *memcpy(void *d, const void *s, unsigned long n) { unsigned char *p = d; const unsigned char *q = s; while (n--) *p++ = *q++; return d; }

static u64 len(const char *s) { u64 n = 0; while (s[n]) n++; return n; }
static void die(const char *a, const char *b) {
    SYS(NR_write, 2, "texec: ", 7, 0, 0, 0);
    SYS(NR_write, 2, a, len(a), 0, 0, 0);
    if (b) { SYS(NR_write, 2, ": ", 2, 0, 0, 0); SYS(NR_write, 2, b, len(b), 0, 0, 0); }
    SYS(NR_write, 2, "\n", 1, 0, 0, 0);
    SYS(NR_exit_group, 127, 0, 0, 0, 0, 0);
    for (;;) {}
}

static u64 page;

typedef struct { u64 bias, entry, phdr; u16 phnum; char interp[256]; } Loaded;

/* Static glibc registers set_robust_list (and rseq) in every new thread while all signals are
   blocked, so Android's trap kills the thread before any handler runs (10-30, tests/fs_glibc.sh).
   tools/patch_svc.py fixes the glibc Trough ships on disk; a static program's image is fixed here,
   in memory, before its text loses write permission: `svc #0` whose x8 was set by a movz within
   the 8 preceding instructions (no branch between) to a trapped number becomes x0 = -ENOSYS. */
static int is_branch(u32 w) {
    return (w & 0x7C000000) == 0x14000000 || (w & 0xFE000000) == 0x54000000 ||
           (w & 0xFE000000) == 0xD6000000 || (w & 0x7E000000) == 0x34000000 ||
           (w & 0x7E000000) == 0x36000000;
}
static void patch_trapped(u32 *start, u32 *end) {
    for (u32 *p = start; p < end; p++) {
        if (*p != 0xD4000001 /* svc #0 */) continue;
        for (u32 *q = p - 1; q >= start && q >= p - 8; q--) {
            u32 w = *q;
            if (is_branch(w)) break;
            if ((w & 0x7FE0001F) == 0x52800008) {   /* movz w8|x8, #imm */
                u32 imm = (w >> 5) & 0xFFFF;
                if (imm == 99 || imm == 293 || imm == 449) *p = 0x92800000 | (37 << 5);  /* movn x0, #37 */
                break;
            }
            if ((w & 0x1F) == 8) break;   /* x8 set some other way: leave it */
        }
    }
}

static void load(const char *path, Loaded *out, int patch_static) {
    i64 fd = SYS(NR_openat, -100, path, 0, 0, 0, 0);
    if (fd < 0) die("cannot open", path);
    Ehdr eh;
    if (SYS(NR_pread64, fd, &eh, sizeof eh, 0, 0, 0) != (i64)sizeof eh ||
        eh.ident[0] != 0x7f || eh.ident[1] != 'E' || eh.machine != 183 /* AArch64 */)
        die("not an arm64 ELF", path);
    Phdr ph[32];
    if (eh.phnum > 32 || SYS(NR_pread64, fd, ph, eh.phnum * sizeof(Phdr), eh.phoff, 0, 0) < 0)
        die("bad program headers", path);

    u64 lo = ~0UL, hi = 0;
    out->interp[0] = 0;
    out->phdr = 0;
    for (int i = 0; i < eh.phnum; i++) {
        if (ph[i].type == PT_LOAD) {
            if (ph[i].vaddr < lo) lo = ph[i].vaddr;
            if (ph[i].vaddr + ph[i].memsz > hi) hi = ph[i].vaddr + ph[i].memsz;
        } else if (ph[i].type == PT_INTERP && ph[i].filesz < sizeof out->interp) {
            SYS(NR_pread64, fd, out->interp, ph[i].filesz, ph[i].offset, 0, 0);
            out->interp[ph[i].filesz] = 0;
        } else if (ph[i].type == PT_PHDR) {
            out->phdr = ph[i].vaddr;
        }
    }
    lo &= ~(page - 1);
    hi = (hi + page - 1) & ~(page - 1);

    u64 bias = 0;
    if (eh.type == ET_DYN) {  /* reserve the whole span, then place segments inside it */
        i64 base = SYS(NR_mmap, 0, hi - lo, 0, MAP_PRIVATE | MAP_ANON, -1, 0);
        if (base < 0 && base > -4096) die("cannot reserve address space", path);
        bias = (u64)base - lo;
    } else if (eh.type != ET_EXEC) {
        die("unsupported ELF type", path);
    }

    for (int i = 0; i < eh.phnum; i++) {
        if (ph[i].type != PT_LOAD) continue;
        Phdr *p = &ph[i];
        int prot = (p->flags & 4 ? PROT_R : 0) | (p->flags & 2 ? PROT_W : 0) | (p->flags & 1 ? PROT_X : 0);
        u64 start = (bias + p->vaddr) & ~(page - 1);
        u64 file_end = bias + p->vaddr + p->filesz;
        u64 mem_end = bias + p->vaddr + p->memsz;
        u64 off = p->offset & ~(page - 1);
        if (p->filesz) {
            i64 m = SYS(NR_mmap, start, file_end - start, prot | PROT_W, MAP_PRIVATE | MAP_FIXED, fd, off);
            if (m < 0 && m > -4096) die("cannot map segment", path);
        }
        if (p->memsz > p->filesz) {  /* .bss: zero the rest of the last file page, map the remainder */
            u64 file_page_end = (file_end + page - 1) & ~(page - 1);
            /* Linux padzero clears the entire page tail, including beyond p_memsz.
             * ld.so's initial calloc uses that slack after _end and assumes it is zero;
             * leaving ELF notes there corrupts its link_map (Ubuntu glibc 2.39). */
            if (p->filesz) for (u64 a = file_end; a < file_page_end; a++) *(volatile char *)a = 0;
            u64 anon = p->filesz ? file_page_end : start;
            if (mem_end > anon) {
                i64 m = SYS(NR_mmap, anon, ((mem_end + page - 1) & ~(page - 1)) - anon, prot | PROT_W,
                            MAP_PRIVATE | MAP_FIXED | MAP_ANON, -1, 0);
                if (m < 0 && m > -4096) die("cannot map bss", path);
            }
        }
        if (patch_static && !out->interp[0] && (prot & PROT_X) && p->filesz)
            patch_trapped((u32 *)(bias + p->vaddr), (u32 *)((bias + p->vaddr + p->filesz) & ~3UL));
        /* Drop the temporary write permission where the segment did not ask for it. */
        if (!(prot & PROT_W)) {
            u64 end = (mem_end + page - 1) & ~(page - 1);
            SYS(226 /* mprotect */, start, end - start, prot, 0, 0, 0);
        }
    }
    if (!out->phdr) {  /* no PT_PHDR: headers sit at phoff inside the first segment */
        for (int i = 0; i < eh.phnum; i++)
            if (ph[i].type == PT_LOAD && ph[i].offset == 0) { out->phdr = ph[i].vaddr + eh.phoff; break; }
    }
    out->bias = bias;
    out->entry = bias + eh.entry;
    out->phdr += bias;
    out->phnum = eh.phnum;
    SYS(NR_close, fd, 0, 0, 0, 0, 0);
}

__attribute__((noreturn)) static void jump(u64 sp, u64 entry) {
    __asm__ volatile("mov sp, %0\n mov x0, #0\n br %1" :: "r"(sp), "r"(entry) : "memory");
    __builtin_unreachable();
}

/* Trough's root is the app's own data dir, reached by the shortest stable absolute path:
   package "t.t" -> /data/data/t.t (15 chars). Short enough to fit in place of strings in
   stock binaries, so nothing is moved and no fd tricks are needed:
     PT_INTERP  /lib/ld-linux-aarch64.so.1 -> /data/data/t.t/x   (texec; tools/set_interp.py)
     glibc      /etc/resolv.conf           -> /data/data/t.t/r   (tools/patch_paths.py)
   (10-27: an fd-98 version broke whenever a parent closed all fds before exec, which
   Python's subprocess does by default.) */
#ifndef TROUGH_TEXEC
#define TROUGH_TEXEC "/data/data/t.t/x"
#endif
#ifndef TROUGH_LD
#define TROUGH_LD "/data/data/t.t/l/ld-linux-aarch64.so.1"
#endif
#ifndef TROUGH_LIB
/* Patched glibc first, then libraries dpkg installed under the Debian root d/. */
#define TROUGH_LIB "/data/data/t.t/l:/data/data/t.t/d/usr/lib/aarch64-linux-gnu:/data/data/t.t/d/lib/aarch64-linux-gnu"
#endif

static int prefix(const char *s, const char *p) { while (*p) if (*s++ != *p++) return 0; return 1; }
static int contains(const char *s, const char *p) { for (; *s; s++) if (prefix(s, p)) return 1; return 0; }
static int streq(const char *a, const char *b) { return len(a) == len(b) && prefix(a, b); }
static int ends_with(const char *s, const char *p) { u64 a = len(s), b = len(p); return a >= b && prefix(s + a - b, p); }

/* Threads that block all signals (node's libuv pool) are KILLED by a trapped syscall instead
   of reaching the handler, and glibc registers rseq in every new thread. So rseq is switched
   off at the source. set_robust_list needs nothing: glibc tries it once in the main thread,
   gets ENOSYS from the handler, and never calls it again. */
static char tunables[1024];
static const char *with_rseq_off(const char *existing) {
    const char *add = "glibc.pthread.rseq=0";
    char *w = tunables;
    const char *k = "GLIBC_TUNABLES=";
    while (*k) *w++ = *k++;
    if (existing) {
        const char *v = existing + 15;
        if (contains(v, "glibc.pthread.rseq") || len(v) > sizeof tunables - 64) return existing;
        while (*v) *w++ = *v++;
        *w++ = ':';
    }
    while (*add) *w++ = *add++;
    *w = 0;
    return tunables;
}

/* Trough's lib dir goes first on LD_LIBRARY_PATH: binaries keep their stock layout (no
   rpath edits), and a parent that cleared the environment does not strand its child. */
static char libpath[2048];
static const char *with_trough_lib(const char *existing) {
    char *w = libpath;
    const char *k = "LD_LIBRARY_PATH=", *t = TROUGH_LIB;
    while (*k) *w++ = *k++;
    while (*t) *w++ = *t++;
    if (existing) {
        const char *v = existing + 16;
        if (prefix(v, TROUGH_LIB) || len(v) > sizeof libpath - len(TROUGH_LIB) - 32) return existing;
        if (*v) *w++ = ':';
        while (*v) *w++ = *v++;
    }
    *w = 0;
    return libpath;
}

/* execmap (native/execmap): stock programs exec /bin/sh, #!/usr/bin/perl, ...; Android has no
   /bin or /usr, so every glibc program gets the exec mapper preloaded -- only if Trough
   installed it, so a texec without it behaves exactly as before. */
#ifndef TROUGH_PRELOAD
#define TROUGH_PRELOAD "/data/data/t.t/l/libexecmap.so"
#endif
static char preload[2048];
static const char *with_execmap(const char *existing) {
    if (SYS(NR_faccessat, -100 /* AT_FDCWD */, TROUGH_PRELOAD, 4 /* R_OK */, 0, 0, 0) != 0) return existing;
    char *w = preload;
    const char *k = "LD_PRELOAD=", *t = TROUGH_PRELOAD;
    while (*k) *w++ = *k++;
    while (*t) *w++ = *t++;
    if (existing) {
        const char *v = existing + 11;
        if (contains(v, TROUGH_PRELOAD) || len(v) > sizeof preload - len(TROUGH_PRELOAD) - 32) return existing;
        if (*v) *w++ = ':';
        while (*v) *w++ = *v++;
    }
    *w = 0;
    return preload;
}

__attribute__((used)) void texec_main(u64 *sp) {
    i64 argc = (i64)sp[0];
    char **argv = (char **)(sp + 1);
    char **envp = argv + argc + 1;
    char **e = envp;
    while (*e) e++;
    u64 *auxv = (u64 *)(e + 1);
    page = 4096;
    u64 at_entry = 0;
    for (u64 *a = auxv; a[0] != AT_NULL; a += 2) {
        if (a[0] == AT_PAGESZ) page = a[1];
        if (a[0] == AT_ENTRY) at_entry = a[1];
    }

    struct { void (*h)(int, void *, void *); u64 flags; void (*restorer)(void); u64 mask; } sa =
        { on_sigsys, 0x4 /* SA_SIGINFO */ | 0x10000000 /* SA_RESTART */ | 0x40000000 /* SA_NODEFER: execve from
          the handler must not leave SIGSYS blocked */ | 0x08000000 /* SA_ONSTACK: Go's goroutine stacks are small */, 0, 0 };
    SYS(NR_rt_sigaction, 31 /* SIGSYS */, &sa, 0, 8, 0, TROUGH_MARK);   /* marked: an inherited filter lets it by */

    const char *ld_path = TROUGH_LD, *tun = 0, *llp = 0, *pre = 0;
    int ld_forced = 0;  /* TROUGH_LD set: it wins over the program's own PT_INTERP */
    int trap = -1;      /* TROUGH_TRAP: 1 force, 0 never, unset: by program kind */
    for (char **v = envp; *v; v++) {
        if (prefix(*v, "TROUGH_TRAP=")) trap = (*v)[12] == '1';
        if (prefix(*v, "TROUGH_LD=")) { ld_path = *v + 10; ld_forced = 1; }
        if (prefix(*v, "GLIBC_TUNABLES=")) tun = *v;
        if (prefix(*v, "LD_LIBRARY_PATH=")) llp = *v;
        if (prefix(*v, "LD_PRELOAD=")) pre = *v;
    }

    /* Two ways in. As PT_INTERP of a patched binary (trough-apt rewrites every installed ELF
       to name texec), the kernel has already mapped the program and AT_ENTRY is ITS entry:
       we load the real ld.so and hand over with argv untouched -- this is what makes exec
       from any parent work. As a launcher (`texec prog args`), we map the program too. */
    extern char _start[];
    int as_interp = at_entry != (u64)_start;
    Loaded prog = {0}, ld;
    u64 entry, base = 0;
    i64 skip = 0;
    const char *argv0 = 0;
    if (as_interp) {
        load(ld_path, &ld, 0);
        entry = ld.entry;
        base = ld.bias;
    } else {
        if (argc < 2) die("usage: texec [-a argv0] <program> [args...]", 0);
        skip = 1;
        if (argc >= 4 && streq(argv[1], "-a")) { argv0 = argv[2]; skip = 3; }   /* exec redirects keep argv[0] */
        char pbuf[BUF], ibuf[BUF];
        /* Virtual first (a program inside the root), then literal (a host path from outside). */
        const char *pp = map(argv[skip], pbuf);
        if (!pp || SYS(NR_faccessat, -100, pp, 0 /* F_OK */, 0, 0, 0) != 0) pp = argv[skip];
        load(pp, &prog, 1);
        entry = prog.entry;
        /* glibc programs (stock or patched interp) run on Trough's glibc, which maps paths
           itself; everything else (static, musl, ...) needs the path filter. */
        int glibc = ld_forced || ends_with(prog.interp, "/texec") || streq(prog.interp, TROUGH_TEXEC)
                    || ends_with(prog.interp, "/ld-linux-aarch64.so.1");
        if (trap < 0) trap = !glibc;
        if (prog.interp[0]) {  /* a patched binary names texec as interp: use the real ld.so */
            /* Stock binaries name /lib/ld-linux-aarch64.so.1, which Android does not have
               (10-27 phone run: "cannot open"); patched ones name texec itself. */
            const char *ip = glibc ? ld_path : map(prog.interp, ibuf);
            if (!ip || SYS(NR_faccessat, -100, ip, 0, 0, 0, 0) != 0) ip = prog.interp;
            load(ip, &ld, 0);
            entry = ld.entry;
            base = ld.bias;
        }
    }
    if (trap > 0) ensure_filter();

    /* Fresh stack: argc, argv, NULL, envp (+tunables), NULL, auxv with the target's values. */
    u64 nenv = (u64)(e - envp), naux = 0;
    for (u64 *a = auxv; a[0] != AT_NULL; a += 2) naux++;
    u64 words = 1 + (u64)argc + 1 + nenv + 4 + 2 * (naux + 1);
    u64 size = 8UL << 20;
    i64 stack = SYS(NR_mmap, 0, size, PROT_R | PROT_W, MAP_PRIVATE | MAP_ANON, -1, 0);
    if (stack < 0 && stack > -4096) die("cannot map stack", 0);
    u64 *nsp = (u64 *)(((u64)stack + size - words * 8 - 64) & ~15UL);
    u64 *w = nsp;
    *w++ = (u64)(argc - skip);
    for (i64 i = skip; i < argc; i++) *w++ = (u64)(i == skip && argv0 ? argv0 : argv[i]);
    *w++ = 0;
    for (u64 i = 0; i < nenv; i++) if (envp[i] != tun && envp[i] != llp && envp[i] != pre) *w++ = (u64)envp[i];
    *w++ = (u64)with_rseq_off(tun);
    *w++ = (u64)with_trough_lib(llp);
    const char *p = with_execmap(pre);
    if (p) *w++ = (u64)p;
    *w++ = 0;
    for (u64 *a = auxv; a[0] != AT_NULL; a += 2) {
        u64 v = a[1];
        if (a[0] == AT_BASE) v = base;
        else if (!as_interp && a[0] == AT_PHDR) v = prog.phdr;
        else if (!as_interp && a[0] == AT_PHENT) v = sizeof(Phdr);
        else if (!as_interp && a[0] == AT_PHNUM) v = prog.phnum;
        else if (!as_interp && a[0] == AT_ENTRY) v = prog.entry;
        else if (!as_interp && a[0] == AT_EXECFN) v = (u64)argv[skip];
        *w++ = a[0];
        *w++ = v;
    }
    *w++ = AT_NULL;
    *w++ = 0;
    jump((u64)nsp, entry);
}

__asm__(".global _start\n_start:\n mov x0, sp\n b texec_main\n");
