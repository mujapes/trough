# Viewport scene from derived layouts

The first view pipeline on the stage-3 machinery, built in `native/android/derive.c` and
checked by `android.view`. Paragraph text cells and a viewport cell feed derived layouts,
which feed a tree of height summaries, which feeds the scene: the glyphs visible in the
viewport. A presenter draws the scene with the GPU glyph renderer.

## Nodes

- **layout(i):** paragraph i at the width, through the platform stack
  ([LAYOUT_DERIVE.md](LAYOUT_DERIVE.md)). A blob.
- **summary(level, j):** the total height of 16 children: paragraph layouts at level 0,
  summaries above. A scalar, so a recomputed summary with the same height cuts off.
- **scene:** reads the viewport cell (scroll and height), then walks the tree depth-first in
  order. A subtree's total is read only when the subtree starts above the scroll position
  (to skip it or enter it). A subtree that starts inside the viewport is entered directly,
  and the walk stops at the viewport's end. Output is a blob: count, then (face, glyph, x, y)
  in viewport coordinates, for glyphs whose line intersects the viewport.
- **static operators:** layout and summary are declared static (`dv_set_static`), so a
  recompute keeps all old dependencies and runs once rather than once per unverified child.
  In `core.derive` the same mechanism cuts restarts per job from 0.354 to 0.290, and a mutant
  that skips verifying such hints is caught.

## Oracle (`android.view`, 600 paragraphs, width 1000, viewport 1080x1400, 2 workers)

Every step's scene is byte-identical to a flat Java walk over directly computed layouts.
Operator runs per step, the same in three runs on bundle `07ee550eef85`:

| step | layout | summary | scene | time |
|---|---|---|---|---|
| top of document | 16 (8 paragraphs) | 0 | 10 | 65-111 ms |
| scroll to the middle | 1008 | 68 | 13 | 1.8-3.0 s |
| edit below the viewport | 0 | 0 | 0 | about 1 ms |
| edit in the viewport | 1 | 1 | 1 | 3-14 ms |
| edit above, same height | 1 | 1 | **0** (cutoff) | 3-5 ms |
| edit above, taller | 1 | 2 | 12 | 12-14 ms |

- **Pixels:** the final scene (1372 glyphs, 73 unique masks), drawn by the GPU renderer, is
  compared with the platform drawing the same glyphs at the same snapped positions: 0 bad
  pixels, max channel diff 1.
- **Trace:** each job's operator, key and outcome are available through `deriveTrace`. The
  trace showed that an early count mismatch was the oracle's own race (it snapshotted after
  the demand had started work), not the graph.

## Derived frames

`deriveFrame` turns the settled scene into a GPU frame, using only derived values and the
fenced atlas:
- every distinct glyph's **mask node** (face and glyph id as key, no dependencies) is demanded;
  each is rasterised once by the platform on a worker, as a canonical blob;
- each glyph is placed with `at_use`, keyed by the mask's blob handle, and new masks are
  uploaded as rectangles;
- the frame is submitted through the presenter's frame ring ([ATLAS.md](ATLAS.md)).

`android.view` renders 5 viewports, scrolling through the document and back. Each frame must
match the platform drawing the same scene glyphs. Bundle `2168390adcb1`:

| frame | glyphs | mask settle | build | uploads | atlas hits (cumulative) | bad pixels |
|---|---|---|---|---|---|---|
| 1 (middle) | 1162 | 20.0 ms | 30.6 ms | 71 | 1091 | 0 |
| 2 (+300 px) | 1215 | 0.4 ms | 4.2 ms | 0 | 2306 | 0 |
| 3 (+900 px) | 1161 | 0.6 ms | 3.9 ms | 0 | 3467 | 0 |
| 4 (+2400 px) | 1198 | 0.4 ms | 7.3 ms | 0 | 4665 | 0 |
| 5 (middle again) | 1162 | 0.4 ms | 2.4 ms | 0 | 5827 | 0 |

The max channel diff is 1 throughout. The corpus's 73 distinct glyphs are rasterised once
(73 mask runs in total), and later frames only reuse resident masks. The frame wait shown in
the receipt (18-94 ms) includes the oracle's full 1080x1400 readback, so it is not a
presentation cost.

## Known costs and their workarounds

- **Exact positions are expensive.** Scrolling to the middle lays out every paragraph above
  the viewport, plus the rest of the top-level subtree that holds the scroll line (504
  paragraphs here). Editors avoid this with estimated heights for unmeasured content, refined
  as layouts arrive and with scroll anchoring so content does not jump (CodeMirror's height
  map, Zed's sum tree). In this model an estimate is a cheap node a summary can use until the
  real layout is clean.
- **Dynamic restarts.** The scene is dynamic, so after a height change above the viewport it
  re-asks for its visible layouts one by one (12 runs, about 14 ms). Fixes: batched needs, or
  hinting old dependencies that are already memoized.
- **Presentation:** frames are drawn offscreen and read back. The swapchain path
  (`ps_present`) must take the frame ring and its atlas; `android.surface` needs Trough
  visible to check it.
