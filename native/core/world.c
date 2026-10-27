#include "world.h"
#include <string.h>

static int same_id(tr_id a, tr_id b) {
    return a.slot == b.slot && a.generation == b.generation;
}
static tr_status cell(const tr_world *w, tr_id id, const tr_cell **out) {
    if (!w || !w->cells || id.slot >= w->capacity || !id.generation) return TR_INVALID;
    const tr_cell *c = &w->cells[id.slot];
    if (!c->live || c->generation != id.generation) return TR_STALE;
    *out = c;
    return TR_OK;
}
tr_status tr_world_init(tr_world *w, tr_cell *storage, size_t capacity) {
    if (!w || !storage || !capacity || capacity > UINT32_MAX || capacity > SIZE_MAX / sizeof(*storage))
        return TR_INVALID;
    memset(storage, 0, capacity * sizeof(*storage));
    w->cells = storage; w->capacity = capacity; w->publication = 0;
    for (size_t i = 0; i < capacity; i++) storage[i].generation = 1;
    return TR_OK;
}
tr_status tr_create(tr_world *w, uint64_t value, tr_id *out) {
    if (!w || !w->cells || !out) return TR_INVALID;
    if (w->publication == UINT64_MAX) return TR_EXHAUSTED;
    for (size_t i = 0; i < w->capacity; i++) {
        tr_cell *c = &w->cells[i];
        if (!c->live && c->generation != UINT64_MAX) {
            c->value = value; c->revision = ++w->publication; c->live = 1;
            *out = (tr_id){(uint32_t)i, c->generation};
            return TR_OK;
        }
    }
    return TR_CAPACITY;
}
tr_status tr_remove(tr_world *w, tr_id id) {
    const tr_cell *c;
    tr_status s = cell(w, id, &c);
    if (s != TR_OK) return s;
    if (w->publication == UINT64_MAX) return TR_EXHAUSTED;
    tr_cell *mutable = &w->cells[id.slot];
    mutable->live = 0; mutable->value = 0; mutable->revision = ++w->publication;
    // A maximum-generation tombstone is permanent; no ABA by integer wrap.
    if (mutable->generation != UINT64_MAX) mutable->generation++;
    return TR_OK;
}
tr_status tr_current(const tr_world *w, tr_id id, tr_read *out) {
    if (!out) return TR_INVALID;
    const tr_cell *c;
    tr_status s = cell(w, id, &c);
    if (s != TR_OK) return s;
    *out = (tr_read){id, c->revision, c->value};
    return TR_OK;
}
tr_status tr_begin(tr_transaction *t, tr_world *w, tr_id module,
                   tr_read *reads, size_t nr, tr_write *writes, size_t nw) {
    if (!t) return TR_INVALID;
    *t = (tr_transaction){0};
    if (!reads || !nr || (nw && !writes)) return TR_INVALID;
    tr_read code;
    tr_status s = tr_current(w, module, &code);
    if (s != TR_OK) return s;
    *t = (tr_transaction){w, reads, writes, 1, 0, nr, nw, 1, TR_OK};
    reads[0] = code;
    return TR_OK;
}
static tr_status fail(tr_transaction *t, tr_status s) {
    if (t && t->active && t->failure == TR_OK) t->failure = s;
    return s;
}
tr_status tr_read_value(tr_transaction *t, tr_id id, uint64_t *out) {
    if (!t || !t->active || !out) return fail(t, TR_INVALID);
    if (t->failure != TR_OK) return t->failure;
    for (size_t i = 0; i < t->read_count; i++) {
        if (same_id(t->reads[i].id, id)) { *out = t->reads[i].value; return TR_OK; }
    }
    if (t->read_count == t->read_capacity) return fail(t, TR_CAPACITY);
    tr_read r;
    tr_status s = tr_current(t->world, id, &r);
    if (s != TR_OK) return fail(t, s);
    t->reads[t->read_count++] = r; *out = r.value;
    return TR_OK;
}
tr_status tr_write_value(tr_transaction *t, tr_id id, uint64_t value) {
    if (!t || !t->active) return TR_INVALID;
    if (t->failure != TR_OK) return t->failure;
    for (size_t i = 0; i < t->write_count; i++)
        if (same_id(t->writes[i].id, id)) return fail(t, TR_DUPLICATE);
    if (t->write_count == t->write_capacity) return fail(t, TR_CAPACITY);
    uint64_t previous;
    tr_status s = tr_read_value(t, id, &previous);
    if (s != TR_OK) return s;
    t->writes[t->write_count++] = (tr_write){id, value};
    return TR_OK;
}
tr_status tr_commit(tr_transaction *t, tr_receipt *receipt) {
    if (receipt) *receipt = (tr_receipt){0};
    if (!t || !t->active || !receipt) return TR_INVALID;
    t->active = 0;
    if (t->failure != TR_OK) return t->failure;
    tr_world *w = t->world;
    for (size_t i = 0; i < t->read_count; i++) {
        tr_read now;
        tr_status s = tr_current(w, t->reads[i].id, &now);
        if (s != TR_OK) return s;
        if (now.revision != t->reads[i].revision) return TR_STALE;
    }
    size_t changed = 0;
    for (size_t i = 0; i < t->write_count; i++)
        changed += w->cells[t->writes[i].id.slot].value != t->writes[i].value;
    if (changed && w->publication == UINT64_MAX) return TR_EXHAUSTED;
    if (changed) {
        uint64_t revision = ++w->publication;
        for (size_t i = 0; i < t->write_count; i++) {
            tr_write v = t->writes[i]; tr_cell *c = &w->cells[v.id.slot];
            if (c->value != v.value) { c->value = v.value; c->revision = revision; }
        }
    }
    *receipt = (tr_receipt){w->publication, changed};
    return TR_OK;
}
void tr_abort(tr_transaction *t) { if (t) t->active = 0; }
