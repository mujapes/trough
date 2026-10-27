/* Dynamic glibc + threads that block every signal: node's exact failure mode on Android.
 * Runs only if Trough's patched glibc turns set_robust_list/rseq into inline -ENOSYS, since
 * a trapped syscall on a fully blocked thread kills the process before texec's handler. */
#include <gnu/libc-version.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>

static void *work(void *arg) {
    sigset_t all;
    sigfillset(&all);
    pthread_sigmask(SIG_BLOCK, &all, 0);
    volatile unsigned long x = 0;
    for (unsigned long i = 0; i < 50000000UL; i++) x += i;
    *(unsigned long *)arg = x;
    return 0;
}

int main(void) {
    pthread_t t[4];
    unsigned long r[4];
    for (int i = 0; i < 4; i++) pthread_create(&t[i], 0, work, &r[i]);
    for (int i = 0; i < 4; i++) pthread_join(t[i], 0);
    printf("{\"threads\":4,\"ok\":%s,\"glibc\":\"%s\"}\n", r[0] == r[3] ? "true" : "false", gnu_get_libc_version());
    return 0;
}
