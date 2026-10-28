#ifndef TROUGH_KEEPER_H
#define TROUGH_KEEPER_H
#include "draft.h"

/* Storage owner, construction stage 2 (docs/KEEPER.md). One thread opens the store and makes
 * every store call, so the store's single-owner rule holds by construction. It drives attached
 * draft queues: an interaction owner calls ti_edit and then tk_poke, which never blocks on
 * storage; the keeper captures, saves, acknowledges, retries failures with backoff, and rebases
 * a queue when its document changes. Other store work (agent edits, Ask) runs on the keeper
 * through tk_run, after which every attached document is checked for an external revision. */
typedef struct tk_owner tk_owner;

ts_status tk_start(const char *dir, tk_owner **);
/* Saves every attached dirty queue (bounded retries), detaches them, closes the store. */
void tk_stop(tk_owner *);

/* Loads the document on the keeper thread and returns a queue it drives. The queue stays
 * owned by the keeper until tk_detach (which flushes it first) or tk_stop. */
ts_status tk_attach(tk_owner *, uint64_t document, size_t log_limit, ti_queue **);
ts_status tk_detach(tk_owner *, ti_queue *);

/* Interaction owner, after ti_edit: wakes the keeper. Never waits for storage. */
void tk_poke(tk_owner *);
/* Blocks until local revision `local` (or later) of q is durable; *durable names that document
 * revision, the exact input an Ask must capture. TS_CONFLICT if q entered conflict; the last
 * save failure if saving is failing and timeout_ms elapses (0 waits indefinitely). */
ts_status tk_flush(tk_owner *, ti_queue *, uint64_t local, unsigned timeout_ms, ts_read *durable);

/* Runs task(store, arg) on the keeper thread and returns its status. Afterwards each attached
 * document is checked and rebased (or put into conflict) if task changed it. */
typedef ts_status (*tk_task)(ts_store *, void *);
ts_status tk_run(tk_owner *, tk_task, void *);

/* poke_contended: pokes that found the keeper's lock held (interaction-latency diagnostics). */
/* An eventfd (non-blocking, close-on-exec) the keeper signals whenever an attached draft changes
 * other than through its interaction owner: an external revision merged or put in conflict, or
 * a save failure. A UI thread watches it in its event loop instead of polling. Owned by the
 * keeper; valid until tk_stop. */
int tk_notify_fd(tk_owner *);

typedef struct { uint64_t saves, failures, rebases, conflicts, poke_contended; } tk_stats;
void tk_stats_get(tk_owner *, tk_stats *);
#endif
