# Atlas residency

`native/core/atlas.{h,c}` decides where glyph masks and other small images live in a GPU
texture, and when a place may be reused. It is the first resource whose lifetime ends at a
GPU fence ([CONSTRUCTION.md](CONSTRUCTION.md) section 3: "buffer/fence lifetime semantics").
It makes no GPU calls: the presenter uploads into the rectangles it hands out and reports
fence completion.

## Contract

- **Frames:** numbered in submission order on one queue (`at_frame`, `at_submit`). Fences
  signal in that order, so `at_retire(n)` means every frame up to n has completed.
- **Placement:** `at_use(key, w, h)` places key in the current frame.
  - `TR_OK`: the key is resident at the returned rectangle, so no upload is needed.
  - `TR_STALE`: newly placed; the caller uploads w x h before submitting.
  - `TR_CAPACITY`: no space can be made without touching a frame in flight.
- **Space:** square pages are given on demand to one size class (cells of 16, 32, 64, 128 or
  256 px), so a freed cell fits any entry of its class and nothing fragments.
- **Lifetime:** a cell records the newest frame that sampled it. It can be overwritten only
  once that frame has retired. Eviction is least-recently-used among such cells. When a class
  has no free, retired or unassigned cell, a whole page of another class whose cells have all
  retired is reclaimed, so no class starves.
- **Index:** keys are opaque (a mask blob handle, or face plus glyph). The index is a
  fixed-size hash with tombstone rebuilds.

## Oracle (`core.atlas`, `tests/core_atlas.sh`)

A shadow GPU records which key was uploaded at each rectangle. Frames draw skewed key sets: a
hot set that drifts over time, plus a long tail, with 1 in 10 (or 1 in 3) keys large. Up to 3
frames are in flight, and fences retire in order at random times. Checks:
- an upload never overwrites a rectangle that an unretired frame samples for another key;
- every hit finds its key at the rectangle returned;
- every refusal is justified by brute force: all pages assigned, every cell of the class held
  by a frame in flight, and no other page wholly retired;
- live cells never overlap and stay inside the atlas.

Results (ASan/UBSan):
- 1024x1024, 4000 frames: 722k uses, hit rate 0.83, 124k evictions, 0 refusals.
- 512x512 under pressure, 4000 frames: 718k uses, hit rate 0.53, 226 pages reclaimed, 253k
  refusals, every one justified.
- Mutants: 6 of 6 caught. Eviction ignoring fences, reclamation ignoring fences, hits not
  marking the frame, eviction leaving the old key indexed, retiring the recording frame, no
  reclamation.

## Not yet built

- **Presenter integration:** glyph-mask nodes (derived and canonical), partial texture uploads
  (`vkCmdCopyBufferToImage` per rectangle), several frames in flight, and real fences feeding
  `at_retire`.
- **When refused:** split the frame, or fall back to drawing the glyph from a per-frame
  upload; never stall.
- **Cost:** placement scans the pages of a class (O(cells)). A per-class free list and an LRU
  heap are the fix if profiles show it.
