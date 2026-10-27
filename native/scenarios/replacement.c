#include "modules.h"
#include "fixtures/api.h"
#include <assert.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#define OK(x) assert((x)==TR_OK)
typedef struct { void *library; const test_api *api; } code_object;
typedef struct { const test_api *api; void *data; tr_id code_dependency; } state_object;
static unsigned destroyed_code,destroyed_state;
static tr_resources p;
static tr_world w;
static uint64_t identity(tr_id id) { tr_resource_info r; OK(tr_resource_get(&p,id,&r)); return r.identity; }
static tr_id load(const char *path) {
    code_object *c=calloc(1,sizeof(*c)); assert(c);
    c->library=dlopen(path,RTLD_NOW|RTLD_LOCAL);
    if(!c->library) { fprintf(stderr,"dlopen: %s\n",dlerror()); exit(1); }
    void *symbol=dlsym(c->library,"test_module"); assert(symbol);
    const test_api *(*entry)(void);
    _Static_assert(sizeof(entry)==sizeof(symbol),"POSIX function pointer size");
    memcpy(&entry,&symbol,sizeof(entry)); c->api=entry(); assert(c->api->abi==1);
    tr_id id; OK(tr_resource_create(&p,TR_CODE,(uint64_t)(uintptr_t)c,sizeof(*c),&id)); return id;
}
static tr_id state_new(tr_id code,uint64_t snapshot) {
    tr_resource_info ci; OK(tr_resource_get(&p,code,&ci)); code_object *c=(void *)(uintptr_t)ci.payload;
    state_object *s=malloc(sizeof(*s)); assert(s); s->api=c->api; s->data=s->api->restore(snapshot); assert(s->data);
    OK(tr_resource_acquire(&p,code,&s->code_dependency));
    tr_id id; OK(tr_resource_create(&p,TR_STATE,(uint64_t)(uintptr_t)s,128,&id)); return id;
}
static uint64_t evaluate(tr_job *j,uint64_t input) {
    tr_resource_info ci,si; OK(tr_lease_get(&p,j->code_lease,&ci)); OK(tr_lease_get(&p,j->state_lease,&si));
    code_object *c=(void *)(uintptr_t)ci.payload; state_object *s=(void *)(uintptr_t)si.payload;
    return c->api->evaluate(s->data,input);
}
static uint64_t snapshot(tr_job *j) {
    tr_resource_info si; OK(tr_lease_get(&p,j->state_lease,&si));
    state_object *s=(void *)(uintptr_t)si.payload; return s->api->snapshot(s->data);
}
static unsigned reap(void) {
    unsigned n=0; tr_resource_info r;
    while(tr_reap_begin(&p,&r)==TR_OK) {
        if(r.kind==TR_STATE) {
            state_object *s=(void *)(uintptr_t)r.payload;
            s->api->destroy(s->data); OK(tr_lease_release(&p,s->code_dependency)); free(s); destroyed_state++;
        } else {
            assert(r.kind==TR_CODE); code_object *c=(void *)(uintptr_t)r.payload;
            assert(dlclose(c->library)==0); free(c); destroyed_code++;
        }
        OK(tr_reap_done(&p,r.id)); n++;
    }
    return n;
}
static void job_begin(tr_module *m,tr_job *j,tr_read *r,tr_write *wr) { OK(tr_job_begin(m,j,r,8,wr,4)); }
int main(int argc,char **argv) {
    assert(argc==3);
    tr_resource resources[32]; tr_lease leases[64]; tr_cell cells[16];
    OK(tr_resources_init(&p,resources,32,leases,64,8192)); OK(tr_world_init(&w,cells,16));
    tr_id old_code=load(argv[1]), old_state=state_new(old_code,41), cc,sc,out,unrelated;
    OK(tr_create(&w,identity(old_code),&cc)); OK(tr_create(&w,identity(old_state),&sc));
    OK(tr_create(&w,0,&out)); OK(tr_create(&w,0,&unrelated));
    tr_module module; OK(tr_module_init(&module,&w,&p,cc,sc,old_code,old_state));
    tr_job old_job; tr_read jr[8]; tr_write jw[4]; job_begin(&module,&old_job,jr,jw);
    assert(evaluate(&old_job,3)==44);
    tr_change change; tr_read cr[8]; tr_write cw[4]; OK(tr_change_begin(&module,&change,cr,8,cw,4));
    assert(snapshot(&change.source)==41);
    tr_id new_code=load(argv[2]), new_state=state_new(new_code,snapshot(&change.source));
    OK(tr_change_stage(&change,new_code,new_state));
    // Real migration to different physical layout. Candidate work also publishes coherent outputs.
    OK(tr_write_value(&change.source.transaction,out,47));
    tr_transaction u; tr_read ur[2]; tr_write uw[1]; tr_receipt receipt;
    OK(tr_begin(&u,&w,unrelated,ur,2,uw,1)); OK(tr_write_value(&u,unrelated,123)); OK(tr_commit(&u,&receipt));
    OK(tr_change_activate(&change,&receipt)); assert(receipt.changed==3);
    assert(reap()==0); // Old worker pins state and code despite their retirement.
    assert(evaluate(&old_job,3)==44); // Library really remains mapped and its state is valid.
    OK(tr_write_value(&old_job.transaction,out,999)); assert(tr_commit(&old_job.transaction,&receipt)==TR_STALE);
    tr_job new_job; tr_read nr[8]; tr_write nw[4]; job_begin(&module,&new_job,nr,nw);
    assert(evaluate(&new_job,3)==47 && snapshot(&new_job)==41);
    tr_read current; OK(tr_current(&w,out,&current)); assert(current.value==47);
    OK(tr_job_finish(&old_job)); assert(reap()==2 && destroyed_state==1 && destroyed_code==1);
    puts("ok real native replacement: state schema migration, old job continuity, atomic publication and deferred dlclose");

    // Cancelled work keeps its resources until its completion is acknowledged.
    tr_job_cancel(&new_job); assert(reap()==0); assert(evaluate(&new_job,4)==49);
    OK(tr_job_finish(&new_job)); assert(tr_job_finish(&new_job)==TR_INVALID);
    puts("ok cancellation revokes publishing without destroying executing state");

    // Two overlapping migrations: one wins; the stale candidate cannot overwrite it.
    tr_change a,b; tr_read ar[8],br[8]; tr_write aw[4],bw[4];
    OK(tr_change_begin(&module,&a,ar,8,aw,4)); OK(tr_change_begin(&module,&b,br,8,bw,4));
    tr_id ca=load(argv[1]),sa=state_new(ca,snapshot(&a.source));
    tr_id cb=load(argv[1]),sb=state_new(cb,snapshot(&b.source));
    OK(tr_change_stage(&a,ca,sa)); OK(tr_change_stage(&b,cb,sb));
    OK(tr_change_activate(&a,&receipt)); assert(tr_change_activate(&b,&receipt)==TR_STALE);
    OK(tr_change_abort(&b)); OK(tr_resource_retire(&p,cb)); OK(tr_resource_retire(&p,sb)); reap();
    job_begin(&module,&new_job,nr,nw); assert(evaluate(&new_job,5)==46); OK(tr_job_finish(&new_job));
    puts("ok racing migrations reject stale candidate; failed candidate resources retire independently");

    // Compatible reload adopts the existing state; its destructor still pins its original code.
    tr_id adopted=sa;
    OK(tr_change_begin(&module,&a,ar,8,aw,4)); tr_id adopt_code=load(argv[1]);
    OK(tr_change_stage(&a,adopt_code,adopted)); OK(tr_change_activate(&a,&receipt));
    assert(receipt.changed==1); assert(reap()==0);
    job_begin(&module,&new_job,nr,nw); assert(evaluate(&new_job,7)==48);
    OK(tr_change_begin(&module,&a,ar,8,aw,4)); tr_id edited_state=state_new(adopt_code,52);
    OK(tr_change_stage(&a,adopt_code,edited_state)); OK(tr_change_activate(&a,&receipt));
    assert(receipt.changed==1 && evaluate(&new_job,7)==48);
    assert(tr_commit(&new_job.transaction,&receipt)==TR_STALE);
    OK(tr_job_finish(&new_job)); reap();
    job_begin(&module,&new_job,nr,nw); assert(evaluate(&new_job,7)==59);
    puts("ok ordinary state publication uses the same ownership/activation law without replacing code");
    OK(tr_module_close(&module)); assert(tr_commit(&new_job.transaction,&receipt)==TR_STALE);
    assert(evaluate(&new_job,7)==59); assert(reap()==0);
    OK(tr_job_finish(&new_job)); reap();
    assert(p.retained_bytes==0 && destroyed_code==5 && destroyed_state==5);
    assert(tr_module_close(&module)==TR_INVALID);
    puts("ok same-state adoption, destructor code ownership and shutdown with in-flight work");
    printf("{\"ok\":true,\"code_destroyed\":%u,\"state_destroyed\":%u,\"retained_bytes\":%llu}\n",
        destroyed_code,destroyed_state,(unsigned long long)p.retained_bytes);
    return 0;
}
