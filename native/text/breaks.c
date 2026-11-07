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
 * C11 adaptation of Minikin OptimalLineBreaker.cpp, commit
 * 1e1d5d137d487df875d7db69b5ff24e7d0291612: fixed width, high quality,
 * no justification or bounds-based width. Caller-owned storage/cancellation.
 */
#include "breaks.h"
#include <float.h>
#include <math.h>

int br_optimal(const br_candidate *c, size_t n, float width, float line_penalty,
               br_scratch *s, size_t capacity, br_result *out,
               br_cancel cancelled, void *context) {
    if (!out) return -1;
    out->count = 0;
    if (!c || !s || !n || n > UINT32_MAX || capacity < n ||
            (out->capacity && !out->lines) || !isfinite(width) || width < 0 ||
            !isfinite(line_penalty) || line_penalty < 0) return -1;
    if (c[0].offset || c[0].pre || c[0].post || c[0].penalty) return -1;
    for (size_t i = 0; i < n; ++i) {
        if (cancelled && cancelled(context)) return -2;
        if (!isfinite(c[i].pre) || !isfinite(c[i].post) ||
                !isfinite(c[i].penalty) || c[i].penalty < 0 ||
                (i && c[i].offset < c[i-1].offset)) return -1;
    }
    s[0] = (br_scratch){0, 0, 0};
    uint32_t active = 0;
    for (uint32_t i = 1; i < n; ++i) {
        float best = FLT_MAX, hope = 0;
        uint32_t previous = 0;
        float left = c[i].post - width;
        for (uint32_t j = active; j < i; ++j) {
            if (cancelled && cancelled(context)) return -2;
            if (s[j].score + hope >= best) continue;
            float delta = c[j].pre - left;
            if (!isfinite(delta)) return -1;
            float cost = 0, extra = 0;
            if (delta < 0) cost = 1e12f;
            else if (i == n - 1) extra = 4 * c[j].penalty;
            else cost = delta * delta;
            if (delta < 0) active = j + 1;
            else hope = cost;
            float score = s[j].score + cost + extra;
            if (score <= best) { best = score; previous = j; }
        }
        float score = best + c[i].penalty + line_penalty;
        if (!isfinite(score) || best == FLT_MAX) return -1;
        s[i] = (br_scratch){score, previous, s[previous].lines + 1};
    }
    if (cancelled && cancelled(context)) return -2;
    size_t count = s[n-1].lines;
    /* Recover backwards into the final slots; a short buffer is count-only. */
    if (out->capacity >= count) {
        size_t slot = count;
        for (uint32_t i = (uint32_t)n - 1; i; i = s[i].previous) {
            uint32_t prev = s[i].previous;
            out->lines[--slot] = (br_line){c[i].offset, i, c[i].post - c[prev].pre};
        }
    }
    out->count = count;
    return 0;
}
