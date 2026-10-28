#ifndef TROUGH_WORKSPACE_H
#define TROUGH_WORKSPACE_H
#include "stream.h"

/* A durable workspace root names its draft and stream independently of view/module lifetime.
 * The three records and key associations are created atomically. Identity keys are selected
 * by the trusted workspace owner, distinct/nonzero, and are never recycled after deletion. */
enum { TW_SCHEMA = 0x54570101 };
typedef struct { uint8_t root[16], draft[16], stream[16]; } tw_keys;
typedef struct {
    ts_record record;
    tw_keys keys;
    td_snapshot draft;
    tl_head stream;
} tw_workspace;
/* Retrying an existing root with the same child keys reopens its CURRENT state. The initial
 * draft is used only on first creation; retry must never reset an edited draft. */
ts_status tw_create(ts_store *, const tw_keys *, const td_value *initial, tw_workspace *);
/* Loads metadata. Use td_load for text and tl_begin/tl_page for entries. A missing or
 * incompatible child is invalid workspace state, not permission to create a replacement. */
ts_status tw_load(ts_store *, const uint8_t root[16], tw_workspace *);
#endif
