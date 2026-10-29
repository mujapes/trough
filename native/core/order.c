#include "order.h"
#include <string.h>

/* docs/ORDER.md. Recursive descent from slot 0; every node on the path is fixed up on the way
 * back (counts, units, splits, underflow), so the tree is valid between calls. */

#define MIN (OR_FAN / 2)

static void mark(or_tree *t, uint32_t s) {
    if (!t->marked[s]) { t->marked[s] = 1; t->changed[t->nchanged++] = s; }
}
static uint64_t sum_count(const or_node *x) { uint64_t c = 0; for (uint32_t k = 0; k < x->n; k++) c += x->count[k]; return c; }
static uint64_t sum_units(const or_node *x) { uint64_t u = 0; for (uint32_t k = 0; k < x->n; k++) u += x->units[k]; return u; }
static int alloc(or_tree *t, uint32_t *s) {
    if (!t->nfree) return 0;
    *s = t->free[--t->nfree];
    t->used[*s] = 1;
    memset(&t->nodes[*s], 0, sizeof t->nodes[*s]);
    mark(t, *s);
    return 1;
}
static void release(or_tree *t, uint32_t s) {
    t->used[s] = 0;
    t->free[t->nfree++] = s;
    mark(t, s);                                     /* republished as nothing */
}
static void entry_insert(or_node *x, uint32_t k, uint32_t child, uint64_t count, uint64_t units) {
    memmove(&x->child[k + 1], &x->child[k], (x->n - k) * sizeof x->child[0]);
    memmove(&x->count[k + 1], &x->count[k], (x->n - k) * sizeof x->count[0]);
    memmove(&x->units[k + 1], &x->units[k], (x->n - k) * sizeof x->units[0]);
    x->child[k] = child; x->count[k] = count; x->units[k] = units; x->n++;
}
static void entry_remove(or_node *x, uint32_t k) {
    memmove(&x->child[k], &x->child[k + 1], (x->n - k - 1) * sizeof x->child[0]);
    memmove(&x->count[k], &x->count[k + 1], (x->n - k - 1) * sizeof x->count[0]);
    memmove(&x->units[k], &x->units[k + 1], (x->n - k - 1) * sizeof x->units[0]);
    x->n--;
}
/* Moves entries [from, n) of a to the end of b. */
static void move_tail(or_node *a, uint32_t from, or_node *b) {
    for (uint32_t k = from; k < a->n; k++) { b->child[b->n] = a->child[k]; b->count[b->n] = a->count[k]; b->units[b->n] = a->units[k]; b->n++; }
    a->n = from;
}

tr_status or_init(or_tree *t, or_node *nodes, uint32_t capacity, uint32_t *free, uint32_t *changed, uint8_t *marked, uint8_t *used) {
    if (!t || !nodes || capacity < 1 || !free || !changed || !marked || !used) return TR_INVALID;
    *t = (or_tree){nodes, capacity, free, 0, changed, 0, marked, used};
    memset(marked, 0, capacity); memset(used, 0, capacity);
    for (uint32_t s = capacity; s > 1; s--) free[t->nfree++] = s - 1;
    used[0] = 1;
    memset(&nodes[0], 0, sizeof nodes[0]);
    nodes[0].leaf = 1;
    mark(t, 0);
    return TR_OK;
}
uint64_t or_count(const or_tree *t) { return sum_count(&t->nodes[0]); }
uint64_t or_units(const or_tree *t) { uint64_t n = or_count(t); return sum_units(&t->nodes[0]) + (n ? n - 1 : 0); }

/* The entry of internal x holding position *pos, with *pos made relative to it. A lookup
 * (pos < count) takes the entry whose range contains pos; an insertion (pos <= count) takes
 * the earliest entry pos can end, so a position at a boundary appends to the left child. */
static uint32_t find(const or_node *x, uint64_t *pos, int inserting) {
    uint32_t k = 0;
    while (k + 1 < x->n && (inserting ? *pos > x->count[k] : *pos >= x->count[k])) { *pos -= x->count[k]; k++; }
    return k;
}

/* Splits slot s (n == OR_FAN + 1) into s and a new right sibling, *right. */
static int split(or_tree *t, uint32_t s, uint32_t *right) {
    if (!alloc(t, right)) return 0;
    or_node *a = &t->nodes[s], *b = &t->nodes[*right];
    b->leaf = a->leaf;
    move_tail(a, a->n / 2, b);
    mark(t, s);
    return 1;
}

static tr_status insert_at(or_tree *t, uint32_t s, uint64_t pos, uint32_t id, uint64_t units) {
    or_node *x = &t->nodes[s];
    if (x->leaf) { entry_insert(x, (uint32_t)pos, id, 1, units); mark(t, s); return TR_OK; }
    uint32_t k = find(x, &pos, 1);
    uint32_t c = x->child[k];
    tr_status st = insert_at(t, c, pos, id, units);
    if (st) return st;
    x = &t->nodes[s];
    if (t->nodes[c].n > OR_FAN) {
        uint32_t r;
        if (!split(t, c, &r)) return TR_CAPACITY;
        x = &t->nodes[s];
        entry_insert(x, k + 1, r, sum_count(&t->nodes[r]), sum_units(&t->nodes[r]));
    }
    x->count[k] = sum_count(&t->nodes[c]); x->units[k] = sum_units(&t->nodes[c]);
    mark(t, s);
    return TR_OK;
}

tr_status or_insert(or_tree *t, uint64_t pos, uint32_t id, uint64_t units) {
    if (pos > or_count(t)) return TR_INVALID;
    /* Room for one split per level plus a new root: refuse up front so a failure changes nothing. */
    uint32_t depth = 1;
    for (const or_node *x = &t->nodes[0]; !x->leaf; x = &t->nodes[x->child[0]]) depth++;
    if (t->nfree < depth + 2) return TR_CAPACITY;
    tr_status st = insert_at(t, 0, pos, id, units);
    if (st) return st;
    or_node *root = &t->nodes[0];
    if (root->n > OR_FAN) {                          /* the root splits: its halves move down */
        uint32_t a, b;
        if (!alloc(t, &a) || !alloc(t, &b)) return TR_CAPACITY;
        root = &t->nodes[0];
        t->nodes[a] = *root;
        t->nodes[b].leaf = root->leaf;
        move_tail(&t->nodes[a], t->nodes[a].n / 2, &t->nodes[b]);
        root->n = 0; root->leaf = 0;
        entry_insert(root, 0, a, sum_count(&t->nodes[a]), sum_units(&t->nodes[a]));
        entry_insert(root, 1, b, sum_count(&t->nodes[b]), sum_units(&t->nodes[b]));
        mark(t, 0);
    }
    return TR_OK;
}

/* Child k of internal x underflowed: borrow from a sibling with spare entries, else merge. */
static void rebalance(or_tree *t, uint32_t s, uint32_t k) {
    or_node *x = &t->nodes[s];
    uint32_t c = x->child[k];
    or_node *a = &t->nodes[c];
    if (a->n >= MIN) return;
    if (k > 0 && t->nodes[x->child[k - 1]].n > MIN) {           /* the left sibling's last entry */
        uint32_t l = x->child[k - 1]; or_node *b = &t->nodes[l];
        entry_insert(a, 0, b->child[b->n - 1], b->count[b->n - 1], b->units[b->n - 1]);
        b->n--;
        x->count[k - 1] = sum_count(b); x->units[k - 1] = sum_units(b);
        mark(t, l);
    } else if (k + 1 < x->n && t->nodes[x->child[k + 1]].n > MIN) {   /* the right sibling's first */
        uint32_t r = x->child[k + 1]; or_node *b = &t->nodes[r];
        entry_insert(a, a->n, b->child[0], b->count[0], b->units[0]);
        entry_remove(b, 0);
        x->count[k + 1] = sum_count(b); x->units[k + 1] = sum_units(b);
        mark(t, r);
    } else if (x->n > 1) {                                       /* merge into the left one of a pair */
        uint32_t left = k > 0 ? k - 1 : k, ls = x->child[left], rs = x->child[left + 1];
        move_tail(&t->nodes[rs], 0, &t->nodes[ls]);
        entry_remove(x, left + 1);
        release(t, rs);
        x->count[left] = sum_count(&t->nodes[ls]); x->units[left] = sum_units(&t->nodes[ls]);
        mark(t, ls);
        mark(t, s);
        return;
    }
    x->count[k] = sum_count(a); x->units[k] = sum_units(a);
    mark(t, c);
    mark(t, s);
}

static void remove_at(or_tree *t, uint32_t s, uint64_t pos, uint32_t *id) {
    or_node *x = &t->nodes[s];
    if (x->leaf) { *id = x->child[pos]; entry_remove(x, (uint32_t)pos); mark(t, s); return; }
    uint32_t k = find(x, &pos, 0);
    uint32_t c = x->child[k];
    remove_at(t, c, pos, id);
    x = &t->nodes[s];
    x->count[k] = sum_count(&t->nodes[c]); x->units[k] = sum_units(&t->nodes[c]);
    mark(t, s);
    rebalance(t, s, k);
}

tr_status or_remove(or_tree *t, uint64_t pos, uint32_t *id) {
    if (pos >= or_count(t)) return TR_INVALID;
    uint32_t got;
    remove_at(t, 0, pos, &got);
    or_node *root = &t->nodes[0];
    if (!root->leaf && root->n == 1) {               /* one child left: it becomes the root */
        uint32_t c = root->child[0];
        *root = t->nodes[c];
        release(t, c);
        mark(t, 0);
    }
    if (id) *id = got;
    return TR_OK;
}

tr_status or_set_units(or_tree *t, uint64_t pos, uint64_t units) {
    if (pos >= or_count(t)) return TR_INVALID;
    uint32_t s = 0;
    uint32_t path[64]; uint32_t at[64]; int depth = 0;
    for (;;) {
        or_node *x = &t->nodes[s];
        if (x->leaf) { x->units[pos] = units; break; }
        uint32_t k = find(x, &pos, 0);
        path[depth] = s; at[depth] = k; depth++;
        s = x->child[k];
    }
    for (int i = depth - 1; i >= 0; i--) { or_node *x = &t->nodes[path[i]]; x->units[at[i]] = sum_units(&t->nodes[x->child[at[i]]]); }
    return TR_OK;
}

tr_status or_at(const or_tree *t, uint64_t pos, uint32_t *id, uint64_t *units) {
    if (pos >= or_count(t)) return TR_INVALID;
    const or_node *x = &t->nodes[0];
    while (!x->leaf) { uint32_t k = find(x, &pos, 0); x = &t->nodes[x->child[k]]; }
    if (id) *id = x->child[pos];
    if (units) *units = x->units[pos];
    return TR_OK;
}

tr_status or_offset(const or_tree *t, uint64_t pos, uint64_t *offset) {
    if (pos > or_count(t)) return TR_INVALID;
    uint64_t g = 0, p = pos;
    const or_node *x = &t->nodes[0];
    while (!x->leaf) {
        uint32_t k = 0;
        while (k + 1 < x->n && p >= x->count[k]) { g += x->units[k] + x->count[k]; p -= x->count[k]; k++; }
        x = &t->nodes[x->child[k]];
    }
    for (uint32_t k = 0; k < p && k < x->n; k++) g += x->units[k] + 1;
    *offset = g;
    return TR_OK;
}

tr_status or_locate(const or_tree *t, uint64_t g, uint64_t *pos, uint64_t *off) {
    uint64_t n = or_count(t);
    if (!n) return TR_INVALID;
    uint64_t p = 0;
    const or_node *x = &t->nodes[0];
    while (!x->leaf) {                               /* a subtree spans units + count (its separators) */
        uint32_t k = 0;
        while (k + 1 < x->n && g >= x->units[k] + x->count[k]) { g -= x->units[k] + x->count[k]; p += x->count[k]; k++; }
        x = &t->nodes[x->child[k]];
    }
    uint32_t k = 0;
    while (k + 1 < x->n && g > x->units[k]) { g -= x->units[k] + 1; k++; }
    *pos = p + k;
    *off = g < x->units[k] ? g : x->units[k];
    return TR_OK;
}

uint32_t or_take_changed(or_tree *t, uint32_t *slots, uint32_t max) {
    uint32_t n = t->nchanged < max ? t->nchanged : max;
    for (uint32_t k = 0; k < n; k++) { slots[k] = t->changed[k]; t->marked[slots[k]] = 0; }
    memmove(t->changed, t->changed + n, (t->nchanged - n) * sizeof *t->changed);
    t->nchanged -= n;
    return n;
}

static void put_be32(unsigned char *p, uint32_t v) { p[0] = (unsigned char)(v >> 24); p[1] = (unsigned char)(v >> 16); p[2] = (unsigned char)(v >> 8); p[3] = (unsigned char)v; }
static uint32_t get_be32(const unsigned char *p) { return (uint32_t)p[0] << 24 | (uint32_t)p[1] << 16 | (uint32_t)p[2] << 8 | p[3]; }

size_t or_encode(const or_tree *t, uint32_t slot, unsigned char *out) {
    if (slot >= t->capacity || !t->used[slot]) return 0;
    const or_node *x = &t->nodes[slot];
    put_be32(out, (x->leaf ? 1u << 31 : 0) | x->n);
    for (uint32_t k = 0; k < x->n; k++) { put_be32(out + 4 + 8 * k, x->child[k]); put_be32(out + 8 + 8 * k, (uint32_t)x->count[k]); }
    return 4 + 8 * (size_t)x->n;
}

tr_status or_step(const unsigned char *b, size_t n, uint64_t *pos, uint32_t *child, int *leaf) {
    if (!b || n < 4) return TR_INVALID;
    uint32_t h = get_be32(b), count = h & 0x7fffffffu;
    if (count > OR_FAN || n < 4 + 8 * (size_t)count) return TR_INVALID;
    *leaf = (int)(h >> 31);
    for (uint32_t k = 0; k < count; k++) {
        uint32_t c = get_be32(b + 8 + 8 * k);
        if (*pos < c) { *child = get_be32(b + 4 + 8 * k); return TR_OK; }
        *pos -= c;
    }
    return TR_INVALID;
}
