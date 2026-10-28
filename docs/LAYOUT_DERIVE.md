# Layout as a derivation operator

The first real operator on the executor ([EXEC.md](EXEC.md)): paragraph text layout through
the platform text stack ([TEXT_RENDERING.md](TEXT_RENDERING.md)), in process, in
`libtrough-android.so`.

## Shape

- `native/android/derive.c` owns a world with one text cell per paragraph and one width
  cell. A text cell's value is a UTF-16 blob handle; the cell holds a reference, and the
  replaced text is released. There is one layout node per paragraph. Everything else is the
  generic core (`world`, `derive`, `blobs`, `exec`), compiled into the bionic library by `native/bionic.sh`.
- The operator is a C trampoline on executor workers. Workers attach to the JVM through
  `ex_on_worker` start/stop hooks. The trampoline reads text and width from the job, asking
  for both together on the first run so there is a single restart, and builds a `jstring`
  from the held text blob. It then calls `DeriveLayout.layout(String, int)` and hands the
  returned bytes to the owner, which interns them.
- `DeriveLayout` and its method are resolved once from the calling Java thread and kept as a
  global reference. A natively attached worker thread cannot find hot-loaded classes.
- `DeriveLayout` gives each thread its own `Paint`, since Paint is not thread-safe. It
  serializes lines and glyphs, naming fonts by face.

## Font identity

`PositionedGlyphs.getFont` returns a new `Font` object on every shaping call, so keying by
object made equal layouts serialize differently. On the first device run, 0 of 240 layouts
matched and no duplicate shared a handle. `Font` is equal by value, but on the Pixel
`hashCode` costs 46 us per fresh wrapper, more than shaping the run itself (37 us), and
`equals` costs 9 us. Using them tripled the cost of layout. Faces are therefore keyed by
`getSourceIdentifier()` (1.3 us), TTC index, style and variation axes, with a per-thread
identity cache in front. The probe (`font_probe` in the receipt) keeps measuring these costs.

## Oracle (`android.derive`)

- **Corpus:** 240 mixed-script paragraphs (Latin, Hebrew, Arabic, Devanagari, CJK, Greek,
  digits); every 8th repeats an earlier one.
- **Identity:** every derived layout is byte-identical to `DeriveLayout.layout` run on the
  calling thread. This holds after the first settle, after an edit, after a width change and
  after re-demand: 240/240 each time. All 30 duplicates share one handle.
- **Exact job counts:** the first settle runs 480 jobs (one restart each). An edit runs 1, a
  width change 240 (one per demanded paragraph), edits to undemanded paragraphs 0, and
  re-demanding 120 runs 120.
- **Timing:** within one run, the executor with 1 worker is within about 7% of a direct loop
  (1098 ms against 1028 ms, bundle `c80af4157717`).
- **Scaling:** comparing executor workers with plain Java threads on the same split in the
  same run (bundle `a8bdc2b17b11`):

  | workers / threads | 1 | 2 | 4 | 8 |
  |---|---|---|---|---|
  | executor (ms) | 1126 | 1394 | 818 | 834 |
  | Java threads (ms) | 1102 | 1184 | 885 | 748 |

  Both stop at about 1.3-1.5x. The ceiling is in the platform text stack or the device, not
  in the executor. Absolute times swing about 2x between runs of the same bundle (direct loop:
  870 to 1987 ms), so only comparisons within one run mean anything.

## Where the ceiling is

`stage_scaling` times each platform stage alone over the corpus on 1 and 4 threads.
Two runs on bundle `c086af09cfa8`:

| stage (1 thread -> 4 threads) | run 1 | run 2 |
|---|---|---|
| shaping (`TextRunShaper`) | 136 -> 135 ms | 89 -> 137 ms |
| line breaking (`MeasuredText` + `LineBreaker`) | 83 -> 210 ms | 90 -> 122 ms |
| bidi (`java.text.Bidi`, pure Java) | 76 -> 17 ms | 40 -> 12 ms |

The pure-Java stage scales 3-4x; the native text stages do not scale at all. That is
consistent with minikin serializing these calls process-wide. A lock is the likely cause; a
native profile would confirm it. Consequences:
- operators that call the platform text stack get 1-2 workers, and parallelism goes to other work;
- a Trough-owned shaper (HarfBuzz and FreeType, built natively, with no process lock) is the
  workaround if layout throughput becomes the bottleneck. It must still match the platform's
  pixels, which is the text-rendering decision's oracle.

## Not yet built

- **Thermal state in receipts:** record thermal status and CPU frequency with every timing.
- **Granularity:** layout per line range for long paragraphs, so one edit does not re-lay a
  whole paragraph.
- **Next consumers:** scene compilation from layout blobs, then a glyph atlas as a resource
  with fence lifetime.
