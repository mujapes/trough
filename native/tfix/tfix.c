/* tfix, native: after dpkg, make everything under Trough's Debian root work from /data/data/t.t
 * on the phone, where there is no Python. Same behaviour as tools/tfix.py (the differential
 * oracle, tests/tfix.sh, checks byte-identical results):
 *   1. every 64-bit ELF gets PT_INTERP /data/data/t.t/x (texec), overwritten in place, never
 *      moving a byte (set_interp.py);
 *   2. every absolute symlink not already under /data/data/t.t is re-rooted under the root;
 *   3. links whose target is missing are reported (they would dangle on the phone): exit 1.
 * Run by apt as DPkg::Post-Invoke (tools/root.sh writes the hook).
 *   tfix [tree=/data/data/t.t/d] [--root /data/data/t.t/d]
 */
#define _GNU_SOURCE
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <ftw.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static const char NEW[] = "/data/data/t.t/x", TROUGH[] = "/data/data/t.t/";
static const char *root = "/data/data/t.t/d";
static long elfs, links, ndangling;
static char dangling[20][2 * PATH_MAX + 8];

/* 1 if rewritten, 0 if not applicable or already texec. */
static int set_interp(const char *path) {
    int fd = open(path, O_RDWR | O_CLOEXEC);
    if (fd < 0) return 0;
    Elf64_Ehdr eh;
    int done = 0;
    if (pread(fd, &eh, sizeof eh, 0) != sizeof eh || memcmp(eh.e_ident, ELFMAG, SELFMAG) || eh.e_ident[EI_CLASS] != ELFCLASS64
        || eh.e_phentsize != sizeof(Elf64_Phdr)) goto out;
    for (int i = 0; i < eh.e_phnum; i++) {
        Elf64_Phdr ph;
        if (pread(fd, &ph, sizeof ph, eh.e_phoff + (off_t)i * sizeof ph) != sizeof ph) break;
        if (ph.p_type != PT_INTERP) continue;
        if (ph.p_filesz < sizeof NEW || ph.p_filesz > 4096) break;   /* slot too small: leave it */
        char old[4096];
        if (pread(fd, old, ph.p_filesz, ph.p_offset) != (ssize_t)ph.p_filesz) break;
        if (strncmp(old, NEW, ph.p_filesz) == 0 && old[sizeof NEW - 1] == 0) break;   /* already set */
        if (!strncmp(old, "/system/bin/linker", 18)) break;   /* bionic: Android's linker loads it */
        char slot[4096] = {0};
        memcpy(slot, NEW, sizeof NEW - 1);
        done = pwrite(fd, slot, ph.p_filesz, ph.p_offset) == (ssize_t)ph.p_filesz;
        break;
    }
out:
    close(fd);
    return done;
}

static int visit(const char *p, const struct stat *st, int type, struct FTW *ftw) {
    (void)st; (void)ftw;
    if (type == FTW_SL || type == FTW_SLN) {
        char t[PATH_MAX];
        ssize_t n = readlink(p, t, sizeof t - 1);
        if (n < 0) return 0;
        t[n] = 0;
        if (t[0] == '/' && strncmp(t, TROUGH, sizeof TROUGH - 1) != 0) {
            char nt[PATH_MAX];
            snprintf(nt, sizeof nt, "%s%s", root, t);
            if (unlink(p) == 0 && symlink(nt, p) == 0) links++;
            strcpy(t, nt);
        }
        struct stat ls;
        if (t[0] == '/' && lstat(t, &ls) != 0) {
            if (ndangling < 20) snprintf(dangling[ndangling], sizeof dangling[0], "%s -> %s", p, t);
            ndangling++;
        }
        return 0;
    }
    if (type == FTW_F && S_ISREG(st->st_mode)) elfs += set_interp(p);
    return 0;
}

int main(int argc, char **argv) {
    const char *tree = "/data/data/t.t/d";
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--root") && i + 1 < argc) root = argv[++i];
        else tree = argv[i];
    }
    if (nftw(tree, visit, 64, FTW_PHYS) != 0) { fprintf(stderr, "tfix: %s: %s\n", tree, strerror(errno)); return 2; }
    printf("%s: %ld ELF files now use texec, %ld absolute links re-rooted under %s\n", tree, elfs, links, root);
    if (ndangling) {
        printf("%ld links would dangle on the phone (target missing from the root):\n", ndangling);
        for (long i = 0; i < ndangling && i < 20; i++) printf("  %s\n", dangling[i]);
        return 1;
    }
    return 0;
}
