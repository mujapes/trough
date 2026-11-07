/* Trough: virtual FHS paths inside glibc (Trough repo docs/FILESYSTEM.md).
   One list of the path syscalls, used by C (sysdep.h's inline syscalls, the
   cancellation path, the mapper) and by assembly (DO_CALL in syscall stubs).

   TROUGH_PATHS (X, n) expands X (n, number, argmask) once per path syscall:
   bit i of argmask = argument i is a path the mapper rewrites when absolute.
   getcwd and readlinkat are here for their reverse mapping (results); bind
   and connect map an AF_UNIX sockaddr, getsockname and getpeername unmap it.  */
#ifndef _TROUGH_PATH_H
#define _TROUGH_PATH_H 1

/* Passed in x5 by every mapped path syscall (no path syscall has a sixth
   argument): the seccomp layer (texec) lets marked calls through untrapped.  */
#define TROUGH_MARK 0x7472677670617468	/* "trgvpath" */

/* faccessat (AT_FDCWD, NULL, TROUGH_PROBE) unmarked: the kernel says -EFAULT;
   under texec's path filter its handler says -TROUGH_FILTERED instead.  */
#define TROUGH_PROBE 0x7472677670726f62UL	/* "trgvprob" */
#define TROUGH_FILTERED 131	/* ENOTRECOVERABLE */

/* The host-exec broker (native/hostexec): runs Android's own programs outside
   the path filter for processes that are under it.  */
#define TROUGH_HOSTEXEC "/data/data/t.t/hostexec.sock"

#define TROUGH_PATHS(X, n) \
  X (n, 5, 1)	/* setxattr */		X (n, 6, 1)	/* lsetxattr */	\
  X (n, 8, 1)	/* getxattr */		X (n, 9, 1)	/* lgetxattr */	\
  X (n, 11, 1)	/* listxattr */		X (n, 12, 1)	/* llistxattr */	\
  X (n, 14, 1)	/* removexattr */	X (n, 15, 1)	/* lremovexattr */	\
  X (n, 17, 0)	/* getcwd */		X (n, 27, 2)	/* inotify_add_watch */	\
  X (n, 33, 2)	/* mknodat */		X (n, 34, 2)	/* mkdirat */	\
  X (n, 35, 2)	/* unlinkat */		X (n, 36, 5)	/* symlinkat */	\
  X (n, 37, 10)	/* linkat */		X (n, 38, 10)	/* renameat */	\
  X (n, 39, 1)	/* umount2 */		X (n, 40, 3)	/* mount */	\
  X (n, 41, 3)	/* pivot_root */	X (n, 43, 1)	/* statfs */	\
  X (n, 45, 1)	/* truncate */		X (n, 48, 2)	/* faccessat */	\
  X (n, 49, 1)	/* chdir */		X (n, 51, 1)	/* chroot */	\
  X (n, 53, 2)	/* fchmodat */		X (n, 54, 2)	/* fchownat */	\
  X (n, 56, 2)	/* openat */		X (n, 60, 2)	/* quotactl */	\
  X (n, 78, 2)	/* readlinkat */	X (n, 79, 2)	/* newfstatat */	\
  X (n, 88, 2)	/* utimensat */		X (n, 89, 1)	/* acct */	\
  X (n, 221, 1)	/* execve */		X (n, 224, 1)	/* swapon */	\
  X (n, 225, 1)	/* swapoff */		X (n, 263, 16)	/* fanotify_mark */	\
  X (n, 264, 2)	/* name_to_handle_at */	X (n, 276, 10)	/* renameat2 */	\
  X (n, 281, 2)	/* execveat */		X (n, 291, 2)	/* statx */	\
  X (n, 428, 2)	/* open_tree */		X (n, 429, 10)	/* move_mount */	\
  X (n, 433, 2)	/* fspick */		X (n, 437, 2)	/* openat2 */	\
  X (n, 439, 2)	/* faccessat2 */	X (n, 442, 2)	/* mount_setattr */	\
  X (n, 452, 2)	/* fchmodat2 */		X (n, 463, 2)	/* setxattrat */	\
  X (n, 464, 2)	/* getxattrat */	X (n, 465, 2)	/* listxattrat */	\
  X (n, 466, 2)	/* removexattrat */					\
  X (n, 200, 0)	/* bind: sockaddr_un */	X (n, 203, 0)	/* connect */	\
  X (n, 204, 0)	/* getsockname: result */ X (n, 205, 0) /* getpeername */

/* Assembly: `.if 0 TROUGH_PATHS (TROUGH_ASM_EQ, nr)` is true for a path syscall.  */
#define TROUGH_ASM_EQ(n, nr, mask) | ((n) == (nr))

/* Only libc and ld.so carry the mapper; other glibc libraries keep raw svc.  */
#if IS_IN (libc) || IS_IN (rtld)
# define TROUGH_MAPPED 1
#else
# define TROUGH_MAPPED 0
#endif

#ifndef __ASSEMBLER__
# define TROUGH_CASE(n, nr, mask) case nr:
# define TROUGH_MASK(n, nr, mask) case nr: return mask;

static inline __attribute__ ((always_inline)) int
__trough_is_path (long int nr)
{
  switch (nr)
    {
    TROUGH_PATHS (TROUGH_CASE, _)
      return TROUGH_MAPPED;
    default:
      return 0;
    }
}

static inline __attribute__ ((always_inline)) unsigned int
__trough_path_args (long int nr)
{
  switch (nr)
    {
    TROUGH_PATHS (TROUGH_MASK, _)
    default:
      return 0;
    }
}

/* Register-preserving entry (only x0 and x30 change, like svc plus a call):
   x8 = syscall number, x0..x5 = arguments.  */
extern void __trough_svc (void) attribute_hidden;
#endif

#endif
