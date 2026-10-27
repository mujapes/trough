#include "api.h"
#include <stdlib.h>
#include <assert.h>
#ifndef VERSION
#define VERSION 1
#endif
#if VERSION == 1
typedef struct { uint64_t value; } state;
static void *restore(uint64_t n) { state *s=malloc(sizeof(*s)); if(s) s->value=n; return s; }
static uint64_t snapshot(const void *p) { return ((const state *)p)->value; }
#else
typedef struct { uint64_t guard, padding[7], encoded; } state;
static void *restore(uint64_t n) {
    state *s=calloc(1,sizeof(*s)); if(s) { s->guard=0x41544f4d; s->encoded=n^0xabcdef; } return s;
}
static uint64_t snapshot(const void *p) {
    const state *s=p; assert(s->guard==0x41544f4d); return s->encoded^0xabcdef;
}
#endif
static uint64_t evaluate(const void *p,uint64_t input) { return snapshot(p)+VERSION*input; }
static void destroy(void *p) { free(p); }
static const test_api api={1,VERSION,restore,snapshot,evaluate,destroy};
const test_api *test_module(void) { return &api; }
