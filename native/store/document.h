#ifndef TROUGH_DOCUMENT_H
#define TROUGH_DOCUMENT_H
#include "text.h"

/* Durable document owner, construction stage 2. UTF-16 code units are lossless (including
 * isolated surrogates and NUL); offsets are code units, not graphemes or UTF-8 bytes.
 * These are storage-owner calls, never synchronous UI/IME callbacks. See docs/DOCUMENTS.md. */
enum { TD_SCHEMA = 0x54440101, TD_HEADER_SIZE = 32,
       TD_TEXT_LIMIT = (TS_DATA_LIMIT - TD_HEADER_SIZE) / 2, TD_EDIT_LIMIT = 256 };
typedef struct {
    const uint16_t *text;
    size_t length;
    uint32_t anchor, caret;             /* direction of the selection is preserved */
    int32_t composing_start, composing_end; /* both -1, or 0 <= start <= end <= length */
} td_value;
typedef struct {
    ts_record record;
    size_t length;
    uint32_t anchor, caret;
    int32_t composing_start, composing_end;
} td_snapshot;
typedef struct {
    size_t start, end;                  /* range in the result of the preceding edit */
    const uint16_t *text;
    size_t length;
} td_edit;

/* Canonical v1 wire codec; exact length, version and reserved bytes are checked.
 * On failure no output bytes are written. decode returns metadata on TS_CAPACITY. */
ts_status td_encode(const td_value *, void *wire, size_t capacity, size_t *size);
ts_status td_decode(const void *wire, size_t size, td_snapshot *, uint16_t *text, size_t capacity);
/* Pure batch: final selection/composition are explicit. All edits and bounds are validated
 * before output changes. The caller owns output; it must not overlap inputs. No persistence. */
ts_status td_apply(const td_value *, const td_edit *, size_t count,
                   uint32_t anchor, uint32_t caret, int32_t composing_start, int32_t composing_end,
                   uint16_t *text, size_t capacity, td_value *result);

ts_status td_create(ts_store *, const td_value *, td_snapshot *);
/* revision 0 reads current, otherwise immutable history. Capacity is in UTF-16 units. */
ts_status td_load(ts_store *, uint64_t id, uint64_t revision, td_snapshot *, uint16_t *, size_t capacity);
/* expected identifies the exact durable input. No blind or stale save. TS_OK's snapshot
 * names the acknowledged revision even for unchanged data; later UI edits stay owned by UI. */
ts_status td_save(ts_store *, ts_read expected, const td_value *, td_snapshot *);

/* Indexed form (TDOC v2): the same selection/composition header followed by a tx_text chunk
 * manifest (docs/TEXT.md), so a save writes only changed chunks and a load shares chunks.
 * td_load reads both versions. td_create/td_save still write v1 snapshots. */
enum { TD_INDEXED_LIMIT = TX_UNIT_LIMIT };
typedef struct {
    tx_text *text;
    uint32_t anchor, caret;
    int32_t composing_start, composing_end;
} td_text_value;
/* expected.id == 0 creates. Same revision and receipt rules as td_save. Chunks are put before
 * the record commit; a failed commit can leave unreferenced chunk objects, never a partial save. */
ts_status td_save_text(ts_store *, ts_read expected, const td_text_value *, td_snapshot *);
/* Either version; *text is a new reference (NULL for empty text). */
ts_status td_load_text(ts_store *, uint64_t id, uint64_t revision, td_snapshot *, tx_text **);

/* Ask captures one document plus ordered dependencies (source, route, model/policy records)
 * and bounded canonical options. It prepares an effect; it cannot authorise or dispatch it.
 * A retry with the same intent and identical capsule succeeds even after those inputs change.
 * New captures require all inputs current. Reusing an intent for different inputs conflicts. */
enum { TD_ASK_KIND = 0x54410101, TD_DEPENDENCY_LIMIT = 32, TD_OPTIONS_LIMIT = 4096,
       TD_CAPTURE_HEADER = 40, TD_CAPTURE_LIMIT = TD_CAPTURE_HEADER + 24 * TD_DEPENDENCY_LIMIT + TD_OPTIONS_LIMIT };
typedef struct { uint64_t id, revision; uint32_t schema; } td_reference;
typedef struct {
    ts_read document;
    const td_reference *dependencies;
    size_t count;
    const void *options;
    size_t options_size;
} td_capture;
/* Decoded pointers borrow only the supplied wire buffer; references are copied by value. */
typedef struct {
    ts_read document;
    td_reference dependencies[TD_DEPENDENCY_LIMIT];
    size_t count;
    const void *options;
    size_t options_size;
} td_captured;
ts_status td_capture_encode(const td_capture *, void *, size_t capacity, size_t *size);
ts_status td_capture_decode(const void *, size_t, td_captured *);
/* Validate new capture inputs and return their exact read guards for a larger atomic
 * publication. The owner must pass every returned guard to that same store's commit.
 * Pure retry acknowledgement should compare the existing capsule without revalidating. */
ts_status td_capture_validate(ts_store *, const td_capture *, ts_read *, size_t capacity, size_t *count);
ts_status td_ask(ts_store *, const uint8_t intent[16], const td_capture *, ts_receipt *);
#endif
