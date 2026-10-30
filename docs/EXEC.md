# Derivation executor

`native/core/exec.{h,c}` runs a derivation graph ([DERIVE.md](DERIVE.md)) on threads. It is
the "bounded execution / traces" step of stage 3 ([CONSTRUCTION.md](CONSTRUCTION.md)).

## Contract

- **One owner thread** holds the world and the graph; no other thread touches them. Clients
  act through `ex_call(fn, arg)`, which runs `fn` on the owner thread between scheduling
  rounds and waits for it. Edits, demand changes and reads of results all go through it.
- **Workers** (1 to 8, fixed at init) take jobs from fixed slots (2 per possible worker) and
  run the operator registered for the job's module cell. An operator is a pure function of
  the job: `ex_read` returns a value it was given and records the read, `ex_need` asks for
  one it was not given. An operator never sees the world or the graph.
- **Scheduling:** after every call and every completion, the owner completes finished jobs,
  polls the graph into free slots and wakes workers.
- **Priorities:** a worker starts the most urgent ready job (its current `dv_job_level`,
  then issue order). Waiting jobs follow their demand on every schedule. Background jobs in
  flight are limited to `workers - reserve` (at least 1; `ex_set_reserve`, default 1). The
  graph enforces the limit (`dv_poll_levels`), so the reserved worker is always free for
  urgent and visible work. This is Chromium's thread-pool rule: best-effort tasks get a
  smaller concurrency limit.
- **Cancellation:** a running or queued job whose node the latest poll did not reach is
  withdrawn from the graph (`dv_cancel`) and its flag is raised. Operators check
  `ex_cancelled` and stop early. A queued job whose flag is up is not started.
- **Settling:** `ex_settle` waits until every demanded node is verified at the publication
  current when it was called (or later), with no job running. `on_settled` runs on the owner
  thread once per new settled publication, in increasing order. It must not call `ex_call`.
- **Settling by level:** `ex_settle_at(level)` returns once the roots at that level and every
  more urgent one are verified (`dv_unsettled`), while background work may still run. Every
  call resets the per-level marks, because demand changes do not move the publication.
- **Errors are sticky:** a graph capacity or invalid-result error, or a job for an
  unregistered operator, stops scheduling, and `ex_settle` returns 0.
- **Tracing:** every job leaves a record in a 1024-entry ring. It holds the node, operator,
  ticket, revision, worker, queued/start/end times and the outcome (value, need, cancelled,
  stale or invalid).

## Oracle (`core.exec`, `tests/core_exec.sh`)

- **Concurrent:** 3 client threads make 400 random calls each (cell edits, module bumps,
  demand and release) and settle at random. 4 workers run operators that reuse the
  derivation oracle's dynamic dependencies, with random spin and cancellation checks.
  Whenever a client checks, every node the graph reports clean equals a memo-free evaluation
  of the world. At the end every demanded node is clean, and settled notifications are
  strictly increasing.
- **Priorities:** a worker starts the most urgent ready job (its current `dv_job_level`,
  then issue order). Waiting jobs follow their demand on every schedule. Background jobs in
  flight are limited to `workers - reserve` (at least 1; `ex_set_reserve`, default 1). The
  graph enforces the limit (`dv_poll_levels`), so the reserved worker is always free for
  urgent and visible work. This is Chromium's thread-pool rule: best-effort tasks get a
  smaller concurrency limit.
- **Cancellation:** a 2 s job stops within 0.02-0.12 ms (VM) after its demand is released,
  and the node is left unverified.
- **Priorities (VM, 4 workers):** 20 urgent 0.3 ms jobs arrive every 3 ms during 64
  background 5 ms jobs. With 1 worker reserved, urgent jobs start after 0.10 ms (p50) and
  0.2-0.7 ms (p90), and background jobs never run on more than 3 workers (112 ms total).
  With no reservation it is 1.9 ms (p50) and up to 4.5 ms (85 ms total). With 16 visible
  5 ms jobs queued, urgent jobs start within 2.7-6.5 ms, and so does a queued job promoted by
  an urgent demand. FIFO order would put them behind the 11 queued jobs, about 14 ms. The
  thresholds are p90 < 2.5 ms (the Pixel once measured just over 1.5 ms after the GPU
  scenarios; it usually measures 0.09-0.20 ms) and < 10 ms, because the host preempts the VM's vCPUs and causes
  single 2-4 ms outliers. 3 of 3 mutants caught (no background cap, first-ready-slot pick,
  levels frozen at issue). TSan and ASan/UBSan clean.
- **Settle by level:** the executor runs 64 background 5 ms jobs on 4 workers. A 20 ms urgent
  job demanded 2 ms in settles in 20.2 ms with 52 background jobs left, and everything
  settles in 112 ms. Mutants are caught: no per-call reset (it returned before the job ran)
  and `ex_settle_at` waiting for everything.
- **Pixel bundle `79bdc5b7eedb`:** priorities with 1 reserved worker: urgent jobs wait 0.072 ms
  (p50) and 0.78 ms (p90). Without the reserve they wait 0.90 ms (p50) and up to 3.8 ms. With
  the visible flood, urgent and promoted jobs start within 3.9 ms. The urgent level settles in
  23.1 ms with 52 background jobs left.
- **Unregistered operator:** a sticky `TR_INVALID`; settle returns 0 instead of hanging.
- **Scaling:** 96 independent 2 ms jobs. On the VM: 1 worker 193 ms, 2 97, 4 54, 8 31.
  This measures scheduling overhead, not energy.
- **Sanitizers:** clean under TSan and ASan/UBSan.
- **Pixel bundle `1353a0fd5380...`:** all checks pass. 894 concurrent edits, 3817 clean nodes
  equal the reference, and cancellation takes 0.175 ms. Scaling: 1 worker 209.8 ms, 2 107.8,
  4 58.4, 8 43.9. With 8 workers the speedup is 4.8x, against 6.2x on the VM. The likely cause
  is the slower A55 cores, but core placement was not measured. `build.native` rebuilds `core-exec` byte-identically.
- **Mutants:** 3 of 6 caught (no withdrawal of unwanted jobs, a silently skipped unregistered
  operator, an unlocked completion under TSan). The 3 survivors are equivalent.
  `ex_settle`'s target check and the call-time reset of `idle` each guard the same property
  alone. A cancelled job's value result is already refused by the graph, because the owner
  withdraws the job before raising the flag.

## Admission

2026-10-29. `ex_register_cost` gives an operator a working-memory estimate per job, taken on the
owner when the job is issued. With `ex_set_budget(budget, bg_reserve)`:
- a worker starts a ready job only if the running jobs' estimates plus its own fit the budget;
  a background job must fit within budget - bg_reserve;
- charges live in a `resources.h` ledger whose byte limit is the budget. A charge is created at
  start, then retired and reaped when the operator returns. Freed bytes wake the waiting
  workers;
- jobs are admitted in priority order (level, then issue order). The first job that does not
  fit holds back every job after it, so small jobs cannot starve a large one;
- a job larger than the whole budget is charged the budget and runs alone;
- a ready job already cancelled is admitted at no cost, since it skips its operator.

`core.exec` "admission": 52 jobs of 5-60 units plus one of 150, background and urgent, on 4
workers, against a budget of 100 with a background reserve of 20. Each operator checks the live
sum on entry. Result: peak 100, background peak 80, 137 deferrals, 0 violations, and the large
job ran alone. Without admission the same workload peaks at 210 with 44 violations.

Mutants: no background reserve, no release, no clamp for oversized jobs, and no limit are all
killed. The survivor drops the wake-up after a release. That is performance only: the worker
that released continues and admits the next job itself.

## Operator costs

2026-10-30. The derived view's platform operators declare working memory
(`native/android/derive.c`, `layout_cost` / `mask_cost`), measured on the Pixel by `android.cost`.
A call's cost is the Java bytes its thread allocates (ART's per-thread counter) plus the native
heap's growth over the call. The native part is mostly shaping objects' native peers, which stay
until the GC runs. Medians of 5 warm calls:

| operator | measured | model |
|---|---|---|
| layout, 16 units | 16-22 KB | 24 KiB + 896 B/unit |
| layout, 1024 units | 735-765 KB (~180 B/unit Java, ~565 native) | 942 KB |
| layout, 16384 units | 11.9-12.9 MB | 14.7 MB |
| mask, mono (73 glyphs) | 0.1-12 KB | 64 KiB |
| mask, colour (2) | 31 KB | 64 KiB |

A layout job issued before it has read its text costs 0: that run only asks for the text. Hot
bundles call `deriveAdmission(maxMemory / 4, budget / 4)` when the library loads. `android.cost`
gates on the model covering every sample, then runs 12 paragraphs of 16000 units on 4 workers
with a budget of 2.5 layouts (35.9 MB). The charged peak was 28.7 MB, with 21-34 deferrals. The
settle took 463-489 ms, against 417-476 ms without admission, and every layout equalled the
direct one. The process native heap is no peak signal here: GC frees of earlier garbage
dominate it.

## Not yet built

- **Worker placement and energy:** the Pixel's cores are 2 X1, 2 A78 and 4 A55. Worker count and
  affinity must come from measured energy per task under concurrent load, not from this
  throughput test.
- **Admission, remaining:** the budget is a fraction of the Java heap limit, not yet derived
  from the device's memory pressure (onTrimMemory, available RAM) or from other owners' leases
  (atlas, decoders). Caret, selection and scene operators are uncounted (small, bounded by the
  viewport). Outputs are bounded separately by the blob store's byte limit. Preempting a running background job is not built:
  an urgent job waits for one only when every worker runs visible work.
- **Owner cost:** each completion triggers a poll over the demanded roots (the graph's root
  list; clean roots cost O(1)). A dirty-root worklist would skip even those, but it only
  matters with thousands of roots.
- **Real operators:** text layout and scene compilation as registered operators (JNI for
  the platform text stack).
