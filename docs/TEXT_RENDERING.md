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

## Not yet built

Subpixel glyph positioning (positions are snapped to pixels now); colour glyphs such as emoji,
which need an RGBA atlas; atlas eviction and luminance-bucket keys; line breaking, paragraph
bidi and grapheme-aware cursoring in the layout layer; and scaling with display density and
font scale.
