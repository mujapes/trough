/*
 * Copyright (C) 2014, 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/* C11 port of AOSP Minikin's tailored grapheme rules. See docs/NATIVE_TEXT.md.
 * UTF-16 decoding is local so the operator needs only public NDK ICU property APIs. */
#include "measure.h"
#include <stdbool.h>
#include <float.h>
#include <math.h>
#include <unicode/uchar.h>

static uint32_t prev_cp(const uint16_t *t, size_t start, size_t *off) {
    uint32_t c = t[--*off];
    if (c >= 0xDC00 && c <= 0xDFFF && *off > start && t[*off - 1] >= 0xD800 && t[*off - 1] <= 0xDBFF)
        c = 0x10000 + ((t[--*off] - 0xD800) << 10) + c - 0xDC00;
    return c;
}
static uint32_t next_cp(const uint16_t *t, size_t end, size_t *off) {
    uint32_t c = t[(*off)++];
    if (c >= 0xD800 && c <= 0xDBFF && *off < end && t[*off] >= 0xDC00 && t[*off] <= 0xDFFF)
        c = 0x10000 + ((c - 0xD800) << 10) + t[(*off)++] - 0xDC00;
    return c;
}
#define MS_IS_TRAIL(c) ((c) >= 0xDC00 && (c) <= 0xDFFF)
#define MS_IS_LEAD(c) ((c) >= 0xD800 && (c) <= 0xDBFF)
#define MS_LENGTH(c) ((c) >= 0x10000 ? 2 : 1)
#define MS_PREV(t, start, off, c) ((c) = prev_cp((t), (start), &(off)))
#define MS_NEXT(t, off, end, c) ((c) = next_cp((t), (end), &(off)))

static int32_t tailoredGraphemeClusterBreak(uint32_t c) {
    // Characters defined as Control that we want to treat them as Extend.
    // These are curated manually.
    if (c == 0x00AD                      // SHY
        || c == 0x061C                   // ALM
        || c == 0x180E                   // MONGOLIAN VOWEL SEPARATOR
        || c == 0x200B                   // ZWSP
        || c == 0x200E                   // LRM
        || c == 0x200F                   // RLM
        || (0x202A <= c && c <= 0x202E)  // LRE, RLE, PDF, LRO, RLO
        || ((c | 0xF) == 0x206F)         // WJ, invisible math operators, LRI, RLI, FSI, PDI,
                                         // and the deprecated invisible format controls
        || c == 0xFEFF                   // BOM
        || ((c | 0x7F) == 0xE007F))      // recently undeprecated tag characters in Plane 14
        return U_GCB_EXTEND;
    // THAI CHARACTER SARA AM is treated as a normal letter by most other implementations: they
    // allow a grapheme break before it.
    else if (c == 0x0E33)
        return U_GCB_OTHER;
    else
        return u_getIntPropertyValue(c, UCHAR_GRAPHEME_CLUSTER_BREAK);
}

// Returns true for all characters whose IndicSyllabicCategory is Pure_Killer.
// From http://www.unicode.org/Public/9.0.0/ucd/IndicSyllabicCategory.txt
static bool isPureKiller(uint32_t c) {
    return (c == 0x0E3A || c == 0x0E4E || c == 0x0F84 || c == 0x103A || c == 0x1714 ||
            c == 0x1734 || c == 0x17D1 || c == 0x1BAA || c == 0x1BF2 || c == 0x1BF3 ||
            c == 0xA806 || c == 0xA953 || c == 0xABED || c == 0x11134 || c == 0x112EA ||
            c == 0x1172B);
}

bool ms_grapheme_break(const float* advances, const uint16_t* buf, size_t start,
                                    size_t count, const size_t offset) {
    // This implementation closely follows Unicode Standard Annex #29 on
    // Unicode Text Segmentation (http://www.unicode.org/reports/tr29/),
    // implementing a tailored version of extended grapheme clusters.
    // The GB rules refer to section 3.1.1, Grapheme Cluster Boundary Rules.

    // Rule GB1, sot ÷; Rule GB2, ÷ eot
    if (offset <= start || offset >= start + count) {
        return true;
    }
    if (MS_IS_TRAIL(buf[offset])) {
        // Don't break a surrogate pair, but a lonely trailing surrogate pair is a break
        return !MS_IS_LEAD(buf[offset - 1]);
    }
    uint32_t c1 = 0;
    uint32_t c2 = 0;
    size_t offset_back = offset;
    size_t offset_forward = offset;
    MS_PREV(buf, start, offset_back, c1);
    MS_NEXT(buf, offset_forward, start + count, c2);
    int32_t p1 = tailoredGraphemeClusterBreak(c1);
    int32_t p2 = tailoredGraphemeClusterBreak(c2);
    // Rule GB3, CR x LF
    if (p1 == U_GCB_CR && p2 == U_GCB_LF) {
        return false;
    }
    // Rule GB4, (Control | CR | LF) ÷
    if (p1 == U_GCB_CONTROL || p1 == U_GCB_CR || p1 == U_GCB_LF) {
        return true;
    }
    // Rule GB5, ÷ (Control | CR | LF)
    if (p2 == U_GCB_CONTROL || p2 == U_GCB_CR || p2 == U_GCB_LF) {
        return true;
    }
    // Rule GB6, L x ( L | V | LV | LVT )
    if (p1 == U_GCB_L && (p2 == U_GCB_L || p2 == U_GCB_V || p2 == U_GCB_LV || p2 == U_GCB_LVT)) {
        return false;
    }
    // Rule GB7, ( LV | V ) x ( V | T )
    if ((p1 == U_GCB_LV || p1 == U_GCB_V) && (p2 == U_GCB_V || p2 == U_GCB_T)) {
        return false;
    }
    // Rule GB8, ( LVT | T ) x T
    if ((p1 == U_GCB_LVT || p1 == U_GCB_T) && p2 == U_GCB_T) {
        return false;
    }

    // This is used to decide font-dependent grapheme clusters. If we don't have the advance
    // information, we become conservative in grapheme breaking and assume that it has no advance.
    const bool c2_has_advance = (advances != NULL && advances[offset - start] != 0.0);

    // All the following rules are font-dependent, in the way that if we know c2 has an advance,
    // we definitely know that it cannot form a grapheme with the character(s) before it. So we
    // make the decision in favor a grapheme break early.
    if (c2_has_advance) {
        return true;
    }

    // Rule GB9, x (Extend | ZWJ); Rule GB9a, x SpacingMark; Rule GB9b, Prepend x
    if (p2 == U_GCB_EXTEND || p2 == U_GCB_ZWJ || p2 == U_GCB_SPACING_MARK || p1 == U_GCB_PREPEND) {
        return false;
    }

    // Tailored version of Rule GB11
    // \p{Extended_Pictographic} Extend* ZWJ x \p{Extended_Pictographic}
    if (offset_back > start && p1 == U_GCB_ZWJ &&
        u_hasBinaryProperty(c2, UCHAR_EXTENDED_PICTOGRAPHIC)) {
        uint32_t c0 = 0;
        size_t offset_backback = offset_back;
        int32_t p0 = 0;

        MS_PREV(buf, start, offset_backback, c0);
        p0 = tailoredGraphemeClusterBreak(c0);

        while (p0 == U_GCB_EXTEND && offset_backback > start) {
            MS_PREV(buf, start, offset_backback, c0);
            p0 = tailoredGraphemeClusterBreak(c0);
        }

        if (u_hasBinaryProperty(c0, UCHAR_EXTENDED_PICTOGRAPHIC)) {
            return false;
        }
    }

    // Tailored version of Rule GB12 and Rule GB13 that look at even-odd cases.
    // sot   (RI RI)*  RI x RI
    // [^RI] (RI RI)*  RI x RI
    //
    // If we have font information, we have already broken the cluster if and only if the second
    // character had no advance, which means a ligature was formed. If we don't, we look back like
    // UAX #29 recommends, but only up to 1000 code units.
    if (p1 == U_GCB_REGIONAL_INDICATOR && p2 == U_GCB_REGIONAL_INDICATOR) {
        if (advances != NULL) {
            // We have advances information. But if we are here, we already know c2 has no advance.
            // So we should definitely disallow a break.
            return false;
        } else {
            // Look at up to 1000 code units.
            const size_t lookback_barrier = offset_back - start > 1000 ? offset_back - 1000 : start;
            size_t offset_backback = offset_back;
            while (offset_backback > lookback_barrier) {
                uint32_t c0 = 0;
                MS_PREV(buf, lookback_barrier, offset_backback, c0);
                if (tailoredGraphemeClusterBreak(c0) != U_GCB_REGIONAL_INDICATOR) {
                    offset_backback += MS_LENGTH(c0);
                    break;
                }
            }
            // The number 4 comes from the number of code units in a whole flag.
            return (offset - offset_backback) % 4 == 0;
        }
    }
    // Cluster Indic syllables together (tailoring of UAX #29).
    // Immediately after each virama (that is not just a pure killer) followed by a letter, we
    // disallow grapheme breaks (if we are here, we don't know about advances, or we already know
    // that c2 has no advance).
    if (u_getIntPropertyValue(c1, UCHAR_CANONICAL_COMBINING_CLASS) == 9  // virama
        && !isPureKiller(c1) &&
        u_getIntPropertyValue(c2, UCHAR_GENERAL_CATEGORY) == U_OTHER_LETTER) {
        return false;
    }
    // Rule GB999, Any ÷ Any
    return true;
}

size_t ms_cursor(const float* advances, const uint16_t* buf, size_t start,
                                       size_t count, size_t offset, int opt) {
    switch (opt) {
        case MS_AFTER:
            if (offset < start + count) {
                offset++;
            }
            __attribute__((fallthrough));
        case MS_AT_OR_AFTER:
            while (!ms_grapheme_break(advances, buf, start, count, offset)) {
                offset++;
            }
            break;
        case MS_BEFORE:
            if (offset > start) {
                offset--;
            }
            __attribute__((fallthrough));
        case MS_AT_OR_BEFORE:
            while (!ms_grapheme_break(advances, buf, start, count, offset)) {
                offset--;
            }
            break;
        case MS_AT:
            if (!ms_grapheme_break(advances, buf, start, count, offset)) {
                offset = (size_t)-1;
            }
            break;
    }
    return offset;
}


static bool isAsciiOrBidiControlCharacter(uint16_t c) {
    return c <= 0x001F || c == 0x061C || c == 0x200E || c == 0x200F
        || (c >= 0x202A && c <= 0x202E) || (c >= 0x2066 && c <= 0x2069);
}

static float run_advance_impl(const float* advances, const uint16_t* buf, size_t layoutStart,
                           size_t start, size_t count, size_t offset) {
    float advance = 0.0f;
    size_t lastCluster = start;
    float clusterWidth = 0.0f;
    for (size_t i = start; i < offset; i++) {
        float charAdvance = advances[i - layoutStart];
        if (charAdvance != 0.0f) {
            advance += charAdvance;
            lastCluster = i;
            clusterWidth = charAdvance;
        }
    }
    if (offset < start + count && !isAsciiOrBidiControlCharacter(buf[offset]) &&
        advances[offset - layoutStart] == 0.0f) {
        // In the middle of a cluster, distribute width of cluster so that each grapheme cluster
        // gets an equal share.
        // TODO: get caret information out of font when that's available
        size_t nextCluster;
        for (nextCluster = offset + 1; nextCluster < start + count; nextCluster++) {
            if (advances[nextCluster - layoutStart] != 0.0f ||
                isAsciiOrBidiControlCharacter(buf[nextCluster])) {
                break;
            }
        }
        int numGraphemeClusters = 0;
        int numGraphemeClustersAfter = 0;
        for (size_t i = lastCluster; i < nextCluster; i++) {
            bool isAfter = i >= offset;
            if (ms_grapheme_break(advances + (start - layoutStart), buf, start, count,
                                               i)) {
                numGraphemeClusters++;
                if (isAfter) {
                    numGraphemeClustersAfter++;
                }
            }
        }
        if (numGraphemeClusters > 0) {
            advance -= clusterWidth * numGraphemeClustersAfter / numGraphemeClusters;
        }
    }
    return advance;
}

float ms_run_advance(const float* advances, const uint16_t* buf, size_t start, size_t count,
                    size_t offset) {
    return run_advance_impl(advances, buf, start, start, count, offset);
}

/**
 * Essentially the inverse of getRunAdvance. Compute the value of offset for which the
 * measured caret comes closest to the provided advance param, and which is on a grapheme
 * cluster boundary.
 *
 * The actual implementation fast-forwards through clusters to get "close", then does a finer-grain
 * search within the cluster and grapheme breaks.
 */
size_t ms_offset_for_advance(const float* advances, const uint16_t* buf, size_t start, size_t count,
                           float advance) {
    float x = 0.0f, xLastClusterStart = 0.0f, xSearchStart = 0.0f;
    size_t lastClusterStart = start, searchStart = start;
    size_t max = start + count;
    for (size_t i = start; i < max; i++) {
        if (ms_grapheme_break(advances, buf, start, count, i)) {
            searchStart = lastClusterStart;
            xSearchStart = xLastClusterStart;
        }
        float width = advances[i - start];
        if (width != 0.0f) {
            lastClusterStart = i;
            xLastClusterStart = x;
            x += width;
            if (x > advance) {
                break;
            }
        }
    }
    size_t best = searchStart;
    float bestDist = FLT_MAX;
    for (size_t i = searchStart; i <= max; i++) {
        if (ms_grapheme_break(advances, buf, start, count, i)) {
            // "getRunAdvance(layout, buf, start, count, i) - advance" but more efficient
            float delta = run_advance_impl(advances, buf, start, searchStart, max - searchStart, i)

                          + xSearchStart - advance;
            if (fabsf(delta) < bestDist) {
                bestDist = fabsf(delta);
                best = i;
            }
            if (delta >= 0.0f) {
                break;
            }
        }
    }
    return best;
}

