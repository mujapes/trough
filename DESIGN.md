# Trough: an operating system in one app

Current architectural contract, redesigned 2026-10-30 around the user's direction that Trough
is the operating system (it was "one coherent machine", 2026-10-27; the substrate sections
below carry over). Read this before implementing any subsystem.
[Host contract](docs/HOST.md) defines the fixed APK and the bundle entry points.
[Construction plan](docs/CONSTRUCTION.md) records dependency order and status.
[Hot replacement](docs/HOT_RELOAD.md) defines the lifecycle contract.
[Historical decisions and device evidence](docs/IMPLEMENTATION_HISTORY.md) are not a second specification.
[HANDOFF.md](HANDOFF.md) is the live state for whichever model is building (Claude, GPT/Codex, others).

## Intent

Trough is the user's whole computing environment: an operating system that runs inside one
sideloaded Android app and is never closed. Android is reduced to the hardware, driver and
radio layer beneath it. Everything the user does (browsing, files, writing, thinking, media,
agents, apps, building Trough itself) happens inside Trough, in one fluid, co-evolving surface.
It replaces Forge's VM/daemon/client arrangement, which the phone kept killing.

What an OS is, and where each part comes from:

| OS part | In Trough |
| --- | --- |
| Kernel interface, drivers, radios | Android/Linux beneath the app, reached as directly as an app can (Hardware access) |
| Process and lifetime management | the host's process model: main process, slots, isolated sandboxes; never closed (Lifetime) |
| Filesystem and userland | the Debian root under /data/data/t.t, native arm64 (texec, patched glibc, apt/dpkg) |
| Shell, windowing, input | Trough's own scene, renderer (Vulkan) and input/IME ownership |
| Browser | Trough's own engine, first-class; no WebView |
| Applications | Trough modules, Linux programs, web apps, and Android apps run inside Trough via Lift |
| App store | Lift + the module system: install, specialise, rebuild, roll back |
| Self-modification | hot replacement and on-device self-build, owned by the running system |

The user's clarification remains fundamental: **parallelisation means harmony**, not merely
more threads. The system should exploit agreements between its parts: the same fact need not
be rediscovered, the same result need not be represented repeatedly, independent changes need
not interfere, and work should reach the relevant hardware without unnecessary intervening
work. An OS assembled from separately designed features would miss the objective.

We design the complete system first and construct its actual internals in dependency order.
There is no succession of temporary chat apps, fallback web shells or user-tested demos.
The existing chat console remains a development instrument, not the product being polished.
Automated tests, differential oracles and device measurements are the builder's work; the user
is not asked to operate the phone or approve each intermediate subsystem.

A design can eliminate structural mistakes, not guarantee unknown hardware behaviour or
arbitrary-web compatibility. Measurements can change an implementation inside a contract.
If evidence invalidates a contract, revise the whole affected design explicitly; do not hide
an exception behind another adapter and call the architecture complete.

## Construction is part of the architecture

User clarifications, 2026-10-27 and 2026-10-30: acknowledge the full scale (an OS: browser
engine, filesystem, app runtime, decompile/recompile, hardware access, hot replacement and
autonomous build/test loops) rather than reducing scope to make a demo look complete. Hardware
modification is out of scope. Near-hardware-limit efficiency is an objective to investigate
and measure across complete paths. "Humans would take years" is not a reason to cut scope:
the builder specialises to this exact device and environment, and borrows from existing
implementations wherever they are good, but does not rush.

The user stays in the VM-hosted environment until Trough is substantially complete; both apps
run in split view. The builder controls, exercises, observes, builds and refactors Trough
without asking the user for taps, screenshots, test runs or repetitive judgements. The
development loop is a first-class consumer of the same action, observation, effect, build and
activation contracts as the finished product: initially the VM orchestrates it (tools/loop.sh,
signed scenario channel), finally Trough owns it. See [AUTONOMOUS_DEVELOPMENT.md](docs/AUTONOMOUS_DEVELOPMENT.md).

Plan, implementation and end state stay aligned: each invariant names its implementing
contract and automated evidence; implementation findings update the affected design, not a
growing list of exceptions. Several models build Trough in turn (Claude, GPT/Codex); the
repository is their shared memory: DESIGN.md (what), docs/ (contracts), HANDOFF.md (now).

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

The interaction reference is Forge's final deck/Flow language, including the unified pad,
stream and resident source specified in `forge/docs/forge-next-design.md` and the study in
`forge/forge-ui/design/flow-next.html`. Copy their interaction intent and final visual layout,
not their DOM, timers, transport or old host assumptions.

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

- Apps are not separate worlds. A Trough module, a Linux program, a web app and a lifted
  Android app all present through the same scene, records and action contracts, so the user
  (and prediction) can grab, link and compose anything on screen with anything else.

### Fluid, co-evolving surface (user direction, 2026-10-30)

The user still wants Forge's original idea: a UI that co-evolves with them. The whole app acts
as a heuristic, so the surface feels fluid and anything can be handled directly. Eventually
data from any website, app or source can be reached and connected fluidly, with physical
(physics-like) interaction. The system should predict the user's next actions well from very
little data. This is not a concrete build direction. It is a freedom-maximising bias: where
the construction order allows, prefer designs that keep it open.
- Every interactable thing is a record with provenance in the one scene, so any of them can
  be grabbed, linked or composed. No feature should own a surface others cannot reach into.
- Interaction physics (drag, fling, spring and connection) belongs to the scene/input owner
  as one model with derived state, not to per-widget animation.
- Prediction is a local consumer of the same records and interaction history. It may
  prefetch, pre-layout and order, but it never acts or sends anything. No unwatched model
  turns, and nothing leaves the device without the user's permission.

## The OS layer

### The host: a fixed APK holding every mechanism

The APK is the host: the only part that needs reinstalling, so it is designed to be final.
It declares, once, every permission, component and role an OS-in-an-app may need, and forwards
each to the running bundle. It holds mechanism, never policy (docs/HOST.md):
- **Bundle entry points:** `boot` (process-level, no window needed), `attach`/`detach` (a window
  comes and goes), `intent` (every Intent Trough receives), `dispatch` (every component callback),
  `stop`. v1 bundles (`start(Activity, ...)`) still run, tied to a window.
- **Components, inert until a bundle implements them and the user grants them:** input method,
  notification listener, VPN (Trough's own network layer), default-dialer/SMS/call-screening/
  self-managed-call services, assistant (voice interaction + recognition), quick-settings tile,
  screensaver, live wallpaper, media browser, autofill, credential provider (passwords, passkeys),
  NFC card emulation, device admin, a documents provider (Trough's filesystem in Android's picker),
  a file provider, a revive job, boot/package/SMS receivers, eight process slots, isolated sandboxes.
- **One Activity** (singleTask) receives HOME, launcher, browser links, every file/share/text
  intent, dial/SMS/camera/alarm/search/assist intents, USB attach and NFC discovery.
- **Permissions:** every permission the Android 14 framework defines (signature ones too: a
  device owner, a role or `pm grant` over adb can grant some), plus later ones by name.
- **Longevity:** extra trusted bundle keys (files/hot/trusted, installed by a trusted bundle), an
  app-private inbox (files/hot/inbox) for bundles Trough builds or fetches itself, every
  Activity/Application callback forwarded, sixteen process slots with every launch mode. The
  host should never need reinstalling for a capability Android grants to apps.

The accessibility service is declared too (user, 2026-10-30, reversing an earlier note: "give
it access to absolutely everything", in case it is needed). The direction is unchanged: Trough
brings software inside (Lift) rather than driving other apps' UI; the service is a last resort.

### Lifetime: never closed

Trough's main process lives as long as the device allows. The bundle belongs to the process
(HotLoader.process), not to a window: swiping Trough from Recents, Back, rotation or a crash of
the window does not stop it; the next window re-attaches to the running bundle. TroughService
is a foreground service (sticky, not stopped with the task); a partial wake lock keeps work
running with the screen off (Power, `/awake`); the battery-optimisation exemption is requested;
a persisted 15-minute job and the boot receiver bring the process back if Android kills it;
the user disables Android 14+'s child-process restrictions once. As the HOME app, Trough is
the first thing the device shows. Quit in the notification is the only intended exit.

### Process model

| Process | What runs there | Privileges |
| --- | --- | --- |
| main (`t.t`) | loader, bundle, stores, scene, renderer, agents, userland exec | everything granted to the app |
| slots `:p0`..`:p7` | guest apps or isolated trusted work a bundle places there (Slot.java) | the app's, separately killable, own heap |
| sandboxes (`isolatedProcess`) | untrusted parsing/scripting/layout: web content, unverified guest code | none: no files, network or permissions; code arrives as dex over a binder |
| userland children | Linux programs (claude, codex, compilers, servers) under texec | the app's uid; seccomp-filtered |

Process placement is a policy of the replaceable program, constrained by privilege boundaries.

### Hardware access: as close to the kernel as an app can get

Speed comes from reaching the hardware without intervening layers. Ladder, from most direct:
1. **Native NDK paths in-process:** Vulkan (in use, Mali-G710), AHardwareBuffer (zero-copy
   between camera, codec, GPU and CPU), AAudio with MMAP exclusive mode, NDK camera,
   AImageDecoder/AMediaCodec, ASensorManager direct channels (high-rate sensors into shared
   memory), ADPF performance hints, thermal API, the system ICU and font matcher.
2. **Device file descriptors Android hands to apps:** USB host (`UsbDeviceConnection` gives a
   raw fd: userspace drivers for any USB device, libusb-style), USB accessory, Bluetooth
   (L2CAP/RFCOMM sockets), NFC tags and card emulation, the VPN tun fd (every packet), the
   camera and media surfaces.
3. **Shell-uid bridge:** Android's own wireless debugging, paired from inside Trough to the same
   device (prior art: LADB, Shizuku), gives adb/shell-uid capabilities: `pm grant` of
   development permissions, input injection, screen capture, settings, dumpsys, process stats.
   Opt-in by the user once; Trough must work without it.
4. **Device owner** (optional; set over adb on a device without accounts): lock-task (Trough
   cannot be left), silent app installs, policy control. Recorded as a possibility, not a requirement.

Kernel and driver modification stays out of scope (no root, no unlocked bootloader assumed).
Anything Android forbids an app (SELinux, seccomp, hidden APIs) is met with workarounds first
(below), and a recorded limit only after they fail.

### Remote compute as an execution target (user direction, 2026-10-30)

Cloud and other remote compute extend the device's hardware first-class, not as a separate
service. Derivations already capture exact, content-addressed inputs and code identity, which
is what remote execution needs (prior art: Bazel Remote Execution API, Buildbarn, distcc,
ccache remote storage). A remote executor is one more placement target beside CPU, GPU and
codecs: the planner sends a job there when measured cost says so (radio energy and latency vs
local CPU, thermal headroom, deadline), and the result returns as an ordinary publication.
- Candidates: builds and Lift of large artifacts, test suites, inference, media transcodes,
  pre-rendering and prefetch of web content.
- Inputs leave the device only with the user's permission, per scope; secrets never do.
  Everything keeps working offline; remote results are verified like local ones (oracles, or
  reproducible content identities).
- Compute must be the user's own or rented for this purpose: their machines, cloud VMs or
  containers, cloud agent sessions. GitHub Actions minutes on the public repo are for building
  and testing Trough itself (CI), which their terms allow; Actions is not a general compute
  backend for the OS.

Ambition this enables (user, 2026-10-30): rented servers and GPUs turn the phone into a
high-compute machine. GPU experiments run remotely; with low enough latency, remote rendering
and game streaming (hardware decode via MediaCodec low-latency mode, input sent upstream;
prior art Moonlight/Sunshine, Parsec) make PC gaming from the device possible. The transport
and decode path is part of the hardware ladder's media adapters, measured end to end.

### The Linux filesystem: complete Debian, not a subset

User direction, 2026-10-30: the Linux side is a fully developed Debian, never a half-built,
limited-capability workaround. A few GB of downloads and storage are affordable.
- **Complete distribution:** a dpkg/apt-owned Debian root (today `/data/data/t.t/d`) where any
  Debian package installs and works with its normal maintainer scripts, updates and alternatives.
  Preinstall a broad base: build-essential, clang/LLVM, Python, Node, a JDK, git, man pages,
  locales, fonts, ffmpeg and the libraries Trough itself builds against. The package list grows
  by use, never by excluding things that are hard.
- **Normal paths for every program:** each process sees a standard FHS (`/etc`, `/usr`, `/var`,
  `/home`, `/tmp`, `/run`, `/dev/shm`, `/proc`, `/sys` where Android allows) with the root's files
  in place, without root or mount namespaces (USER_NS is compiled out on this kernel). Layered
  path virtualisation, fastest layer first:
  1. Trough's patched glibc maps paths at its syscall wrappers (open, stat, exec, readlink, ...),
     covering every dynamically linked glibc program at near-zero cost;
  2. texec installs a seccomp filter that traps the path syscalls glibc did not map (static
     binaries, Go, musl, raw `syscall`) to an in-process SIGSYS handler that rewrites them
     (prior art: gVisor systrap, zpoline/lazypoline, proot's rewriting without ptrace's cost);
  3. execmap and Debian's fakechroot remain for maintainer scripts and exec mapping.
  Host paths Android owns (`/system`, `/data`, `/proc` entries, `/dev` nodes) pass through.
- **Identity:** one Android uid, presented as root inside the root to dpkg and tools, with a
  persistent ownership/mode table (fakeroot semantics) so packages that chown keep working.
- **Services:** Trough supervises long-running userland daemons itself (no systemd PID 1):
  start, restart, logs and resource limits as Trough tasks, so servers and databases run normally.
- **Shared with the OS:** the root is Trough's filesystem. The documents provider shows it in
  Android's file picker, Lift reads and writes it, and modules store large artifacts in it.
- Evidence decides "works": each layer has an oracle (tests/execmap.sh, phone filesystem tests,
  Debian packages' own test suites where they run without root).

### Lift: decompile/recompile as a first-class OS component

User direction, 2026-10-30: decompile/recompile is a component of the whole OS, not a tool.
**Lift** turns external artifacts into Trough's records and lowers them back:
- formats: dex/APK, ELF, wasm, JS/HTML/CSS (the browser's input), fonts, media containers,
  documents and data formats;
- lifted programs are records with provenance and revisions like Trough's own code: they can
  be inspected, linked, specialised to this device, rebuilt and hot-replaced;
- this is how outside software joins the fluid, co-evolving surface, and how the app store works:
  installing is lifting plus a build; updating is re-lifting; rolling back is a revision.

Lift reuses existing decompilers/assemblers (dexlib2/smali/baksmali, d8/r8, LLVM tools,
wasm tools) as dependencies of the self-hosted build, chosen by fidelity and licence.

### Android apps inside Trough

Trough already runs on ART, so a guest app's dex compiles to native code exactly as an installed
app's would: native speed is the starting point, not a goal. The work is the framework contract
the app expects: its own package identity, Context, resources, data directory, Activities,
Services, permissions and binder calls to system services. Two known approaches, combinable:
- **runtime virtualisation** (VirtualApp, DroidPlugin, Parallel Space): stub components in the
  host, proxied system-service binders, the guest's classes loaded in a slot process;
- **recompilation** (Lift): rewrite the guest's dex so its framework calls land in Trough
  shims, and its UI renders into Trough's scene instead of separate Android windows. This is
  the preferred path: the guest becomes part of the fluid surface.

High-security apps that check hardware-backed attestation of their own signature (banking)
may need the real installed app; keep looking for ways in rather than conceding early.
Browsers matter more to the user than APKs; APKs are the last hurdle, not the first.

### Building the user's own software

User direction, 2026-10-30: Trough is where the user builds their own software, not only where
they run other people's. The module system is the app platform: a user-built app is a module
(schemas, derivations, actions, scene semantics, effect adapters) that Trough builds on the
device, hot-replaces while it runs, and that co-evolves with its user like the rest of the surface.
Agents build it with the user, inside Trough, with the same build -> oracle -> activate loop.

Reference case: the user's own delivery-round software for their milk-round job, written from
scratch rather than taken from the existing handheld's software. It sets concrete requirements
for the platform, all of which belong to the OS rather than to that one app:
- offline-first records (customers, rounds, orders, deliveries, payments) in the one store,
  with history, and export/sync when a network is available;
- camera barcode/QR scanning on the native camera path, with no round trip through Java;
- location, maps and route order (location, offline map data, routing as derivations);
- peripherals through the hardware ladder: Bluetooth and USB printers and scanners;
- one-hand, glanceable, fast interaction in a moving vehicle and in daylight;
- reliability: a delivery recorded is durable before it is shown as recorded.

### App development is native to the OS

User direction, 2026-10-30: the OS supports building software cleanly and natively, not by
bolting features onto Trough. Three kinds of app, one toolchain on the device:
- **Trough apps:** modules in the scene, built and hot-replaced in place (the default).
- **Full Android APKs:** built on the phone (aapt2, d8/r8, apksigner from the Debian root or
  the SDK's jars, all already run by the self-build), installed through PackageInstaller
  (REQUEST_INSTALL_PACKAGES), or run inside Trough as guests without installing.
- **Linux programs:** anything Debian builds.
An app's source, builds, tests and releases are records like everything else; agents work on
them with the user.

### Trough's own language

The long-term module language is Trough's own (user approves; low-level design is the builder's
call). Purpose: a language Trough itself parses, type-checks, specialises, compiles and
hot-replaces, so modules, user-built apps and lifted code share one representation that Lift,
the derivation planner and the models can reason about completely. Requirements: the
semantic substrate's contracts (records, revisions, derivations, effects, capabilities) are
language-level, not libraries; incremental compilation fits the derivation graph; it lowers to
native code (via C11 or LLVM) and to dex where Android APIs are needed; memory-safe by
construction at untrusted boundaries. Designed after the substrate it describes has settled;
until then C11 and Java remain the implementation languages.

### Model CLIs and Trough's own agent harness

Trough runs any model CLI as a first-class agent backend: Claude Code, Codex, Gemini CLI,
Antigravity's agent and any other, plus Trough's own loop over provider APIs (TroughAgent).
All share Trough's task, tool, permission and artifact semantics (DESIGN.md "Complete experience").
Beyond hosting them, Trough builds its own harness infrastructure for maximal model control.

**Harness self-improvement (user direction, 2026-10-30: the highest-value part of Trough).**
Models running in Trough can improve their own scaffolding as close to source as access allows:
- what is editable: Trough's own harness (TroughAgent: loop, tools, context assembly, memory,
  prompts) fully; open-source CLIs (Codex, Gemini CLI) rebuilt from source on the device and
  hot-swapped; closed CLIs (Claude Code) through their supported extension points (plugins,
  hooks, skills, settings). Model weights are out of reach and out of scope;
- how: a model proposes a change to a harness component; it is built and evaluated against
  pinned task suites and oracles (does it do the work better, at what cost); the result,
  diff and evidence are shown to the user, who approves activation; every change is a revision
  with provenance and one-step rollback. Evaluations are records, so improvement is measured;
- **the oversight layer is never model-editable:** permission and approval gates, the
  no-unwatched-turns rule, capability scopes, the change log and evaluation records, and the
  host. A model cannot widen its own permissions, approve its own changes, or hide a change.
  Changes to that layer come only from the user (or a builder the user directs, as code review).
Compute stays small; the aim is improvement that stays visible and under human control, not
speed of self-improvement.

### Continuous improvement without patchwork

User direction, 2026-10-30: once Trough runs, it is improved continually in small steps. In
Forge, stepwise patches made behaviour messy. The design must make small changes compose
cleanly at the most fundamental level:
- **Local by construction:** a module touches only its declared records, derivations, effects
  and scene regions. No global event bus, shared timers, ad hoc flags or duplicated state
  (Forge's DOM/JS/server copies), so a patch cannot create hidden coupling.
- **Every behaviour has an oracle**, so a change that breaks something else fails before it is
  activated, not after the user notices.
- **Every change is a revision with provenance** (what, why, which contract), and can be rolled
  back alone.
- **Consolidation is normal work:** when patches accumulate around a contract, the next step
  is a redesign of that contract with migration, measured by contract size, special cases and
  oracle coverage, not a further patch. Code that contradicts DESIGN.md is a bug in one of them.
- Hot replacement keeps all of this seamless for the user: improvement never needs a restart.

### Workarounds before sacrifices

User direction, 2026-10-27: when a platform limit blocks something, search hard for a workaround
before accepting any sacrifice: dropping a capability, pinning or skipping updates, narrowing
scope, or settling for a slower or less complete path. Search existing practice first (Debian,
Termux, mmdebstrap, upstream tools and their documentation), then compose or build a mechanism.
A sacrifice is a last resort, recorded with the workarounds tried and why each failed.

Example: Android has no app mount namespace, so Debian maintainer scripts could not write their
absolute /etc paths. Neither reverting the openjdk security update (its older version has the
same script) nor holding updates was the answer. Debian's own fakechroot, which mmdebstrap uses
for unprivileged installs, was the answer. It is scoped to maintainer scripts (native/execmap).
Earlier narrow-scope notes in code are judgements to revisit when a workaround needs them, not
prohibitions.

### Languages (open for discussion with the user, 2026-10-30)

Decided so far: the trusted core is C11 (user, 2026-10-30: no Rust rewrite); Java 8 source on
ART for the Android-facing layer (host and hot bundle); shell for build glue; C++ only where a
dependency requires it (HarfBuzz, built without its runtime). Open: the language(s) for the
browser engine, Lift, and the long-term module language for the co-evolving surface (one that
Trough can itself parse, specialise and hot-replace). Choose by measured cost on this device,
memory safety at untrusted boundaries, self-hostability on the phone and how well Lift can
reason about it. Do not add a toolchain speculatively.

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
and can have a small explicit ABI (language choice for the rest: "Languages" below). It handles trusted validated metadata, never raw web input.
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
boundary exchanges batches/handles; no per-draw reflective calls. (2026-10-28: native code
Java calls in-process is built against bionic as well as glibc; see docs/BIONIC.md.) An Android text-input adapter
preserves IME composition, selection, bidi, clipboard and accessibility contracts even when
the text itself is rendered by Trough.

(2026-10-30, user decision: native text.) Shaping and line layout move into native code
under Trough's ownership: system font fallback through the NDK's `AFontMatcher`, HarfBuzz
shaping (MIT, built as C++ without its runtime), bidi and break iteration from the system ICU
(NDK `libicu`, API 31+), and the line breaker ported from AOSP Minikin (Apache-2.0). The reason
is measured: about three quarters of a platform layout's working memory is native memory held by
Java peers until the GC runs (docs/EXEC.md "Operator costs"). Native layout frees when its job
retires, needs no JNI on the executor's workers, and runs under admission like any operator.
The platform stack stays as the differential oracle: native layout must match TextRunShaper,
LineBreaker and StaticLayout before it replaces them (docs/NATIVE_TEXT.md). Glyph masks stay
with the platform rasteriser for now, because each glyph is rasterised once and then cached.
The core stays C11 (user, 2026-10-30: no Rust rewrite).

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

## The browser: arbitrary web, site specialisation and security

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
The host declares isolated sandboxes (`Components$Sandbox0/1`, `android:isolatedProcess`;
`bindIsolatedService` gives one process per instance name, docs/HOST.md). Their lifecycle and
containment must still be proven on the device before claiming it. Platform sandboxing is necessary but does not replace
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

Userland (the Debian root) is Trough's filesystem and tool layer, run natively ("The Linux
filesystem" above); it is not the application host. Preserve the verified t.t/texec/
patched-glibc environment, apt/dpkg ownership and source-build facilities. Today's evidence
establishes working tools, not yet universal path compatibility; that is the layered path
virtualisation's job, not a reason to narrow the package set.
Provider and CLI adapters translate at entry/exit; no localhost daemon, HTTP UI server, SSE
internal bus or copied Forge plumbing. Network SSE remains a provider's external protocol.

The fixed APK (the host, docs/HOST.md) declares actual platform components and capabilities;
permission declaration is not runtime consent or an ability to bypass Android restrictions. Platform adapters hold
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

The finished product is the user's operating system: it runs independently of the Forge VM,
is never closed, and presents the full unified surface. Typing, history, media, gaze, the
browser, files, apps (native, Linux, web, lifted Android), agents and self-modification obey
the same revision/ownership laws. Reliable actions survive contention, process death and
recovery; replaceable work does not accumulate behind the user. Focus, Back, audio, model
permissions, origin isolation and saved-state acknowledgements agree across the whole experience.

Automated scenario oracles exercise the complete paths, including delayed IO, out-of-order
completion, stale observations, reload while jobs are live, process death, storage failure,
thermal pressure, IME composition and incompatible code/schema changes. Engine conformance uses
pinned test262/WPT coverage; rendering uses reference geometry, text and golden screenshots;
Lift uses round-trip and differential execution against the original artifact; guest apps use
their own behaviour as the oracle. Full-screen device evidence verifies real presentation.

No milestone is called the product because one chat screen boots. Internal construction stages
exist to make the complete design mechanically defensible.

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
- [VirtualApp](https://github.com/asLody/VirtualApp), DroidPlugin, Parallel Space: running
  unmodified APKs inside a host app (stub components, binder proxies).
- [smali/baksmali, dexlib2](https://github.com/JesusFreke/smali): dex disassembly and rewriting.
- [Shizuku](https://github.com/RikkaApps/Shizuku), [LADB](https://github.com/tytydraco/LADB):
  shell-uid access from an app via the device's own wireless debugging.
- [Termux](https://github.com/termux/termux-app): long-lived userland in an app (foreground
  service, wake lock, documents provider for its home).
