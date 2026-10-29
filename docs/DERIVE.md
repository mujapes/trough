# Derivation graph

`native/core/derive.{h,c}` is the first stage-3 piece ([CONSTRUCTION.md](CONSTRUCTION.md)
section 3): demand propagation, dependency- and equality-based invalidation, and cache identity
over the publication kernel (`world.h`). Every derived product (layout, scene, search index,
task plan) is meant to be a node here. This document is its contract.

## Model

- A **node** is a cached value identified by `(operator module cell, key)`; `dv_intern` is
  the cache identity. The module cell's value is the operator's code identity, so replacing a
  module invalidates its nodes the same way a changed input does.
- A **dependency** is a world cell or another node, recorded in the order the operator read
  it. Dependencies are dynamic: which ones are read can depend on values read earlier.
- The owner **demands** roots. `dv_poll` verifies them at the world's current publication and
  hands out **jobs** for nodes that must be computed.
- A job is a snapshot: the node's key, its operator identity and the values it is given, all
  at one revision. Workers run the operator on the job alone and never see the graph or the
  world. A worker returns either a value plus the refs it actually read, or the refs it needs
  and was not given.

## Verification (Salsa's red-green algorithm)

A node verified at revision V is still valid at R when its module cell and each recorded
dependency are unchanged since V. Dependencies are checked in read order, and node
dependencies are verified recursively first. The world's `publication` is the global clock,
because a cell's `revision` is the publication at which it last changed. A recomputed node whose value is
equal keeps its old `changed_at`, so its dependants verify without running (early cutoff).
Removed cells are a stable `DV_ABSENT` value, since world ids never revive.

## Dynamic dependencies (Skyframe's restart model)

A node is recomputed when verification fails. Its old dependencies that are free to supply
(cells, and nodes already verified at R) are kept as provided values. Everything else the
operator must ask for. A `DV_RESULT_NEED` adds the missing refs and interns their nodes. Once
those are verified, the node is reissued with all values re-captured at one revision. A dependency
the operator stops reading is never recomputed on its behalf.

## Concurrency, staleness and cancellation

Several jobs may run at once, and the world may move while they run. A completion is still
accepted: its value is correct as of the job's revision, and it is recorded as verified at
that revision. The next poll re-verifies it against anything newer. There is one job per
node; `dv_cancel` withdraws it, and the late completion is `TR_STALE`, even if the node was
reissued. `dv_wanted` tells a worker whether the last poll still reached its node, so
unwanted work can stop early.

## Levels and admission

Each demand has a level: `DV_URGENT` (input-blocking), `DV_VISIBLE` (on screen; `dv_demand`)
or `DV_BACKGROUND` (speculative). `dv_poll` walks roots level by level, so jobs come out most
urgent first and a node shared across levels is issued at its most urgent one. Every node
records the most urgent level that reached it in the sweep; `dv_job_level` reads it for a job,
so a queued job follows its demand (promoted by an urgent root, demoted when that root goes,
`DV_LEVELS` when unwanted).

`dv_unsettled` is the most urgent level that still has a demanded root not verified by the last
poll. Every level more urgent than that one is settled at the poll's publication. This lets the
executor settle the urgent roots without waiting for background work.

`dv_poll_levels` takes a per-level limit on new jobs. A refused node stays to recompute and is
reported blocked, so the poll returns `DV_WAITING` and a later poll issues it. Every root is
still walked after capacity or a limit runs out. Stopping early, as the first `dv_poll` did,
leaves later roots unreached, so their running jobs look unwanted and the executor cancels
them; the levels case reproduces this exact situation.

## Cycles

When a node's dependency is already on the path being verified, the job gets that ref with
`DV_CYCLE`, and the operator decides what a cycle means (normally an error value). A
cycle's values depend on which node the demand entered first, so a node that saw `DV_CYCLE` is
volatile: it is never reused at a later revision.

## Bounds

Nodes, dependency blocks (8 dependencies each, chained), the intern table and the walk stack
all live in caller storage. Exceeding any of them is `TR_CAPACITY`, never an allocation.
`dv_evict` drops an undemanded, idle node, and dependants that recorded it recompute. Choosing
what to evict, and admission by bytes, are owner policy outside this kernel. Values are
canonical handles, so the resources they name stay with their owner (`resources.h` leases).

## Oracle (`core.derive`, `tests/core_derive.sh`)

Six operators with value-dependent fan-out and choice run as workers that see only job values.
A memo-free reference evaluates the current world from scratch.

- Deterministic cases: an unchanged world and unrelated cell edits run 0 jobs. Early cutoff:
  9 edits changed `op2` but not `op4`'s inputs, and `op4` never ran. Other cases cover module
  replacement, removed inputs, cancellation (including completion of the old ticket while
  its reissue runs), eviction followed by an input change, invalid results (unprovided reads,
  needs that were already provided), a dependency that is no longer read and is not
  recomputed, a two-node cycle (settles, recovers, path-dependent re-demand), and node capacity.
- Random: cell edits, module bumps, cell removal, demand changes and evictions. Up to 6 jobs
  run at once and complete in random order, with cancellations and edits made while jobs
  run. At every idle point, each demanded node and each node reported clean equals the
  reference. 3000 rounds under ASan/UBSan: 61033 checks. 30000 rounds: 653795 checks in 2.1 s.
- Levels: issue order across levels, a background limit of 0 (`DV_WAITING`, then issued),
  a running job still wanted after an earlier root fills capacity, promotion and demotion of a
  running job, and per-level release. The random run gives each root a random level and
  random per-level limits while jobs run, and checks issue order, limits and wantedness on
  every poll. The random run also checks at every idle point that the root list holds exactly the
  demanded nodes, linked both ways. 5 of 5 mutants caught (stopping the walk at capacity, ignoring limits, ignoring
  level order, the last reach setting the level, releasing at the wrong level).
- Pixel bundle `0ab61dc6c126...`: `core.derive` passes in 63 ms, and `build.native` rebuilds every
  output, including `core-derive`, byte-identically on the phone.
- Mutants: 10 of 10 caught. They drop early cutoff, ignore cell changes, ignore module identity,
  skip the ticket check, provide unverified hints, verify at completion time instead of job
  time, ignore node dependency changes, trust evicted dependencies, accept unprovided reads,
  and ignore volatility.

## Not yet built

- Restart cost: 35% of random-run jobs are restarts (29% with static operators, `dv_set_static`). Batching needs (an operator asks for
  every dependency it can name before it stops) and per-operator dependency hints are the
  known fixes; measure them on real operators first.
- Incremental sweeps. The root list is built: demanded nodes are linked in demand order, so
  an idle poll over 8192 nodes with 8 demanded costs 0.07 us (VM; 0.06 us on the Pixel), down from 33 us when it
  scanned every node. A clean root stops its walk at once, so the remaining poll cost is
  proportional to the roots plus the work found under them.
- Byte/resource admission and cancellation leases joined to `resources.h`, and IO workers.
  Job-count admission per level is built (above).
- Real operators: document layout and scene compilation as nodes, which is the next consumer.
- Value ownership: done through `dv_set_hooks` ([BLOBS.md](BLOBS.md)).
