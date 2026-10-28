#ifndef TROUGH_TASK_H
#define TROUGH_TASK_H
#include "stream.h"

/* A task's identity is its prepared-effect/stream-entry key. Capture is authoritative in
 * the effect request; ordering is authoritative in the stream. There is no copied status.
 * Dispatch/capability checks belong to the effect owner, never these storage APIs. */
enum { TT_ASK_ENTRY = 0x54540101 };
typedef struct {
    tl_entry entry;
    ts_effect_record effect;
    td_captured captured;
    uint8_t request[TD_CAPTURE_LIMIT];
} tt_task;
/* Captured timestamp is part of request identity: retry the original action, not a new clock.
 * New actions validate the draft/dependencies and stream head in one durable publication. */
ts_status tt_ask(ts_store *, ts_read stream, const uint8_t intent[16], uint64_t created_ms,
                  const td_capture *, tl_entry *);
/* Request/options pointers refer into out->request. Do not shallow-copy then drop out.
 * On capacity failure only entry/effect metadata is valid; neither request nor outcome is
 * copied by the store. The caller may size its outcome buffer using effect.outcome_size. */
ts_status tt_load(ts_store *, const uint8_t intent[16], tt_task *out, void *outcome, size_t capacity);
#endif
