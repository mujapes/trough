#ifndef TROUGH_TEXT_H
#define TROUGH_TEXT_H
#include "store.h"

/* Indexed text, construction stage 2. See docs/TEXT.md.
 *
 * tx_text is an immutable, reference-counted UTF-16 text: a height-balanced tree of leaf chunks
 * with unit and line counts in every node. An edit is O(log n + inserted) and shares every
 * untouched chunk with the input, so a snapshot (tx_retain) is O(1) and stays valid while the
 * owner keeps editing. Snapshots may be read from any thread; the counts are atomic.
 * NULL is the empty text. Lines are separated by U+000A only; offsets are code units.
 *
 * Durable form: each leaf chunk is a content-addressed store object (UTF-16LE), and a
 * revision is a manifest of chunk hashes with their counts. A save writes only chunks that
 * have no durable identity yet, so typing into a large text writes one or two chunks plus the
 * manifest. The manifest goes into the owner's record commit; chunks are put before it. */
typedef struct tx_node tx_text;
enum { TX_LEAF_TARGET = 1024, TX_LEAF_MIN = TX_LEAF_TARGET / 4, TX_LEAF_MAX = 2048, TX_UNIT_LIMIT = 1u << 30,
       TX_MANIFEST_HEADER = 32, TX_MANIFEST_ENTRY = 40, TX_SCHEMA = 0x54580101 };

tx_text *tx_retain(tx_text *);
void tx_release(tx_text *);

ts_status tx_build(const uint16_t *, size_t, tx_text **out);
/* Replaces [start, end) with text. The input is unchanged; *out is a new reference. */
ts_status tx_edit(tx_text *, size_t start, size_t end, const uint16_t *, size_t, tx_text **out);
size_t tx_length(const tx_text *);
size_t tx_newlines(const tx_text *);
ts_status tx_read(const tx_text *, size_t start, size_t count, uint16_t *out);
/* Offset of the first unit of line (0-based). TS_NOT_FOUND past the last line. */
ts_status tx_line_start(const tx_text *, size_t line, size_t *offset);
/* Line containing offset (offset == length is on the last line). */
ts_status tx_line_of(const tx_text *, size_t offset, size_t *line);
/* Zero-copy access to the chunk containing offset (< length). The pointer is valid while the
 * caller holds a reference to the text. */
ts_status tx_chunk(const tx_text *, size_t offset, const uint16_t **units, size_t *length, size_t *chunk_start);

typedef struct { size_t leaves, height, smallest, largest; } tx_stats;
/* Diagnostic: checks balance, counts and leaf bounds of the whole tree. TS_INVALID if broken. */
ts_status tx_verify(const tx_text *, tx_stats *);

/* Single storage owner. Puts every chunk without a durable identity, then writes the manifest.
 * TS_CAPACITY reports the required size and puts nothing. */
typedef struct { size_t chunks, written; } tx_saved;
ts_status tx_store(ts_store *, tx_text *, void *manifest, size_t capacity, size_t *size, tx_saved *);
/* Validates the manifest and every chunk (hash, length, line count) before building. */
ts_status tx_load(ts_store *, const void *manifest, size_t size, tx_text **out);
#endif
