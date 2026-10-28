# Storage owner (keeper)

2026-10-28. Stage 2 of [construction](CONSTRUCTION.md). `native/store/keeper.{h,c}` (`tk_*`)
is the storage owner that [DRAFTS.md](DRAFTS.md) assumed: one thread that opens the store and
makes every store call, so the store's single-owner rule holds by construction rather than by
caller discipline. It is the component an Android input adapter and agents will talk to.

## Contract

- `tk_start(dir)` opens the store on the keeper thread. `tk_stop` saves every attached dirty
  queue (bounded attempts, without backoff), closes the queues and the store.
- `tk_attach(document)` loads the document there (`td_load_text`) and returns a draft queue the
  keeper drives. `tk_detach` saves it first. It returns `TS_CONFLICT` or `TS_INVALID` instead
  of dropping edits while the queue is in conflict or still being edited.
- The interaction owner calls `ti_edit`, then `tk_poke`. A poke takes a mutex only to signal a
  condition variable; it never waits for storage. Each wake saves every dirty queue until it is
  clean: capture, `td_save_text`, then acknowledge. On a storage conflict the keeper loads,
  rebases and saves again.
- Failed saves back off exponentially (50 ms doubling to 5 s). Pokes, meaning continued typing,
  do not bypass the backoff, so failing storage is not hammered.
- `tk_flush(q, local)` blocks until a revision at or after `local` is acknowledged. It returns
  the exact document revision that acknowledgement produced: the input an Ask must capture
  ("Ask follows acknowledgement of the intended draft revision", DOCUMENTS.md). It returns
  `TS_CONFLICT` if the queue is in conflict. With a timeout, it returns the last save failure.
- `tk_run(task)` runs other store work (agent edits, Ask capture, quotas) on the keeper thread.
  Afterwards it checks every attached document and rebases those that moved, or puts them in
  conflict. This is how external revisions reach drafts; there is no polling.

Lock order: the keeper's lock may be held while reading a queue's state, never the reverse.
Queue calls release their lock before the keeper locks. Slot fields change only on the keeper
thread and are published under the keeper's lock.

## Evidence

`tests/core_keeper.sh` / `core.keeper`; CI runs it under ASan/UBSan and TSan.
- **Typing:** 4000 edits on another thread with a poke after each coalesce into about 100
  saves. Ten flushes each name a durable revision whose text is exactly the text at some local
  revision at or after the one flushed; the oracle hashes every local revision.
- **Agents:** a disjoint agent edit merges into the live draft with the caret kept. An
  overlapping edit (made deterministic inside one keeper task) produces `TS_CONFLICT` until
  the owner keeps its text.
- **Failure:** a zero-headroom quota gives `TS_FULL` after 4 backed-off attempts in 400 ms, and
  20 spaced pokes add at most one attempt. Lifting the quota saves the draft.
- **Drain:** stop saves 300 edits that were never poked.
- **SIGKILL:** in 6 cycles, the recovered text equals the text at a local revision at or after
  the last flush the child reported.
- **Mutants:** 7 of 8 caught. The survivor (one save per wake) is equivalent under the
  contract that every edit is followed by a poke.

## Not yet built

The Android `InputConnection` adapter and Java binding; lifecycle hooks (flush on pause,
`tk_stop` on destroy); write coalescing by time or energy budget (each wake saves immediately,
with one fsync per changed chunk plus the record commit); and persisting unacknowledged edits
across process death.
