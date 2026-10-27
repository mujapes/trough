#include "resources.h"
#include <string.h>

static tr_status resource(const tr_resources *p, tr_id id, const tr_resource **out) {
    if (!p || !p->resources || id.slot >= p->resource_capacity || !id.generation) return TR_INVALID;
    const tr_resource *r = &p->resources[id.slot];
    if (r->state == TR_FREE || r->generation != id.generation) return TR_STALE;
    *out = r; return TR_OK;
}
static tr_status lease(const tr_resources *p, tr_id id, const tr_lease **out) {
    if (!p || !p->leases || id.slot >= p->lease_capacity || !id.generation) return TR_INVALID;
    const tr_lease *l = &p->leases[id.slot];
    if (!l->live || l->generation != id.generation) return TR_STALE;
    *out = l; return TR_OK;
}
tr_status tr_resources_init(tr_resources *p, tr_resource *r, size_t nr,
                            tr_lease *l, size_t nl, uint64_t limit) {
    if (!p || !r || !l || !nr || !nl || nr > UINT32_MAX || nl > UINT32_MAX ||
        nr > SIZE_MAX / sizeof(*r) || nl > SIZE_MAX / sizeof(*l)) return TR_INVALID;
    memset(r, 0, nr * sizeof(*r)); memset(l, 0, nl * sizeof(*l));
    for (size_t i = 0; i < nr; i++) r[i].generation = 1;
    for (size_t i = 0; i < nl; i++) l[i].generation = 1;
    *p = (tr_resources){r, l, nr, nl, limit, 0, 1};
    return TR_OK;
}
tr_status tr_resource_create(tr_resources *p, uint32_t kind, uint64_t payload,
                             uint64_t bytes, tr_id *out) {
    if (!p || !p->resources || !out || !kind) return TR_INVALID;
    if (bytes > p->byte_limit - p->retained_bytes) return TR_CAPACITY;
    if (p->next_identity == UINT64_MAX) return TR_EXHAUSTED;
    for (size_t i = 0; i < p->resource_capacity; i++) {
        tr_resource *r = &p->resources[i];
        if (r->state == TR_FREE && r->generation != UINT64_MAX) {
            *r = (tr_resource){r->generation, p->next_identity++, payload, bytes, 0, kind, TR_LIVE};
            p->retained_bytes += bytes; *out = (tr_id){(uint32_t)i, r->generation};
            return TR_OK;
        }
    }
    return TR_CAPACITY;
}
tr_status tr_resource_get(const tr_resources *p, tr_id id, tr_resource_info *out) {
    if (!out) return TR_INVALID;
    const tr_resource *r; tr_status s = resource(p, id, &r);
    if (s != TR_OK) return s;
    *out = (tr_resource_info){id, r->identity, r->payload, r->bytes, r->kind};
    return TR_OK;
}
static tr_status issue(tr_resources *p, tr_id id, tr_id *out) {
    if (!out) return TR_INVALID;
    tr_resource *r = &p->resources[id.slot];
    if (r->references == UINT64_MAX) return TR_EXHAUSTED;
    for (size_t i = 0; i < p->lease_capacity; i++) {
        tr_lease *l = &p->leases[i];
        if (!l->live && l->generation != UINT64_MAX) {
            l->live = 1; l->resource = id; r->references++;
            *out = (tr_id){(uint32_t)i, l->generation}; return TR_OK;
        }
    }
    return TR_CAPACITY;
}
tr_status tr_resource_acquire(tr_resources *p, tr_id id, tr_id *out) {
    const tr_resource *r; tr_status s = resource(p, id, &r);
    if (s != TR_OK) return s;
    if (r->state != TR_LIVE) return TR_STALE;
    return issue(p, id, out);
}
tr_status tr_lease_clone(tr_resources *p, tr_id id, tr_id *out) {
    const tr_lease *l; tr_status s = lease(p, id, &l);
    if (s != TR_OK) return s;
    return issue(p, l->resource, out);
}
tr_status tr_lease_get(const tr_resources *p, tr_id id, tr_resource_info *out) {
    const tr_lease *l; tr_status s = lease(p, id, &l);
    return s == TR_OK ? tr_resource_get(p, l->resource, out) : s;
}
tr_status tr_lease_release(tr_resources *p, tr_id id) {
    const tr_lease *l; tr_status s = lease(p, id, &l);
    if (s != TR_OK) return s;
    tr_resource *r = &p->resources[l->resource.slot];
    if (!r->references) return TR_INVALID;
    r->references--;
    tr_lease *m = &p->leases[id.slot]; m->live = 0;
    if (m->generation != UINT64_MAX) m->generation++;
    return TR_OK;
}
tr_status tr_resource_retire(tr_resources *p, tr_id id) {
    const tr_resource *r; tr_status s = resource(p, id, &r);
    if (s != TR_OK) return s;
    if (r->state != TR_LIVE) return TR_STALE;
    p->resources[id.slot].state = TR_RETIRED;
    return TR_OK;
}
tr_status tr_reap_begin(tr_resources *p, tr_resource_info *out) {
    if (!p || !p->resources || !out) return TR_INVALID;
    for (size_t i = 0; i < p->resource_capacity; i++) {
        tr_resource *r = &p->resources[i];
        if (r->state == TR_RETIRED && !r->references) {
            tr_id id = {(uint32_t)i, r->generation};
            tr_status s = tr_resource_get(p, id, out);
            if (s != TR_OK) return s;
            r->state = TR_REAPING; return TR_OK;
        }
    }
    return TR_CAPACITY;
}
tr_status tr_reap_done(tr_resources *p, tr_id id) {
    const tr_resource *r; tr_status s = resource(p, id, &r);
    if (s != TR_OK) return s;
    if (r->state != TR_REAPING || r->references) return TR_INVALID;
    p->retained_bytes -= r->bytes;
    uint64_t gen = r->generation;
    if (gen != UINT64_MAX) gen++;
    p->resources[id.slot] = (tr_resource){.generation = gen};
    return TR_OK;
}
