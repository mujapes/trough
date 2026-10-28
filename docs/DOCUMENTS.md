# Durable documents and captured requests

2026-10-28. Stage 2 of [construction](CONSTRUCTION.md), implementing the document and
captured-request contracts in [DESIGN.md](../DESIGN.md). `native/store/document.{h,c}`
is a consumer of the same transactional store and effect outbox, linked into
`libtroughstore.a`. It has no renderer, provider, network operation or independent event loop.

## Ownership and acknowledgements

The interaction owner retains an immediate working text/selection/composition revision.
`td_apply` is a pure, bounded batch operation, with ranges interpreted sequentially and
explicit final selection/composition. It validates every edit before changing output.
The caller owns all input/output buffers. The storage owner alone calls `td_create`,
`td_save`, `td_load` and `td_ask`; these synchronous durable operations must run outside
the input/display owner. They do not turn an IME callback into a blocking SQLite commit.

A save carries the exact previous durable document revision. A stale save cannot overwrite
a more recent revision. Its successful snapshot identifies that specific durable revision
and commit; an unchanged save returns the existing commit rather than manufacturing a save.
The eventual input adapter must correlate its local revision with this receipt, retain
unacknowledged edits, and advance its Saved marker only for the acknowledged revision.
It must not replace the live draft with the returning storage snapshot. The queue that does
this (coalescing, acknowledgement, rebasing) is [DRAFTS.md](DRAFTS.md); Android
InputConnection is still to build.

Text, selection direction and composition form one deliberate document transaction domain.
UTF-16 code units preserve Java/IME text losslessly, including NUL and isolated surrogates;
there is no implicit normalization or UTF-8 conversion. Offsets are code units, not
grapheme clusters or displayed positions. Shaping, grapheme navigation, spans and bidi
semantics belong to later text/interaction operators. Composition is absent only when
both endpoints are -1; otherwise it is an ordered in-range half-open interval. Selection
endpoints can be reversed. Empty text and empty composition intervals are valid.

V1 is a canonical little-endian `TDOC` envelope: magic, version, unit count, anchor, caret,
composition endpoints, reserved zero field, then UTF-16LE units. Its 32-byte header and
exact length are validated before any output is copied. No native struct layout or pointers
are persisted. Unknown versions, extra bytes and reserved fields fail closed. Read capacity
failures provide metadata and leave caller text untouched. The schema ID is `0x54440101`.
Historical revisions retain the complete envelope, including after document deletion.

Admission is currently 524272 UTF-16 units per document and 256 edits per batch. Exceeding
these bounds rejects before acceptance; it never truncates text. V1 stores a complete
snapshot per changed revision; it does not claim indexed large-document storage or a
low-cost keystroke path. Persistent text chunks, revision retention/compaction and fine-grained
text versus selection invalidation remain required construction work before product use.
Referenced history cannot be reclaimed while captured requests depend on it.

## Ask is an immutable capture, not dispatch authority

`td_ask` creates a prepared effect with caller-owned 16-byte intent identity and kind
`0x54410101`. Its canonical `TASK` capsule contains the document ID/revision, up to 32 ordered
dependencies with exact schema/revision, and up to 4096 bytes of caller-canonical options.
Dependencies are where the task owner supplies source, route, model and policy revisions;
the store does not choose a provider or silently replace subscription access with an API.
Duplicate dependencies and invalid identities are rejected. Options must exclude secrets.

A new capture validates that every input is live and current and commits that read set
with the prepared effect atomically. It never clears the document, alters selection or
submits a provider request. Explicit Ask must follow acknowledgement of the intended draft
revision; constructing a capsule from the current mutable UI state later would be wrong.

Redelivery of the same intent and byte-identical capsule is an idempotent acknowledgement,
even after the draft changes or is deleted. It preserves an advanced, terminal or unknown
effect state. Changing any captured input/options under the same identity conflicts.
After restart, the outbox scan/read APIs recover both capsule and outcome. Historical
document/source reads reconstruct the request's original inputs without consulting the
latest draft. Issued requests become unknown, and a repeated Ask never reissues them.

The trusted effect owner must still validate capability, current revocations, provider
idempotency and postconditions before authorising/issuing/reconciling. Schema or kind matching
alone grants no authority. Task lifecycle UI, stream references and provider dispatch are
downstream components. The codec handles app-owned storage; it is not a web-content parser
or an exception to the memory-safe untrusted-content boundary.

## Replacement and evidence

All authoritative state is in durable records/effects, not module statics. Loaded snapshots
and decoded captures are caller-owned; decoded options borrow the caller's wire buffer only.
An unchanged schema can reload these records. A representation/schema change still needs
the module coordinator's explicit migration and activation; persisted bytes alone do not
establish live input/resource continuity.

`tests/core_documents.sh` and the identical phone scenario `core.documents` exercise a
literal independent wire fixture, 4000 reference-vector edit batches, 571 stale save attempts,
unchanged receipts, immutable history, 40 reopens, capture redelivery after source/draft
changes and deletion, maximum-size/full-store cases and SIGKILL with active composition
and an issued request. ASan/UBSan runs cover the native implementation. This is semantic
and storage evidence; it is not actual Android IME, visual continuity or power-cut evidence.
