#ifndef TROUGH_ATLAS_H
#define TROUGH_ATLAS_H
#include "world.h"

/* Texture-atlas residency with fence lifetimes (docs/ATLAS.md). Owner-serialised; no GPU calls:
 * the caller uploads into the rectangles this hands out and tells it when frames retire.
 *
 * Space: the atlas is cut into square pages, each given on demand to one size class whose
 * cells are 16 << c pixels square, so a freed cell is reused by any entry of its class with no
 * fragmentation. Lifetime: frames are numbered in submission order on one queue, so they retire
 * in order; a cell may be overwritten only when the last frame that used it has retired
 * (completed watermark). Eviction is least-recently-used among such cells; when a class has no
 * free, retired or unassigned space, a page of another class whose cells have all retired is
 * reclaimed, so no class starves because pages went to another one first. */
#define AT_CLASSES 5
typedef struct { uint16_t x, y, w, h; } at_rect;
typedef struct {
    uint64_t key, last_use, last_frame;   /* last_frame: newest frame that samples it (0 = none) */
    uint32_t page; uint8_t live;
} at_cell;
typedef struct {
    at_cell *cells; size_t cell_capacity;
    uint32_t *index; size_t index_capacity;       /* key -> cell + 1; UINT32_MAX tombstone */
    uint32_t width, height, page, pages, pages_used;
    int32_t *page_class;                          /* -1: unassigned */
    uint64_t clock, frame, retired, tombstones, hits, misses, evictions, refusals, reclaimed;
} at_atlas;

/* width and height are multiples of page; page is a multiple of 16 << (AT_CLASSES - 1). cells must
 * hold every cell of every page at the smallest class: (width/16) * (height/16). index_capacity:
 * power of two >= 2 * cell_capacity. page_class holds (width/page) * (height/page). */
tr_status at_init(at_atlas *, uint32_t width, uint32_t height, uint32_t page, at_cell *cells, size_t cell_capacity,
                  uint32_t *index, size_t index_capacity, int32_t *page_class);
/* The frame being recorded (1, 2, ...). at_submit closes it and opens the next. */
uint64_t at_frame(const at_atlas *);
uint64_t at_submit(at_atlas *);
/* Every frame <= frame has completed on the GPU (fences signal in submission order). */
tr_status at_retire(at_atlas *, uint64_t frame);
/* Uses key in the current frame. TR_OK: resident at *rect (no upload needed). TR_STALE: newly
 * placed at *rect, upload w x h bytes there before submitting. TR_CAPACITY: no cell of the class
 * is free or evictable now (all are used by frames still in flight or by this frame). */
tr_status at_use(at_atlas *, uint64_t key, uint16_t w, uint16_t h, at_rect *rect);
#endif
