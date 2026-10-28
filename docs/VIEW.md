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
- **Rasterising and atlas packing** are done by the oracle here. They belong to the presenter
  as glyph-mask nodes plus an atlas resource whose slots are leased until the GPU fence of
  the frame that used them.
