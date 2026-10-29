# Viewport scene from derived layouts

The first view pipeline on the stage-3 machinery, built in `native/android/derive.c` and
checked by `android.view`. Paragraph text cells and a viewport cell feed derived layouts,
which feed a tree of height summaries, which feeds the scene: the glyphs visible in the
viewport. A presenter draws the scene with the GPU glyph renderer.

## Nodes

- **layout(i):** paragraph i at the width, through the platform stack
  ([LAYOUT_DERIVE.md](LAYOUT_DERIVE.md)). A blob.
- **viewport cell:** (anchor paragraph, offset into it, height). This is scroll anchoring:
  what is shown is defined relative to a paragraph, so edits above it cannot move it.
- **scene:** stacks exact layouts from the anchor (its top at -offset) until the viewport is
  full, and emits the glyphs whose line intersects it. Output is a blob: count, then (face,
  glyph, x, y) in viewport coordinates. It reads only the viewport cell and the visible layouts.
- **summary(level, j):** the total *estimated* height of 16 children. An estimate is
  ceil(units x 17 / width) lines of 48 px: pure and cheap. It serves the scrollbar and jumps;
  nothing is drawn at estimated positions. Level-0 summaries read their 16 text cells and the
  width and estimate inline (operator fusion). One node per estimate made the first jump
  cost 1200 tiny jobs and 900 ms of owner scheduling.
- **jump:** `deriveJump(per mille)` demands the root summary and walks the tree on the owner
  thread to the paragraph at that fraction of the estimated height. That paragraph becomes
  the anchor.
- **static operators:** layout and summary are declared static (`dv_set_static`), so a
  recompute keeps all old dependencies and runs once rather than once per unverified child.
  In `core.derive` the same mechanism cuts restarts per job from 0.354 to 0.290, and a mutant
  that skips verifying such hints is caught.

## Oracle (`android.view`, 600 paragraphs, width 1000, viewport 1080x1400, 2 workers)

Every step's scene is byte-identical to a flat Java walk from the anchor over directly
computed layouts. The jump's anchor equals a flat Java scan of the same estimates. Operator
runs per step, the same in two runs on bundle `96f2a2bd429f`:

| step | layout | summary | scene | estimated | time |
|---|---|---|---|---|---|
| top of document | 16 (8 paragraphs) | 0 | 10 | 0 | 95-199 ms |
| jump to the middle | **12** (6 paragraphs) | 84 | 7 | 600 | 105-253 ms |
| edit below the viewport | 0 | 1 | 0 | 8 | 1-2 ms |
| edit in the viewport | 1 | 1 | 5 | 16 | 7-17 ms |
| edit above the anchor, adding lines | **0** | 3 | **0** | 16 | 1-3 ms |
| scroll 40 px within the anchor | 0 | 0 | 7 | 0 | 6-9 ms |

Before anchoring, positions were exact sums of layout heights. Jumping to the middle then
laid out 504 paragraphs (1008 runs, 1.8-3.0 s), and an edit above the viewport that added
lines re-ran the scene, moving everything shown.

- **Pixels:** the scene (about 1430 glyphs, 73 unique masks), drawn by the GPU renderer, is
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

## Scrolling by pixels

`deriveScroll(dy)` moves the anchor across *exact* heights on the owner thread. A paragraph
whose layout is not yet clean (crossed upward, or past the viewport) is demanded, settled and
released, and the walk continues. The scroll's position lives in the call until the walk
completes, so the viewport cell is written once. Writing intermediate anchors re-derived the
scene at every pause: 95 scene runs for one 2600 px scroll, now 8. Bundle `aaec04031403`, from
the anchor plus 40 px:

| dy | anchor, offset (= exact-height Java walk) | layout runs | scene runs | time |
|---|---|---|---|---|
| +700 | 307, 17 | 8 | 8 | 78 ms |
| +1300 | 312, 81 | 10 | 8 | 87 ms |
| -2600 | 301, 574 | 6 | 8 | 144 ms |
| -900 | 299, 102 | 4 | 6 | 60 ms |
| +333 | 301, 7 | 0 | 5 | 49 ms |

Each resulting scene is byte-identical to the reference at that anchor and offset.

## Known costs and their workarounds
- **Dynamic restarts.** The scene is dynamic, so after a change it re-asks for its visible
  layouts one by one (5-7 runs). Fixes: batched needs, or hinting old dependencies that are
  already memoized.
- **Presentation:** `ps_frame_present` draws a ring frame into the next swapchain image,
  with a per-slot acquire semaphore and the same uploads and fences. If acquire fails, the
  frame's uploads are still submitted, so residency stays true. `deriveSurfaceOpen` puts the
  view on an Android Surface, and `android.viewsurface` checks the presented image and
  measures pacing. It needs Trough visible and has not run yet.

## Background prefetch

`derivePrefetch(before, after)` demands the layouts of paragraphs `[anchor - before, anchor +
after]` at `DV_BACKGROUND` and moves that window whenever the viewport cell is written, by
`deriveViewport` or the end of a scroll. Paragraphs that enter it are demanded, and paragraphs
that leave it are released. It uses the graph's levels ([DERIVE.md](DERIVE.md), [EXEC.md](EXEC.md)):
- a scroll that crosses a paragraph with no clean layout pauses (stats[20] counts pauses), and
  demands that paragraph at `DV_URGENT`. It waits with `ex_settle_at(DV_URGENT)`, so
  prefetching never delays a crossing;
- `deriveSettleVisible`, jumps and the mask settle in `deriveFrame` wait for `DV_VISIBLE` and
  more urgent work only; `deriveSettle` still waits for everything;
- the executor keeps one worker free of background work, which matters little at 2 workers.

With prefetch off (0, 0), the scheduling is the one measured in the tables above. The
`android.view` step "prefetch_scrolls" turns on a window of 8 above and 24 below. It then
scrolls 700, 1300, 900 and -600 px. Each scene must equal the reference, and no scroll may
pause. Pixel bundle `79bdc5b7eedb`:

| scroll | pauses | scroll call | scene visible | window layouts after |
|---|---|---|---|---|
| +700 | 0 | 0.41 ms | 0.84 ms | 2 |
| +1300 | 0 | 0.13 ms | 0.68 ms | 10 |
| +900 | 0 | 0.18 ms | 1.06 ms | 8 |
| -600 | 0 | 0.24 ms | 1.19 ms | 0 |

Without prefetch, the same kind of scroll takes 15-35 ms and pauses up to 11 times (the
"scrolls" step). Filling the first 33-paragraph window costs 26 layout runs in 32 ms, in the
background. The first Pixel run used 2 above. The -600 px scroll crossed 3 paragraphs and
paused once, which was the oracle's window, not the mechanism.

## On the swapchain (`android.viewsurface`)

Pixel bundle `79bdc5b7eedb`, first run (Trough visible): 5 derived frames presented to the
Surface, 0 bad pixels, max channel diff 1. Build takes 11.0 ms for the first frame (71 uploads)
and 1.3-1.8 ms after it. Pacing: 120 frames at 16.20 ms per frame, with a worst frame call of
20.5 ms and 0 failed presents.

## Flings and display timing

The presenter enables `VK_GOOGLE_display_timing` when the device has it. Each present carries
its frame number, and `ps_present_timing` / `derivePresentTimes` return when each frame
actually reached the display. `android.viewsurface` flings 80 px per frame for 120 frames,
first without prefetch, then with a window of 8 above and 32 below. Each frame scrolls, settles
what is visible (`deriveSettleVisible`) and presents. Pixel, 2 runs (bundle `672ee13db0b7`):

| | scroll + settle avg / worst | skipped vsyncs (display) | present margin |
|---|---|---|---|
| no prefetch | 3.0-3.7 / 17.6 ms | 1, 0 | 43 ms |
| prefetch | 1.3-1.4 / 4.2-5.1 ms | 0, 0 | 43 ms |

The refresh period is 16.65 ms. Timing frame-call intervals on the CPU reported 1-16 "missed"
frames: those were queueing jitter, not dropped vsyncs. Display timing is the measure. The
3-frame ring hides most layout stalls, at a cost of latency. Each frame is ready about 43 ms
(2.6 refreshes) before its vsync, so a scroll reaches the glass about 3 frames late. Next:
pace frame starts from the display timing so the margin shrinks to the build time.

## Frame pacing (latency)

`deriveFrameBegin` runs before a frame samples input. With `derivePace` on, it sleeps until
`vsync - budget`. The vsync is the one after the previous frame's, extrapolated from the newest
displayed frame at one refresh per frame. The budget is the CPU work estimate (start to present,
EWMA mean + 2 deviations) plus a learned slack for GPU and compositor latch. The slack starts at
2 ms. It grows by 2 ms whenever a frame lands more than half a refresh after its target, and
shrinks by 0.25 ms while frames are more than 4 ms early. Each frame's start time is recorded,
so display timing gives the true input-to-display latency. Pixel, fling with prefetch, bundle `b373f4aac810`:

| | latency p50 / p90 | skipped vsyncs / 120 | margin |
|---|---|---|---|
| unpaced (3-frame ring full) | 75.2 / 76.5 ms | 0 | 42.8 ms |
| paced, fixed 2 ms slack (earlier bundle) | 22.1 / 33.4 ms | 37-38 | 6.7 ms |
| paced, learned slack | 27.9 / 38.5 ms | 3 | 8.3 ms |
| paced, slack carried over | 29.9 / 40.3 ms | 1 | 10.5 ms |

The slack settles near 19-21 ms, about one refresh. That refresh is the compositor's latch
before scan-out, which the CPU estimate cannot see. The controller still saws: it misses its own
target 10-17 times per 120 frames, against 1-3 vsyncs actually skipped.

**Mode 2, platform deadlines.** A looper thread keeps the latest `AChoreographer_postVsyncCallback`
frame timelines: the compositor latch deadline and the expected present time. The API is
Android 13+, resolved with dlsym because the library targets API 28. `deriveFrameBegin` aims at
the earliest deadline the work can meet. Its slack starts at 1 ms, grows 1 ms per frame shown
more than half a refresh after the expected time, and shrinks 0.05 ms per on-time frame. Same
fling, bundle `394101b00c66`:

| mode | latency p50 / p90 / max | skipped vsyncs | own-target misses | slack |
|---|---|---|---|---|
| 0 unpaced | 75.1 / 76.9 / 78.6 ms | 0 | - | - |
| 1 learned grid | 27.8 / 38.1 / 76.5 ms | 3 | 16 | 17.8 ms |
| 1, slack carried | 29.7 / 39.8 / 75.0 ms | 2 | 13 | 21.3 ms |
| 2 platform deadline (learning) | 54.3 / 60.9 / 72.9 ms | 1 | 33 | 29.6 ms |
| 2, slack carried | **34.6 / 36.9 / 49.7 ms** | 1 | **0** | 23.6 ms |

The choreographer's deadline is about one refresh optimistic for a Vulkan swapchain producer.
Its timelines are made for producers that tag frames with `ASurfaceTransaction_setFrameTimeline`,
while a swapchain buffer must also clear its GPU fence and BufferQueue. So mode 2 still learns
about 24 ms of slack. Once learned, it is the steadiest mode: 0 misses of its own target and a
latency spread of 34.6-49.7 ms. Mode 1 is lower at the median, but it has longer tails and more
skipped vsyncs. Mode 2's first-run overshoot came from the +1 ms/-0.05 ms steps starting at 1 ms.
The slack now starts at 1.5 refreshes. Bundle `bbbd9d3616b5`: a cold mode 2 run measures
29.6 / 32.4 / 43.3 ms with 0 own-target misses and 1 skipped vsync. A second run (13 ms slack)
measures 36.6 / 39.9 / 40.7 ms, also with 0 misses; why the lower slack gave a higher median is
unexplained. Mode 1 in the same session: 28.6 / 39.7 / 75.4 ms, 4 skipped. Mode 2 is the default
candidate: its median is similar and its tails are bounded.

The first run of this oracle hung the scenario thread. A fling started past the last paragraph,
and `scroll_step` read a node index out of range. `deriveViewport` now clamps the anchor, and
the scroll walk stops at or past the last paragraph.

## Touch

`DerivedView` (hot/src/.../DerivedView.java) is the document view as a component: a SurfaceView,
a render thread and touch input. The UI thread only accumulates a pending pixel delta and the
time of the oldest unshown event, plus a release velocity from `VelocityTracker`. The render
thread owns an `OverScroller` and the deriver's frame calls. Each frame runs
`deriveFrameBegin` (paced), takes all input that has arrived, then calls `deriveFrameInput`
(the event time), `deriveScroll`, `deriveSettleVisible` and `deriveFrame`. When no input is
pending and no fling runs, it makes no frames.

`android.touch` dispatches real MotionEvents through the view: DOWN, then 30 MOVEs of 24 px
8 ms apart, then UP, so the view flings. It runs with prefetch (8/32) and pace mode 2. Pixel
bundle `662358b66638`, first run:
- the final position (191, 104) equals an exact-height walk of every consumed pixel: 720 px
  of drag plus 556 px of fling over 616 ms. The scene equals the reference;
- 50 frames, 0 skipped vsyncs, 0 pace misses, 0 layout pauses, 0 frames after idle;
- frame start to display: 31.6 ms p50, 33.4 ms max. Touch to display, from the oldest event a
  frame consumes: 46.6 / 49.0 / 49.9 ms (p50/p90/max), over 17 input frames. Moves 8 ms apart
  are batched into 16.65 ms frames, and an event waits up to one frame for the next paced start.

## Caret

The selection is a world cell holding `(paragraph << 32 | offset) + 1`, or 0 for none. Two
operators derive what is drawn:
- **caret(paragraph << 32 | offset):** static, reads the paragraph's text and the width. Java
  `DeriveLayout.caret` gives TextLayout's primary-caret x (Layout.getPrimaryHorizontal's rule)
  and the line's top and bottom;
- **overlay:** reads the viewport and selection cells and walks exact layout heights from the
  anchor to the selected paragraph, like the scene. It emits the caret as a rectangle, 2 px
  wide and centred on x, in viewport coordinates. The scene's glyph format is unchanged; the
  frame draws overlay rectangles under the glyphs (`ps_frame_present` and
  `ps_frame_submit` take rects).

`deriveTap(x, y)` finds the paragraph and line under a viewport point from the clean layouts
the scene read. TextLayout's tap rule (Layout.getOffsetForHorizontal) then gives the offset,
through Java on the calling thread, and the result becomes the selection. `deriveSelect` sets
it directly. `DerivedView` treats a touch that ends inside the touch slop as a tap: the render
thread applies it after any pending scroll, then makes a frame.

`android.caret` runs offscreen against StaticLayout over the same paragraphs. Pixel bundle
`c2b6112a36a6`:
- 24 taps over the viewport (margins and past line ends included): 0 paragraph or offset
  mismatches against a flat walk of StaticLayout heights, `getLineForVertical` and
  `getOffsetForHorizontal`. The caret rectangle equals `getPrimaryHorizontal` ± 1 and the
  line box exactly (max difference 0);
- no caret move ran any layout or scene job. A new position costs 2 caret runs (one restart
  for text and width) and 1-4 overlay runs, depending on which layouts the previous selection
  read. A position already derived costs no caret run, and the same selection again costs
  nothing. An edit of the caret's paragraph re-runs its caret once, and the caret stays exact;
- the frame with the caret against the platform drawing it (no antialiasing, the pixel-centre
  rule) under the glyphs: 0 bad pixels, max channel diff 1. The first run differed because
  `new Paint()` antialiases by default on current Android, which was the reference's fault.

`android.touch` now ends with a tap after the fling (bundle `0772e5703eae`). It selects
(194, 271), as StaticLayout does at the scrolled position, draws one caret, runs no layout or
scene job, and does not scroll.

## Selection

A range adds a second cell, `sel_anchor` (same encoding as the selection). 0, or equal to the
selection, means a caret; `deriveSelect` clears it, and `deriveSelectRange(pa, oa, pb, ob)` sets
both ends. Then, for each visible paragraph in the range, the overlay reads a
**highlight(paragraph << 42 | flags << 40 | start << 20 | end)** node. That node is static, reads
text and width, and comes from Java `TextLayout.selection`. The flags record whether the range
began in an earlier paragraph or continues into a later one, because paragraphs are separate
layouts. The overlay emits rectangles of kind 1, which the frame draws translucent (the caret
colour at alpha 89) under the glyphs. The overlay format is now count, then (x0, y0, x1, y1,
kind).

`TextLayout.selection` follows Layout.getSelection. On a line, each directional run is clipped
to the range, from the leading edge of its first character to the trailing edge of its last. A
range over several lines fills its first line from the text to the far edge, its middle lines
completely and its last line from the near edge. android.selection found two whitespace rules
that are not in the documentation:
- on a left-to-right line, trailing whitespace (not drawn) is selected, past the drawn text, by its advance;
- on a right-to-left line, trailing whitespace is not selected. It hangs at the left, and the
  first line's fill toward the left edge stops short by its advance.

`android.selection` (offscreen). The reference is one StaticLayout over the joined paragraphs
and its `getSelectionPath`. Both sides are filled as paths without antialiasing and compared
pixel for pixel. Pixel bundle `50ac6bd7990f`:
- 160 random ranges over 1-3 paragraphs, all scripts of the corpus: 0 differing pixels. Before
  the two whitespace rules, 29 ranges differed, all by one space's advance on one line;
- six derived overlays in the viewport (viewport offset 37; ranges reversed, empty, beginning
  above the anchor, crossing 5 paragraphs): 0 differing pixels, and no layout or scene job;
- extending the focus: 2 highlight runs, 4 overlay runs, 0 layout, 0 scene;
- frame with the highlight under the glyphs against the platform: 0 bad pixels, max channel diff 2;
- setting a caret afterwards clears the range: no highlight, one caret.
