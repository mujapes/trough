/* Trough: virtual FHS paths inside glibc (Trough repo docs/FILESYSTEM.md).

   The kernel sees host paths; programs see virtual ones.  An absolute path
   outside Android's own trees maps into Trough's Debian root:
       /etc/passwd   -> /data/data/t.t/d/etc/passwd
       /data/...     -> unchanged (passthrough: /data /system /proc /dev ...)
   Relative paths are unchanged (cwd and dirfds are host already).  Mapping is
   idempotent: a mapped path is under /data.  getcwd and readlink results lose
   the root prefix again.

   Linked into libc and ld.so (also before ld.so relocates itself), so: no
   global data needing relocation, no libc calls, no FP/SIMD registers
   (-mgeneral-regs-only: __trough_svc callers expect only x0 and x30 to
   change), no stack protector (the canary is not set up that early).  */

#include <errno.h>
#include <sysdep.h>
#include <trough-path.h>

#define ROOT "/data/data/t.t/d"
#define ROOT_LEN (sizeof ROOT - 1)
#define BUF 4096		/* PATH_MAX */

/* Android's trees.  One string (no pointer table: that needs relocation).  */
static const char passthrough[] =
  "data\0system\0system_ext\0apex\0vendor\0product\0odm\0proc\0sys\0dev\0"
  "storage\0sdcard\0mnt\0linkerconfig\0config\0metadata\0";

/* C is a path component equal to NAME (followed by '/' or the end).  */
static int
component_is (const char *c, const char *name)
{
  while (*name)
    if (*c++ != *name++)
      return 0;
  return *c == '/' || *c == '\0';
}

static unsigned long int
length (const char *s)
{
  unsigned long int n = 0;
  while (s[n])
    n++;
  return n;
}

/* P is absolute: does it go into the root?  */
static int
maps (const char *p)
{
  while (*p == '/')
    p++;
  if (component_is (p, "dev"))
    {
      /* Android has no /dev/shm; POSIX shared memory lives in the root.  */
      p += 3;
      while (*p == '/')
	p++;
      return component_is (p, "shm");
    }
  for (const char *e = passthrough; *e; e += length (e) + 1)
    if (component_is (p, e))
      return 0;
  return 1;
}

/* The host path for P: P itself, or BUF filled with ROOT + P, or 0 if too
   long.  NULL and relative P are returned as they are.  */
static const char *
map (const char *p, char *buf)
{
  if (p == 0 || p[0] != '/' || !maps (p))
    return p;
  unsigned long int n = length (p);
  if (ROOT_LEN + n >= BUF)
    return 0;
  for (unsigned long int i = 0; i < ROOT_LEN; i++)
    buf[i] = ROOT[i];
  for (unsigned long int i = 0; i <= n; i++)
    buf[ROOT_LEN + i] = p[i];
  return buf;
}

/* S[0..N) is a host path (not terminated): strip the root prefix in place.
   Returns the new length.  */
static long int
unmap (char *s, long int n)
{
  if (n < (long int) ROOT_LEN)
    return n;
  for (unsigned long int i = 0; i < ROOT_LEN; i++)
    if (s[i] != ROOT[i])
      return n;
  if (n == (long int) ROOT_LEN)
    {
      s[0] = '/';
      return 1;
    }
  if (s[ROOT_LEN] != '/')
    return n;
  for (long int i = ROOT_LEN; i < n; i++)
    s[i - ROOT_LEN] = s[i];
  return n - ROOT_LEN;
}

static long int
raw (long int nr, long int a0, long int a1, long int a2, long int a3,
     long int a4, long int a5)
{
  register long int x0 asm ("x0") = a0;
  register long int x1 asm ("x1") = a1;
  register long int x2 asm ("x2") = a2;
  register long int x3 asm ("x3") = a3;
  register long int x4 asm ("x4") = a4;
  register long int x5 asm ("x5") = a5;
  register long int x8 asm ("x8") = nr;
  asm volatile ("svc 0" : "+r" (x0)
		: "r" (x1), "r" (x2), "r" (x3), "r" (x4), "r" (x5), "r" (x8)
		: "memory");
  return x0;
}

/* AF_UNIX socket paths: sockaddr_un is { u16 family; char sun_path[108]; },
   the path not necessarily NUL-terminated within the given length.  */
#define SUN_MAX 108

static long int
map_sockaddr (long int *a, char *b0)
{
  const char *sa = (const char *) a[1];
  long int len = a[2];
  if (sa == 0 || len < 3 || sa[0] != 1 || sa[1] != 0 /* AF_UNIX */ || sa[2] != '/')
    return 0;
  char path[SUN_MAX + 1];
  long int n = 0;
  while (n < len - 2 && n < SUN_MAX && sa[2 + n])
    {
      path[n] = sa[2 + n];
      n++;
    }
  path[n] = '\0';
  if (!maps (path))
    return 0;
  if (ROOT_LEN + n >= SUN_MAX)
    return -ENAMETOOLONG;
  b0[0] = 1;
  b0[1] = 0;
  const char *m = map (path, b0 + 2);
  if (m == 0)
    return -ENAMETOOLONG;
  a[1] = (long int) b0;
  a[2] = 2 + ROOT_LEN + n + 1;
  return 0;
}

/* Rewrite the path arguments of NR in A[0..5) into B0/B1.  0 or -errno.  */
long int attribute_hidden
__trough_map_args (long int nr, long int *a, char *b0, char *b1)
{
  if (nr == __NR_bind || nr == __NR_connect)
    return map_sockaddr (a, b0);
  unsigned int mask = __trough_path_args (nr);
  char *buf = b0;
  for (int i = 0; i < 5; i++)
    if (mask & (1u << i))
      {
	const char *p = (const char *) a[i];
	const char *m = map (p, buf);
	if (m == 0 && p != 0)	/* NULL is valid: utimensat (fd, NULL) = futimens */
	  return -ENAMETOOLONG;
	if (m != p)
	  {
	    a[i] = (long int) m;
	    buf = b1;
	  }
      }
  return 0;
}

#define TEXEC "/data/data/t.t/x"

/* Little-endian loads from a byte buffer (no alignment or aliasing assumptions).  */
static unsigned long int
rd (const char *p, int bytes)
{
  unsigned long int v = 0;
  for (int i = bytes - 1; i >= 0; i--)
    v = (v << 8) | (unsigned char) p[i];
  return v;
}

static int
same (const char *a, const char *b)
{
  while (*a && *a == *b)
    a++, b++;
  return *a == *b;
}

/* Under texec's path filter?  An unmarked probe traps there, and the handler
   answers -TROUGH_FILTERED; without the filter the kernel says -EFAULT.  */
static int
filtered (void)
{
  return raw (__NR_faccessat, -100, 0, TROUGH_PROBE, 0, 0, 0) == -TROUGH_FILTERED;
}

static int
starts (const char *s, const char *p)
{
  while (*p)
    if (*s++ != *p++)
      return 0;
  return 1;
}

/* Kernel ABI shapes for sendmsg (64-bit).  */
struct t_iovec { void *base; unsigned long int len; };
struct t_msghdr { void *name; unsigned int namelen; struct t_iovec *iov;
		  unsigned long int iovlen; void *control;
		  unsigned long int controllen; int flags; };

static int
put_all (long int fd, const char *b, unsigned long int n)
{
  while (n)
    {
      long int w = raw (__NR_write, fd, (long int) b, n, 0, 0, 0);
      if (w <= 0)
	return -1;
      b += w, n -= w;
    }
  return 0;
}

/* Run HOST (an Android program) through the host-exec broker (native/hostexec)
   and exit with its status, so the exec looks ordinary to our parent.  Returns
   only if the broker cannot be reached.  */
static long int
hostexec (const char *host, char *const *argv, char *const *envp)
{
  char cwd[BUF];
  long int cl = raw (__NR_getcwd, (long int) cwd, BUF, 0, 0, 0, TROUGH_MARK);
  if (cl <= 0)
    cwd[0] = '/', cwd[1] = '\0';
  unsigned long int bytes = length (host) + 1 + length (cwd) + 1;
  unsigned int argc = 0, envc = 0;
  for (; argv && argv[argc]; argc++)
    bytes += length (argv[argc]) + 1;
  /* Android's linker must not follow glibc's loader variables.  */
  for (char *const *e = envp; e && *e; e++)
    if (!starts (*e, "LD_PRELOAD=") && !starts (*e, "LD_LIBRARY_PATH="))
      bytes += length (*e) + 1, envc++;
  long int m = raw (__NR_mmap, 0, bytes, 3 /* RW */, 0x22 /* PRIVATE|ANON */, -1, 0);
  if (m < 0 && m > -4096)
    return m;
  char *w = (char *) m;
  const char *parts[2] = { host, cwd };
  for (int i = 0; i < 2; i++)
    for (const char *q = parts[i]; (*w++ = *q++); )
      ;
  for (unsigned int i = 0; i < argc; i++)
    for (const char *q = argv[i]; (*w++ = *q++); )
      ;
  for (char *const *e = envp; e && *e; e++)
    if (!starts (*e, "LD_PRELOAD=") && !starts (*e, "LD_LIBRARY_PATH="))
      for (const char *q = *e; (*w++ = *q++); )
	;

  long int r = -1, sock = raw (__NR_socket, 1 /* AF_UNIX */, 1 /* STREAM */ | 02000000, 0, 0, 0, 0);
  char sa[2 + sizeof TROUGH_HOSTEXEC] = { 1, 0 };
  for (unsigned long int i = 0; i < sizeof TROUGH_HOSTEXEC; i++)
    sa[2 + i] = TROUGH_HOSTEXEC[i];
  if (sock >= 0
      && raw (__NR_connect, sock, (long int) sa, sizeof sa, 0, 0, TROUGH_MARK) == 0)
    {
      unsigned int h[4] = { 0x74786563u, argc, envc, (unsigned int) bytes };
      /* cmsghdr { size_t len; int level, type; } + fds 0, 1, 2: CMSG_LEN 28, CMSG_SPACE 32.  */
      struct { unsigned long int len; int level, type; int fds[3]; int pad; } ctl
	= { 16 + 3 * 4, 1 /* SOL_SOCKET */, 1 /* SCM_RIGHTS */, { 0, 1, 2 }, 0 };
      struct t_iovec iov = { h, sizeof h };
      struct t_msghdr msg = { 0, 0, &iov, 1, &ctl, sizeof ctl, 0 };
      int status = 0;
      if (raw (__NR_sendmsg, sock, (long int) &msg, 0x4000 /* NOSIGNAL */, 0, 0, 0) == sizeof h
	  && put_all (sock, (const char *) m, bytes) == 0)
	{
	  unsigned long int got = 0;
	  while (got < sizeof status)
	    {
	      long int n = raw (__NR_read, sock, (long int) &status + got, sizeof status - got, 0, 0, 0);
	      if (n <= 0)
		break;
	      got += n;
	    }
	  r = got == sizeof status ? 0 : -1;
	}
      if (r == 0)
	{
	  if ((status & 0x7f) == 0)	/* exited */
	    raw (__NR_exit_group, (status >> 8) & 0xff, 0, 0, 0, 0, 0);
	  long int sig = status & 0x7f;
	  long int dfl[4] = { 0, 0, 0, 0 };	/* SIG_DFL, then die the same way */
	  raw (__NR_rt_sigaction, sig, (long int) dfl, 0, 8, 0, 0);
	  raw (__NR_kill, raw (__NR_getpid, 0, 0, 0, 0, 0, 0), sig, 0, 0, 0, 0);
	  raw (__NR_exit_group, 128 + sig, 0, 0, 0, 0, 0);
	}
    }
  if (sock >= 0)
    raw (__NR_close, sock, 0, 0, 0, 0, 0);
  raw (__NR_munmap, m, bytes, 0, 0, 0, 0);
  return r;
}

/* execve of PATH (as the program gave it), HOST its mapped form.  The kernel
   resolves #! interpreters and PT_INTERP on the host, where /usr/bin/perl and
   /lib/ld-linux-aarch64.so.1 do not exist, and runs static programs without
   texec (Android's traps kill them).  So: a script whose interpreter is a
   virtual path runs that interpreter, mapped, with the kernel's argv layout
   (interp [optarg] path args...); an ELF program that does not name texec or
   an Android linker runs through `texec -a argv0 host args...`.  Everything
   else goes to the kernel as is.  Returns only on failure, like execve.  */
static long int
exec_redirect (const char *path, const char *host, char *const *argv,
	       char *const *envp, int depth)
{
  char head[1024];
  long int fd = raw (__NR_openat, -100, (long int) host, 02000000 /* O_CLOEXEC */,
		     0, 0, TROUGH_MARK);
  long int n = fd < 0 ? 0 : raw (__NR_read, fd, (long int) head, sizeof head - 1, 0, 0, 0);
  if (fd >= 0)
    raw (__NR_close, fd, 0, 0, 0, 0, 0);
  long int argc = 0;
  while (argv && argv[argc])
    argc++;
  if (n < 0)
    n = 0;
  head[n] = '\0';

  if (n >= 2 && head[0] == '#' && head[1] == '!' && depth < 4)
    {
      char *i = head + 2, *arg = 0;
      while (*i == ' ' || *i == '\t')
	i++;
      char *e = i;
      while (*e && *e != ' ' && *e != '\t' && *e != '\n')
	e++;
      if (*e == ' ' || *e == '\t')
	{
	  *e++ = '\0';
	  while (*e == ' ' || *e == '\t')
	    e++;
	  if (*e && *e != '\n')
	    {
	      arg = e;
	      while (*e && *e != '\n')
		e++;
	      while (e > arg && (e[-1] == ' ' || e[-1] == '\t'))
		e--;
	    }
	}
      *e = '\0';
      char ibuf[BUF];
      const char *ih = map (i, ibuf);
      if (i[0] == '/' && ih != i && ih != 0)
	{
	  const char *nargv[argc + 3];
	  long int k = 0;
	  nargv[k++] = i;
	  if (arg)
	    nargv[k++] = arg;
	  nargv[k++] = path;
	  for (long int j = 1; j < argc; j++)
	    nargv[k++] = argv[j];
	  nargv[k] = 0;
	  return exec_redirect (i, ih, (char *const *) nargv, envp, depth + 1);
	}
    }
  else if (n >= 64 && head[0] == 0x7f && head[1] == 'E' && head[2] == 'L'
	   && head[3] == 'F' && head[4] == 2 /* 64-bit */)
    {
      unsigned long int phoff = rd (head + 0x20, 8);
      unsigned int phnum = rd (head + 0x38, 2);
      int interp_ok = 0, has_interp = 0;
      for (unsigned int k = 0; k < phnum; k++)
	{
	  unsigned long int o = phoff + k * 56;
	  if (o + 56 > (unsigned long int) n)
	    {
	      has_interp = interp_ok = 1;	/* headers out of reach: leave it to the kernel */
	      break;
	    }
	  if (rd (head + o, 4) != 3 /* PT_INTERP */)
	    continue;
	  unsigned long int off = rd (head + o + 8, 8);
	  unsigned long int sz = rd (head + o + 32, 8);
	  char interp[256];
	  has_interp = 1;
	  if (sz == 0 || sz >= sizeof interp)
	    break;
	  long int f = raw (__NR_openat, -100, (long int) host, 02000000, 0, 0, TROUGH_MARK);
	  long int r = f < 0 ? -1 : raw (__NR_pread64, f, (long int) interp, sz, off, 0, 0);
	  if (f >= 0)
	    raw (__NR_close, f, 0, 0, 0, 0, 0);
	  if (r != (long int) sz)
	    break;
	  interp[sz] = '\0';
	  /* texec itself, or a path Android owns (/system/bin/linker64).  */
	  interp_ok = same (interp, TEXEC) || (interp[0] == '/' && !maps (interp));
	  /* An Android program would inherit texec's path filter with no handler
	     and die on its first path syscall: the broker runs it unfiltered.  */
	  if (interp_ok && !same (interp, TEXEC) && filtered ())
	    {
	      long int e = hostexec (host, argv, envp);
	      (void) e;		/* broker unreachable: plain exec, as before */
	    }
	  break;
	}
      if (!has_interp || !interp_ok)
	{
	  const char *nargv[argc + 5];
	  long int k = 0;
	  nargv[k++] = TEXEC;
	  nargv[k++] = "-a";
	  nargv[k++] = argc ? argv[0] : path;
	  nargv[k++] = host;
	  for (long int j = 1; j < argc; j++)
	    nargv[k++] = argv[j];
	  nargv[k] = 0;
	  return raw (__NR_execve, (long int) TEXEC, (long int) nargv,
		      (long int) envp, 0, 0, TROUGH_MARK);
	}
    }
  return raw (__NR_execve, (long int) host, (long int) argv, (long int) envp,
	      0, 0, TROUGH_MARK);
}

/* R[0..6) = x0..x5, R[6] = x8.  Returns the syscall result (x0).  */
static long int __attribute__ ((used, noinline))
trough_path_syscall (long int *r)
{
  long int nr = r[6];
  long int a[5] = { r[0], r[1], r[2], r[3], r[4] };
  if (!__trough_is_path (nr))	/* syscall (): any number arrives here */
    return raw (nr, a[0], a[1], a[2], a[3], a[4], r[5]);
  /* No "already mapped" shortcut on x5: for calls with fewer than six
     arguments x5 is whatever the register held, and after a marked call that
     is the mark itself (javac on the phone, 10-30).  Mapping is idempotent,
     so a path the cancellation path mapped just passes through again.  */

  char b0[BUF], b1[BUF];
  long int e = __trough_map_args (nr, a, b0, b1);
  if (e < 0)
    return e;

  if (nr == __NR_execve)
    return exec_redirect ((const char *) r[0], (const char *) a[0],
			  (char *const *) a[1], (char *const *) a[2], 0);
  if ((nr == __NR_getsockname || nr == __NR_getpeername) && a[1] && a[2])
    {
      /* Into a full-size buffer, so the host path is never truncated where the
	 virtual one fits; then copy out the way the kernel would.  */
      unsigned int *lenp = (unsigned int *) a[2];
      unsigned int given = *lenp, full = 128;	/* sizeof (struct sockaddr_storage) */
      long int r = raw (nr, a[0], (long int) b0, (long int) &full, 0, 0, TROUGH_MARK);
      if (r != 0)
	return r;
      if (full > 2 && full <= SUN_MAX + 2 && b0[0] == 1 && b0[1] == 0)
	{
	  long int n = full - 2;
	  int nul = b0[2 + n - 1] == '\0';
	  long int m = unmap (b0 + 2, n - nul);
	  if (m != n - nul)
	    {
	      b0[2 + m] = '\0';
	      full = 2 + m + 1;
	    }
	}
      for (unsigned int i = 0; i < full && i < given; i++)
	((char *) a[1])[i] = b0[i];
      *lenp = full;
      return 0;
    }
  if (nr == __NR_getcwd)
    {
      long int n = raw (nr, (long int) b0, BUF, 0, 0, 0, TROUGH_MARK);
      if (n <= 0)
	return n;
      n = unmap (b0, n - 1);		/* the kernel's count includes the NUL */
      b0[n++] = '\0';
      if ((unsigned long int) n > (unsigned long int) a[1])
	return -ERANGE;
      for (long int i = 0; i < n; i++)
	((char *) a[0])[i] = b0[i];
      return n;
    }
  if (nr == __NR_readlinkat && a[3] > 0)
    {
      long int n = raw (nr, a[0], a[1], (long int) b1, BUF, 0, TROUGH_MARK);
      if (n <= 0)
	return n;
      n = unmap (b1, n);
      if (n > a[3])
	n = a[3];
      for (long int i = 0; i < n; i++)
	((char *) a[2])[i] = b1[i];
      return n;
    }
  return raw (nr, a[0], a[1], a[2], a[3], a[4], TROUGH_MARK);
}


/* x0..x18, x29, x30 and flags-free: callers (inline asm, DO_CALL) treat this
   like svc that also clobbers x30.  */
asm (".text\n"
     ".p2align 2\n"
     ".globl __trough_svc\n"
     ".hidden __trough_svc\n"
     ".type __trough_svc, %function\n"
     "__trough_svc:\n"
     "	hint 34\n"			/* bti c */
     "	sub sp, sp, #176\n"
     "	stp x0, x1, [sp, #0]\n"
     "	stp x2, x3, [sp, #16]\n"
     "	stp x4, x5, [sp, #32]\n"
     "	stp x8, x6, [sp, #48]\n"
     "	stp x7, x9, [sp, #64]\n"
     "	stp x10, x11, [sp, #80]\n"
     "	stp x12, x13, [sp, #96]\n"
     "	stp x14, x15, [sp, #112]\n"
     "	stp x16, x17, [sp, #128]\n"
     "	stp x18, x29, [sp, #144]\n"
     "	str x30, [sp, #160]\n"
     "	add x29, sp, #144\n"
     "	mov x0, sp\n"
     "	bl trough_path_syscall\n"
     "	ldr x1, [sp, #8]\n"
     "	ldp x2, x3, [sp, #16]\n"
     "	ldp x4, x5, [sp, #32]\n"
     "	ldp x8, x6, [sp, #48]\n"
     "	ldp x7, x9, [sp, #64]\n"
     "	ldp x10, x11, [sp, #80]\n"
     "	ldp x12, x13, [sp, #96]\n"
     "	ldp x14, x15, [sp, #112]\n"
     "	ldp x16, x17, [sp, #128]\n"
     "	ldp x18, x29, [sp, #144]\n"
     "	ldr x30, [sp, #160]\n"
     "	add sp, sp, #176\n"
     "	ret\n"
     ".size __trough_svc, .-__trough_svc\n");
