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
 * Usage: texec <program> [args...]
 * Build: gcc -O2 -static -nostdlib -ffreestanding -fno-builtin -fno-stack-protector \
 *            -Wl,-Ttext-segment=0x6f00000000 -o texec texec.c
 * (linked high so it never overlaps a non-PIE target at 0x400000)
 */
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
#define ENOSYS 38

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

static void on_sigsys(int sig, void *info, void *uc) {
    (void)sig; (void)info;
    ((struct kucontext *)uc)->mc.regs[0] = (u64)-ENOSYS;  /* pc is already past the svc */
}

/* GCC emits memset for zero-initialised structs even when freestanding. */
void *memset(void *d, int c, unsigned long n) { unsigned char *p = d; while (n--) *p++ = (unsigned char)c; return d; }

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

static void load(const char *path, Loaded *out) {
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
            if (p->filesz) for (u64 a = file_end; a < file_page_end && a < mem_end; a++) *(volatile char *)a = 0;
            u64 anon = p->filesz ? file_page_end : start;
            if (mem_end > anon) {
                i64 m = SYS(NR_mmap, anon, ((mem_end + page - 1) & ~(page - 1)) - anon, prot | PROT_W,
                            MAP_PRIVATE | MAP_FIXED | MAP_ANON, -1, 0);
                if (m < 0 && m > -4096) die("cannot map bss", path);
            }
        }
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
        { on_sigsys, 0x4 /* SA_SIGINFO */ | 0x10000000 /* SA_RESTART */, 0, 0 };
    SYS(NR_rt_sigaction, 31 /* SIGSYS */, &sa, 0, 8, 0, 0);

    const char *ld_path = TROUGH_LD, *tun = 0, *llp = 0, *pre = 0;
    int ld_forced = 0;  /* TROUGH_LD set: it wins over the program's own PT_INTERP */
    for (char **v = envp; *v; v++) {
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
    if (as_interp) {
        load(ld_path, &ld);
        entry = ld.entry;
        base = ld.bias;
    } else {
        if (argc < 2) die("usage: texec <program> [args...]", 0);
        skip = 1;
        load(argv[1], &prog);
        entry = prog.entry;
        if (prog.interp[0]) {  /* a patched binary names texec as interp: use the real ld.so */
            /* Stock binaries name /lib/ld-linux-aarch64.so.1, which Android does not have
               (10-27 phone run: "cannot open"); patched ones name texec itself. */
            load(ld_forced || ends_with(prog.interp, "/texec") || streq(prog.interp, TROUGH_TEXEC)
                 ? ld_path : prog.interp, &ld);
            entry = ld.entry;
            base = ld.bias;
        }
    }

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
    for (i64 i = skip; i < argc; i++) *w++ = (u64)argv[i];
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
        else if (!as_interp && a[0] == AT_EXECFN) v = (u64)argv[1];
        *w++ = a[0];
        *w++ = v;
    }
    *w++ = AT_NULL;
    *w++ = 0;
    jump((u64)nsp, entry);
}

__asm__(".global _start\n_start:\n mov x0, sp\n b texec_main\n");
