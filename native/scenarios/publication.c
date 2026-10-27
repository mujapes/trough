#include "world.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

#define OK(x) assert((x) == TR_OK)
typedef struct { tr_transaction tx; tr_read reads[16]; tr_write writes[8]; } job;
static void begin(job *j, tr_world *w, tr_id code) {
    OK(tr_begin(&j->tx,w,code,j->reads,16,j->writes,8));
}
static uint64_t read(job *j,tr_id id) { uint64_t value; OK(tr_read_value(&j->tx,id,&value)); return value; }
static uint64_t current(tr_world *w,tr_id id) { tr_read r; OK(tr_current(w,id,&r)); return r.value; }
static tr_receipt commit(job *j) { tr_receipt r; OK(tr_commit(&j->tx,&r)); return r; }
static void set(tr_world *w,tr_id code,tr_id id,uint64_t value) {
    job j; begin(&j,w,code); OK(tr_write_value(&j.tx,id,value)); commit(&j);
}
static uint32_t rng = 0x735914;
static uint32_t random_u32(void) { rng ^= rng << 13; rng ^= rng >> 17; rng ^= rng << 5; return rng; }
int main(void) {
    tr_cell cells[16]; tr_world w; OK(tr_world_init(&w,cells,16));
    tr_id code,pad,video,geometry,hits,policy;
    OK(tr_create(&w,1,&code)); OK(tr_create(&w,10,&pad)); OK(tr_create(&w,20,&video));
    OK(tr_create(&w,0,&geometry)); OK(tr_create(&w,0,&hits)); OK(tr_create(&w,1,&policy));
    job a,b; tr_receipt receipt; tr_read before;
    begin(&a,&w,code); uint64_t text=read(&a,pad);
    OK(tr_write_value(&a.tx,geometry,text*2)); OK(tr_write_value(&a.tx,hits,text*3));
    set(&w,code,video,21); // unrelated playback does not invalidate text layout
    receipt=commit(&a); assert(receipt.changed==2);
    tr_read g,h; OK(tr_current(&w,geometry,&g)); OK(tr_current(&w,hits,&h));
    assert(g.revision==h.revision && g.value==20 && h.value==30);
    puts("ok unrelated domains advance independently; geometry and hit targets publish together");

    begin(&a,&w,code); text=read(&a,pad);
    OK(tr_write_value(&a.tx,geometry,text+100)); OK(tr_write_value(&a.tx,hits,text+200));
    set(&w,code,pad,11);
    assert(read(&a,pad)==10); // retained snapshot, no silent second read of newer data
    assert(tr_commit(&a.tx,&receipt)==TR_STALE && receipt.changed==0);
    assert(current(&w,geometry)==20 && current(&w,hits)==30);
    assert(tr_commit(&a.tx,&receipt)==TR_INVALID);
    puts("ok stale work cannot partially publish, mix captures or commit twice");

    begin(&a,&w,code); read(&a,pad); OK(tr_write_value(&a.tx,geometry,77));
    set(&w,code,pad,12); set(&w,code,pad,11);
    assert(tr_commit(&a.tx,&receipt)==TR_STALE);
    begin(&a,&w,code); OK(tr_write_value(&a.tx,geometry,77));
    set(&w,code,code,2); assert(tr_commit(&a.tx,&receipt)==TR_STALE);
    puts("ok change-back and module replacement revoke old publication rights");

    begin(&a,&w,code); OK(tr_write_value(&a.tx,geometry,99));
    begin(&b,&w,code); OK(tr_write_value(&b.tx,geometry,100));
    commit(&a); assert(tr_commit(&b.tx,&receipt)==TR_STALE && current(&w,geometry)==99);
    OK(tr_current(&w,geometry,&before));
    begin(&a,&w,code); OK(tr_write_value(&a.tx,geometry,99)); receipt=commit(&a);
    OK(tr_current(&w,geometry,&g)); assert(receipt.changed==0 && g.revision==before.revision);
    puts("ok competing writers cannot lose updates; unchanged results stop revision propagation");

    begin(&a,&w,code); read(&a,policy); OK(tr_write_value(&a.tx,geometry,123));
    tr_id retired=policy; OK(tr_remove(&w,policy)); OK(tr_create(&w,1,&policy));
    assert(retired.slot==policy.slot && retired.generation!=policy.generation);
    assert(tr_commit(&a.tx,&receipt)==TR_STALE);
    assert(tr_current(&w,retired,&before)==TR_STALE);
    puts("ok recycled slots cannot impersonate retired identities");

    // Hard capacity failures leave world state untouched and never drop a write silently.
    tr_read small_reads[1]; tr_write small_writes[1]; tr_transaction small;
    OK(tr_begin(&small,&w,code,small_reads,1,small_writes,1));
    assert(tr_write_value(&small,geometry,999)==TR_CAPACITY);
    assert(current(&w,geometry)==99); tr_abort(&small);
    assert(tr_commit(&small,&receipt)==TR_INVALID);
    begin(&a,&w,code); OK(tr_write_value(&a.tx,geometry,101));
    assert(tr_write_value(&a.tx,geometry,102)==TR_DUPLICATE); tr_abort(&a.tx);
    assert(current(&w,geometry)==99);
    begin(&a,&w,code); a.tx.write_capacity=1;
    OK(tr_write_value(&a.tx,geometry,1000));
    assert(tr_write_value(&a.tx,hits,1001)==TR_CAPACITY);
    assert(tr_commit(&a.tx,&receipt)==TR_CAPACITY && current(&w,geometry)==99);
    puts("ok capacity, duplicate and cancellation paths have no partial effects, even after staged writes");

    // Differential oracle: independent scalar model, asynchronous candidate completion.
    // 10000 interleavings mix relevant/irrelevant changes, no-op writes and code replacement.
    uint64_t expected_geometry=99, expected_hits=30;
    unsigned accepted=0,rejected=0;
    for(unsigned i=0;i<10000;i++) {
        begin(&a,&w,code); uint64_t p=read(&a,pad), v=read(&a,policy);
        uint64_t result=p*7+v; // deterministic stand-in for a typed derivation
        OK(tr_write_value(&a.tx,geometry,result)); OK(tr_write_value(&a.tx,hits,result+1));
        unsigned change=random_u32()%5; int stale=0;
        switch(change) {
            case 0: set(&w,code,video,current(&w,video)+1); break;
            case 1: set(&w,code,pad,p+1); stale=1; break;
            case 2: set(&w,code,policy,v); break;
            case 3: set(&w,code,code,current(&w,code)+1); stale=1; break;
            case 4: set(&w,code,policy,v+1); stale=1; break;
        }
        tr_status status=tr_commit(&a.tx,&receipt);
        if(stale) { assert(status==TR_STALE); rejected++; }
        else { assert(status==TR_OK); expected_geometry=result; expected_hits=result+1; accepted++; }
        assert(current(&w,geometry)==expected_geometry && current(&w,hits)==expected_hits);
    }
    printf("ok differential interleavings: 10000, accepted=%u stale=%u\n",accepted,rejected);

    // Integer exhaustion is a defined failure, not permission to wrap back into an old ID.
    begin(&a,&w,code); OK(tr_write_value(&a.tx,geometry,UINT64_MAX));
    uint64_t old=current(&w,geometry); w.publication=UINT64_MAX;
    assert(tr_commit(&a.tx,&receipt)==TR_EXHAUSTED && current(&w,geometry)==old);
    assert(tr_remove(&w,pad)==TR_EXHAUSTED);
    assert(tr_create(&w,1,&retired)==TR_EXHAUSTED);
    puts("ok publication exhaustion refuses mutation");

    tr_cell tiny[1]; tr_world one; OK(tr_world_init(&one,tiny,1));
    OK(tr_create(&one,1,&retired)); tiny[0].generation=UINT64_MAX-1;
    retired.generation=UINT64_MAX-1; OK(tr_remove(&one,retired));
    assert(tr_create(&one,2,&retired)==TR_CAPACITY);
    puts("ok exhausted generation slot is permanently retired");
    puts("core-world: all checks passed (correctness only; no device performance claim)");
    return 0;
}
