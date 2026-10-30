/* Native shaping (docs/NATIVE_TEXT.md). The control flow follows AOSP Minikin (Apache-2.0),
 * which TextRunShaper uses: Layout::doLayoutRunCached splits a run into pieces at word breaks
 * (LayoutSplitter, LayoutUtils.cpp), LayoutPiece itemizes each piece to fonts, splits font runs
 * into script runs (ScriptUtils.cpp) and shapes them with HarfBuzz at a 24.8 fixed-point scale
 * (LayoutCore.cpp). Fonts come from the same system fallback through AFontMatcher. */
#include "shape.h"
#include <android/font.h>
#include <android/font_matcher.h>
#include <hb.h>
#include <unicode/uscript.h>
#include <fcntl.h>
#include <math.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#define MAX_FACES 256
#define MAX_AXES 8
typedef struct {
    char path[256]; uint32_t index, naxes; uint32_t tags[MAX_AXES]; float values[MAX_AXES]; float size;
    hb_font_t *font;
} face;

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static face faces[MAX_FACES];
static size_t nfaces;
static AFontMatcher *matcher;                     /* not thread-safe: used under lock */
static hb_language_t language;
static int ready;

int sh_init(const char *locales) {
    pthread_mutex_lock(&lock);
    if (!ready) {
        matcher = AFontMatcher_create();
        if (locales && *locales) AFontMatcher_setLocales(matcher, locales);
        char first[64] = {0};
        if (locales) { size_t n = strcspn(locales, ","); if (n >= sizeof first) n = sizeof first - 1; memcpy(first, locales, n); }
        language = first[0] ? hb_language_from_string(first, -1) : HB_LANGUAGE_INVALID;
        ready = 1;
    }
    pthread_mutex_unlock(&lock);
    return 0;
}

static void unmap(void *p) { (void)p; }   /* font files stay mapped for the process: faces are never removed */

/* A new reference to the font file's blob: one mapping per file, shared by its faces. */
static hb_blob_t *file_blob(const char *path) {
    for (size_t f = 0; f < nfaces; f++)
        if (!strcmp(faces[f].path, path)) return hb_face_reference_blob(hb_font_get_face(faces[f].font));
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return NULL;
    struct stat st;
    void *p = fstat(fd, &st) == 0 && st.st_size > 0 ? mmap(NULL, (size_t)st.st_size, PROT_READ, MAP_PRIVATE, fd, 0) : MAP_FAILED;
    close(fd);
    if (p == MAP_FAILED) return NULL;
    return hb_blob_create(p, (unsigned)st.st_size, HB_MEMORY_MODE_READONLY, p, unmap);
}

/* The face for a matched AFont at size (under lock): registered once per identity. */
static int face_of(const AFont *af, float size) {
    const char *path = AFont_getFontFilePath(af);
    uint32_t index = (uint32_t)AFont_getCollectionIndex(af), naxes = (uint32_t)AFont_getAxisCount(af);
    if (naxes > MAX_AXES) naxes = MAX_AXES;
    for (size_t f = 0; f < nfaces; f++) {
        face *c = &faces[f];
        if (c->index != index || c->naxes != naxes || c->size != size || strcmp(c->path, path)) continue;
        int same = 1;
        for (uint32_t a = 0; a < naxes; a++) same &= c->tags[a] == AFont_getAxisTag(af, a) && c->values[a] == AFont_getAxisValue(af, a);
        if (same) return (int)f;
    }
    if (nfaces == MAX_FACES || strlen(path) >= sizeof faces[0].path) return -1;
    hb_blob_t *blob = file_blob(path);
    if (!blob) return -1;
    face *c = &faces[nfaces];
    memset(c, 0, sizeof *c);
    strcpy(c->path, path); c->index = index; c->naxes = naxes; c->size = size;
    hb_variation_t vars[MAX_AXES];
    for (uint32_t a = 0; a < naxes; a++) {
        c->tags[a] = AFont_getAxisTag(af, a); c->values[a] = AFont_getAxisValue(af, a);
        vars[a].tag = c->tags[a]; vars[a].value = c->values[a];
    }
    hb_face_t *hf = hb_face_create(blob, index);
    hb_blob_destroy(blob);
    c->font = hb_font_create(hf);
    hb_face_destroy(hf);
    if (naxes) hb_font_set_variations(c->font, vars, naxes);
    /* Minikin: ppem = size, scale = HBFloatToFixed(size) = size * 2^8 (24.8 positions). */
    hb_font_set_ppem(c->font, (unsigned)size, (unsigned)size);
    hb_font_set_scale(c->font, (int)scalbnf(size, 8), (int)scalbnf(size, 8));
    hb_font_make_immutable(c->font);
    return (int)nfaces++;
}

/* LayoutUtils.cpp */
static int break_after(uint16_t c) {
    return c == ' ' || (c >= 0x2000 && c <= 0x200A) || c == 0x3000
        || (c >= 0x2066 && c <= 0x2069) || (c >= 0x202A && c <= 0x202E) || c == 0x200E || c == 0x200F;
}
static int break_before(uint16_t c) { return break_after(c) || (c >= 0x3400 && c <= 0x9FFF); }
static size_t prev_break(const uint16_t *t, size_t n, size_t off) {
    if (off == 0) return 0;
    if (off > n) off = n;
    if (break_before(t[off - 1])) return off - 1;
    for (size_t i = off - 1; i > 0; i--) if (break_before(t[i]) || break_after(t[i - 1])) return i;
    return 0;
}
static size_t next_break(const uint16_t *t, size_t n, size_t off) {
    if (off >= n) return n;
    if (break_after(t[off])) return off + 1;
    for (size_t i = off + 1; i < n; i++) if (break_before(t[i])) return i;
    return n;
}

static uint32_t decode(const uint16_t *t, size_t end, size_t i, size_t *len) {
    uint32_t c = t[i]; *len = 1;
    if (c >= 0xD800 && c < 0xDC00 && i + 1 < end && t[i + 1] >= 0xDC00 && t[i + 1] < 0xE000) {
        c = 0x10000 + ((c - 0xD800) << 10) + (t[i + 1] - 0xDC00); *len = 2;
    } else if (c >= 0xD800 && c < 0xE000) c = 0xFFFD;   /* isolated surrogate */
    return c;
}
/* ScriptUtils.cpp: the script run starting at pos, within [pos, end). */
static size_t script_run(const uint16_t *t, size_t end, size_t pos, hb_script_t *script) {
    size_t len;
    uint32_t cp = decode(t, end, pos, &len);
    UErrorCode e = U_ZERO_ERROR;
    UScriptCode cur = uscript_getScript((UChar32)cp, &e);
    if (U_FAILURE(e)) cur = USCRIPT_INVALID_CODE;
    hb_unicode_funcs_t *uf = hb_unicode_funcs_get_default();
    hb_script_t hb = hb_unicode_script(uf, cp);
    size_t i;
    for (i = pos + len; i < end; i += len) {
        cp = decode(t, end, i, &len);
        e = U_ZERO_ERROR;
        UScriptCode next = uscript_getScript((UChar32)cp, &e);
        if (U_FAILURE(e)) next = USCRIPT_INVALID_CODE;
        if (cur != next) {
            if (cur == USCRIPT_INHERITED || cur == USCRIPT_COMMON) { cur = next; hb = hb_unicode_script(uf, cp); }
            else if (next == USCRIPT_INHERITED || next == USCRIPT_COMMON) continue;
            else break;
        }
    }
    *script = cur == USCRIPT_INHERITED ? HB_SCRIPT_COMMON : hb;
    return i;
}

static void emit(sh_run *out, uint32_t f, uint32_t g, float x, float y) {
    if (out->count < out->capacity) out->glyphs[out->count] = (sh_glyph){f, g, x, y};
    out->count++;
}

/* LayoutPiece: buf[0, n) is the piece's context, [start, start + count) the piece. */
static int shape_piece(const uint16_t *buf, size_t n, size_t start, size_t count, int rtl, float size,
                       hb_buffer_t *b, float *x, sh_run *out) {
    /* Itemize the piece (FontCollection::itemize over the piece only). */
    struct { size_t s, e; int f; } items[64]; size_t nitems = 0;
    pthread_mutex_lock(&lock);
    for (size_t p = 0; p < count;) {
        uint32_t run = 0;
        AFont *af = AFontMatcher_match(matcher, "sans-serif", buf + start + p, (uint32_t)(count - p), &run);
        int f = face_of(af, size);
        AFont_close(af);
        if (f < 0 || run == 0 || nitems == 64) { pthread_mutex_unlock(&lock); return -1; }
        if (nitems && items[nitems - 1].f == f && items[nitems - 1].e == p) items[nitems - 1].e = p + run;
        else { items[nitems].s = p; items[nitems].e = p + run; items[nitems].f = f; nitems++; }
        p += run;
    }
    pthread_mutex_unlock(&lock);
    static const hb_feature_t chws = {HB_TAG('c', 'h', 'w', 's'), 1, 0, ~0u};
    for (size_t k = 0; k < nitems; k++) {
        size_t r = rtl ? nitems - 1 - k : k;
        hb_font_t *font = faces[items[r].f].font;   /* registered faces never move */
        /* Script runs over the font run. Minikin's ScriptText reads the context buffer at the
         * piece-relative offsets (LayoutCore.cpp); kept so the scripts match where the piece
         * starts inside its context. */
        for (size_t s = items[r].s; s < items[r].e;) {
            hb_script_t script;
            size_t e = script_run(buf, items[r].e, s, &script);
            hb_buffer_clear_contents(b);
            hb_buffer_set_script(b, script);
            hb_buffer_set_direction(b, rtl ? HB_DIRECTION_RTL : HB_DIRECTION_LTR);
            if (language != HB_LANGUAGE_INVALID) hb_buffer_set_language(b, language);
            hb_buffer_add_utf16(b, buf, (int)n, (unsigned)(start + s), (int)(e - s));
            hb_shape(font, b, &chws, 1);
            unsigned ng;
            hb_glyph_info_t *info = hb_buffer_get_glyph_infos(b, &ng);
            hb_glyph_position_t *pos = hb_buffer_get_glyph_positions(b, NULL);
            for (unsigned g = 0; g < ng; g++) {
                emit(out, (uint32_t)items[r].f, info[g].codepoint, *x + scalbnf((float)pos[g].x_offset, -8), -scalbnf((float)pos[g].y_offset, -8));
                *x += scalbnf((float)pos[g].x_advance, -8);
            }
            s = e;
        }
    }
    return 0;
}

/* Layout::doLayoutRunCached over LayoutSplitter's (context, piece) pairs. */
int sh_shape(const uint16_t *t, size_t n, size_t start, size_t end, int rtl, float size, sh_run *out) {
    if (!ready || start > end || end > n) return -1;
    out->count = 0; out->advance = 0;
    hb_buffer_t *b = hb_buffer_create();
    float x = 0;
    int rc = 0;
    if (start == end) goto done;
    if (!rtl) {
        size_t pos = start;
        size_t cs = prev_break(t, n, pos == end ? pos : pos + 1), ce = next_break(t, n, pos);
        size_t ps = pos, pe = ce < end ? ce : end;
        while (pos != end) {
            if ((rc = shape_piece(t + cs, ce - cs, ps - cs, pe - ps, 0, size, b, &x, out))) break;
            pos = pe; cs = pos; ce = next_break(t, n, pos); ps = pos; pe = ce < end ? ce : end;
        }
    } else {
        size_t pos = end;
        size_t cs = prev_break(t, n, pos), ce = next_break(t, n, pos == 0 ? 0 : pos - 1);
        size_t ps = cs > start ? cs : start, pe = pos;
        while (pos != start) {
            if ((rc = shape_piece(t + cs, ce - cs, ps - cs, pe - ps, 1, size, b, &x, out))) break;
            pos = ps; cs = prev_break(t, n, pos); ce = pos; ps = cs > start ? cs : start; pe = pos;
        }
    }
done:
    hb_buffer_destroy(b);
    out->advance = x;
    return rc;
}

int sh_face_name(uint32_t f, char *o, size_t cap) {
    pthread_mutex_lock(&lock);
    if (f >= nfaces) { pthread_mutex_unlock(&lock); return -1; }
    face *c = &faces[f];
    int w = snprintf(o, cap, "%s#%u#", c->path, c->index);
    for (uint32_t a = 0; a < c->naxes && w >= 0 && (size_t)w < cap; a++)
        w += snprintf(o + w, cap - (size_t)w, "%s%c%c%c%c=%g", a ? "," : "", (char)(c->tags[a] >> 24), (char)(c->tags[a] >> 16), (char)(c->tags[a] >> 8), (char)c->tags[a], (double)c->values[a]);
    pthread_mutex_unlock(&lock);
    return w;
}

size_t sh_faces(void) { pthread_mutex_lock(&lock); size_t n = nfaces; pthread_mutex_unlock(&lock); return n; }
