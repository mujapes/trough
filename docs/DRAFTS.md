# Input persistence queue

2026-10-28. Stage 2 of [construction](CONSTRUCTION.md): the input-owner obligations that
[DOCUMENTS.md](DOCUMENTS.md) left open. `native/store/draft.{h,c}` (`ti_*`, in
`libtroughstore.a`) sits between the interaction owner, which holds the live draft, and the
storage owner, which alone calls `td_save`/`td_load`. It has no thread, timer, renderer or
Android binding of its own.

## Ownership

The live value is a [`tx_text`](TEXT.md) (since 2026-10-28 evening). The interaction owner calls
`ti_edit` (same batch contract as `td_apply`: the whole batch and final selection are validated
before anything changes; each edit is then O(log n + inserted)) and `ti_view` (a flat copy), or
`ti_snapshot` (O(1), an immutable reference for layout or agents).
These take a mutex that the storage side holds only for copies, never across SQLite, so
typing does not wait on storage. Every accepted edit advances the live `local` revision.

The storage owner runs `ti_capture` (retains the live snapshot, no copy) -> `td_save_text`
(outside the lock; writes only changed chunks, [TDOC v2](DOCUMENTS.md)) -> `ti_acknowledge` or
`ti_reject` for that exact `local`, then releases the captured reference. Storage-owner calls are
not concurrent with each other. At most one capture is in flight; edits made meanwhile stay
pending and the next capture carries all of them, so saves coalesce under load (4000 edits
became 13 saves on the Pixel, 67-441 in the VM, depending on scheduling). Acknowledgement
advances `saved`/`durable` and drops the covered batches. It never replaces the live value
with the storage snapshot. A failed save leaves everything pending.

Phases: `CLEAN` (saved == local), `DIRTY`, `SAVING`, `CONFLICT`. A clean queue learns of an
external durable revision only when the storage owner passes it to `ti_rebase` (after a
`TS_CONFLICT` save, or from a future change notification). Scheduling captures and polling or
subscribing for external revisions are the storage owner's job.

## External revisions: rebase or conflict

The queue keeps the last acknowledged durable text (base, a shared snapshot) and the ranges of
every unacknowledged batch (not their text). Invariant: base + logged batches = live.
`ti_rebase` diffs base against the new durable text into one replacement (common prefix and
suffix; chunks shared at the same offset are skipped without comparison; the scan runs outside
the lock), transforms it through the logged batches and applies it to the live value, mapping
selection and composition. Logged batches are transformed the other way, so later rebases stay
exact. Ties: a local edit ending at the remote start (including an insertion at the same point)
goes first; one starting at the remote end goes after.

Conflict, with the live value left untouched: overlapping ranges, a remote change touching
the interior of the active composition (the IME owns that span), or an overflowed log
(`log_limit` bytes; default 1 MiB). Then edits and captures return `TS_CONFLICT` until the owner
decides: `ti_resolve_keep(durable)` saves the live value over that revision, or the owner
reopens the queue from storage to take the durable text. The queue never guesses.

The single prefix/suffix replacement is exact but coarse: remote edits at both ends of a
document look like one large replacement and can conflict with a local edit between them.
A finer diff (multiple hunks) can replace it without changing the contract.

## Evidence

`tests/core_drafts.sh` (`core.drafts` scenario; CI under ASan/UBSan with the `TI_CHECK`
invariant, and TSan). Merge correctness is checked independently of the transform: every
inserted unit is unique, so a merge must contain every remote unit unless deleted locally,
lose a live unit only if it was durable and deleted remotely, and preserve both relative
orders. 60 documents: about 4000 edits, 860 saves, 217 merges and 216 conflicts, plus
boundary, composition, overflow and two-thread typing cases. 8/8 hand mutants were caught
(tie order, boundary, shifts, untransformed log, ignored composition, retained log, ignored
overflow, clean rebase dirtying). Phone: bundle
`5eadad0bf7024750ce7ace5c31df03a6eef0b94c3b43a9c77e9506de4620f992`, receipt in
`out/evidence/core.drafts.json`.

Since the move to `tx_text` the oracle also runs 200 rebases against revisions that share
chunks with the base, six periodic-text cases where chunks are shared at shifted offsets, and
20 leaf-boundary constructions for the diff's alignment and clamp guards. Each case is checked
against an independent flat minimal diff. Mutants: 10/10 on the earlier set plus 5/5 on the
chunk-aware diff (prefix/suffix alignment, clamp, off-by-one, non-terminating prefix).

## Not yet built

Android `InputConnection`/IME adapter that drives this queue; the storage-owner worker and its
scheduling; change notification for external revisions; persisting unacknowledged edits across
process death (they live only in memory until acknowledged). A rebase against a freshly loaded
revision still compares O(n) units, since loaded chunks are distinct objects; chunk identity by
hash could remove that.
