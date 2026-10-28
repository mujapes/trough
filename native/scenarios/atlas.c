/* core.atlas: atlas residency with fence lifetimes (docs/ATLAS.md) against a shadow GPU. Frames
 * use random glyph keys (skewed, many sizes), up to 3 frames are in flight, and fences retire
 * in order at random times. An upload must never overwrite a rectangle that an unretired frame
 * samples for another key; every hit must find its key there; every refusal must be justified
 * by brute force (no free, retired or unassigned space for the class); live rectangles never
 * overlap. */
#include "atlas.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define CHECK(c) do { if (!(c)) { fprintf(stderr, "%s:%d check failed: %s\n", __FILE__, __LINE__, #c); abort(); } } while (0)

enum { W = 1024, H = 1024, PAGE = 256, KEYS = 4000, INFLIGHT = 3, MAXUSE = 400 };
static at_atlas atlas; static at_cell cells[(W / 16) * (H / 16)]; static uint32_t index_[16384]; static int32_t pages[(W / PAGE) * (H / PAGE)];
static uint64_t content[W / 16][H / 16];
static int large_share; static uint32_t aw, ah;               /* shadow GPU: key uploaded at each 16-px origin */
typedef struct { at_rect r; uint64_t key; } use;
static use frames[INFLIGHT + 1][MAXUSE]; static int nuse[INFLIGHT + 1]; static uint64_t frame_id[INFLIGHT + 1];
static int inflight;
static uint64_t total_refusals, total_reclaimed;                                     /* frames[0..inflight-1] submitted; [inflight] recording */
static uint64_t rng = 0x2545f4914f6cdd1dULL;
static uint64_t next(void) { rng ^= rng << 13; rng ^= rng >> 7; rng ^= rng << 17; return rng; }
static void size_of(uint64_t key, uint16_t *w, uint16_t *h) {
    uint64_t m = key * 0x9e3779b97f4a7c15ULL >> 40;
    uint16_t base = (m % (uint64_t)large_share == 0) ? 60 + m % 60 : 6 + m % 26;   /* mostly glyph-sized, some large */
    *w = base; *h = (uint16_t)(base / 2 + (m >> 8) % (base / 2 + 1));
}
static int cls(uint16_t w, uint16_t h) { int c = 0; while (w > (16u << c) || h > (16u << c)) c++; return c; }

static void upload_check(at_rect r, uint64_t key) {
    for (int f = 0; f <= inflight; f++) for (int k = 0; k < nuse[f]; k++)
        if (frames[f][k].r.x == r.x && frames[f][k].r.y == r.y) CHECK(frames[f][k].key == key);   /* no overwrite in flight */
    content[r.x / 16][r.y / 16] = key;
}
static void justify_refusal(uint16_t w, uint16_t h) {
    int c = cls(w, h);
    for (uint32_t p = 0; p < atlas.pages; p++) {
        CHECK(atlas.page_class[p] >= 0);                 /* no unassigned page was available */
        int pc = atlas.page_class[p], busy = 0;
        uint32_t n = PAGE / (16u << pc);
        for (uint32_t i = 0; i < n * n; i++) {
            const at_cell *e = &atlas.cells[p * (PAGE / 16) * (PAGE / 16) + i];
            if (pc == c) CHECK(e->live && e->last_frame > atlas.retired);   /* nothing free or retired */
            if (e->live && e->last_frame > atlas.retired) busy = 1;
        }
        if (pc != c) CHECK(busy);                        /* no other page was wholly reclaimable */
    }
}
static void overlap_check(void) {
    static unsigned char owner[W][H / 8];
    memset(owner, 0, sizeof owner);
    for (uint32_t p = 0; p < atlas.pages; p++) {
        int c = atlas.page_class[p];
        if (c < 0) continue;
        uint32_t n = PAGE / (16u << c), across = W / PAGE, s = 16u << c;
        for (uint32_t i = 0; i < n * n; i++) {
            if (!atlas.cells[p * (PAGE / 16) * (PAGE / 16) + i].live) continue;
            uint32_t x0 = (p % (aw / PAGE)) * PAGE + (i % n) * s, y0 = (p / (aw / PAGE)) * PAGE + (i / n) * s;
            (void)across;
            CHECK(x0 + s <= aw && y0 + s <= ah);
            for (uint32_t x = x0; x < x0 + s; x += 8) for (uint32_t y = y0; y < y0 + s; y += 8) {
                CHECK(!(owner[x][y / 8] & 1)); owner[x][y / 8] |= 1;
            }
        }
    }
}

static void run(int rounds, const char *name) {
    memset(content, 0, sizeof content); memset(nuse, 0, sizeof nuse); inflight = 0;
    CHECK(at_init(&atlas, aw, ah, PAGE, cells, sizeof cells / sizeof cells[0], index_, 16384, pages) == TR_OK);
    uint64_t uses = 0, uploads = 0, refusals = 0;
    for (int round = 0; round < rounds; round++) {
        /* Record one frame: a skewed draw of keys (a working set that drifts over time). */
        int f = inflight; nuse[f] = 0; frame_id[f] = at_frame(&atlas);
        int count = 30 + (int)(next() % 300);
        uint64_t drift = (uint64_t)round / 40;
        for (int k = 0; k < count && nuse[f] < MAXUSE; k++) {
            uint64_t r = next() % 1000, key;
            key = r < 800 ? drift * 7 + next() % 150 : next() % KEYS;        /* hot set + long tail */
            uint16_t w, h; size_of(key + 1, &w, &h);
            at_rect rect;
            tr_status s = at_use(&atlas, key + 1, w, h, &rect);
            uses++;
            if (s == TR_CAPACITY) { refusals++; justify_refusal(w, h); continue; }
            CHECK(s == TR_OK || s == TR_STALE);
            CHECK(rect.w == w && rect.h == h && rect.x % 16 == 0 && rect.y % 16 == 0);
            if (s == TR_STALE) { upload_check(rect, key + 1); uploads++; }
            else CHECK(content[rect.x / 16][rect.y / 16] == key + 1);       /* resident: really there */
            frames[f][nuse[f]].r = rect; frames[f][nuse[f]].key = key + 1; nuse[f]++;
        }
        CHECK(at_submit(&atlas) == frame_id[f]);
        inflight++;
        /* Fences: retire the oldest frames, in order, sometimes several, sometimes none. */
        while (inflight == INFLIGHT || (inflight && next() % 3 == 0)) {
            CHECK(at_retire(&atlas, frame_id[0]) == TR_OK);
            memmove(&frames[0], &frames[1], sizeof frames[0] * (size_t)inflight);
            memmove(&nuse[0], &nuse[1], sizeof nuse[0] * (size_t)inflight);
            memmove(&frame_id[0], &frame_id[1], sizeof frame_id[0] * (size_t)inflight);
            inflight--;
        }
        if (round % 97 == 0) overlap_check();
    }
    CHECK(at_retire(&atlas, at_frame(&atlas)) == TR_INVALID);   /* the recording frame cannot retire */
    printf("ok %s: %ux%u, %d frames, %llu uses, hit rate %.3f, %llu uploads, %llu evictions, %llu pages reclaimed, "
           "%llu refusals (all justified); no upload overwrote a rectangle an in-flight frame samples\n",
           name, aw, ah, rounds, (unsigned long long)uses, (double)atlas.hits / (double)(atlas.hits + atlas.misses),
           (unsigned long long)uploads, (unsigned long long)atlas.evictions, (unsigned long long)atlas.reclaimed,
           (unsigned long long)refusals);
    total_refusals += refusals; total_reclaimed += atlas.reclaimed;
}

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IONBF, 0);
    int rounds = argc > 1 ? atoi(argv[1]) : 4000;
    aw = W; ah = H; large_share = 10; run(rounds, "roomy");
    aw = 512; ah = 512; large_share = 3; run(rounds, "pressure");
    CHECK(total_refusals > 0 && total_reclaimed > 0);    /* both paths exercised */
    printf("{\"ok\":true,\"frames\":%d,\"refusals\":%llu,\"reclaimed\":%llu}\n", 2 * rounds,
           (unsigned long long)total_refusals, (unsigned long long)total_reclaimed);
    return 0;
}
