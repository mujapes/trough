#include "modules.h"
#include <string.h>

static int same(tr_id a, tr_id b) { return a.slot == b.slot && a.generation == b.generation; }
static tr_status pair_acquire(tr_resources *p, tr_id code, tr_id state, tr_id *cl, tr_id *sl) {
    tr_resource_info c,s;
    tr_status r = tr_resource_get(p,code,&c); if (r != TR_OK) return r;
    r = tr_resource_get(p,state,&s); if (r != TR_OK) return r;
    if (c.kind != TR_CODE || s.kind != TR_STATE) return TR_INVALID;
    r = tr_resource_acquire(p,code,cl); if (r != TR_OK) return r;
    r = tr_resource_acquire(p,state,sl);
    if (r != TR_OK) (void)tr_lease_release(p,*cl);
    return r;
}
tr_status tr_module_init(tr_module *m, tr_world *w, tr_resources *p, tr_id cc,
                         tr_id sc, tr_id code, tr_id state) {
    if (!m || same(cc,sc)) return TR_INVALID;
    tr_read c,s; tr_resource_info cr,sr;
    tr_status r = tr_current(w,cc,&c); if (r != TR_OK) return r;
    r = tr_current(w,sc,&s); if (r != TR_OK) return r;
    r = tr_resource_get(p,code,&cr); if (r != TR_OK) return r;
    r = tr_resource_get(p,state,&sr); if (r != TR_OK) return r;
    if (c.value != cr.identity || s.value != sr.identity) return TR_INVALID;
    tr_id cl,sl; r = pair_acquire(p,code,state,&cl,&sl); if (r != TR_OK) return r;
    *m = (tr_module){w,p,cc,sc,cl,sl,1}; return TR_OK;
}
tr_status tr_module_close(tr_module *m) {
    if (!m || !m->live) return TR_INVALID;
    tr_resource_info code,state;
    tr_status r = tr_lease_get(m->resources,m->code_lease,&code); if (r != TR_OK) return r;
    r = tr_lease_get(m->resources,m->state_lease,&state); if (r != TR_OK) return r;
    /* Module shutdown must invalidate outstanding transaction captures before retiring code. */
    tr_transaction tx; tr_read reads[2]; tr_write writes[2]; tr_receipt receipt;
    r = tr_begin(&tx,m->world,m->code_cell,reads,2,writes,2); if (r != TR_OK) return r;
    r = tr_write_value(&tx,m->code_cell,0); if (r != TR_OK) return r;
    r = tr_write_value(&tx,m->state_cell,0); if (r != TR_OK) return r;
    r = tr_commit(&tx,&receipt); if (r != TR_OK) return r;
    m->live = 0;
    (void)tr_resource_retire(m->resources,code.id);
    (void)tr_resource_retire(m->resources,state.id);
    (void)tr_lease_release(m->resources,m->code_lease);
    (void)tr_lease_release(m->resources,m->state_lease);
    return TR_OK;
}
tr_status tr_job_begin(tr_module *m, tr_job *j, tr_read *reads, size_t nr, tr_write *writes, size_t nw) {
    if (!j || !m || !m->live) return TR_INVALID;
    *j = (tr_job){0};
    tr_status r = tr_begin(&j->transaction,m->world,m->code_cell,reads,nr,writes,nw);
    if (r != TR_OK) return r;
    uint64_t state_value;
    r = tr_read_value(&j->transaction,m->state_cell,&state_value);
    if (r != TR_OK) { tr_abort(&j->transaction); return r; }
    tr_resource_info code,state;
    r = tr_lease_get(m->resources,m->code_lease,&code); if (r != TR_OK) goto fail;
    r = tr_lease_get(m->resources,m->state_lease,&state); if (r != TR_OK) goto fail;
    if (j->transaction.reads[0].value != code.identity || state_value != state.identity) { r=TR_STALE; goto fail; }
    r = tr_resource_acquire(m->resources,code.id,&j->code_lease); if (r != TR_OK) goto fail;
    r = tr_resource_acquire(m->resources,state.id,&j->state_lease);
    if (r != TR_OK) { (void)tr_lease_release(m->resources,j->code_lease); goto fail; }
    j->module=m; j->live=1; return TR_OK;
fail:
    tr_abort(&j->transaction); return r;
}
void tr_job_cancel(tr_job *j) { if (j && j->live) tr_abort(&j->transaction); }
tr_status tr_job_finish(tr_job *j) {
    if (!j || !j->live) return TR_INVALID;
    tr_resources *p=j->module->resources;
    tr_abort(&j->transaction);
    tr_status a=tr_lease_release(p,j->code_lease), b=tr_lease_release(p,j->state_lease);
    j->live=0; return a != TR_OK ? a : b;
}
tr_status tr_change_begin(tr_module *m,tr_change *c,tr_read *r,size_t nr,tr_write *w,size_t nw) {
    if (!c) return TR_INVALID;
    *c=(tr_change){0};
    return tr_job_begin(m,&c->source,r,nr,w,nw);
}
tr_status tr_change_stage(tr_change *c,tr_id code,tr_id state) {
    if (!c || !c->source.live || c->staged || !c->source.transaction.active) return TR_INVALID;
    tr_resources *p=c->source.module->resources;
    tr_resource_info old,old_state,new_code,new_state;
    tr_status r=tr_lease_get(p,c->source.code_lease,&old); if (r != TR_OK) return r;
    r=tr_resource_get(p,code,&new_code); if (r != TR_OK) return r;
    r=tr_resource_get(p,state,&new_state); if (r != TR_OK) return r;
    r=tr_lease_get(p,c->source.state_lease,&old_state); if (r != TR_OK) return r;
    if (same(code,old.id) && same(state,old_state.id)) return TR_INVALID;
    r=pair_acquire(p,code,state,&c->code_lease,&c->state_lease); if (r != TR_OK) return r;
    r=tr_write_value(&c->source.transaction,c->source.module->code_cell,new_code.identity);
    if (r == TR_OK) r=tr_write_value(&c->source.transaction,c->source.module->state_cell,new_state.identity);
    if (r != TR_OK) {
        (void)tr_lease_release(p,c->code_lease); (void)tr_lease_release(p,c->state_lease);
        return r;
    }
    c->staged=1; return TR_OK;
}
tr_status tr_change_activate(tr_change *c,tr_receipt *receipt) {
    if (receipt) *receipt=(tr_receipt){0};
    if (!c || !c->source.live || !c->staged || !receipt) return TR_INVALID;
    tr_module *m=c->source.module; tr_resources *p=m->resources;
    if (!m->live) return TR_STALE;
    tr_resource_info old_code,old_state,new_code,new_state;
    tr_status r=tr_lease_get(p,m->code_lease,&old_code); if (r != TR_OK) return r;
    r=tr_lease_get(p,m->state_lease,&old_state); if (r != TR_OK) return r;
    r=tr_lease_get(p,c->code_lease,&new_code); if (r != TR_OK) return r;
    r=tr_lease_get(p,c->state_lease,&new_state); if (r != TR_OK) return r;
    if (p->resources[new_code.id.slot].state != TR_LIVE || p->resources[new_state.id.slot].state != TR_LIVE)
        return TR_STALE;
    r=tr_commit(&c->source.transaction,receipt); if (r != TR_OK) return r;
    tr_id cl=m->code_lease,sl=m->state_lease;
    m->code_lease=c->code_lease; m->state_lease=c->state_lease;
    c->staged=0; // candidate holds became host ownership, no callback/observable midpoint
    if (!same(old_code.id,new_code.id)) (void)tr_resource_retire(p,old_code.id);
    if (!same(old_state.id,new_state.id)) (void)tr_resource_retire(p,old_state.id);
    (void)tr_lease_release(p,cl); (void)tr_lease_release(p,sl);
    return tr_job_finish(&c->source);
}
tr_status tr_change_abort(tr_change *c) {
    if (!c || !c->source.live) return TR_INVALID;
    tr_resources *p=c->source.module->resources;
    if (c->staged) {
        (void)tr_lease_release(p,c->code_lease); (void)tr_lease_release(p,c->state_lease); c->staged=0;
    }
    return tr_job_finish(&c->source);
}
