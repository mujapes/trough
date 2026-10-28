# Constructing the complete Trough

2026-10-27. This is the executable work map for [DESIGN.md](../DESIGN.md), not a series of
small product releases. No stage below introduces a temporary UI architecture or asks the
user to test a partial app. Automated proof and measurement proceed throughout construction.
The [autonomous development loop](AUTONOMOUS_DEVELOPMENT.md) is a foundational capability:
VM-orchestrated against the real split-view app during construction, then wholly app-owned.
Each stage extends that same loop and keeps plan, implementation and acceptance in agreement.

## Dependency map

```text
automation + bootstrap + hot-replacement / identity / schemas / capabilities
               |
        ownership + publication
          /         |          
 durable store   derivation    resource lifetimes
 + effect log    + invalidation + buffers/fences
          \         |          /
          planner / bounded execution / traces
            /       |          \
       document   media/gaze    tasks/tools/builds
            \       |          /
         scene + actions + accessibility
                    |
         native presentation + platform input
                    |
       whole-experience and recovery acceptance
```

This is a dependency diagram, not a single serial runtime pipeline. Storage does not sit
between each video frame and the display. The planner does not inspect every pixel. Each
consumer uses the narrowest stable contract and the data's native representation.

## Cross-system contracts to freeze before their consumers

| Contract | Required contents | Owner |
| --- | --- | --- |
| Identity | durable ID, live slot+generation, schema, origin/trust | semantic store / owning runtime domain |
| Captured input | ID, revision, immutable value/resource lease | publication owner |
| Derivation | operator/code identity, exact inputs, outputs, equality, cost class | planner |
| Publication | read validation, write conflicts, atomic output set, revision receipt | domain owner |
| Resource | format, extent, producer/consumer rights, fence, release condition | resource arena/device adapter |
| Intent | stable ID, captured inputs, action type, capability/scope, postcondition | effect owner |
| Scene revision | paint, geometry, hit regions, accessible semantics, source references | scene owner |
| Persistence receipt | durable revision, storage transaction, error/unknown state | storage owner |
| Activation | old/new module identity, migration, quiescence, retirement obligations | module owner |

The same laws apply to all domains. They are not all serialized through the same queue or
represented by identical structs. Domain schemas should be concrete and generated into
in-process and IPC forms, with explicit field limits and compatibility versions.

## End-state build order

### 1. Autonomous, hot-replaceable semantic and lifetime substrate

Preserve Opus's working loader, recovery and self-build mechanisms. Define and implement the
module prepare/activation/retirement protocol from [HOT_RELOAD.md](HOT_RELOAD.md) alongside
state/resource ownership, before building product consumers. Loading code alone is not state
migration. Establish typed scenario control, observations and evidence identities at these same
boundaries, so construction does not depend on user-operated tests. Every later stage inherits
these contracts; effectively any application edit being seamless is the target to expand and
prove, not a promise already satisfied by the initial loader.

Implement stable identities, revision capture, bounded transactions, output equality and
publication rejection. Then add immutable object arenas and resource leases, with retirement
tracked separately from cancellation. Define schema generation and bounded wire validation.

Required properties: an unrelated edit cannot invalidate a result; a relevant edit cannot
publish a stale result; a retired ID cannot be reused ambiguously; partial output sets cannot
escape; a cancelled job cannot free a buffer still owned by a device; changing code invalidates
results derived from its old semantics. No durable state is hidden in a view or module static.

### 2. Durable meaning and effects

Implement transactional records, revisioned documents, stream ordering/provenance, artifact
object storage, captured requests and effect receipts. Exercise process-death recovery,
full-disk behaviour, interrupted writes, duplicate delivery and uncertain external outcomes.

The input adapter's IME transaction semantics and the store's acknowledgement semantics must
be designed together. Text editing does not wait for a model or a UI renderer. Durable capture
is not reduced to an async save callback that can overwrite a later edit.

The implemented document/capture storage contract is [DOCUMENTS.md](DOCUMENTS.md), including
its asynchronous input-owner obligations, bounded admission and remaining text-index work.

### 3. Work planning and real resource ownership

Implement demand propagation, dependency/equality-based invalidation, cache identity and
operator fusion for known trusted plans. Define bounded dynamic regions for document and task
work. Add cancellation leases, admission by bytes/resources, isolated IO workers and tracing.

Implement resource pools and transfer contracts for CPU arenas, GPU buffers/images, camera
images and decoder surfaces. Their actual buffer/fence lifetime semantics constrain the plan.
Choose worker counts and accelerator overlap from combined-workload evidence; don't fix them
as one thread per feature. No benchmark that ignores concurrent playback, input and thermal
conditions can establish the final cost model.

### 4. The full interaction/scene system

Implement the document model, text shaping, line layout, stream range indexing, focus/Back,
selection and scroll anchoring, then compile them into one retained scene with paint, hit,
accessibility and agent-observation products. Integrate IME and Android accessibility at this
contract, including bidi, composition and text scaling.

Implement the Vulkan presenter, damage/transform reuse, font/image resources, surface lifetime,
display pacing and real presentation diagnostics. Render the actual final Forge-inspired
surface from these primitives, including resident sources and media. There is no intermediate
widget-based chat product to port again later.

### 5. Content, media and agents as consumers of the same core

Document engine: parsing, DOM/style, layout, scripting/host semantics, navigation/networking,
origin storage, workers, events, media/forms/history and accessibility. Build the isolation
protocol before connecting this to app capabilities. Track pinned standards coverage and
compatibility workloads. Known-site adapters specialise meaning into the same records/scene;
they do not create a separate UI stack or general exemption from input validation.

Media/gaze: one audio policy and resident playback ownership; timestamped camera/inference
resources; intent generation through the same interaction rules. Inference placement includes
conversion and contention, not just isolated model invocation time.

Agents/tools: CLI and API transports translate to tasks, captured requests, permission scopes,
structured output chunks and artifacts. Native userland remains an execution capability. Tool
results become records without a renderer or global bus being in their execution path.

Self-hosting integration (using the stage-1 replacement protocol): compile the actual module graph,
prove candidates, migrate state, publish module
and state together, then retire the old revision when jobs/devices release it. Debug-bundle
publication and Java class replacement do not substitute for this transaction protocol.

### 6. Integrate and accept the complete experience

Run the real mixed scenarios automatically, against both semantic and device presentation
oracles. Migrate Forge records and drafts with recoverable original IDs and provenance;
never auto-submit migrated drafts. Switch the product surface only when the acceptance set
passes. Retire debug-only plumbing deliberately, preserving a recovery path outside the
replaceable runtime. No UI reorganisation is used to cover an incomplete ownership model.

## Concrete seams that must never become patchwork

| Trigger | Work that should change | Work that should remain valid |
| --- | --- | --- |
| Type a character | document revision, affected shaping/layout, persistence | media clocks/decoder, unrelated task inputs |
| Scroll | viewport/transform, newly demanded visible data | unchanged shaped text and durable records |
| Receive agent output | task output chunks, stream projection if visible | caret, current page generation, unrelated origin state |
| Receive a gaze sample | current inference/intent under age/confidence policy | document contents and playback source |
| Change a font | dependent shaping/layout/paint/hit/accessibility coherently | durable text and provider sessions |
| Navigate a page | source document generation and its leases | independent tabs, draft and retained media policy |
| Complete a save | durable acknowledgement of that exact revision | edits made after that revision |
| Replace a module | its affected plans/state/caches after migration | unaffected domains and eligible resident resources |
| Thermal pressure | admission and representation-quality choices within semantics | accepted edits, permission meaning and recovery obligations |

## Implementation choices and unresolved engineering work

These are decisions to resolve with source inspection and automated evidence, not user
preference questions or reasons to build an interim application.

- The trusted publication kernel uses C11 and the existing native build. Its ABI admits only
  trusted validated metadata. The first implementation is owner-serialized and allocation-free;
  it is not a global concurrent object store.
- The durable implementation is SQLite plus immutable blob storage. Select schema/transaction
  granularity around documents/tasks/media, not around the current Chat.java view tree.
- Presentation is native Vulkan plus Android platform services and MediaCodec surfaces.
  Select text/font/image primitives for conformance and license, while keeping Trough's scene,
  input and lifetime contracts. A shared surface is not proof of an overlay or zero copies.
- Trusted application behaviour becomes typed native operators and generated plans. There is
  no commitment to invent a general JS-to-native compiler. Arbitrary JS is a distinct conforming
  engine requirement. Existing oxc/swc/tsgo names in the history did not settle that requirement.
- The document engine's parser/JS engine candidates, supported standards matrix, memory-safe
  boundary implementation and Android isolated-service topology are still unselected. This is
  a major outstanding engineering dependency, not a completed browser concealed behind an API.
  Resolve it before laying out downstream host bindings; record licenses and oracle coverage.
- GPU/CPU inference and a device-supported accelerator path need real model/operator/format
  evidence. No TPU dependency is allowed to quietly become necessary for basic navigation.
- Generated schema tooling, immutable arenas, incremental planner and device presenter remain
  outstanding. Durable storage and native module lifecycle components now exist separately
  from the publication kernel; their existence does not complete their product integration.

A few days or weeks is a construction horizon, not an evidenced estimate for a complete new
standards-compatible browser. No defensible completion date follows from this design alone.
The architectural intent is fixed; the amount of standards and device work remains measurable.

## What exists now

Preserved infrastructure: the APK/hot loader, native glibc userland, apt tooling, agent protocol
adapters and self-hosted C/Java builds documented in the historical evidence. They remain
useful mechanisms. The Java Chat is a debug instrument; it has not become the new product.

New foundation: `native/core/world.h` and `world.c` implement bounded, owner-serialized semantic
publication. A transaction captures code identity and exact input revisions, automatically
captures write targets, rejects stale jobs before any mutation, publishes related output
revisions together, ignores unrelated changes, preserves unchanged revisions and prevents
slot-generation wrap/reuse ambiguity. Values are scalar/canonical immutable-resource identities;
resource retention is an explicit caller obligation, not an implemented arena manager.

The core builds as `lib/libtroughcore.a` through the same `native/build.sh` used by self-hosting.
Sources travel with development bundles; native reproduction checks include the archive.
The debug application does not call the kernel yet. This is the actual first piece of the
end-state substrate, not a claim that the engine, planner or integrated application exists.

`tests/core_world.sh` compares against a scalar reference over 10,000 controlled completion
interleavings and checks conflict atomicity, independent domains, change-back, code replacement,
retired identity, bounded capacity, unchanged revisions and counter exhaustion. CI runs it on
x86 with memory/undefined-behaviour checks and on arm64. These are correctness oracles, not
phone performance results. Build/reproduction evidence is recorded in the handoff.

The initial experiment batching callbacks into the old debug UI was removed from the working
implementation after the user's clarification. There is no local UI patch presented as the
foundation of the unified architecture.

Stage 1 also now includes `native/core/resources` (leases, fences and retirement) and
`native/core/modules` (prepare/activation/retirement), independently checked by the resource
and dynamic-library replacement scenarios. The signed development controller runs named
scenarios on the phone and binds receipts to the exact bundle/runtime. Full presentation,
input, audio and application-owned orchestration remain downstream obligations.

Stage 2 includes `native/store/store` (SQLite revisions, atomic read-validated commits,
durable effect lifecycle, bounded outbox recovery, verified immutable objects) and
`native/store/document` (canonical UTF-16 documents, pure edit batches, durable save receipts,
historical captures and idempotent prepared Ask). The native archive, source bundle and
scenario registry carry these same implementations to self-hosting. `native/store/stream`
adds atomic named entries, ordered tails, historical provenance and stable snapshot paging
([contract](STREAMS.md)). Retention, indexed text/range storage and actual input/task owners remain construction
work. Passing these semantic oracles does not establish a complete document engine or UI.

## Agent implementation discipline

Before adding code, name the end-state contract it implements and its upstream/downstream
ownership. Implement the real component against that contract. Keep reference semantics and
an independent oracle close to it. Do not add a shim merely to make the old debug UI show a
feature, or use a new architecture word to rename a disconnected subsystem.

Before treating a component as complete, account for invalidation, lifetime, admission,
security, persistence, recovery, hot replacement and diagnostics, including which obligations
belong to its caller. Before claiming an optimisation, compare complete-workload device
measurements with preserved correctness. Share concise progress when useful; do not make the
user responsible for technical integration or turn every internal proof into a product demo.
