# Paragraph order

2026-10-29. `native/core/order.{h,c}` is the position-indexed sequence VIEW.md "Paragraph
identity" called for. Today the derived view keeps one order cell per position, so inserting
or removing a paragraph rewrites every cell after it and re-estimates O(n / 16) summaries. The
document offset walks in INPUT.md "Document" are O(paragraphs) per IME call. The order tree
makes all of these O(log n).

## Structure

- A B+tree of paragraph ids with fan-out `OR_FAN` (16). Every node except the root holds at
  least 8 entries, and all leaves have the same depth.
- Each entry carries its subtree's paragraph count and UTF-16 unit total, so these are all
  O(log n):
  - position -> id (`or_at`);
  - position -> joined offset (`or_offset`);
  - joined offset -> (position, offset) (`or_locate`). A separator belongs to the end of the
    paragraph before it, and offsets past the end clamp;
  - `or_insert` and `or_remove`.
- Nodes live in caller-provided slots, and slot 0 is always the root. A root split moves both
  halves down into new slots. A root with one child takes over that child's contents.
- Owner-serialised. Insertion checks for room (one slot per level plus two) before changing
  anything, so `TR_CAPACITY` leaves the tree as it was.

## Publication

The derivation graph cannot read the owner's tree. Each node has a published form
(`or_encode`): a header (leaf flag, entry count), then per entry the id or child slot and the
paragraph count. Unit totals are not published, so `or_set_units` (a paragraph's length
changed) republishes nothing.

Every mutation marks the slots whose published form changed (`or_take_changed`): the path,
siblings touched by borrowing or merging, new slots, and released slots, which publish
nothing. Operators walk from slot 0 with `or_step`, which picks the entry holding a position
and makes the position relative to it.

## Evidence

`core.order` (`tests/core_order.sh`, `native/scenarios/order.c`) runs 40000 random operations
against a flat array. Phases grow the document, shrink it and mix both; ends are favoured, and
zero-length paragraphs appear. After every operation (in full every 7th, or always below 64
paragraphs) it checks:
- count and unit totals; the id, units and offset at every position; locate inside each
  paragraph, at its end, on the separator and past the document's end;
- B+tree invariants: occupancy, equal leaf depth, stored sums equal recomputed sums, and
  reachable slots plus free slots equal capacity (no leaks);
- publication: a copy updated only with the republished slots equals a fresh encoding of every
  slot, and `or_step` walks over that copy find every id;
- republished slots per operation are at most 3 x depth + 3, and length changes republish
  none.

VM results: up to 6148 paragraphs, depth 4, at most 7 slots republished (mean 3.4). At 100000
paragraphs an insert takes 0.31 us and a locate 0.29 us. ASan/UBSan are clean. Nine mutants,
each killed: a missing mark after borrow or merge, the separator rule, no rebalancing,
ancestor units not updated, offsets without separators, the collapsed root's slot leaked, no
capacity pre-check, and a stale count after a right borrow.

## Next

Adopt it in `native/android/derive.c`:
- order cells become node cells holding encoded blobs;
- level-0 and higher summaries become one summary per tree node (leaf: estimates of its
  paragraphs; internal: the sum of its children);
- `id_at` walks with `or_step`, and `find_anchor` descends the node summaries;
- `doc_state`, `doc_locate` and `to_global` use `or_offset` and `or_locate`.
