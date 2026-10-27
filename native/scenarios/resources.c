#include "resources.h"
#include <assert.h>
#include <stdio.h>
#define OK(x) assert((x)==TR_OK)
static uint32_t seed=735914;
static uint32_t random32(void) { seed^=seed<<13; seed^=seed>>17; seed^=seed<<5; return seed; }
int main(void) {
    tr_resources p; tr_resource r[8]; tr_lease l[16]; tr_id a,b,c,token,copy;
    tr_resource_info info;
    OK(tr_resources_init(&p,r,8,l,16,100));
    OK(tr_resource_create(&p,1,123,80,&a));
    assert(tr_resource_create(&p,1,456,21,&b)==TR_CAPACITY);
    OK(tr_resource_acquire(&p,a,&token)); copy=token;
    OK(tr_resource_retire(&p,a));
    assert(tr_resource_acquire(&p,a,&b)==TR_STALE);
    assert(tr_reap_begin(&p,&info)==TR_CAPACITY);
    OK(tr_lease_clone(&p,token,&b));
    OK(tr_lease_release(&p,token));
    assert(tr_lease_release(&p,copy)==TR_STALE);
    assert(tr_lease_get(&p,copy,&info)==TR_STALE);
    OK(tr_lease_get(&p,b,&info)); assert(info.payload==123);
    OK(tr_lease_release(&p,b));
    OK(tr_reap_begin(&p,&info)); assert(info.payload==123 && p.retained_bytes==80);
    assert(tr_resource_create(&p,1,456,21,&c)==TR_CAPACITY);
    assert(tr_reap_begin(&p,&info)==TR_CAPACITY);
    OK(tr_reap_done(&p,a)); assert(p.retained_bytes==0);
    assert(tr_reap_done(&p,a)==TR_STALE);
    OK(tr_resource_create(&p,1,789,100,&c));
    assert(c.slot==a.slot && c.generation!=a.generation);
    assert(tr_resource_acquire(&p,a,&token)==TR_STALE);
    puts("ok retirement denies admission; old leases survive; destruction acknowledgement releases budget");
    OK(tr_resource_retire(&p,c)); OK(tr_reap_begin(&p,&info)); OK(tr_reap_done(&p,info.id));

    // Independent single-resource model driven by random lease/retire/reap events.
    // Track tokens independently; registry refcounts, byte accounting and lifecycle must agree.
    unsigned active[16]={0}; tr_id tokens[16]; unsigned refs=0,retired=0,reaping=0;
    OK(tr_resource_create(&p,2,42,50,&a));
    for(unsigned i=0;i<100000;i++) {
        unsigned n=random32()%16,op=random32()%5;
        if(op==0 && !active[n]) {
            tr_status s=tr_resource_acquire(&p,a,&tokens[n]);
            assert(s==(retired?TR_STALE:TR_OK));
            if(s==TR_OK) { active[n]=1; refs++; }
        } else if(op==1 && active[n]) {
            tr_id old=tokens[n]; OK(tr_lease_release(&p,old)); active[n]=0; refs--;
            assert(tr_lease_release(&p,old)==TR_STALE);
        } else if(op==2 && !retired) { OK(tr_resource_retire(&p,a)); retired=1; }
        else if(op==3 && retired && !reaping) {
            tr_status s=tr_reap_begin(&p,&info);
            assert(s==(refs?TR_CAPACITY:TR_OK)); if(s==TR_OK) reaping=1;
        } else if(op==4 && reaping) {
            OK(tr_reap_done(&p,a)); assert(p.retained_bytes==0);
            OK(tr_resource_create(&p,2,42,50,&a)); retired=reaping=0;
        }
        assert(r[a.slot].references==refs && p.retained_bytes==50);
        for(unsigned j=0;j<16;j++) if(active[j]) { OK(tr_lease_get(&p,tokens[j],&info)); assert(info.payload==42); }
    }
    for(unsigned i=0;i<16;i++) if(active[i]) OK(tr_lease_release(&p,tokens[i]));
    if(!retired) OK(tr_resource_retire(&p,a));
    if(!reaping) OK(tr_reap_begin(&p,&info));
    OK(tr_reap_done(&p,a));
    assert(p.retained_bytes==0);
    puts("ok 100000 differential resource transitions; no premature reuse, lost lease or byte drift");

    OK(tr_resources_init(&p,r,1,l,1,UINT64_MAX));
    OK(tr_resource_create(&p,1,0,UINT64_MAX,&a));
    OK(tr_resource_acquire(&p,a,&token));
    assert(tr_lease_clone(&p,token,&b)==TR_CAPACITY);
    assert(r[0].references==1);
    l[0].generation=UINT64_MAX-1; token.generation=UINT64_MAX-1;
    OK(tr_lease_release(&p,token));
    assert(tr_resource_acquire(&p,a,&token)==TR_CAPACITY);
    OK(tr_resource_retire(&p,a)); OK(tr_reap_begin(&p,&info)); OK(tr_reap_done(&p,a));
    p.next_identity=UINT64_MAX;
    assert(tr_resource_create(&p,1,0,0,&a)==TR_EXHAUSTED);
    puts("ok capacity and identity exhaustion do not wrap or leak references");
    return 0;
}
