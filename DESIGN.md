# Trough: one coherent machine

Current architectural contract, 2026-10-27. Read this before implementing any subsystem.
[Historical decisions and device evidence](docs/IMPLEMENTATION_HISTORY.md) preserve the earlier
investigations; they are not a second specification. [Construction plan](docs/CONSTRUCTION.md)
records the dependency order, proof obligations and actual implementation status.
[Hot replacement](docs/HOT_RELOAD.md) defines the founding lifecycle contract and how the
working Opus loader evolves without being discarded.

## Intent

Trough is one self-hosting Android application: a continuous place to consume, think, browse,
create and direct agents. It replaces Forge's VM/daemon/client arrangement. Its shape comes
from the final Forge interaction design; its internal organisation is designed afresh.

The user's clarification is fundamental: **parallelisation means harmony**, not merely more
threads. The system should exploit agreements between its parts: the same fact need not be
rediscovered, the same result need not be represented repeatedly, independent changes need not
interfere, and work should reach the relevant hardware without unnecessary intervening work.
A faster collection of separately assembled features would miss the objective.

We design the complete system first and construct its actual internals in dependency order.
There is no succession of temporary chat apps, fallback web shells or user-tested demos.
The existing debug console remains a development instrument, not the product being polished.
Automated tests, differential oracles and device measurements are the builder's work; the user
is not asked to operate the phone or approve each intermediate subsystem. One integrated
product is exposed when its complete experience satisfies the acceptance contract.

A design can eliminate structural mistakes, not guarantee unknown hardware behaviour or
arbitrary-web compatibility. Measurements can change an implementation inside a contract.
If evidence invalidates a contract, revise the whole affected design explicitly; do not hide
an exception behind another adapter and call the architecture complete.

## Construction is part of the architecture

User clarification, 2026-10-27: acknowledge the full scale rather than reducing scope to make
an intermediate demo look complete. The target includes a ground-up Android browser, a native
filesystem/workspace implementation or suitable port, first-class hot replacement and autonomous
build/test/refinement loops, all governed by the same design. Hardware modification is out of
scope. Near-hardware-limit efficiency is an objective to investigate and measure across complete
paths, not a performance claim or a reason to omit semantics.

The user remains in the existing VM-hosted environment until Trough is substantially complete.
Both apps will be kept open in Android split view. The builder must control, exercise, observe,
build and refactor Trough without asking the user to supply taps, screenshots, test execution or
repetitive judgements. Split view is a development arrangement, not the final architecture or
evidence that all control/observation capabilities already exist.

The development loop is a first-class consumer of the same action, observation, effect, build
and activation contracts as the finished product. Initially the VM orchestrates it through a
narrow development adapter. Finally Trough owns the orchestrator, source, toolchain, cases,
evidence, activation and recovery. That transition moves ownership of one loop; it does not
require inventing a second automation system or retaining a hidden VM dependency.

Plan, implementation and end state must stay aligned: each architectural invariant names its
implementing contract and automated evidence; implementation findings update the affected
end-to-end design, not a growing list of exceptions. Define cases -> implement/refactor ->
verify -> widen coverage -> repeat within the authorised scope. The user supplies direction
and initial constraints; their attention is not a dependency of ordinary construction.
See [AUTONOMOUS_DEVELOPMENT.md](docs/AUTONOMOUS_DEVELOPMENT.md).

## The governing idea

**One semantic model, specialised physical representations, explicit ownership and causality.**

The semantic model says what exists, which revision it has, what depends on it, who may change
it, and which effects are authorised. The execution plan turns that knowledge into only the
necessary work. Physical representations remain appropriate to their consumer: text spans,
compact layout arrays, GPU resources, codec surfaces, file pages and model protocol buffers.
One model does not mean one giant object graph, one global lock, one language, one universal
buffer format, or a message bus through which every byte must pass.

The three levels are deliberately separate:

1. **Meaning:** typed records, revisions, capabilities, actions and provenance. This survives
   renderer choices, model providers and code reloads.
2. **Work:** pure derivations plus explicit effects, with declared inputs, output ownership,
   invalidation rules, admission limits and timing requirements.
3. **Realisation:** compact memory, native kernels, GPU commands, decoder buffers, durable
   transactions, network and process adapters. These are implementations of the first two.

An optimisation is valuable when it removes work across levels while preserving meaning.
A specialised feed view can remove remote scripting and generic layout together. A shared
text measurement can serve layout, selection and accessibility. A compositor-only transform
can move a page without reshaping it. Those are architectural wins before instruction tuning.

## Complete experience

The reference is Forge's final deck/Flow interaction language, including the unified pad,
stream and resident source specified in `forge/docs/forge-next-design.md` and the study in
`forge/forge-ui/design/flow-next.html`. Copy their interaction intent and final visual layout,
not their DOM, timers, transport or old host assumptions. Trough's explicit native/no-WebView
requirements override the old Forge document's implementation prescriptions.

- A resident writing surface preserves draft, selection, composition and scroll anchor.
  Writing is local; an explicit Ask captures a particular revision and any attached sources.
- Thoughts, media, sources, agent replies, tools and artifacts inhabit one chronological
  semantic stream. Different views reference the same records. New arrivals do not drag the
  reader away from history or move the caret.
- Resident sources and playback survive changes in focus, expanded artifacts and agent work.
  One focus/Back owner and one audio policy govern the whole surface. Games keep direct input.
- Deck, dock, teleprompter, beam, work status, power keys and model selection are coordinated
  projections of that state, not autonomous mini-apps with their own lifecycle rules.
- Known-site reading, arbitrary browsing, local files and generated artifacts enter the same
  presentation and action system, with provenance and trust preserved.
- Multiple agent backends and Trough's own model loop share task, tool, permission and artifact
  semantics. CLI subscription access is not silently replaced by a paid API.
- Capture, recovery, local navigation and already available media remain useful without a
  provider, a network connection or the Forge VM. Source changes never send a draft by accident.
- Source editing, building and safe activation are capabilities of the running application.
  Self-hosting is part of the same ownership/revision system, not a second development daemon.

## Semantic substrate

### Identity and revisions

A record has a stable semantic identity and a schema. References to live runtime resources
also have a generation, so a reused slot cannot impersonate a retired object. A revision
identifies an immutable state of one record or one deliberately grouped consistency domain.
There is no global revision used to invalidate every subsystem whenever anything changes.

Core record families:

| Family | Authoritative contents | Derived products |
| --- | --- | --- |
| Document | text revisions, spans, composition, selection, thought boundaries | shaping, line layout, selection geometry, accessible text |
| Stream | stable item IDs, ordering, provenance, keep/read state | visible range, placements, summaries |
| Source | origin, navigation/document generation, session partition, permissions | document tree, native-site records, semantic observations |
| Media | source identity, playback intent, clock, audio policy | decoder state, presented surface, metadata |
| Task | captured request revision, backend, scope, results, receipts | work indicators, conversation projections, tool cards |
| Artifact | content identity, type, source/input revisions | viewers, thumbnails, export |
| Scene | composition of surfaces, geometry, focus, scroll anchors | draw packets, hit regions, accessibility/action observations |
| Module | schema/operator version, build identity, capabilities | executable plans and validated replacement candidates |

Durable IDs and live slot handles are distinct. A GPU handle is not a database key; a file
path is not a universal object identity. Content-addressing deduplicates immutable artifacts;
mutable documents retain their own stable identities and revision history.

### Reads, writes and publication

A computation captures the exact input revisions it uses, including operator/code revision
and any relevant policy, viewport, font or device-format revision. It writes privately owned
outputs. Publication validates the read set and all write targets against current state.
Either the complete result is installed, or none is. An unrelated document edit does not
invalidate it. A changed input that later returns to the same value still has a different
revision, unless reuse is explicitly proved safe by the operator's cache key.

A task is never allowed to mutate a published input or another task's output. Workers see
immutable input views; an owner performs publication. Multiple writers to one record are
serialised or rejected as conflicts. Multi-record invariants share one publication domain;
independent domains do not acquire a fictitious app-wide transaction dependency.

Publication, durability and presentation are different acknowledgements:

- published: a coherent state can be read;
- durable: its storage transaction has been acknowledged;
- presented: a frame containing the referenced scene revision reached the platform's
  presentation boundary, with actual display timing distinguished where available.

The UI never labels a draft Saved because it merely reached RAM, and never reports an agent
navigation successful just because a request was sent or some unrelated pixels changed.

### Derivation and invalidation

An operator declares its read set, output type, equality rule and effects (if any). The build
planner groups operators into native work units; runtime input dependencies refine those
units where data-dependent reads are needed. Small known operations fuse rather than paying
for a generic task object at every field or token.

Dirty means an input may have changed the output, not that every downstream result must be
rebuilt. Recompute or validate the affected operator; if its semantic output is unchanged,
stop propagation. Cache identity includes all semantically relevant inputs and code identity.
Use exact comparison or canonical identity for correctness; a probabilistic hash alone is
not evidence that permission scopes, text or program behaviour match.

Demand comes from visible content, active audio, explicit tasks, durable obligations and
bounded prefetch. Offscreen representation work is not demanded just because records exist.
Cycles are explicit state transitions across epochs (playback, animations, agent turns), or
bounded fixed-point operators with convergence rules. They are not accidental callback loops.

## Harmony in four concrete paths

### Thinking while a source plays

An IME edit advances only the document's text/selection domain. The store persists that
revision; incremental shaping processes affected text; layout derives changed lines; scene
publication updates their geometry. Video remains on its existing decoder surface and clock.
The media decoder does not subscribe to a broad "workspace changed" notification.

Ask captures a durable or explicitly retained immutable text revision and source references.
The agent can finish later without overwriting subsequent typing. Its artifact points back to
those inputs; the stream adds a reference, and the current scroll anchor stays stable.

### Gaze and scrolling

Camera capture supplies an owned image buffer and timestamp. Inference consumes a compatible
representation, with crop/format conversion fused where the actual delegate permits it.
A bounded latest-sample path publishes gaze with confidence and age. The interaction owner
combines gaze, touch and focus policy into a scroll/focus intent. It updates compositor
transforms against an existing scene whenever possible; content layout runs only if needed.

Gaze does not send a camera frame through Java, JSON, a script bridge and back into an ML
runtime. It also does not pretend HardwareBuffer support implies every model runs without
conversion. If inference is late, discard obsolete samples, not reliable user commands.

### Browsing and acting

A page's DOM/layout/action semantics are owned inside its content security domain. The
trusted host receives validated, bounded scene resources and semantic observations tagged
with document and scene revisions. Hit-testing, accessibility and agent observations derive
from the same committed scene. There is no second invisible browser making decisions about
a page the user is not viewing.

An action targets stable element identity plus document generation and the observation's
relevant state. Before execution, revalidate attachment, availability, origin and scope.
A changed page cancels obsolete authority. Completion names the checked postcondition.
A native site adapter may replace a semantic region only when it can preserve that region's
interaction, accessibility, media and security contracts. Recognition is not a license to
silently change what a page does.

### Changing Trough while it runs

Editing source changes module inputs, not live function pointers. Build and validation produce
an immutable candidate keyed by source, compiler, flags, schema and dependencies. A migration
plan prepares state privately. At a quiescent publication boundary, install a compatible code
revision and state together. Old jobs cannot publish with the former code revision. Resources
remain owned until their users and device fences retire; then old code can be unloaded.

A failed build or migration leaves the previous code and state intact. Already issued external
effects are not rolled back by pretending they never happened. A receipt journal resolves
in-flight effects. Media stays resident when its owning domain is unchanged.

## Work and physical execution

### Dependency before concurrency

The work graph is a description of necessary computation, not an instruction to run all nodes
on separate threads. The planner first eliminates unused work, reuses valid outputs, fuses
compatible transforms and chooses representations. Only then does it assign ready work to
CPU, GPU, codec, storage or network execution.

Each operator specifies:

- input/output schema and layout, owner and trust domain;
- exact invalidators and semantic equality;
- cancellation and whether stale work is discardable;
- effects and required capabilities;
- scratch/resident/output memory bounds and backpressure behaviour;
- latency class and demand (display, audio, interaction, explicit task, background);
- a reference oracle and a cost model with measurement provenance.

A generated static plan handles known trusted UI logic. Dynamic document and agent work uses
the same contracts with bounded dynamic plan regions. No generic global event bus and no
per-token trip through a universal scheduler. Network/provider formats are decoded at their
boundary; internal work uses typed records and compact spans.

Independent work may overlap, but concurrency is constrained by memory bandwidth, accelerator
contention, deadline slack and thermal headroom. Video, inference, rendering and compilation
can compete for the same DRAM and power budget despite using different engines. More busy
hardware is not itself success. Runtime cost choices must account for the whole active mix.

### Admission and overload

Every edge declares one of these semantics:

| Edge | When downstream is late |
| --- | --- |
| Reliable command, approval, text edit | retain in bounded durable/in-memory ownership; backpressure or explicit rejection before acceptance |
| Latest state, gaze sample, viewport intent | replace obsolete pending state; release old resource ownership |
| Append stream, model output | preserve sequence; batch into owned chunks; bound memory and spill durable history when appropriate |
| Frame/image resource | finite pool; return ownership with fences; drop only according to declared media policy |
| Background derivation | cancel or defer; never displace an interaction deadline merely to show progress |

Budgets include bytes, outstanding resources and execution time, not just item count. An
unbounded payload in a bounded queue is still unbounded memory. Owner threads do not wait
for work that requires that same owner. Blocking protocol/process IO lives outside display
and audio owners. Cancellation revokes publication rights immediately even if underlying
work takes time to stop. Resource reclamation waits for real completion.

No fixed worker-per-feature rule. CPU workers are a bounded shared execution resource for
eligible pure work; blocking IO has separately bounded admission. GPU submissions share one
resource/dependency accounting model. Android scheduling hints express measured work targets;
they do not promise a chosen core, frequency or thermal exemption.

### Memory and representations

Trusted runtime control uses compact generational handles and explicit arena lifetimes.
Long-lived records, transient jobs and frame scratch have different allocation domains.
A frame cannot keep a whole historical conversation alive through one accidental reference.

Use structure-of-arrays for kernels that scan a few fields of many records; use packed records
when consumers need their fields together. Align/pad only when ownership and cache traffic
justify it. Prefer batch-local mutation and immutable publication over atomics on every field.
Avoid shared reference-count writes in hot inner loops; reclaim by epoch/fence at domain
boundaries where lifetime permits it.

GPU resources, decoder surfaces and camera buffers remain in their native domains. Exchange
handles, sizes, formats and sync primitives, not copies disguised as messages. "Zero copy"
must name the actual boundary; GPU sampling, tiling, colour conversion and composition still
consume bandwidth. A required conversion is placed once at the narrowest compatible boundary.

The first trusted commit kernel is C11 because it is supported by the verified phone toolchain
and can have a small explicit ABI. It handles trusted validated metadata, never raw web input.
Memory-safe parsing/engine components are a separate boundary requirement; C is not a reason
to admit untrusted bytes into this kernel. Add toolchains intentionally to the self-hosted build
when their consumers are selected, not as speculative dependencies.

### Rendering

One retained scene specification produces geometry, paint order, hit regions, accessible
semantics and action targets from coherent revisions. Text shaping/layout caches key on font,
script, direction, width, scale and relevant content revisions. Raster caches and GPU packets
are disposable realisations, not the source of document or interaction truth.

The native renderer targets Vulkan with explicit buffer ownership and presentation fences.
Android remains the surface, input, IME, accessibility and compositor host. A small Java/native
boundary exchanges batches/handles; no per-draw reflective calls. An Android text-input adapter
preserves IME composition, selection, bidi, clipboard and accessibility contracts even when
the text itself is rendered by Trough.

Visible-range layout and indexed text/stream storage make presentation cost depend on what is
visible and changed, not all historical items. Scrolling primarily changes transforms/clip
ranges. A scene publication cannot pair new draw geometry with old hit targets.

MediaCodec may produce a separate Surface for hardware composition. Hardware Composer decides
whether a physical overlay is available: overlapping translucent controls, plane limits and
formats can force GPU composition. The plan evaluates total composition cost; it does not
promise "SurfaceView = no GPU work". Keep audio continuity independent of visual surface moves.

Frame admission follows display timing and actual completion fences. Keep only useful work
in flight, sample current interaction state late where safe, and avoid stuffing a presentation
queue. Choreographer supplies timing input, not a complete low-latency presentation protocol.
An unchanged scene produces no voluntary render loop. Media and active animations retain
only their own clocks and demands.

## Arbitrary web, site specialisation and security

There is **no WebView fallback**, including for arbitrary pages. The final product includes
Trough's document/JS engine, with native-site and component specialisations using the same
scene/action contracts. The browser is a major subsystem, not a later rectangle to embed.

Its complete contract includes navigation/networking, origin-scoped cookies/storage, HTML
parsing, DOM, CSS cascade/layout, fonts/shaping, JS language and host APIs, events, timers,
fetch/streams, media, workers, history, selection, forms, clipboard and accessibility. Track
standards coverage explicitly. A JS parser/transpiler is not a JS engine, and AOT cannot
remove arbitrary dynamic semantics such as eval, proxies or dynamically loaded code.
Trusted known programs may be specialised/AOT-compiled. Arbitrary scripts need a conforming
execution strategy; select reusable engine components by conformance, embedding control,
license and device cost. "Ground up" describes Trough's architecture, not a demand to rewrite
cryptography, codecs, shaping or every standards algorithm without existing implementations.

Untrusted parsing, scripting and layout belong in isolated content domains without model
keys, app-private files, userland exec or privileged Android bridges. One shared process for
all sites is **not** stronger isolation than Chrome. Process partitioning must respect origin
and privilege boundaries; process reuse is a resource policy constrained by that boundary.
The exact Android isolated-service pool and lifecycle must be proven in the actual manifest
and device before claiming containment. Platform sandboxing is necessary but does not replace
origin isolation, IPC validation, resource quotas or capability checks.

The trusted compositor accepts validated resource descriptors, bounded display packets and
semantic records, never arbitrary pointers or native executable commands from a page. Clip,
coordinate, resource-count and index validation belongs at that boundary. Permission prompts
and model tool actions cannot be forged by content pixels or page-provided action metadata.

Site-native adapters consume untrusted data even when their code is trusted; parsing remains
memory-safe and bounded. User sessions remain scoped to their source. Private APIs may change;
a failure produces a supported-engine route or a clear source error, never invented data.
DRM/Widevine/EME remains outside the accepted target. No blanket claim of full-web coverage or
better-than-Chrome security is made before the corresponding evidence exists.

## Effects, storage and external systems

Pure derivation never opens a file, executes a shell command, spends tokens or sends a request
as a hidden side effect. It produces an effect intent with an identity, captured input scope,
capabilities and expected postcondition. An effect owner validates/adopts it, records its
state and dispatches through the appropriate platform/provider/process adapter.

Effect lifecycle: prepared -> authorised -> issued -> observed outcome, with explicit cancelled,
failed and unknown-outcome states. Local durable acceptance uses a transaction/outbox. External
APIs do not magically become exactly-once: retry only with supported idempotency or observed
postconditions; otherwise preserve unknown outcome and avoid duplicate consequences. Revoking
a lease prevents future dispatch, but cannot unsend an already accepted remote action.

Durable records live in one transactional store with schema migrations, record revisions and
an effect journal. Large immutable blobs live in a content-addressed object store referenced
by records. SQLite is the intended transactional implementation, not a custom database project.
Database rows do not become universal runtime objects; loaded working sets have their own
compact layouts. File saves use explicit conflict and durability semantics. Recovery preserves
unfinished text and uncertain external outcomes. Kept artifacts are not evicted like thumbnails.

Userland is a peripheral capability with native execution, not the application host. Preserve
the verified t.t/texec/patched-glibc environment, apt/dpkg ownership and source-build facilities.
The evidence establishes working tools, not a general Linux kernel or universal hardcoded-path
compatibility. Namespace/root prerequisites remain as documented in the historical evidence.
Provider and CLI adapters translate at entry/exit; no localhost daemon, HTTP UI server, SSE
internal bus or copied Forge plumbing. Network SSE remains a provider's external protocol.

The fixed APK declares actual platform components and capabilities; permission declaration is
not runtime consent or an ability to bypass Android restrictions. Platform adapters hold
mechanism; replaceable modules own app policy. Native modules can hold policy when they are
part of the replaceable program. "Native code never holds policy" is not a literal restriction
that would force the planned native application back through scripts for every decision.

## Self-hosting and extensibility

The target is seamless replacement for effectively every application-level change, including
changes to state schemas, runtime behaviour and the replacement machinery itself. This is a
capability to expand systematically, not a compatibility list to freeze at today's easy cases.
User-observable continuity is the criterion; preserving every internal object is not required.
Preparation, state transfer, overlapping revisions and resource handoff can hide internal change
while keeping interaction, playback and accepted effects coherent. Record uncovered edit classes
and demonstrated external constraints explicitly; do not silently lower the target.

Hot replacement is a founding property, designed and built alongside identity and ownership
before the product's subsystems. Opus was right to establish the loader and on-device rebuild
first. Preserve that infrastructure and extend it into the state/resource replacement protocol
in [HOT_RELOAD.md](docs/HOT_RELOAD.md); do not postpone its contracts until after the UI exists.

A module contributes schemas, derivations, actions, effect adapters and scene semantics with
stable versions and explicit capabilities. It does not register its own global event loop,
second store, general-purpose bridge or private navigation stack. Extensibility follows the
same laws as built-in code, so adding a capability does not add a new architectural exception.

Build identity covers the full dependency closure, compiler/toolchain, target ABI, generated
schemas, flags, resources and migration code. Known trusted plans can be statically fused,
inlined, vectorised and linked with whole-program optimisation. Dynamic modules cross a
versioned narrow ABI; changing code cannot leave incompatible cached layouts or retained
objects masquerading as current ones.

Replaceable state lives outside module code allocations when it must survive a swap, or has
an explicit migration. Build -> oracle -> prepare -> quiesce -> atomic activate -> retire is
one transaction protocol. Never unload code while callbacks, destructors, jobs or device fences
still refer to it. The existing class-loader reload is useful evidence, not proof that this
full state/resource migration protocol has been implemented.

## Measurement: physical limits without mythology

Optimise sustained useful work at bounded latency and energy, not a single peak-throughput
number. Report p50/p95/p99 input-to-presentation, frame misses, audio underruns, task completion,
retained memory, bytes copied, allocator traffic, wakeups, cancelled work and energy per task
for the **combined** workload. Compare after warm-up and under sustained thermal conditions.

The performance budget is a vector, not one FPS target. Start with a display-period budget
(`P = 1 / actual refresh rate`), reserve compositor/GPU margin from observed traces, and budget
CPU work against the remaining interval. Audio has its own deadline. Background build speed
is constrained by keeping interaction and audio within their budgets. Exact numerical targets
are acceptance inputs from device measurements, not invented guarantees in a design document.

Counter readings:

- CNTVCT_EL0 is elapsed-time ticks, not CPU cycles. Multiplying by a sampled cpufreq does not
  recover true cycles under DVFS, migration or preemption. Label inferred frequency/throughput
  as estimates. The existing add-chain benchmark is not a universal cycle oracle.
- ADPF hints inform scheduling; they do not hold clocks steady. Affinity may help controlled
  experiments but is not a production strategy to pin everything to the biggest core.
- Pixel power rails were readable from the app, with roughly 20-second refresh in the observed
  report. Use windows spanning real updates, repetitions and baseline subtraction; rails are
  device activity, not automatic per-function energy attribution. Missing data is unavailable,
  never zero joules.
- PMU access is optional diagnostic capability with device-specific restrictions. llvm-mca
  predicts instruction scheduling, not whole-app cache, GPU, memory or thermal behaviour.
- NNAPI is deprecated. Tensor TPU availability, model support, transfer costs and measured
  benefit must be established for the chosen inference path. CPU/GPU are candidates, not
  failed moral versions of a promised TPU implementation.

Optimisation order: eliminate unnecessary semantics/work -> incremental reuse -> fuse compatible
operations -> select layouts and lifetimes -> hardware placement -> compiler optimisation ->
measured specialised kernels. Machine-code search is appropriate for proved hot kernels with
stable semantics and real device oracles. No claim that its benefit is a fixed percentage.

## Completion contract

The finished product runs independently of the Forge VM and presents the full unified surface.
Typing, history, media, gaze, arbitrary document interaction, agents, files and self-modification
obey the same revision/ownership laws. Reliable actions survive contention and recovery;
replaceable work does not accumulate behind the user. Focus, Back, audio, model permissions,
origin isolation and saved-state acknowledgements agree across the whole experience.

Automated scenario oracles exercise the complete paths above, including delayed IO, out-of-order
completion, stale observations, reload while jobs are live, process death, storage failure,
thermal pressure, IME composition and incompatible code/schema changes. Engine conformance uses
pinned test262/WPT coverage; rendering uses reference geometry, text and golden screenshots;
full-screen/device evidence verifies real presentation, including separately composed media.
View.draw into a bitmap alone does not prove SurfaceView/video composition.

No milestone is called the product because one chat screen boots. Internal construction stages
exist to make the complete design mechanically defensible. They are not alternate product
architectures to be shipped and patched together later.

## Research behind these decisions

These sources inform the design; they are not benchmarks of Trough.

- [Rust compiler incremental queries](https://rustc-dev-guide.rust-lang.org/queries/incremental-compilation.html):
  dependency-aware reuse and stopping propagation when a recomputed result is unchanged.
- [Differential dataflow](https://www.microsoft.com/en-us/research/publication/differential-dataflow/):
  incremental computation over changing inputs. Trough borrows the reasoning; it does not adopt
  a distributed analytics runtime as its UI framework.
- [Android frame pacing](https://developer.android.com/games/sdk/frame-pacing):
  queue stuffing, presentation timing and fences explain why peak submission rate is not the
  same as low input-to-screen latency.
- [Hardware Composer](https://source.android.com/docs/core/graphics/hwc):
  hardware decides composition, and overlay suitability depends on the full layer arrangement.
- [ADPF](https://developer.android.google.cn/games/optimize/adpf?hl=en):
  adaptive workloads and scheduling/thermal hints, rather than promised fixed clocks.
- [NNAPI migration](https://developer.android.com/ndk/guides/neuralnetworks/migration-guide):
  the earlier doc's default NNAPI/TPU path was an unverified assumption.
- [Linux false sharing](https://www.kernel.org/doc/html/latest/kernel-hacking/false-sharing.html):
  ownership and access patterns matter; extra parallel writers can create cache traffic.
