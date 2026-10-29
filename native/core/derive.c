#include "derive.h"
#include <string.h>

#define NONE UINT32_MAX
#define TOMB UINT32_MAX

static uint64_t mix(uint64_t x) {
    x ^= x >> 33; x *= 0xff51afd7ed558ccdULL; x ^= x >> 33; x *= 0xc4ceb9fe1a85ec53ULL; return x ^ (x >> 33);
}
static uint64_t hash(tr_id op, uint64_t key) { return mix(mix(((uint64_t)op.slot << 32) ^ op.generation) ^ key); }
static int same_id(tr_id a, tr_id b) { return a.slot == b.slot && a.generation == b.generation; }

tr_status dv_init(dv_graph *g, tr_world *w, dv_node *nodes, size_t node_capacity, dv_block *blocks, size_t block_capacity,
                  uint32_t *table, size_t table_capacity, dv_frame *path) {
    if (!g || !w || !nodes || !blocks || !table || !path || !node_capacity || node_capacity >= NONE || block_capacity >= NONE
        || table_capacity < 2 * node_capacity || (table_capacity & (table_capacity - 1))) return TR_INVALID;
    memset(g, 0, sizeof *g);
    memset(nodes, 0, node_capacity * sizeof *nodes);
    memset(table, 0, table_capacity * sizeof *table);
    for (size_t i = 0; i < block_capacity; i++) blocks[i].next = i + 1 < block_capacity ? (uint32_t)(i + 1) : NONE;
    g->world = w; g->nodes = nodes; g->node_capacity = node_capacity; g->blocks = blocks; g->block_capacity = block_capacity;
    g->free_block = block_capacity ? 0 : NONE; g->table = table; g->table_capacity = table_capacity; g->path = path;
    for (size_t i = 0; i < node_capacity; i++) nodes[i].head = NONE;
    return TR_OK;
}

/* ---- dependency lists: chains of fixed blocks from a shared free list ---- */

static void deps_free(dv_graph *g, dv_node *n) {
    uint32_t b = n->head;
    while (b != NONE) { uint32_t next = g->blocks[b].next; g->blocks[b].next = g->free_block; g->free_block = b; b = next; }
    n->head = NONE; n->count = 0;
}
static dv_dep *dep_at(const dv_graph *g, const dv_node *n, uint32_t i) {
    uint32_t b = n->head;
    for (uint32_t skip = i / DV_BLOCK; skip; skip--) b = g->blocks[b].next;
    return &g->blocks[b].deps[i % DV_BLOCK];
}
/* Appends to the chain starting at *head holding *count deps. */
static tr_status chain_append(dv_graph *g, uint32_t *head, uint32_t *count, dv_dep d) {
    uint32_t b = *head, last = NONE;
    while (b != NONE) { last = b; b = g->blocks[b].next; }
    if (last == NONE || g->blocks[last].count == DV_BLOCK) {
        if (g->free_block == NONE) return TR_CAPACITY;
        uint32_t fresh = g->free_block;
        g->free_block = g->blocks[fresh].next;
        g->blocks[fresh].next = NONE; g->blocks[fresh].count = 0;
        if (last == NONE) *head = fresh; else g->blocks[last].next = fresh;
        last = fresh;
    }
    g->blocks[last].deps[g->blocks[last].count++] = d;
    (*count)++;
    return TR_OK;
}
static void chain_free(dv_graph *g, uint32_t head) {
    dv_node tmp; tmp.head = head; tmp.count = 0;
    deps_free(g, &tmp);
}

/* ---- interning ---- */

static size_t slot_of(const dv_graph *g, tr_id op, uint64_t key, int *found) {
    size_t mask = g->table_capacity - 1, i = hash(op, key) & mask, tomb = SIZE_MAX;
    for (;;) {
        uint32_t e = g->table[i];
        if (!e) { *found = 0; return tomb != SIZE_MAX ? tomb : i; }
        if (e == TOMB) { if (tomb == SIZE_MAX) tomb = i; }
        else {
            const dv_node *n = &g->nodes[e - 1];
            if (same_id(n->op, op) && n->key == key) { *found = 1; return i; }
        }
        i = (i + 1) & mask;
    }
}

void dv_set_hooks(dv_graph *g, void (*retain)(void *, uint64_t), void (*release)(void *, uint64_t), void *ctx) {
    g->retain = retain; g->release = release; g->hooks = ctx;
}

tr_status dv_set_static(dv_graph *g, tr_id op) {
    if (!g || g->nstatic == sizeof g->static_ops / sizeof g->static_ops[0]) return TR_CAPACITY;
    g->static_ops[g->nstatic++] = op;
    return TR_OK;
}
static int is_static(const dv_graph *g, tr_id op) {
    for (size_t i = 0; i < g->nstatic; i++) if (same_id(g->static_ops[i], op)) return 1;
    return 0;
}

static void rebuild(dv_graph *g) {
    memset(g->table, 0, g->table_capacity * sizeof *g->table);
    size_t mask = g->table_capacity - 1;
    for (uint32_t k = 0; k < g->node_capacity; k++) {
        if (!g->nodes[k].live) continue;
        size_t i = hash(g->nodes[k].op, g->nodes[k].key) & mask;
        while (g->table[i]) i = (i + 1) & mask;
        g->table[i] = k + 1;
    }
    g->tombstones = 0;
}

tr_status dv_intern(dv_graph *g, tr_id op, uint64_t key, uint32_t *out) {
    if (!g || !out) return TR_INVALID;
    if ((g->interned + g->tombstones + 1) * 4 > g->table_capacity * 3) rebuild(g);
    int found;
    size_t s = slot_of(g, op, key, &found);
    if (found) { *out = g->table[s] - 1; return TR_OK; }
    uint32_t free = NONE;
    for (uint32_t i = 0; i < g->node_capacity; i++)
        if (!g->nodes[i].live && g->nodes[i].generation != UINT64_MAX) { free = i; break; }
    if (free == NONE) return TR_CAPACITY;
    dv_node *n = &g->nodes[free];
    uint64_t generation = n->generation + 1;
    memset(n, 0, sizeof *n);
    n->op = op; n->key = key; n->generation = generation; n->live = 1; n->head = NONE;
    if (g->table[s] == TOMB) g->tombstones--;
    g->table[s] = free + 1;
    g->interned++;
    *out = free;
    return TR_OK;
}

static dv_node *live(const dv_graph *g, uint32_t i) {
    return g && i < g->node_capacity && g->nodes[i].live ? &g->nodes[i] : NULL;
}
tr_status dv_demand_at(dv_graph *g, uint32_t i, uint32_t level) {
    dv_node *n = live(g, i);
    if (!n || level >= DV_LEVELS || n->demand == UINT32_MAX) return TR_INVALID;
    n->demand++; n->demand_at[level]++;
    return TR_OK;
}
tr_status dv_release_at(dv_graph *g, uint32_t i, uint32_t level) {
    dv_node *n = live(g, i);
    if (!n || level >= DV_LEVELS || !n->demand_at[level]) return TR_INVALID;
    n->demand--; n->demand_at[level]--;
    return TR_OK;
}
tr_status dv_demand(dv_graph *g, uint32_t i) { return dv_demand_at(g, i, DV_VISIBLE); }
tr_status dv_release(dv_graph *g, uint32_t i) { return dv_release_at(g, i, DV_VISIBLE); }

/* ---- verification ---- */

static int clean(const dv_node *n, uint64_t r) { return n->memo && !n->recompute && n->verified_at == r; }
static dv_node *dep_node(const dv_graph *g, const dv_dep *d) {
    dv_node *n = live(g, d->node);
    return n && n->generation == d->generation ? n : NULL;
}

/* Recompute from here: keep, as provided values, the deps that are free to supply now (cells,
 * and nodes already verified at r); the operator asks for anything else. */
static void begin_recompute(dv_graph *g, dv_node *n, uint64_t r) {
    n->recompute = 1;
    uint32_t keep = 0;
    int all = is_static(g, n->op);                    /* static: every live dependency, verified by the walk */
    for (uint32_t i = 0; i < n->count; i++) {
        dv_dep d = *dep_at(g, n, i);
        dv_node *m = d.kind == DV_NODE ? dep_node(g, &d) : NULL;
        if (d.kind == DV_CELL || (m && (all || clean(m, r)))) *dep_at(g, n, keep++) = d;
    }
    /* Truncate to keep: free whole blocks past it. */
    if (!keep) { deps_free(g, n); return; }
    uint32_t b = n->head;
    for (uint32_t skip = (keep - 1) / DV_BLOCK; skip; skip--) b = g->blocks[b].next;
    g->blocks[b].count = (keep - 1) % DV_BLOCK + 1;
    chain_free(g, g->blocks[b].next);
    g->blocks[b].next = NONE;
    n->count = keep;
}

/* Whether a cell differs from how it was seen at revision v: present then and changed since,
 * or removed since (a removed id never comes back). */
static int cell_changed(const tr_world *w, tr_id id, int was_absent, uint64_t v) {
    tr_read r;
    if (tr_current(w, id, &r) != TR_OK) return !was_absent;
    return was_absent || r.revision > v;
}
static int op_changed(const dv_graph *g, const dv_node *n) { return cell_changed(g->world, n->op, n->op_absent, n->verified_at); }

static tr_status emit(dv_graph *g, uint32_t i, dv_job *job, uint64_t r) {
    dv_node *n = &g->nodes[i];
    if (n->count > job->capacity || !job->values) return TR_CAPACITY;
    tr_read op;
    job->node = i; job->generation = n->generation; job->ticket = ++g->next_ticket; job->revision = r;
    job->op_present = tr_current(g->world, n->op, &op) == TR_OK;
    job->op = n->op; job->op_value = job->op_present ? op.value : 0; job->key = n->key;
    job->count = n->count;
    for (uint32_t k = 0; k < n->count; k++) {
        const dv_dep *d = dep_at(g, n, k);
        dv_value *v = &job->values[k];
        memset(v, 0, sizeof *v);
        v->ref.kind = d->kind;
        if (d->kind == DV_CELL) {
            tr_read c;
            v->ref.id = d->cell;
            if (tr_current(g->world, d->cell, &c) == TR_OK) v->value = c.value; else v->presence = DV_ABSENT;
        } else {
            dv_node *m = &g->nodes[d->node];      /* provided deps are live: checked by the walk */
            v->ref.id = m->op; v->ref.key = m->key;
            if (m->on_path) v->presence = DV_CYCLE; else v->value = m->value;
        }
    }
    n->running = 1; n->ticket = job->ticket;
    g->computations++;
    return TR_OK;
}

enum { WALK_DONE, WALK_ERROR };
typedef struct { uint32_t level; size_t capacity, *count, used[DV_LEVELS]; const size_t *limits; } admit;

/* Iterative walk from root. Each frame re-examines its dependency at cursor after a child
 * returns: a child is then either clean at r or marked blocked for this sweep. */
static int walk(dv_graph *g, uint32_t root, dv_job *jobs, admit *a, int *blocked, tr_status *error) {
    uint64_t r = g->world->publication, sweep = g->sweep;
    size_t depth = 0;
    int result = WALK_DONE;
#define ENTER(idx) do { \
        dv_node *e_ = &g->nodes[idx]; \
        if (e_->sweep != sweep) { e_->sweep = sweep; e_->level = (uint8_t)a->level; } \
        if (clean(e_, r)) break; \
        if (e_->running || e_->blocked_sweep == sweep) { *blocked = 1; e_->blocked_sweep = sweep; break; } \
        if (!e_->recompute && (!e_->memo || e_->volatile_ || op_changed(g, e_))) begin_recompute(g, e_, r); \
        e_->on_path = 1; g->path[depth].node = (idx); g->path[depth].cursor = 0; depth++; \
    } while (0)
#define POP_BLOCKED() do { dv_node *p_ = &g->nodes[g->path[depth - 1].node]; p_->on_path = 0; \
        p_->blocked_sweep = sweep; *blocked = 1; depth--; } while (0)
    ENTER(root);
    while (depth) {
        dv_frame *f = &g->path[depth - 1];
        dv_node *n = &g->nodes[f->node];
        if (f->cursor == n->count) {
            if (n->recompute) {
                if (*a->count < a->capacity && (!a->limits || a->used[a->level] < a->limits[a->level])) {
                    tr_status s = emit(g, f->node, &jobs[*a->count], r);
                    if (s) { *error = s; result = WALK_ERROR; break; }
                    jobs[*a->count].level = a->level;
                    (*a->count)++; a->used[a->level]++;
                }                                         /* refused: stays to recompute */
                POP_BLOCKED();
            } else {
                n->verified_at = r; n->on_path = 0; depth--;
            }
            continue;
        }
        dv_dep *d = dep_at(g, n, f->cursor);
        if (d->kind == DV_CELL) {
            if (!n->recompute && cell_changed(g->world, d->cell, (int)d->absent, n->verified_at)) {
                begin_recompute(g, n, r); f->cursor = 0;
            } else f->cursor++;
            continue;
        }
        dv_node *m = dep_node(g, d);
        if (!m) {                                         /* evicted: only a memo can hold it */
            begin_recompute(g, n, r); f->cursor = 0; continue;
        }
        if (m->on_path) {                                 /* a cycle through n */
            if (!n->recompute) { begin_recompute(g, n, r); f->cursor = 0; } else f->cursor++;
            continue;
        }
        if (clean(m, r)) {
            if (!n->recompute && m->changed_at > n->verified_at) { begin_recompute(g, n, r); f->cursor = 0; }
            else f->cursor++;
            continue;
        }
        if (m->sweep == sweep && (m->running || m->blocked_sweep == sweep)) { POP_BLOCKED(); continue; }
        if (depth == g->node_capacity) { *error = TR_CAPACITY; result = WALK_ERROR; break; }
        ENTER(d->node);
    }
    while (depth) g->nodes[g->path[--depth].node].on_path = 0;
#undef ENTER
#undef POP_BLOCKED
    return result;
}

/* Roots are walked level by level, and all of them even once no job can be issued: a root
 * left unwalked would make its running jobs look unwanted, and they would be cancelled. */
tr_status dv_poll_levels(dv_graph *g, dv_job *jobs, size_t capacity, const size_t limits[DV_LEVELS],
                         size_t *count, dv_progress *progress) {
    if (!g || !count || !progress || (capacity && !jobs)) return TR_INVALID;
    *count = 0; *progress = DV_IDLE;
    g->sweep++;
    int blocked = 0;
    tr_status error = TR_OK;
    admit a = {0, capacity, count, {0}, limits};
    for (a.level = 0; a.level < DV_LEVELS; a.level++)
        for (uint32_t i = 0; i < g->node_capacity; i++) {
            if (!g->nodes[i].live || !g->nodes[i].demand_at[a.level]) continue;
            if (walk(g, i, jobs, &a, &blocked, &error) == WALK_ERROR) return error;
        }
    *progress = *count ? DV_READY : blocked ? DV_WAITING : DV_IDLE;
    return TR_OK;
}
tr_status dv_poll(dv_graph *g, dv_job *jobs, size_t capacity, size_t *count, dv_progress *progress) {
    return dv_poll_levels(g, jobs, capacity, NULL, count, progress);
}

/* ---- completion ---- */

static dv_node *running(dv_graph *g, const dv_job *job) {
    dv_node *n = live(g, job->node);
    return n && n->generation == job->generation && n->running && n->ticket == job->ticket ? n : NULL;
}
static int same_ref(const dv_ref *a, const dv_ref *b) {
    return a->kind == b->kind && same_id(a->id, b->id) && (a->kind != DV_NODE || a->key == b->key);
}
static long find(const dv_job *job, const dv_ref *ref) {
    for (size_t k = 0; k < job->count; k++) if (same_ref(&job->values[k].ref, ref)) return (long)k;
    return -1;
}

tr_status dv_complete(dv_graph *g, const dv_job *job, const dv_result *res) {
    if (!g || !job || !res || (res->count && !res->refs)) return TR_INVALID;
    dv_node *n = running(g, job);
    if (!n) return TR_STALE;
    n->running = 0; n->ticket = 0;
    /* The node's dependency list is still exactly the job's values, in order. */
    if (res->kind == DV_RESULT_NEED) {
        if (!res->count) return TR_INVALID;
        for (size_t k = 0; k < res->count; k++) {
            const dv_ref *ref = &res->refs[k];
            if (find(job, ref) >= 0) return TR_INVALID;
            dv_dep d; memset(&d, 0, sizeof d);
            d.kind = ref->kind;
            if (ref->kind == DV_CELL) d.cell = ref->id;
            else if (ref->kind == DV_NODE) {
                tr_status s = dv_intern(g, ref->id, ref->key, &d.node);
                if (s) return s;
                n = &g->nodes[job->node];
                d.generation = g->nodes[d.node].generation;
            } else return TR_INVALID;
            int dup = 0;                                  /* the same need twice in one result */
            for (uint32_t i = (uint32_t)job->count; i < n->count; i++) {
                dv_dep *e = dep_at(g, n, i);
                if (e->kind == d.kind && (d.kind == DV_CELL ? same_id(e->cell, d.cell) : e->node == d.node)) dup = 1;
            }
            if (dup) continue;
            tr_status s = chain_append(g, &n->head, &n->count, d);
            if (s) return s;
        }
        return TR_OK;
    }
    if (res->kind != DV_RESULT_VALUE) return TR_INVALID;
    uint32_t head = NONE, count = 0;
    int cyclic = 0;
    for (size_t k = 0; k < res->count; k++) {
        long at = find(job, &res->refs[k]);
        if (at < 0) { chain_free(g, head); return TR_INVALID; }
        if (job->values[at].presence == DV_CYCLE) { cyclic = 1; continue; }
        dv_dep d = *dep_at(g, n, (uint32_t)at);
        d.absent = job->values[at].presence == DV_ABSENT;
        int dup = 0;
        dv_node tmp; tmp.head = head;
        for (uint32_t i = 0; i < count; i++) {
            dv_dep *e = dep_at(g, &tmp, i);
            if (e->kind == d.kind && (d.kind == DV_CELL ? same_id(e->cell, d.cell) : e->node == d.node)) dup = 1;
        }
        if (dup) continue;
        tr_status s = chain_append(g, &head, &count, d);
        if (s) { chain_free(g, head); return s; }
    }
    deps_free(g, n);
    n->head = head; n->count = count;
    if (!n->memo || n->value != res->value) n->changed_at = job->revision;
    if (g->retain) g->retain(g->hooks, res->value);
    if (n->memo && g->release) g->release(g->hooks, n->value);
    n->value = res->value; n->memo = 1; n->verified_at = job->revision; n->recompute = 0; n->volatile_ = (uint8_t)cyclic;
    n->op_absent = (uint8_t)!job->op_present;
    return TR_OK;
}

tr_status dv_cancel(dv_graph *g, const dv_job *job) {
    if (!g || !job) return TR_INVALID;
    dv_node *n = running(g, job);
    if (!n) return TR_STALE;
    n->running = 0; n->ticket = 0;
    return TR_OK;
}

int dv_wanted(const dv_graph *g, const dv_job *job) {
    const dv_node *n = live(g, job->node);
    return n && n->generation == job->generation && n->sweep == g->sweep;
}
uint32_t dv_job_level(const dv_graph *g, const dv_job *job) {
    return dv_wanted(g, job) ? g->nodes[job->node].level : DV_LEVELS;
}

tr_status dv_get(const dv_graph *g, uint32_t i, uint64_t *value) {
    const dv_node *n = live(g, i);
    if (!n || !value) return TR_INVALID;
    if (!clean(n, g->world->publication)) return TR_STALE;
    *value = n->value;
    return TR_OK;
}

tr_status dv_evict(dv_graph *g, uint32_t i) {
    dv_node *n = live(g, i);
    if (!n || n->demand || n->running) return TR_INVALID;
    int found;
    size_t s = slot_of(g, n->op, n->key, &found);
    if (found) { g->table[s] = TOMB; g->tombstones++; }
    g->interned--;
    deps_free(g, n);
    if (n->memo && g->release) g->release(g->hooks, n->value);
    uint64_t generation = n->generation;
    memset(n, 0, sizeof *n);
    n->generation = generation; n->head = NONE;          /* the next intern bumps it */
    return TR_OK;
}
