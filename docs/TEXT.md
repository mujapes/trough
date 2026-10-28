# Indexed text and chunk storage

2026-10-28. Stage 2 of [construction](CONSTRUCTION.md): the "indexed text storage" that
[DOCUMENTS.md](DOCUMENTS.md) and [DRAFTS.md](DRAFTS.md) left open, and the basis for the
visible-range layout in [DESIGN.md](../DESIGN.md) ("presentation cost depends on what is
visible and changed"). `native/store/text.{h,c}` (`tx_*`, in `libtroughstore.a`).

## In memory

`tx_text` is an immutable, reference-counted UTF-16 text: an AVL-balanced tree of leaf chunks.
Every node carries unit and U+000A counts. An edit splits and joins along two paths, which is
O(log n + inserted). It returns a new root that shares every untouched chunk, so a snapshot is
`tx_retain` (O(1)) and stays valid and unchanged while the owner keeps editing. Reference
counts are atomic, so other threads may read retained snapshots (renderer, storage, agents).
NULL is the empty text.

Queries: `tx_read` (range copy), `tx_chunk` (zero-copy access to the containing chunk, for
layout and shaping), `tx_line_start` / `tx_line_of` (O(log n) line index). Lines split on
U+000A only. Offsets are UTF-16 code units, as in documents; graphemes, bidi and CR handling
belong to the later text/interaction operators.

Leaves stay within 256..2048 units (`TX_LEAF_MIN`/`MAX`), except a text that is one small leaf.
Joins merge two adjacent leaves when they fit. After each edit, the leaves beside its seams are
rebuilt with their neighbours only if one is below the minimum. Typing therefore rewrites one
chunk, not its neighbours. `tx_verify` checks balance, counts and leaf bounds.

## Durable form

Each leaf is a content-addressed store object (UTF-16LE). A revision is a manifest: `TTXT`,
version 1, leaf count, reserved 0, total units, total newlines, then per leaf its SHA-256,
units and newlines (32 + 40 bytes per leaf). `tx_store` puts only leaves without a durable
identity, then fills the manifest. The owner commits the manifest in its record transaction
(schema `TX_SCHEMA`); chunks are put first, so a crash in between leaves only unreferenced
objects. Loaded leaves keep their identity, so re-saving an unchanged text writes nothing.
`tx_load` validates the header, totals and every chunk's hash, length and newline count before
building; corruption is `TS_IO`/`TS_INVALID`, never wrong text. A single storage owner calls
`tx_store` (it records identities on shared leaves).

## Evidence

`tests/core_text.sh` / `core.text`. The 20000-edit differential (typing, backspace, paste up to
6000 units, large cuts) runs against a flat model. Each edit checks length and newlines; every
97 edits it also checks full content, structure, line queries and chunk access. Eight retained
snapshots must stay unchanged across later edits. Durable checks: 300 keystrokes into a 1M-unit
text write at most 2 chunks per save (307 in total); a loaded text re-saves with 0 writes;
manifest and chunk tampering is rejected. Four reader threads verify snapshots while the owner
edits and releases. CI runs it under ASan/UBSan and TSan. Mutants: 12 tried, 10 caught. The two
survivors are a split-boundary change that is equivalent (handled by the recursion) and a
window extension that only matters when both seam leaves are small at the start of a text.

Pixel bundle `ee9f13e5c01b9e0758c20fa9053d0eecd222eb59488af46d876c59498f753bbc`: random
1-unit edit 8.1 us at 64K units, 13.4 us at 4M units, against 262 us for a flat array at 4M.
Receipt in `out/evidence/core.text.json`.

## Not yet built

Adopted 2026-10-28: TDOC v2 documents (`td_save_text`/`td_load_text`) and the draft queue's live
value. Note that a split joins its right part into the following leaf when they fit, so an edit
can rewrite one neighbouring chunk (the durable typing test bounds this at 2 chunks per save).
Also missing: lazy chunk loading for texts larger than memory, and
retention/collection of unreferenced chunk objects, which must trace every manifest that
history and captured requests still reference.
