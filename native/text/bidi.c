#include "bidi.h"
#include <limits.h>
#include <stdlib.h>
#include <unicode/ubidi.h>

struct bd_paragraph { UBiDi *paragraph, *line; const uint16_t *text; size_t length; uint8_t base; };

void bd_close(bd_paragraph *p) {
    if (!p) return;
    if (p->line) ubidi_close(p->line);
    if (p->paragraph) ubidi_close(p->paragraph);
    free(p);
}

bd_paragraph *bd_open(const uint16_t *text, size_t length) {
    if ((!text && length) || length > INT_MAX) return NULL;
    bd_paragraph *p = calloc(1, sizeof *p);
    if (!p) return NULL;
    p->length = length;
    p->text = text;
    if (!length) return p;
    UErrorCode error = U_ZERO_ERROR;
    p->paragraph = ubidi_openSized((int32_t)length, 0, &error);
    if (U_FAILURE(error) || !p->paragraph) goto fail;
    ubidi_setPara(p->paragraph, text, (int32_t)length, UBIDI_DEFAULT_LTR, NULL, &error);
    if (U_FAILURE(error)) goto fail;
    p->base = ubidi_getParaLevel(p->paragraph);
    p->line = ubidi_openSized((int32_t)length, 0, &error);
    if (U_FAILURE(error) || !p->line) goto fail;
    return p;
fail:
    bd_close(p);
    return NULL;
}

int bd_run_at(const bd_paragraph *p, size_t offset, bd_run *out) {
    if (!p || !out || offset >= p->length) return -1;
    int32_t end = 0;
    UBiDiLevel level = 0;
    ubidi_getLogicalRun(p->paragraph, (int32_t)offset, &end, &level);
    if (end <= (int32_t)offset || (size_t)end > p->length) return -1;
    *out = (bd_run){(uint32_t)offset, (uint32_t)end, level};
    return 0;
}

/* Android's layout direction arrays can span a bidi paragraph separator such
 * as CR even when its line breaker keeps that text on one visual line. ICU's
 * setLine rejects that range. Use the resolved paragraph levels, Android's
 * trailing space/tab reset, and UAX #9 L2 run reordering in this case only. */
static int multi_paragraph_line(bd_paragraph *p, size_t start, size_t end, bd_result *out) {
    UErrorCode error = U_ZERO_ERROR;
    int32_t limit;
    ubidi_getParagraph(p->paragraph, (int32_t)start, NULL, &limit, NULL, &error);
    if (U_FAILURE(error) || end <= (size_t)limit) return -1;
    size_t length = end-start;
    for (size_t i = 0; i < length; ++i)
        out->levels[i] = ubidi_getLevelAt(p->paragraph, (int32_t)(start+i));
    if ((out->levels[length-1] & 1) != (p->base & 1)) {
        size_t visible = end;
        while (visible > start && (p->text[visible-1] == ' ' || p->text[visible-1] == '\t')) --visible;
        for (size_t i = visible; i < end; ++i) out->levels[i-start] = p->base;
    }
    size_t count = 1;
    for (size_t i = 1; i < length; ++i) if (out->levels[i] != out->levels[i-1]) ++count;
    out->count = count;
    if (out->capacity < count) return 0;
    size_t used = 0, from = 0;
    int minimum = 126, maximum = 0;
    for (size_t i = 1; i <= length; ++i) {
        if (i != length && out->levels[i] == out->levels[from]) continue;
        uint8_t level = out->levels[from];
        out->runs[used++] = (bd_run){(uint32_t)(start+from), (uint32_t)(start+i), level};
        if (level < minimum) minimum = level;
        if (level > maximum) maximum = level;
        from = i;
    }
    for (int level = maximum; level >= (minimum | 1); --level) {
        size_t i = 0;
        while (i < count) {
            if (out->runs[i].level < (uint32_t)level) { ++i; continue; }
            size_t end_run = i+1;
            while (end_run < count && out->runs[end_run].level >= (uint32_t)level) ++end_run;
            for (size_t a = i, b = end_run-1; a < b; ++a, --b) {
                bd_run swap = out->runs[a]; out->runs[a] = out->runs[b]; out->runs[b] = swap;
            }
            i = end_run;
        }
    }
    return 0;
}

int bd_extract_line(bd_paragraph *p, size_t start, size_t end, bd_result *out) {
    if (!out) return -1;
    out->count = 0; out->base = 0;
    if (!p || start > end || end > p->length || (out->capacity && !out->runs)
            || (end > start && !out->levels) || out->level_capacity < end - start) return -1;
    out->base = p->base;
    if (start == end) return 0;
    UErrorCode error = U_ZERO_ERROR;
    ubidi_setLine(p->paragraph, (int32_t)start, (int32_t)end, p->line, &error);
    if (error == U_ILLEGAL_ARGUMENT_ERROR) return multi_paragraph_line(p, start, end, out);
    if (U_FAILURE(error)) return -1;
    int32_t count = ubidi_countRuns(p->line, &error);
    if (U_FAILURE(error) || count < 0) return -1;
    out->count = (size_t)count;
    for (size_t i = start; i < end; i++) out->levels[i - start] = ubidi_getLevelAt(p->line, (int32_t)(i - start));
    for (size_t i = 0; i < out->count && i < out->capacity; i++) {
        int32_t logical, units;
        ubidi_getVisualRun(p->line, (int32_t)i, &logical, &units);
        if (logical < 0 || units <= 0 || (size_t)logical + (size_t)units > end - start) return -1;
        out->runs[i] = (bd_run){(uint32_t)(start + logical), (uint32_t)(start + logical + units), ubidi_getLevelAt(p->line, logical)};
    }
    return 0;
}

int bd_line(const uint16_t *text, size_t length, size_t start, size_t end, bd_result *out) {
    if (!out) return -1;
    out->count = 0; out->base = 0;
    if (start > end || end > length) return -1;
    bd_paragraph *p = bd_open(text, length);
    if (!p) return -1;
    int result = bd_extract_line(p, start, end, out);
    bd_close(p);
    return result;
}
