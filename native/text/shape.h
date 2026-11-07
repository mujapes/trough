#ifndef TROUGH_SHAPE_H
#define TROUGH_SHAPE_H
/* Native shaping (docs/NATIVE_TEXT.md): system font fallback through the NDK's AFontMatcher,
 * HarfBuzz shaping, ICU script runs, driven the way Minikin drives them under TextRunShaper so
 * the result can be checked against the platform glyph for glyph. Thread-safe: faces are
 * shared, the matcher is serialized, buffers are per call. */
#include <stddef.h>
#include <stdint.h>

typedef struct sh_context sh_context;
typedef struct { float top, ascent, descent, bottom, leading; } sh_metrics;
typedef struct { float ascent, descent; } sh_extent;

typedef struct { uint32_t face, glyph, cluster; float x, y, advance; } sh_glyph;
typedef struct {
    sh_glyph *glyphs; size_t count, capacity;   /* caller storage; count may exceed capacity: retry larger */
    float advance;
    /* Optional caller-owned raw advances, indexed from the requested run's start.
     * Cluster indices in glyph records are absolute offsets in the input context. */
    float *advances;
    size_t advance_capacity;
    sh_context *owner; /* output lease; initialize to NULL, release before reuse; never copy */
    sh_extent extent; /* Minikin font extent, excluding LF/CR-only font runs */
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
/* Diagnostic for the most recent call on this thread. */
const char *sh_error(void);
/* Diagnostic format bits: CBDT=1, sbix=2, COLR=4, SVG=8; -1 for unknown face. */
int sh_face_formats(uint32_t face);
/* Explicit native operator ownership. Context identities scope glyph face indices. The
 * caller holds the owner until retire (exactly once), which consumes that reference and
 * rejects new work. Every successful shape, including a capacity probe, owns an output
 * lease until sh_run_release. Release before retry/reusing the output. A failed shape
 * owns no lease. Text/glyph/advance buffers remain caller-owned. Do not copy a sh_run lease.
 * Concurrent jobs must start while the caller still owns the context. Retired contexts
 * can only be inspected through outstanding outputs, never a released raw pointer. */
sh_context *sh_context_create(const char *locales);
void sh_context_retire(sh_context *);
int sh_shape_owned(sh_context *, const uint16_t *, size_t, size_t, size_t, int, float, sh_run *);
void sh_run_release(sh_run *);
int sh_run_face_name(const sh_run *, uint32_t, char *, size_t);
/* Metrics at the resolved shaping size; the output lease keeps the face alive. */
int sh_run_face_metrics(const sh_run *, uint32_t face, sh_metrics *);
/* Default sans-serif Paint metrics at its requested (untruncated) size. Caller
 * owns the admitted context during this call; returned scalars retain nothing. */
int sh_default_metrics(sh_context *, float size, sh_metrics *);
/* Diagnostic: explicitly owned contexts still awaiting destruction; excludes legacy root. */
size_t sh_context_live(void);
size_t sh_mapping_live(void);
/* Device oracle; JSON result for the development transport. */
void sh_ownership_oracle(const char *locales, char *out, size_t capacity);
#endif
