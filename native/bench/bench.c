/* Cycle measurement without the kernel PMU (DESIGN.md "Measurement without adb").
 *
 * CNTVCT_EL0 is readable from user space with no permission. To turn its ticks into cycles
 * we need the core's clock, and to trust that we check it: each core runs a chain of
 * dependent ADDs whose cycle count is known exactly (1-cycle latency on X1/A78/A55), so
 * cycles / elapsed time must match the frequency cpufreq reports. If they agree, the method
 * measures cycles; if not, the JSON says by how much.
 *
 * Static arm64 executable, shipped in the APK as lib/arm64-v8a/libtroughbench.so and exec'd
 * from nativeLibraryDir. Output: one JSON object on stdout.
 */
#define _GNU_SOURCE
#include <sched.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>

#define CHAIN 64          /* dependent adds per loop iteration */
#define ITERS 2000000ULL  /* 128M adds: ~50ms on an X1 */
#define RUNS 7

static inline uint64_t ticks(void) {
    uint64_t v;
    __asm__ volatile("isb; mrs %0, cntvct_el0" : "=r"(v));
    return v;
}

static uint64_t tick_hz(void) {
    uint64_t f;
    __asm__ volatile("mrs %0, cntfrq_el0" : "=r"(f));
    return f;
}

static long read_khz(int cpu, const char *file) {
    char path[128];
    snprintf(path, sizeof path, "/sys/devices/system/cpu/cpu%d/cpufreq/%s", cpu, file);
    FILE *f = fopen(path, "r");
    if (!f) return -1;
    long v = -1;
    if (fscanf(f, "%ld", &v) != 1) v = -1;
    fclose(f);
    return v;
}

#define ADD8 "add %0, %0, #1\n add %0, %0, #1\n add %0, %0, #1\n add %0, %0, #1\n" \
             "add %0, %0, #1\n add %0, %0, #1\n add %0, %0, #1\n add %0, %0, #1\n"

/* CHAIN dependent adds per iteration; the loop counter and branch run in parallel on
   another pipe, so one iteration costs CHAIN cycles. */
static uint64_t chain(uint64_t n) {
    uint64_t x = 0;
    for (uint64_t i = 0; i < n; i++)
        __asm__ volatile(ADD8 ADD8 ADD8 ADD8 ADD8 ADD8 ADD8 ADD8 : "+r"(x));
    return x;
}

static int cmp(const void *a, const void *b) {
    double x = *(const double *)a, y = *(const double *)b;
    return (x > y) - (x < y);
}

int main(void) {
    int ncpu = (int)sysconf(_SC_NPROCESSORS_CONF);
    uint64_t hz = tick_hz();
    printf("{\"tick_hz\":%llu,\"cpus\":[", (unsigned long long)hz);
    for (int cpu = 0; cpu < ncpu; cpu++) {
        cpu_set_t set;
        CPU_ZERO(&set);
        CPU_SET(cpu, &set);
        int pinned = sched_setaffinity(0, sizeof set, &set) == 0;
        long max_khz = read_khz(cpu, "cpuinfo_max_freq");
        chain(ITERS);  /* warm up: let the governor raise the clock */
        double ghz[RUNS];
        long cur_khz = -1;
        for (int r = 0; r < RUNS; r++) {
            uint64_t a = ticks();
            volatile uint64_t sink = chain(ITERS);
            uint64_t b = ticks();
            (void)sink;
            double secs = (double)(b - a) / (double)hz;
            ghz[r] = (double)ITERS * CHAIN / secs / 1e9;
            if (r == RUNS / 2) cur_khz = read_khz(cpu, "scaling_cur_freq");
        }
        qsort(ghz, RUNS, sizeof ghz[0], cmp);
        printf("%s{\"cpu\":%d,\"pinned\":%s,\"max_khz\":%ld,\"cur_khz\":%ld,"
               "\"measured_ghz_median\":%.4f,\"measured_ghz_min\":%.4f,\"measured_ghz_max\":%.4f}",
               cpu ? "," : "", cpu, pinned ? "true" : "false", max_khz, cur_khz,
               ghz[RUNS / 2], ghz[0], ghz[RUNS - 1]);
        fflush(stdout);
    }
    printf("]}\n");
    return 0;
}
