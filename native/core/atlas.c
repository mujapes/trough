#include "atlas.h"
#include <string.h>

#define TOMB UINT32_MAX

static uint64_t mix(uint64_t x) { x ^= x >> 33; x *= 0xff51afd7ed558ccdULL; x ^= x >> 33; x *= 0xc4ceb9fe1a85ec53ULL; return x ^ (x >> 33); }
static uint32_t cell_size(int c) { return 16u << c; }

tr_status at_init(at_atlas *a, uint32_t width, uint32_t height, uint32_t page, at_cell *cells, size_t cell_capacity,
                  uint32_t *index, size_t index_capacity, int32_t *page_class) {
    if (!a || !cells || !index || !page_class || !page || page % cell_size(AT_CLASSES - 1) || width % page || height % page
        || width > 65535 || height > 65535 || cell_capacity < (size_t)(width / 16) * (height / 16)
        || index_capacity < 2 * cell_capacity || (index_capacity & (index_capacity - 1))) return TR_INVALID;
    memset(a, 0, sizeof *a);
    a->cells = cells; a->cell_capacity = cell_capacity; a->index = index; a->index_capacity = index_capacity;
    a->width = width; a->height = height; a->page = page; a->pages = (width / page) * (height / page);
    a->page_class = page_class; a->frame = 1;
    memset(cells, 0, cell_capacity * sizeof *cells);
    memset(index, 0, index_capacity * sizeof *index);
    for (uint32_t p = 0; p < a->pages; p++) page_class[p] = -1;
    return TR_OK;
}
uint64_t at_frame(const at_atlas *a) { return a->frame; }
uint64_t at_submit(at_atlas *a) { return a->frame++; }
tr_status at_retire(at_atlas *a, uint64_t frame) {
    if (!a || frame >= a->frame) return TR_INVALID;          /* only submitted frames retire */
    if (frame > a->retired) a->retired = frame;
    return TR_OK;
}

/* Cells of page p at class c are numbered page_base(p) + i; a page's cell slots are reserved at
 * the smallest class's count, so numbering never depends on class. */
static uint32_t per_page(const at_atlas *a, int c) { uint32_t n = a->page / cell_size(c); return n * n; }
static uint32_t page_base(const at_atlas *a, uint32_t p) { return p * per_page(a, 0); }
static at_rect rect_of(const at_atlas *a, uint32_t p, int c, uint32_t i, uint16_t w, uint16_t h) {
    uint32_t across = a->width / a->page, n = a->page / cell_size(c);
    at_rect r = {(uint16_t)((p % across) * a->page + (i % n) * cell_size(c)), (uint16_t)((p / across) * a->page + (i / n) * cell_size(c)), w, h};
    return r;
}

static void rebuild(at_atlas *a) {
    memset(a->index, 0, a->index_capacity * sizeof *a->index);
    size_t mask = a->index_capacity - 1;
    for (uint32_t k = 0; k < a->cell_capacity; k++) {
        if (!a->cells[k].live) continue;
        size_t i = mix(a->cells[k].key) & mask;
        while (a->index[i]) i = (i + 1) & mask;
        a->index[i] = k + 1;
    }
    a->tombstones = 0;
}
static size_t find(const at_atlas *a, uint64_t key, int *found) {
    size_t mask = a->index_capacity - 1, i = mix(key) & mask, tomb = SIZE_MAX;
    for (;;) {
        uint32_t e = a->index[i];
        if (!e) { *found = 0; return tomb != SIZE_MAX ? tomb : i; }
        if (e == TOMB) { if (tomb == SIZE_MAX) tomb = i; }
        else if (a->cells[e - 1].key == key) { *found = 1; return i; }
        i = (i + 1) & mask;
    }
}

tr_status at_use(at_atlas *a, uint64_t key, uint16_t w, uint16_t h, at_rect *rect) {
    if (!a || !rect || !w || !h) return TR_INVALID;
    int c = 0;
    while (c < AT_CLASSES && (w > cell_size(c) || h > cell_size(c))) c++;
    if (c == AT_CLASSES) return TR_INVALID;                  /* larger than the largest class */
    if (2 * (a->tombstones + 1) > a->index_capacity / 2) rebuild(a);
    a->clock++;
    int found;
    size_t s = find(a, key, &found);
    if (found) {
        at_cell *e = &a->cells[a->index[s] - 1];
        uint32_t p = e->page, i = (uint32_t)(e - a->cells) - page_base(a, p);
        e->last_use = a->clock; e->last_frame = a->frame; a->hits++;
        *rect = rect_of(a, p, a->page_class[p], i, w, h);
        return TR_OK;
    }
    a->misses++;
    /* A free cell of class c, else a new page, else the least recently used retired cell. */
    uint32_t best = UINT32_MAX, bp = 0, bi = 0; uint64_t oldest = UINT64_MAX;
    for (uint32_t p = 0; p < a->pages; p++) {
        if (a->page_class[p] != c) continue;
        for (uint32_t i = 0; i < per_page(a, c); i++) {
            at_cell *e = &a->cells[page_base(a, p) + i];
            if (!e->live) { best = page_base(a, p) + i; bp = p; bi = i; oldest = 0; goto place; }
            if (e->last_frame <= a->retired && e->last_use < oldest) { oldest = e->last_use; best = page_base(a, p) + i; bp = p; bi = i; }
        }
    }
    if (oldest != 0) {
        for (uint32_t p = 0; p < a->pages; p++) if (a->page_class[p] < 0) {
            a->page_class[p] = c; a->pages_used++;
            best = page_base(a, p); bp = p; bi = 0; oldest = 0;
            goto place;
        }
    }
    if (best == UINT32_MAX) {
        /* Reclaim a page of another class whose cells have all retired, least recently used. */
        uint32_t take = UINT32_MAX; uint64_t newest_of_best = UINT64_MAX;
        for (uint32_t p = 0; p < a->pages; p++) {
            int pc = a->page_class[p];
            if (pc < 0 || pc == c) continue;
            uint64_t newest = 0; int ok = 1;
            for (uint32_t i = 0; i < per_page(a, pc) && ok; i++) {
                const at_cell *e = &a->cells[page_base(a, p) + i];
                if (!e->live) continue;
                if (e->last_frame > a->retired) ok = 0; else if (e->last_use > newest) newest = e->last_use;
            }
            if (ok && newest < newest_of_best) { newest_of_best = newest; take = p; }
        }
        if (take == UINT32_MAX) { a->refusals++; return TR_CAPACITY; }
        for (uint32_t i = 0; i < per_page(a, a->page_class[take]); i++) {
            at_cell *e = &a->cells[page_base(a, take) + i];
            if (!e->live) continue;
            int f; size_t old = find(a, e->key, &f);
            if (f) { a->index[old] = TOMB; a->tombstones++; }
            e->live = 0; a->evictions++;
        }
        a->page_class[take] = c; a->reclaimed++;
        best = page_base(a, take); bp = take; bi = 0;
        s = find(a, key, &found);
    }
place:;
    at_cell *e = &a->cells[best];
    if (e->live) {                                            /* evict: unindex the old key */
        int f; size_t old = find(a, e->key, &f);
        if (f) { a->index[old] = TOMB; a->tombstones++; }
        a->evictions++;
        s = find(a, key, &found);                             /* the probe may have moved */
    }
    e->key = key; e->live = 1; e->page = bp; e->last_use = a->clock; e->last_frame = a->frame;
    if (a->index[s] == TOMB) a->tombstones--;
    a->index[s] = best + 1;
    *rect = rect_of(a, bp, c, bi, w, h);
    return TR_STALE;
}
