# Durable stream ordering and provenance

2026-10-28. Stage 2: `native/store/stream.{h,c}` implements append-only semantic order on
the existing transactional store. Views, visible-range indexes and scroll anchors consume
this order; they do not own it. This component has no event loop or renderer.

A stream has a named head record and immutable named entries. Store schema migration 2
adds a 16-byte key -> durable record ID index. `ts_commit_named` creates those associations
inside the same transaction as ordinary record writes and effects. Associations remain
reserved after deletion; collisions roll back the whole transaction. Numeric IDs are
allocated by SQLite, never predicted by a caller. Keys are app-generated semantic identities,
not capabilities; untrusted content cannot select a privileged identity or mutate the store.

`tl_append` takes an exact head revision, a stable entry key and a typed value. The value
contains a kind, event timestamp, content reference and up to 32 provenance references.
Each reference names an immutable record revision and schema. It can intentionally name
historical content; a later draft/source edit must not invalidate an agent reply's original
inputs. Missing revisions and schema mismatches fail before acceptance. Referenced revisions
are currently retained indefinitely; future compaction must trace these references.

The append writes the new head, entry and key together. The entry records the stream ID,
sequence number and predecessor key. The head's length and last-entry key therefore cannot
get ahead of its entries on a failed write or process death. Commit order determines stream
order; clock skew and delayed timestamps do not reorder accepted entries. Reusing an entry
key with identical content returns its original identity and commit, even with an old head
revision. Reusing it for a different value or stream conflicts. Appending a new key against
a stale head conflicts, giving the owner an explicit retry/rebase decision.

The codec stores bounded little-endian `THED`/`TENT` envelopes with exact lengths, versions
and reserved-zero fields. The head schema is `0x54530101`; entry schema is `0x54530201`.
Entry records are immutable (revision 1); a raw-store rewrite is detected, not silently
accepted as reordered history. These are trusted app storage codecs, not web parsers.

`tl_begin` freezes traversal at an observed head. `tl_page` follows at most 64 predecessor
links per call, newest first. New arrivals cannot pull that cursor toward the tail or insert
duplicates into a traversal. Sequence/stream checks detect broken or cross-stream chains;
failure leaves the cursor unchanged. History can also reconstruct any older head revision.
The caller reverses returned ranges for chronological display. No global history scan is
required to read the recent tail; SQLite indexes stable-key lookups. Indexed arbitrary-range
seeking, visibility summaries and viewport scroll-anchor translation are still downstream
work. Keep/read annotations should be separate owned records, not mutations of entry order.

All API calls execute on the sole storage owner, outside display/input callbacks. Values and
entries are bounded and caller-owned; nothing is hidden in module statics. Reload under the
same schema can recover the head and entries. Changing the schema still requires explicit
migration/activation; this API alone does not establish live scene continuity. Stable-key
lookup reconstructs root ownership after restart, but a complete workspace root registry,
artifact catalog and task owner remain to be built.

`tests/core_streams.sh` / `core.streams` compare 1200 interleaved appends against two independent
serial reference vectors, check snapshot pagination amid later appends, 12 reopen cycles,
old-head redelivery, decreasing timestamps, retained provenance after deletion, schema and
capacity validation, quota rollback, fixed wire fields and immutable-chain corruption.
Ten SIGKILL cycles verify acknowledged entries and contiguous recovered tails. The store
oracle separately proves v1 migration, atomic key/effect/record rollback, tombstone key
reservation and revision exhaustion. These checks establish semantic/storage behaviour,
not rendered scroll continuity or hardware power-loss durability.
