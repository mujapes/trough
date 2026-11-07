/* Native shaping (docs/NATIVE_TEXT.md). The control flow follows AOSP Minikin (Apache-2.0),
 * which TextRunShaper uses: Layout::doLayoutRunCached splits a run into pieces at word breaks
 * (LayoutSplitter, LayoutUtils.cpp), LayoutPiece itemizes each piece to fonts, splits font runs
 * into script runs (ScriptUtils.cpp) and shapes them with HarfBuzz at a 24.8 fixed-point scale
 * (LayoutCore.cpp). Fonts come from the same system fallback through AFontMatcher. */
#include "shape.h"
#include <android/font.h>
#include <android/font_matcher.h>
#include <hb.h>
#include <ft2build.h>
#include FT_FREETYPE_H
#include FT_MULTIPLE_MASTERS_H
#include FT_TRUETYPE_TABLES_H
#include <unicode/uscript.h>
#include <fcntl.h>
#include <math.h>
#include <limits.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <stdatomic.h>

#define MAX_FACES 256
#define MAX_AXES 8
typedef struct {
    char path[256]; uint32_t index, naxes; uint32_t tags[MAX_AXES]; float values[MAX_AXES]; float size;
    hb_font_t *font;
    FT_Library ftlib;
    FT_Face ftface;
    pthread_mutex_t metrics_lock;
    int metrics_error;
    FT_Int32 load_flags;
    sh_metrics metrics;
} face;

/* Vertical metric formulas adapted from Skia (Copyright 2006 The Android Open
 * Source Project), BSD license in native/text/SKIA_LICENSE.
 * SkScalerContext_FreeType::generateFontMetrics (Android 16 release),
 * restricted to the five vertical metrics exposed by Paint.FontMetrics. */
static int vertical_metrics(face *f) {
    FT_Face ft = f->ftface;
    float top, ascent, descent, bottom, leading;
    if (FT_IS_SCALABLE(ft)) {
        float upem = ft->units_per_EM;
        if (upem == 0) return -1;
        TT_OS2 *os2 = FT_Get_Sfnt_Table(ft, ft_sfnt_os2);
        if (os2 && os2->version != 0xffff && (os2->fsSelection & (1 << 7))) {
            ascent = -(float)os2->sTypoAscender / upem;
            descent = -(float)os2->sTypoDescender / upem;
            leading = (float)os2->sTypoLineGap / upem;
        } else {
            ascent = -(float)ft->ascender / upem;
            descent = -(float)ft->descender / upem;
            leading = (float)(ft->height + (ft->descender - ft->ascender)) / upem;
        }
        top = -(float)ft->bbox.yMax / upem;
        bottom = -(float)ft->bbox.yMin / upem;
    } else {
        float ppem = ft->size->metrics.y_ppem;
        if (ppem == 0) return -1;
        ascent = -(float)ft->size->metrics.ascender / (ppem * 64);
        descent = -(float)ft->size->metrics.descender / (ppem * 64);
        leading = (float)ft->size->metrics.height / (ppem * 64) + ascent - descent;
        top = ascent; bottom = descent;
    }
    if (leading < 0) leading = 0;
    /* SkScalerContextRec::computeMatrices uses unit scale for a singular (zero
     * text-size) matrix. Glyphs still have zero advance; font metrics use 1 px. */
    float scale = f->size == 0 ? 1 : f->size;
    f->metrics = (sh_metrics){top * scale, ascent * scale, descent * scale,
                              bottom * scale, leading * scale};
    return 0;
}

struct sh_context {
    pthread_mutex_t lock;
    face faces[MAX_FACES];
    size_t nfaces, references;
    AFontMatcher *matcher;
    hb_language_t language;
    hb_font_funcs_t *metrics_funcs;
    int ready, retired;
};
static sh_context default_context = {.lock = PTHREAD_MUTEX_INITIALIZER};
static _Atomic size_t owned_contexts, mapped_fonts;
static _Thread_local char last_error[384];
const char *sh_error(void) { return last_error; }

/* Android Minikin overrides HarfBuzz's outline advances with Skia/FreeType metrics.
 * Default anti-aliased Paint uses normal hinting, no autohint and no embedded bitmaps.
 * HarfBuzz still owns substitutions, positioning adjustments and bitmap-emoji advances. */
static float metric_advance(face *c, hb_codepoint_t glyph) {
    if (c->size == 0) return 0;
    int err = FT_Load_Glyph(c->ftface, glyph, c->load_flags | FT_LOAD_BITMAP_METRICS_ONLY);
    if (err) {
        c->metrics_error = 1;
        snprintf(last_error, sizeof last_error, "FT_Load_Glyph %s glyph=%u error=%d", c->path, glyph, err);
        return 0;
    }
    return (float)c->ftface->glyph->advance.x / 64.0f;
}

static hb_position_t glyph_advance(hb_font_t *font, void *data, hb_codepoint_t glyph, void *user) {
    (void)font; (void)user;
    return (hb_position_t)(metric_advance(data, glyph) * 256.0f + 0.5f);
}

static hb_bool_t glyph_origin(hb_font_t *font, void *data, hb_codepoint_t glyph,
                             hb_position_t *x, hb_position_t *y, void *user) {
    (void)font; (void)data; (void)glyph; (void)user;
    *x = 0; *y = 0;
    return 1;
}

static int context_init(sh_context *ctx, const char *locales) {
    pthread_mutex_lock(&ctx->lock);
    if (!ctx->ready) {
        ctx->matcher = AFontMatcher_create();
        if (!ctx->matcher) { pthread_mutex_unlock(&ctx->lock); return -1; }
        if (locales && *locales) AFontMatcher_setLocales(ctx->matcher, locales);
        char first[64] = {0};
        if (locales) { size_t n = strcspn(locales, ","); if (n >= sizeof first) n = sizeof first - 1; memcpy(first, locales, n); }
        ctx->language = first[0] ? hb_language_from_string(first, -1) : HB_LANGUAGE_INVALID;
        ctx->metrics_funcs = hb_font_funcs_create();
        hb_font_funcs_set_glyph_h_advance_func(ctx->metrics_funcs, glyph_advance, NULL, NULL);
        /* Match Minikin's scalar (+0.5) fixed-point conversion. HarfBuzz's batched
         * fallback uses this same callback for FreeType metrics. */
        hb_font_funcs_set_glyph_h_origin_func(ctx->metrics_funcs, glyph_origin, NULL, NULL);
        hb_font_funcs_make_immutable(ctx->metrics_funcs);
        ctx->ready = 1;
    }
    pthread_mutex_unlock(&ctx->lock);
    return 0;
}

typedef struct { void *address; size_t length; } font_mapping;
static void unmap(void *value) {
    font_mapping *mapping = value;
    munmap(mapping->address, mapping->length);
    free(mapping);
    atomic_fetch_sub(&mapped_fonts, 1);
}

/* A new reference to the font file's blob: one mapping per file, shared by its faces. */
static hb_blob_t *file_blob(sh_context *ctx, const char *path) {
    for (size_t f = 0; f < ctx->nfaces; f++)
        if (!strcmp(ctx->faces[f].path, path)) return hb_face_reference_blob(hb_font_get_face(ctx->faces[f].font));
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return NULL;
    struct stat st;
    void *p = fstat(fd, &st) == 0 && st.st_size > 0 ? mmap(NULL, (size_t)st.st_size, PROT_READ, MAP_PRIVATE, fd, 0) : MAP_FAILED;
    close(fd);
    if (p == MAP_FAILED) return NULL;
    if ((uint64_t)st.st_size > UINT_MAX) { munmap(p, (size_t)st.st_size); return NULL; }
    font_mapping *mapping = malloc(sizeof *mapping);
    if (!mapping) { munmap(p, (size_t)st.st_size); return NULL; }
    *mapping = (font_mapping){p, (size_t)st.st_size};
    atomic_fetch_add(&mapped_fonts, 1);
    hb_blob_t *blob = hb_blob_create(p, (unsigned)st.st_size, HB_MEMORY_MODE_READONLY, mapping, unmap);
    if (!hb_blob_get_length(blob)) { hb_blob_destroy(blob); return NULL; }
    return blob;
}

/* The face for a matched AFont at size (under the context lock): registered once per identity. */
static int face_of(sh_context *ctx, const AFont *af, float size) {
    const char *path = AFont_getFontFilePath(af);
    uint32_t index = (uint32_t)AFont_getCollectionIndex(af), naxes = (uint32_t)AFont_getAxisCount(af);
    if (naxes > MAX_AXES) naxes = MAX_AXES;
    for (size_t f = 0; f < ctx->nfaces; f++) {
        face *c = &ctx->faces[f];
        if (c->index != index || c->naxes != naxes || c->size != size || strcmp(c->path, path)) continue;
        int same = 1;
        for (uint32_t a = 0; a < naxes; a++) same &= c->tags[a] == AFont_getAxisTag(af, a) && c->values[a] == AFont_getAxisValue(af, a);
        if (same) return (int)f;
    }
    if (ctx->nfaces == MAX_FACES || strlen(path) >= sizeof ctx->faces[0].path) return -1;
    hb_blob_t *blob = file_blob(ctx, path);
    if (!blob) return -1;
    face *c = &ctx->faces[ctx->nfaces];
    memset(c, 0, sizeof *c);
    strcpy(c->path, path); c->index = index; c->naxes = naxes; c->size = size;
    hb_variation_t vars[MAX_AXES];
    for (uint32_t a = 0; a < naxes; a++) {
        c->tags[a] = AFont_getAxisTag(af, a); c->values[a] = AFont_getAxisValue(af, a);
        vars[a].tag = c->tags[a]; vars[a].value = c->values[a];
    }
    hb_face_t *hf = hb_face_create(blob, index);
    c->font = hb_font_create(hf);
    hb_face_destroy(hf);
    if (naxes) hb_font_set_variations(c->font, vars, naxes);
    /* Minikin: ppem = size, scale = HBFloatToFixed(size) = size * 2^8 (24.8 positions). */
    hb_font_set_ppem(c->font, (unsigned)size, (unsigned)size);
    hb_font_set_scale(c->font, (int)scalbnf(size, 8), (int)scalbnf(size, 8));
    /* Minikin uses HarfBuzz advances for CBDT. sbix uses Skia's FreeType metrics. */
    hb_blob_t *color = hb_face_reference_table(hb_font_get_face(c->font), HB_TAG('C', 'B', 'D', 'T'));
    int bitmap = hb_blob_get_length(color) != 0;
    hb_blob_destroy(color);
    int ft_error = 0;
    {
        unsigned length;
        const char *bytes = hb_blob_get_data(blob, &length);
        if ((ft_error = FT_Init_FreeType(&c->ftlib))
            || (ft_error = FT_New_Memory_Face(c->ftlib, (const FT_Byte *)bytes, length, index, &c->ftface))) goto fail;
        if (naxes) {
            FT_MM_Var *axes = NULL;
            if ((ft_error = FT_Get_MM_Var(c->ftface, &axes))) goto fail;
            FT_Fixed *values = calloc(axes->num_axis, sizeof *values);
            if (!values) { FT_Done_MM_Var(c->ftlib, axes); goto fail; }
            for (FT_UInt a = 0; a < axes->num_axis; a++) {
                values[a] = axes->axis[a].def;
                for (uint32_t v = 0; v < naxes; v++) if (axes->axis[a].tag == c->tags[v])
                    values[a] = (FT_Fixed)(c->values[v] * 65536.0f);
            }
            int err = FT_Set_Var_Design_Coordinates(c->ftface, axes->num_axis, values);
            free(values); FT_Done_MM_Var(c->ftlib, axes);
            if (err) { ft_error = err; goto fail; }
        }
        c->load_flags = FT_LOAD_DEFAULT | FT_LOAD_NO_AUTOHINT | FT_LOAD_NO_BITMAP | FT_LOAD_IGNORE_GLOBAL_ADVANCE_WIDTH;
        /* Skia resolves a singular text-size matrix to unit scale before setting
         * FreeType's size or choosing a bitmap strike. Keep the HarfBuzz scale
         * and glyph_advance's zero-size rule unchanged. */
        float ft_size = size == 0 ? 1 : size;
        if (FT_IS_SCALABLE(c->ftface)) {
            if ((ft_error = FT_Set_Char_Size(c->ftface, (FT_F26Dot6)(ft_size * 64), (FT_F26Dot6)(ft_size * 64), 72, 72))) goto fail;
        } else {
            /* Skia chooses the smallest strike at least as large as requested, or the
             * largest available strike if all are smaller, and scales its metrics. */
            FT_Pos requested = (FT_Pos)(ft_size * 64), chosen = 0;
            FT_Int strike = -1;
            for (FT_Int i = 0; i < c->ftface->num_fixed_sizes; i++) {
                FT_Pos ppem = c->ftface->available_sizes[i].y_ppem;
                if (ppem == requested) { strike = i; break; }
                if ((chosen < requested && chosen < ppem)
                    || (requested < ppem && ppem < chosen)) { chosen = ppem; strike = i; }
            }
            if (strike < 0 || (ft_error = FT_Select_Size(c->ftface, strike))) goto fail;
            /* Skia calls FT_Set_Transform before loading the glyph: FreeType scales
             * its advance in 16.16 and rounds to 26.6. Scaling afterward in float
             * loses this quantization and differs from the Pixel by up to 1/128 px. */
            FT_Matrix matrix = {
                (FT_Fixed)((ft_size / c->ftface->size->metrics.x_ppem) * 65536.0f), 0,
                0, (FT_Fixed)((ft_size / c->ftface->size->metrics.y_ppem) * 65536.0f)
            };
            FT_Set_Transform(c->ftface, &matrix, NULL);
            c->load_flags = (c->load_flags & ~FT_LOAD_NO_BITMAP) | FT_LOAD_COLOR;
        }
        if (vertical_metrics(c)) goto fail;
        if (pthread_mutex_init(&c->metrics_lock, NULL)) goto fail;
        if (!bitmap) {
            hb_font_t *sub = hb_font_create_sub_font(c->font);
            hb_font_set_funcs(sub, ctx->metrics_funcs, c, NULL);
            hb_font_destroy(c->font); c->font = sub;
        }
    }
    hb_blob_destroy(blob);
    hb_font_make_immutable(c->font);
    return (int)ctx->nfaces++;
fail:
    snprintf(last_error, sizeof last_error, "font setup %s error=%d", path, ft_error);
    if (c->ftface) FT_Done_Face(c->ftface);
    if (c->ftlib) FT_Done_FreeType(c->ftlib);
    hb_font_destroy(c->font); hb_blob_destroy(blob);
    return -1;
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

static void emit(sh_run *out, uint32_t f, uint32_t g, uint32_t cluster, float x, float y, float advance) {
    if (out->count < out->capacity) out->glyphs[out->count] = (sh_glyph){f, g, cluster, x, y, advance};
    out->count++;
}

/* LayoutPiece: buf[0, n) is the piece's context, [start, start + count) the piece. */
static int shape_piece(sh_context *ctx, const uint16_t *buf, size_t n, size_t start, size_t count, int rtl, float size,
                       hb_buffer_t *b, float *x, sh_run *out, size_t context_offset, size_t run_start) {
    /* Itemize the piece (FontCollection::itemize over the piece only). */
    struct { size_t s, e; int f; } items[64]; size_t nitems = 0;
    pthread_mutex_lock(&ctx->lock);
    for (size_t p = 0; p < count;) {
        uint32_t run = 0;
        AFont *af = AFontMatcher_match(ctx->matcher, "sans-serif", buf + start + p, (uint32_t)(count - p), &run);
        if (!af) { pthread_mutex_unlock(&ctx->lock); return -1; }
        int f = face_of(ctx, af, size);
        AFont_close(af);
        if (f < 0 || run == 0 || run > count - p || nitems == 64) { pthread_mutex_unlock(&ctx->lock); return -1; }
        if (nitems && items[nitems - 1].f == f && items[nitems - 1].e == p) items[nitems - 1].e = p + run;
        else { items[nitems].s = p; items[nitems].e = p + run; items[nitems].f = f; nitems++; }
        p += run;
    }
    pthread_mutex_unlock(&ctx->lock);
    static const hb_feature_t chws = {HB_TAG('c', 'h', 'w', 's'), 1, 0, ~0u};
    for (size_t k = 0; k < nitems; k++) {
        size_t r = rtl ? nitems - 1 - k : k;
        hb_font_t *font = ctx->faces[items[r].f].font;   /* registered faces never move */
        /* Minikin LayoutPiece extends spacing once per selected font run,
         * including spaces and controls except runs consisting only of LF/CR. */
        for (size_t i = items[r].s; i < items[r].e; ++i) {
            uint16_t ch = buf[start + i];
            if (ch != '\n' && ch != '\r') {
                sh_metrics m = ctx->faces[items[r].f].metrics;
                if (m.ascent < out->extent.ascent) out->extent.ascent = m.ascent;
                if (m.descent > out->extent.descent) out->extent.descent = m.descent;
                break;
            }
        }
        /* Font runs are relative to the requested piece, not its surrounding context.
         * Read scripts from that same origin; HarfBuzz still receives the whole context.
         * Otherwise partial mixed-script runs select a preceding character's script and
         * can split an Indic cluster before shaping it. */
        for (size_t s = items[r].s; s < items[r].e;) {
            hb_script_t script;
            size_t e = script_run(buf + start, items[r].e, s, &script);
            hb_buffer_clear_contents(b);
            hb_buffer_set_script(b, script);
            hb_buffer_set_direction(b, rtl ? HB_DIRECTION_RTL : HB_DIRECTION_LTR);
            if (ctx->language != HB_LANGUAGE_INVALID) hb_buffer_set_language(b, ctx->language);
            hb_buffer_add_utf16(b, buf, (int)n, (unsigned)(start + s), (int)(e - s));
            face *c = &ctx->faces[items[r].f];
            if (c->ftface) { pthread_mutex_lock(&c->metrics_lock); c->metrics_error = 0; }
            hb_shape(font, b, &chws, 1);
            if (c->ftface) {
                int failed = c->metrics_error;
                pthread_mutex_unlock(&c->metrics_lock);
                if (failed) return -1;
            }
            unsigned ng;
            hb_glyph_info_t *info = hb_buffer_get_glyph_infos(b, &ng);
            hb_glyph_position_t *pos = hb_buffer_get_glyph_positions(b, NULL);
            for (unsigned g = 0; g < ng; g++) {
                if (info[g].cluster < start || info[g].cluster >= start + count) return -1;
                uint32_t cluster = (uint32_t)(context_offset + info[g].cluster);
                float advance = scalbnf((float)pos[g].x_advance, -8);
                emit(out, (uint32_t)items[r].f, info[g].codepoint, cluster,
                     *x + scalbnf((float)pos[g].x_offset, -8), -scalbnf((float)pos[g].y_offset, -8), advance);
                if (out->advances) out->advances[cluster - run_start] += advance;
                *x += advance;
            }
            s = e;
        }
    }
    return 0;
}

/* Layout::doLayoutRunCached over LayoutSplitter's (context, piece) pairs. */
static int shape_impl(sh_context *ctx, const uint16_t *t, size_t n, size_t start, size_t end, int rtl, float size, sh_run *out) {
    last_error[0] = 0;
    if (!ctx->ready || !out || (!t && n) || (out->capacity && !out->glyphs) || n > INT_MAX
        || start > end || end > n || (out->advances && out->advance_capacity < end - start)
        || !isfinite(size) || size <= 0 || size >= (float)(INT_MAX / 256)) return -1;
    out->count = 0; out->advance = 0;
    out->extent = (sh_extent){0, 0};
    if (out->advances) memset(out->advances, 0, (end - start) * sizeof *out->advances);
    /* MinikinUtils::prepareMinikinPaint truncates size unless Paint has linear metrics.
     * This operator's current configuration is the default, non-linear Paint. */
    size = truncf(size);
    hb_buffer_t *b = hb_buffer_create();
    float x = 0;
    int rc = 0;
    if (start == end) goto done;
    if (!rtl) {
        size_t pos = start;
        size_t cs = prev_break(t, n, pos == end ? pos : pos + 1), ce = next_break(t, n, pos);
        size_t ps = pos, pe = ce < end ? ce : end;
        while (pos != end) {
            if ((rc = shape_piece(ctx, t + cs, ce - cs, ps - cs, pe - ps, 0, size, b, &x, out, cs, start))) break;
            pos = pe; cs = pos; ce = next_break(t, n, pos); ps = pos; pe = ce < end ? ce : end;
        }
    } else {
        size_t pos = end;
        size_t cs = prev_break(t, n, pos), ce = next_break(t, n, pos == 0 ? 0 : pos - 1);
        size_t ps = cs > start ? cs : start, pe = pos;
        while (pos != start) {
            if ((rc = shape_piece(ctx, t + cs, ce - cs, ps - cs, pe - ps, 1, size, b, &x, out, cs, start))) break;
            pos = ps; cs = prev_break(t, n, pos); ce = pos; ps = cs > start ? cs : start; pe = pos;
        }
    }
done:
    hb_buffer_destroy(b);
    out->advance = x;
    return rc;
}

static int face_name(sh_context *ctx, uint32_t f, char *o, size_t cap) {
    pthread_mutex_lock(&ctx->lock);
    if (f >= ctx->nfaces) { pthread_mutex_unlock(&ctx->lock); return -1; }
    face *c = &ctx->faces[f];
    int w = snprintf(o, cap, "%s#%u#", c->path, c->index);
    for (uint32_t a = 0; a < c->naxes && w >= 0 && (size_t)w < cap; a++)
        w += snprintf(o + w, cap - (size_t)w, "%s%c%c%c%c=%g", a ? "," : "", (char)(c->tags[a] >> 24), (char)(c->tags[a] >> 16), (char)(c->tags[a] >> 8), (char)c->tags[a], (double)c->values[a]);
    pthread_mutex_unlock(&ctx->lock);
    return w;
}



static int face_formats(sh_context *ctx, uint32_t f) {
    pthread_mutex_lock(&ctx->lock);
    int formats = -1;
    if (f < ctx->nfaces) {
        static const hb_tag_t tags[] = {HB_TAG('C','B','D','T'), HB_TAG('s','b','i','x'), HB_TAG('C','O','L','R'), HB_TAG('S','V','G',' ')};
        formats = 0;
        for (unsigned i = 0; i < 4; i++) {
            hb_blob_t *table = hb_face_reference_table(hb_font_get_face(ctx->faces[f].font), tags[i]);
            if (hb_blob_get_length(table)) formats |= 1 << i;
            hb_blob_destroy(table);
        }
    }
    pthread_mutex_unlock(&ctx->lock);
    return formats;
}

/* One owner reference plus one reference per successful shaped output. Retirement stops
 * admission; destruction waits until every output (including an in-flight shape) releases. */
static void context_release(sh_context *ctx) {
    pthread_mutex_lock(&ctx->lock);
    int destroy = --ctx->references == 0;
    pthread_mutex_unlock(&ctx->lock);
    if (!destroy) return;
    for (size_t i = 0; i < ctx->nfaces; i++) {
        face *f = &ctx->faces[i];
        if (f->ftface) { FT_Done_Face(f->ftface); pthread_mutex_destroy(&f->metrics_lock); }
        if (f->ftlib) FT_Done_FreeType(f->ftlib);
        hb_font_destroy(f->font);
    }
    hb_font_funcs_destroy(ctx->metrics_funcs);
    AFontMatcher_destroy(ctx->matcher);
    pthread_mutex_destroy(&ctx->lock);
    free(ctx);
    atomic_fetch_sub(&owned_contexts, 1);
}

sh_context *sh_context_create(const char *locales) {
    sh_context *ctx = calloc(1, sizeof *ctx);
    if (!ctx) return NULL;
    if (pthread_mutex_init(&ctx->lock, NULL)) { free(ctx); return NULL; }
    if (context_init(ctx, locales)) { pthread_mutex_destroy(&ctx->lock); free(ctx); return NULL; }
    ctx->references = 1;
    atomic_fetch_add(&owned_contexts, 1);
    return ctx;
}

void sh_context_retire(sh_context *ctx) {
    if (!ctx) return;
    pthread_mutex_lock(&ctx->lock);
    ctx->retired = 1;
    pthread_mutex_unlock(&ctx->lock);
    context_release(ctx);
}

int sh_shape_owned(sh_context *ctx, const uint16_t *t, size_t n, size_t start, size_t end,
                   int rtl, float size, sh_run *out) {
    if (!ctx || !out || out->owner) return -1;
    pthread_mutex_lock(&ctx->lock);
    if (ctx->retired || ctx->references == SIZE_MAX) { pthread_mutex_unlock(&ctx->lock); return -1; }
    ctx->references++;
    pthread_mutex_unlock(&ctx->lock);
    int rc = shape_impl(ctx, t, n, start, end, rtl, size, out);
    if (rc) context_release(ctx);
    else out->owner = ctx;
    return rc;
}

void sh_run_release(sh_run *run) {
    if (!run || !run->owner) return;
    sh_context *ctx = run->owner;
    run->owner = NULL;
    context_release(ctx);
}

int sh_run_face_name(const sh_run *run, uint32_t f, char *out, size_t capacity) {
    return run && run->owner ? face_name(run->owner, f, out, capacity) : -1;
}
int sh_run_face_metrics(const sh_run *run, uint32_t f, sh_metrics *out) {
    if (!run || !run->owner || !out) return -1;
    sh_context *ctx = run->owner;
    pthread_mutex_lock(&ctx->lock);
    int result = -1;
    if (f < ctx->nfaces) { *out = ctx->faces[f].metrics; result = 0; }
    pthread_mutex_unlock(&ctx->lock);
    return result;
}
int sh_default_metrics(sh_context *ctx, float size, sh_metrics *out) {
    if (!ctx || !out || !isfinite(size) || size <= 0 || size >= (float)(INT_MAX / 256)) return -1;
    pthread_mutex_lock(&ctx->lock);
    int result = -1;
    if (!ctx->retired) {
        const uint16_t space = ' ';
        uint32_t units = 0;
        AFont *font = AFontMatcher_match(ctx->matcher, "sans-serif", &space, 1, &units);
        if (font) {
            int id = units == 1 ? face_of(ctx, font, size) : -1;
            AFont_close(font);
            if (id >= 0) { *out = ctx->faces[id].metrics; result = 0; }
        }
    }
    pthread_mutex_unlock(&ctx->lock);
    return result;
}
size_t sh_context_live(void) { return atomic_load(&owned_contexts); }
size_t sh_mapping_live(void) { return atomic_load(&mapped_fonts); }

/* Existing diagnostics retain the process-owned registry. Native layout uses explicit owners. */
int sh_init(const char *locales) { return context_init(&default_context, locales); }
int sh_shape(const uint16_t *t, size_t n, size_t start, size_t end, int rtl, float size, sh_run *out) {
    if (out && out->owner) return -1;
    return shape_impl(&default_context, t, n, start, end, rtl, size, out);
}
int sh_face_name(uint32_t f, char *out, size_t cap) { return face_name(&default_context, f, out, cap); }
int sh_face_formats(uint32_t f) { return face_formats(&default_context, f); }
size_t sh_faces(void) {
    pthread_mutex_lock(&default_context.lock);
    size_t n = default_context.nfaces;
    pthread_mutex_unlock(&default_context.lock);
    return n;
}
