# Blob values

`native/core/blobs.{h,c}` holds the immutable byte objects that derived values name: layout
runs, scene fragments, index pages. It is the first CPU resource pool of stage 3
([CONSTRUCTION.md](CONSTRUCTION.md) section 3), and its byte limit is the first admission
control.

## Contract

- **Canonical handles.** `bl_intern` hashes the bytes and compares them in full against
  entries with the same hash. Equal bytes return the existing handle, so handles are equal
  exactly when bytes are. A node whose recomputed output is byte-identical keeps its value,
  and the graph's early cutoff applies to large outputs, not only to scalars.
- **Tagged.** A handle has bit 63 set and encodes a 24-bit slot plus a 39-bit generation.
  A released slot is reused with a new generation, so an old handle is `TR_STALE`, never
  someone else's bytes.
- **Owner-serialised refcounts.** Intern, retain and release run on the owner thread.
  `bl_get` may run on any thread for a handle the caller holds a reference through. It reads
  only the generation and bytes, which cannot change while the handle is held.
- **Admission.** Interning past `byte_limit` is `TR_CAPACITY`. A taken allocation is
  freed on refusal and on deduplication.
- **Ownership through the graph** (`dv_set_hooks`): a node's value is retained while it is
  the memo, and released when replaced or evicted.
- **Ownership through the executor** (`ex_use_blobs`):
  - every value a job is given is retained from issue until the job retires (completed,
    cancelled, stale or stopped), so a worker can read input bytes while the graph moves on;
  - an operator may return malloc'd bytes instead of a value, and the owner interns them;
  - a refused intern is a sticky executor error.
- **Bounded index.** A fixed open-addressed index; tombstones are rebuilt away once live plus
  tombstoned slots pass 3/4.

## Oracle (`core.blobs`, `tests/core_blobs.sh`)

- **Store:** dedupe, stale handles after release, slot reuse with a new generation, byte
  admission (copied and taken), and 600000 churn inserts through a 16-slot index.
- **Integration:** three blob-producing or blob-reading operator levels on 4 workers, with 3
  clients editing, demanding, releasing and evicting. Every clean node's bytes or scalar
  equals a from-scratch evaluation, and equal outputs share one handle. After all demand is
  released and every node evicted, the store holds 0 objects and 0 bytes. ASan sees no leaked
  allocation.
- **Admission:** a 13-byte output under an 8-byte limit is a sticky `TR_CAPACITY`, and the
  store keeps nothing.
- **Weak-hash build:** everything runs again with a 4-bit hash (`-DBL_WEAK_HASH`), so
  equal-hash objects with different bytes must stay distinct.
- **Sanitizers:** clean under plain, ASan/UBSan and TSan builds.
- **Mutants:** 8 of 8 caught. Job inputs not held (ASan use-after-free), never released, the
  intern reference kept, the old memo kept on replace, the memo kept on evict, the memo not
  retained, no tombstone rebuild (hang), and dedupe by hash alone (weak-hash build).

## Not yet built

- **Admission as backpressure:** the byte limit is a hard error today. The owner needs a
  policy that evicts undemanded nodes, and later demotes representations, before it refuses.
- **Larger objects:** arenas or mapped files for big objects (GPU uploads, decoded images), and
  `resources.h` leases for objects whose lifetime ends at a device fence.
- **Hashing cost:** each intern hashes its whole output. Chunked or incremental hashes would
  make large outputs cheaper.
