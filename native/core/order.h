#ifndef TROUGH_ORDER_H
#define TROUGH_ORDER_H
#include "world.h"

/* Paragraph order (docs/ORDER.md): a B+tree of paragraph ids. Each entry carries its subtree's
 * paragraph count and UTF-16 unit total, so position -> id, document offset -> (paragraph,
 * offset), insertion and removal are O(log n). Owner-serialised; storage is the caller's.
 *
 * Nodes live in numbered slots; slot 0 is always the root. A mutation marks each slot whose
 * published form (or_encode: kind, ids or child slots, paragraph counts; not unit totals)
 * changed, so the caller republishes exactly those, O(log n) per operation. Operators read the
 * published nodes and find a position with or_step, without the owner's tree. */
#define OR_FAN 16                                   /* entries per node; at least OR_FAN / 2 except the root */
typedef struct {
    uint32_t n, leaf;
    uint32_t child[OR_FAN + 1];                     /* leaf: paragraph id; internal: node slot */
    uint64_t count[OR_FAN + 1], units[OR_FAN + 1];  /* leaf: 1 and the paragraph's units */
} or_node;
typedef struct {
    or_node *nodes; uint32_t capacity;
    uint32_t *free; uint32_t nfree;                 /* unused slots */
    uint32_t *changed; uint32_t nchanged;           /* slots to republish, each once */
    uint8_t *marked;                                /* per slot: in changed[] */
    uint8_t *used;                                  /* per slot: holds a node */
} or_tree;

/* nodes, free, changed, marked and used each hold capacity entries. A tree of n paragraphs
 * needs at most 2 * n / (OR_FAN / 2) + 8 slots. Starts empty, root marked. */
tr_status or_init(or_tree *, or_node *nodes, uint32_t capacity, uint32_t *free, uint32_t *changed, uint8_t *marked, uint8_t *used);
uint64_t or_count(const or_tree *);
uint64_t or_units(const or_tree *);                 /* the joined text: units + count - 1 separators */
/* Insert id (with its units) so it becomes position pos (0..count). TR_CAPACITY: no slot. */
tr_status or_insert(or_tree *, uint64_t pos, uint32_t id, uint64_t units);
tr_status or_remove(or_tree *, uint64_t pos, uint32_t *id);
/* The paragraph at pos changed length; unit totals only, nothing to republish. */
tr_status or_set_units(or_tree *, uint64_t pos, uint64_t units);
tr_status or_at(const or_tree *, uint64_t pos, uint32_t *id, uint64_t *units);
/* Offset of paragraph pos's first unit in the joined text (paragraphs separated by one unit). */
tr_status or_offset(const or_tree *, uint64_t pos, uint64_t *offset);
/* The paragraph holding joined offset g and the offset within it, clamped to the document; a
 * separator belongs to the end of the paragraph before it. */
tr_status or_locate(const or_tree *, uint64_t g, uint64_t *pos, uint64_t *off);
/* Published changes: copies up to max slots and clears them. */
uint32_t or_take_changed(or_tree *, uint32_t *slots, uint32_t max);

/* Published form of a node: u32 leaf << 31 | n, then n entries of (u32 child, u32 count),
 * big-endian; 8 * n + 4 bytes. Slots not in use encode nothing (returns 0). */
#define OR_ENCODED_MAX (4 + 8 * OR_FAN)
size_t or_encode(const or_tree *, uint32_t slot, unsigned char *out);
/* One step of a position lookup over published nodes: in node bytes, the entry holding pos.
 * Leaf: *child is the id and *leaf = 1. Internal: *child is the slot to read next, and *pos
 * becomes the position within it. TR_INVALID: malformed bytes or pos past the node. */
tr_status or_step(const unsigned char *bytes, size_t n, uint64_t *pos, uint32_t *child, int *leaf);
#endif
