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
- **Cancellation:** a running or queued job whose node the latest poll did not reach is
  withdrawn from the graph (`dv_cancel`) and its flag is raised. Operators check
  `ex_cancelled` and stop early. A queued job whose flag is up is not started.
- **Settling:** `ex_settle` waits until every demanded node is verified at the publication
  current when it was called (or later), with no job running. `on_settled` runs on the owner
  thread once per new settled publication, in increasing order. It must not call `ex_call`.
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
- **Cancellation:** a 2 s job stops within 0.02-0.12 ms (VM) after its demand is released,
  and the node is left unverified.
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

## Not yet built

- **Worker placement and energy:** the Pixel's cores are 2 X1, 2 A78 and 4 A55. Worker count and
  affinity must come from measured energy per task under concurrent load, not from this
  throughput test.
- **Admission:** by bytes and resources (`resources.h` leases for job inputs and outputs), and
  priorities (interaction before background).
- **Owner cost:** the owner sweeps the whole demanded graph after each completion. A
  dirty-root worklist is the fix once real graphs are large.
- **Real operators:** text layout and scene compilation as registered operators (JNI for
  the platform text stack).
