#ifndef TROUGH_SHAPE_H
#define TROUGH_SHAPE_H
/* Native shaping (docs/NATIVE_TEXT.md): system font fallback through the NDK's AFontMatcher,
 * HarfBuzz shaping, ICU script runs, driven the way Minikin drives them under TextRunShaper so
 * the result can be checked against the platform glyph for glyph. Thread-safe: faces are
 * shared, the matcher is serialized, buffers are per call. */
#include <stddef.h>
#include <stdint.h>

typedef struct { uint32_t face, glyph; float x, y; } sh_glyph;
typedef struct {
    sh_glyph *glyphs; size_t count, capacity;   /* caller storage; count may exceed capacity: retry larger */
    float advance;
} sh_run;

/* Process-wide state: the font registry and the matcher's locale list (BCP 47, comma separated,
 * as Paint.getTextLocales; the first locale tags the buffer). Idempotent; 0 on success. */
int sh_init(const char *locales);
/* Shapes text[start, end) inside the context text[0, length) in one direction, as
 * TextRunShaper.shapeTextRun with forced direction: pieces between word breaks (Minikin's
 * layout cache units) are itemized to fonts, split into script runs and shaped; positions start
 * at x = 0 and advance in logical order for LTR, from the end for RTL. size in pixels. */
int sh_shape(const uint16_t *text, size_t length, size_t start, size_t end, int rtl, float size, sh_run *out);
/* A face's identity: its font file, collection index and variation axes ("path#index#tag=value,...").
 * Returns the length written (truncated to cap), or -1 for an unknown id. */
int sh_face_name(uint32_t face, char *out, size_t cap);
/* Faces registered so far (each distinct file, index, axes and size). */
size_t sh_faces(void);
#endif
