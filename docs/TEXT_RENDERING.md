# Text rendering

2026-10-28. DESIGN.md asks for text "primitives selected for conformance and license" under
Trough's own scene, input and lifetime contracts.

## Decision: platform shaping and glyph rasterisation, Trough GPU composition

- **Shaping:** `android.graphics.text.TextRunShaper` (API 31+). It is Android's shaping
  stack (Minikin and HarfBuzz) with system font fallback. It returns positioned glyph IDs,
  each with its `Font`. Bidi runs are shaped per direction; paragraph-level bidi and line
  breaking belong to Trough's layout layer.
- **Rasterisation:** `Canvas.drawGlyphs` of a single glyph into an `ALPHA_8` mask, which is
  the platform rasteriser (Skia and FreeType) with the system's hinting and contrast.
- **Composition:** Trough's GPU renderer (`native/present/scene.c`). Masks are packed into an
  R8 atlas, and glyph instances are drawn by `texelFetch` at integer positions, after any
  rectangles in the same pass.

This gives the platform's conformance (scripts, fallback, emoji fonts' availability) without
vendoring HarfBuzz/FreeType and a C++ runtime into the bionic build. The text and glyph caches
stay under Trough's control; the platform only produces immutable masks.

Constraint found on the device: the platform's masks depend on the text colour's luminance
(Skia's contrast/gamma preblend). A black mask reused for coloured text came out up to 40
levels too light. Masks are therefore keyed by colour; the production cache should key by
Skia's luminance bucket instead of the exact colour.

## Evidence

- `android.glyphs` (synthetic oracle) on Mali-G710: 745k pixels, max channel difference 1.
  On lavapipe a one-texel addressing mutant gives 377k large differences.
- `android.text` (bundle `f339ce750037...`) draws seven runs: Latin with ligatures and
  kerning, Latin and digits, Arabic RTL, Devanagari, CJK and Japanese, Greek, Hebrew RTL.
  That is 156 glyphs (128 unique) from 11 fonts, in seven colours, three of them translucent.
  - Every pixel matches the platform drawing the same glyphs directly: 0 bad pixels, maximum
    channel difference 2, over 41k inked pixels.
  - GPU composition takes 0.58 ms.

## Layout

`TextLayout.java` turns paragraphs into lines and positioned glyphs with the platform stack:
- `java.text.Bidi` gives the paragraph direction (first strong) and level runs.
- `MeasuredText` (style runs per bidi run) feeds `LineBreaker` with the high-quality strategy.
  Hyphenation is off until hyphen edits are drawn.
- Each line's runs are put in visual order and shaped with `TextRunShaper`. An RTL paragraph
  is aligned to its width, and trailing whitespace is not drawn.
- Line metrics follow `StaticLayout`'s rule: the paint's integer metrics, extended (never
  shrunk) by fallback fonts on the line. Lines therefore stack on whole pixels.

`android.layout` compares against `StaticLayout` (same text, width, paint, strategy, direction
heuristic, no padding, fallback line spacing). Six texts (Latin paragraphs, Arabic, mixed
bidi with numbers, Devanagari, CJK with Latin, leading empty paragraphs) run at widths 240,
410, 655 and 1000 px. On the Pixel (bundle `bfe844c88fd9...`): 24 cases and 115 lines, with
every line start and baseline equal and 0 differing pixels when our positioned glyphs are
drawn by the platform. Two fixes came from this oracle, both now in the code: integer line
metrics, and the extend-never-shrink rule.

Carets and taps follow `Layout`'s own rules rather than a heuristic:
- `caretX` is the primary caret (`getPrimaryHorizontal`). It trails the previous character
  when that character's bidi level is lower than the level at the offset, and otherwise leads
  the character at the offset. At a line start the paragraph level stands in for the previous
  character, so a line that starts with an RTL run inside an LTR paragraph has its caret at the line edge.
- `offsetAt` is `getOffsetForHorizontal`: the cursor position (via `getTextRunCursor`) whose
  primary caret is nearest the tap. A wrapped line stops before its final character, while a
  paragraph's last line and the layout's last line reach their end. Earlier offsets win ties,
  except the final position. Candidates and their x are cached per line on the first tap.

The oracle checks every offset's caret and taps every 7 px on every line. The first Pixel run,
with a per-run `getOffsetForAdvance` and a "paragraph-direction run wins" caret, failed 2 of
2132 carets and 113 of 7653 taps: at the שלום boundaries, at a line starting with עברית, and at
the layout's last line end. Bundle `fc978451ba7c...`: 0 of 2132 and 0 of 7653.

## Not yet built

Subpixel glyph positioning on the GPU path (positions are snapped to pixels there, while
`TextLayout` keeps exact positions); hyphen edits; colour glyphs such as emoji,
which need an RGBA atlas; atlas eviction and luminance-bucket keys; caret movement by keyboard (visual left/right across bidi runs) and selection highlights; and scaling with display density and
font scale.
