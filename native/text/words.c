/*
 * Copyright (C) 2015 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * C11 adaptation of Minikin WordBreaker.cpp / OptimalLineBreaker.cpp /
 * GreedyLineBreaker.cpp, commit
 * 1e1d5d137d487df875d7db69b5ff24e7d0291612: default line style, one locale, no hyphenation or letter spacing.
 */
#include "words.h"
#include <limits.h>
#include <math.h>
#include <unicode/ubrk.h>
#include <unicode/uchar.h>
#include <unicode/utf16.h>

typedef struct {
    const uint16_t *text;
    int32_t length, current, scan;
    int url;
    UBreakIterator *icu;
    br_cancel cancelled;
    void *context;
    int aborted;
} words;

static int interrupted(words *w) {
    if (w->cancelled && w->cancelled(w->context)) w->aborted = 1;
    return w->aborted;
}

static int valid(const words *w, int32_t at) {
    if (at == UBRK_DONE || at == w->length) return 1;
    if (at <= 0 || at > w->length) return 0;
    UChar32 prev, next;
    int32_t before = at, after = at;
    U16_PREV(w->text, 0, before, prev);
    switch (prev) {
        case 0x2d: case 0x58a: case 0x5be: case 0x1400: case 0x2010:
        case 0x2013: case 0x2027: case 0x2e17: case 0x2e40:
        case 0xad: case 0x1039: return 0;
    }
    U16_NEXT(w->text, after, w->length, next);
    if (prev == 0x200d && u_hasBinaryProperty(next, UCHAR_EMOJI)) return 0;
    if (u_hasBinaryProperty(next, UCHAR_EMOJI_MODIFIER)) {
        if (prev == 0xfe0f && before > 0) U16_PREV(w->text, 0, before, prev);
        if (prev == 0x1f91d || prev == 0x1f93c ||
                u_hasBinaryProperty(prev, UCHAR_EMOJI_MODIFIER_BASE)) return 0;
    }
    return 1;
}
static int break_before(uint16_t c) {
    return c == '~' || c == '.' || c == ',' || c == '-' || c == '_' ||
           c == '?' || c == '#' || c == '%' || c == '=' || c == '&';
}
static int32_t next_word(words *w) {
    int32_t last = w->current;
    if (last >= w->length) return UBRK_DONE;
    if (last >= w->scan) {
        int state = 0; /* start, @, colon, colon/slash, colon/double slash */
        int32_t i;
        for (i = last; i < w->length; ++i) {
            if (!(i & 255) && interrupted(w)) return UBRK_DONE;
            uint16_t c = w->text[i];
            if (!(c > ' ' && c <= 0x7e)) break;
            if (!state && c == '@') state = 1;
            else if (!state && c == ':') state = 2;
            else if (state == 2 || state == 3) state = c == '/' ? state + 1 : 0;
        }
        w->url = state == 1 || state == 4;
        if (w->url && !ubrk_isBoundary(w->icu, i)) i = ubrk_following(w->icu, i);
        w->scan = i;
    }
    if (w->url) {
        uint16_t prev = w->text[last];
        int32_t i;
        for (i = last + 1; i < w->scan; ++i) {
            if (!(i & 255) && interrupted(w)) return UBRK_DONE;
            if (prev == ':' || prev == '=' || prev == '&') break;
            if (prev == '/' && i >= last + 2 && w->text[i-2] == '/') break;
            uint16_t c = w->text[i];
            if (prev != '-' && (break_before(c) ||
                    (c == '/' && prev != '/' && !(i+1 < w->scan && w->text[i+1] == '/')))) break;
            prev = c;
        }
        w->current = i;
    } else {
        int32_t i = ubrk_following(w->icu, last);
        while (!valid(w, i)) {
            if (interrupted(w)) return UBRK_DONE;
            i = ubrk_next(w->icu);
        }
        w->current = i;
    }
    return w->current;
}
static int end_space(uint16_t c) {
    return c == '\n' || c == ' ' || c == 0x1680 ||
           (c >= 0x2000 && c <= 0x200a && c != 0x2007) || c == 0x205f || c == 0x3000;
}
int br_candidates(const uint16_t *text, const float *advances, size_t length,
                  const char *locale, float width, float size,
                  br_candidate *out, size_t capacity, size_t *count,
                  br_cancel cancelled, void *context) {
    if (!count) return -1;
    *count = 0;
    if ((!text && length) || (!advances && length) || length > INT_MAX ||
            !out || capacity <= length || !isfinite(width) || width < 0 ||
            !isfinite(size) || size < 0) return -1;
    for (size_t i = 0; i < length; ++i) {
        if (cancelled && cancelled(context)) return -2;
        if (text[i] == '\t') return -3;
        if (!isfinite(advances[i])) return -1;
    }
    out[0] = (br_candidate){0, 0, 0, 0};
    if (!length) { *count = 1; return 0; }
    UErrorCode error = U_ZERO_ERROR;
    words w = {.text = text, .length = (int32_t)length, .cancelled = cancelled, .context = context};
    w.icu = ubrk_open(UBRK_LINE, locale ? locale : "", text, (int32_t)length, &error);
    if (!w.icu || U_FAILURE(error)) { if (w.icu) ubrk_close(w.icu); return -1; }
    UBreakIterator *fallback = NULL;
    size_t used = 1;
    int result = -1;
    float total = 0, effective = 0, previous_width = 0;
    float hyphen_penalty = (float)(0.5 * size * width);
    int32_t start = 0, end;
    while ((end = next_word(&w)) != UBRK_DONE) {
        if (end <= start || end > w.length) goto done;
        for (int32_t i = start; i < end; ++i) {
            if (cancelled && cancelled(context)) { result = -2; goto done; }
            total += advances[i];
            if (!end_space(text[i])) effective = total;
        }
        if (!isfinite(total)) goto done;
        if (effective - previous_width > width) {
            /* A fresh iterator starting inside a paragraph can expose fallback
             * boundaries absent from the original traversal, even with the same
             * default style (dictionary/URL context). Preserve Minikin's lower
             * fallback penalty instead of treating every cluster as desperate. */
            error = U_ZERO_ERROR;
            if (!fallback) fallback = ubrk_open(UBRK_LINE, locale ? locale : "",
                                               text, (int32_t)length, &error);
            else ubrk_setText(fallback, text, (int32_t)length, &error);
            if (!fallback || U_FAILURE(error)) goto done;
            words fresh = {.text = text, .length = (int32_t)length, .current = start,
                           .scan = start, .icu = fallback, .cancelled = cancelled, .context = context};
            int32_t next = next_word(&fresh);
            int calculate_fallback = next >= start && next < end;
            float sum = advances[start];
            for (int32_t i = start + 1; i < end; ++i) {
                if (cancelled && cancelled(context)) { result = -2; goto done; }
                float a = advances[i];
                if (a == 0) continue;
                float at = previous_width + sum;
                float penalty = 1e10f;
                if (calculate_fallback && i == next) { penalty = 1e6f; next = next_word(&fresh); }
                out[used++] = (br_candidate){(uint32_t)i, at, at, penalty};
                sum += a;
            }
            if (fresh.aborted) { result = -2; goto done; }
        }
        out[used++] = (br_candidate){(uint32_t)end, total, effective,
                                    w.url && end < w.scan ? hyphen_penalty : 0};
        start = end; previous_width = total;
    }
    if (w.aborted) { result = -2; goto done; }
    if (start != w.length) goto done;
    *count = used; result = 0;
done:
    if (fallback) ubrk_close(fallback);
    ubrk_close(w.icu);
    return result;
}

int br_greedy(const uint16_t *text, const float *advances, size_t length,
              const char *locale, float width, const float *stops, size_t stop_count,
              float tab_width, br_result *out, br_cancel cancelled, void *context) {
    if (!out) return -1;
    out->count = 0;
    if ((!text && length) || (!advances && length) || length > INT_MAX ||
            out->capacity < length || (length && !out->lines) ||
            !isfinite(width) || width < 0 || !isfinite(tab_width) || tab_width < 0 ||
            (stop_count && !stops)) return -1;
    for (size_t i = 0; i < stop_count; ++i)
        if (!isfinite(stops[i]) || stops[i] < 0 || (i && stops[i] < stops[i-1])) return -1;
    for (size_t i = 0; i < length; ++i) {
        if (cancelled && cancelled(context)) return -2;
        if (!isfinite(advances[i])) return -1;
    }
    if (!length) return 0;
    UErrorCode error = U_ZERO_ERROR;
    words w = {.text = text, .length = (int32_t)length, .cancelled = cancelled, .context = context};
    w.icu = ubrk_open(UBRK_LINE, locale ? locale : "", text, (int32_t)length, &error);
    if (!w.icu || U_FAILURE(error)) { if (w.icu) ubrk_close(w.icu); return -1; }
    int result = -1;
    size_t used = 0;
    int32_t start = 0, end, line_start = 0, saved = -1;
    int saved_url = 0;
    float total = 0, effective = 0, saved_total = 0, saved_width = 0;
    while ((end = next_word(&w)) != UBRK_DONE) {
        if (end <= start || end > w.length) goto done;
        for (int32_t i = start; i < end; ++i) {
            if (cancelled && cancelled(context)) { result = -2; goto done; }
            if (text[i] == '\t') {
                size_t j = 0;
                while (j < stop_count && stops[j] <= total) ++j;
                total = j < stop_count ? stops[j] :
                        tab_width == 0 ? 0 : (float)(floor(total / tab_width + 1) * tab_width);
                effective = total;
            } else {
                total += advances[i];
                if (!end_space(text[i])) effective = total;
            }
        }
        if (!isfinite(total)) goto done;
        int consumed = 0;
        while (effective > width) {
            if (cancelled && cancelled(context)) { result = -2; goto done; }
            int32_t at;
            float line_width, remaining_width, remaining_total;
            if (saved != -1) {
                at = saved; line_width = saved_width;
                remaining_width = effective - saved_total;
                remaining_total = total - saved_total;
            } else {
                /* Upstream's cluster fallback uses raw measured advances here,
                 * including a tab's zero advance; preserve its behavior. */
                float sum = advances[line_start];
                for (at = line_start + 1; at < end; ++at) {
                    if (cancelled && cancelled(context)) { result = -2; goto done; }
                    float a = advances[at];
                    if (a == 0) continue;
                    if (sum + a > width) break;
                    sum += a;
                }
                if (at == end) {
                    line_width = effective; remaining_width = remaining_total = 0; consumed = 1;
                } else {
                    line_width = sum; remaining_width = effective - sum; remaining_total = total - sum;
                }
            }
            if (at <= line_start || used >= out->capacity) goto done;
            out->lines[used++] = (br_line){(uint32_t)at, 0, line_width};
            line_start = at; effective = remaining_width; total = remaining_total;
            saved = -1; saved_url = 0; saved_total = saved_width = 0;
            if (consumed) break;
        }
        if (!consumed) {
            int url = w.url && end < w.scan;
            if (saved == -1 || saved_url || !url) {
                saved = end; saved_width = effective; saved_total = total; saved_url = url;
            }
        }
        start = end;
    }
    if (w.aborted) { result = -2; goto done; }
    if (start != w.length) goto done;
    if (line_start != w.length && saved != -1) {
        if (used >= out->capacity) goto done;
        out->lines[used++] = (br_line){(uint32_t)saved, 0, effective};
    }
    out->count = used; result = 0;
done:
    ubrk_close(w.icu);
    return result;
}
