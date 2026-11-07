/* trough-path.c outside glibc (texec's SIGSYS layer, tests/fs_map.c): the glibc-internal
   names it needs. Build with -Inative/glibc/outside -Inative/glibc.  */
#include <asm/unistd.h>
#define MODULE_libc 1
#define MODULE_rtld 2
#ifndef IN_MODULE
# define IN_MODULE MODULE_libc
#endif
#define IS_IN(lib) (IN_MODULE == MODULE_##lib)
#define attribute_hidden __attribute__ ((visibility ("hidden")))
