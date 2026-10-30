# Current handoff: Trough as the OS, 2026-10-30

## Start here (any model: Claude, GPT/Codex, others)

**What Trough is now.** The user's operating system, inside one Android app that is never
closed (DESIGN.md, redesigned 2026-10-30: read "Intent" and "The OS layer" first). The user may
switch between models (Claude and GPT) to build it; this file is the live state. Update this
"Start here" block whenever the state changes, and commit early: the phone that hosts the VM
crashes or powers off semi-regularly, and a context window is not memory.

**Where you are running.** A Debian arm64 VM (Android's Linux Terminal) on the user's Pixel 7.
The phone's shared storage is `/mnt/shared` (`/mnt/shared/Download/trough/` = the phone's
`Download/trough/`). The VM's `/tmp` is RAM: keep big files out. Trough itself runs on the phone,
not in the VM; the two talk through Download/trough (bundles, signed control channel, evidence).

**State on 2026-10-30 evening (Claude Opus 5.5):**
- Host APK rewritten to be final (docs/HOST.md): process-owned loader (TroughApp/
  HotLoader.process), bundle contract v2 (`boot`/`attach`/`detach`/`intent`/`dispatch`),
  every component and role declared as forwarding shims (Components.java, accessibility
  included), Activity/Application callbacks forwarded, 16 process slots in every launch mode,
  isolated sandboxes (one app-zygote), extra trusted keys + app-private inbox, revive job, boot
  receiver, all 892 framework permissions. Built, not yet installed:
  **the user must install `Download/trough/trough-os.apk`** (same signing key, data kept). Nothing in
  it has run on a device yet: after install, verify launch, Recents swipe -> reopen keeps the
  agent session, Back -> background, notification Quit, and a process kill -> revive.
- Hot bundle `061fd2ce` published (Power: `/awake`, screen on while visible, partial wake lock,
  Back to background, battery-exemption request; Main/Chat/Development `attach`). It is a v1
  bundle (`start`, no `boot`): with the new host it waits for a window, then keeps running across
  windows.
- DESIGN.md redesigned for the OS pivot; docs/CONSTRUCTION.md "OS layer" gives the build order.
- Native text (HarfBuzz shaping, Minikin-style) is on branch `native-text-wip`: written, never
  built or verified, no `android.shape` oracle yet. Its DESIGN.md decision text is on master.
- `tools/hot.sh` now touches the published files: shared storage kept the old mtime across
  rename, so the phone missed a bundle.

**Next, in order** (docs/CONSTRUCTION.md "OS layer"):
1. After the user installs the APK: device checks above; fix anything the host gets wrong.
2. Bundle `Main.boot` (headless start) + `detach` + `intent` routing + a `dispatch` table.
3. Complete Debian filesystem (DESIGN.md "The Linux filesystem"): layered path virtualisation.
4. Native text: build the branch, write `android.shape` vs TextRunShaper, then line breaking.
5. Then hardware ladder adapters, Lift (dex first), guest apps, browser, user-built software.
The user also wants to discuss programming languages for the OS (DESIGN.md "Languages", open).

**How to build, publish and verify.** AGENTS.md "Layout". Bundles: `tools/hot.sh` (signs with
~/.trough/hot-key.pem). Scenarios on the phone: `tools/loop.sh` (needs Trough's Activity alive;
visible-only scenarios SKIP when Trough is hidden; ask the user to bring Trough to the front
when you need them). New scenarios go in hot/.../Development.java AND tools/scenario.py's choices.
Phone results: `/mnt/shared/Download/trough/{status,bench,crash}-*.json|txt`, `evidence/<sha>/`.

**The user's standing preferences.** Full autonomy, including moving credentials. Keep going
past milestones unless there is a real question. Slow, oracle-first work is fine after an honest
estimate. Workarounds before sacrifices. No unwatched model turns. The accessibility service is
declared but a last resort.
No WebView. Short, direct messages; the user types fast and without spaces; read intent, not
spelling. The user guides high-level decisions and leaves low-level design to the builder.
Do not target the user's employer's devices or software (2026-10-30: they build their own).

# Previous handoff: autonomous construction, 2026-10-28

## Active continuation (2026-10-28)

Stage 3 started (late 2026-10-28): `native/core/derive`, the derivation graph ([contract](docs/DERIVE.md)):
demand, red-green verification on the world clock, early cutoff, Skyframe-style restarts for
dynamic deps, snapshot jobs, cancellation, cycles, eviction. `core.derive` matches a memo-free
reference (653795 checks / 30000 rounds), 10/10 mutants. Next: batch needs (35% restarts), then
real operators (layout/scene as nodes) and admission/leases.
Then `native/core/exec` ([contract](docs/EXEC.md)): owner thread + workers + cancellation + trace;
`core.exec` passes on the Pixel (bundle 1353a0fd5380), TSan/ASan clean. Next: real operators
(text layout via JNI, scene compilation) registered on the executor, then admission/leases.
Then `native/core/blobs` ([contract](docs/BLOBS.md)): canonical interned values with graph/executor
retain hooks; core.blobs passes on the Pixel (bundle e36731edbc61), 8/8 mutants. Next: layout operator
in libtrough-android.so (JNI trampoline into TextLayout on exec workers), android.derive oracle.
Done: android.derive ([doc](docs/LAYOUT_DERIVE.md)): 240 paragraphs byte-identical to direct layout,
exact job counts, duplicates share handles. Font identity by source id (Font.hashCode is 46 us).
Layout stops scaling at ~1.5x for both executor and raw Java threads (platform-side). Next: scene
compilation from layout blobs; glyph atlas as a fenced resource; simpleperf the layout ceiling.
Done: android.view ([doc](docs/VIEW.md)): layout -> height summaries -> viewport scene; scene bytes
equal a flat reference, edit costs exact (below viewport 0 jobs, same-height edit cuts off the scene),
GPU pixels equal platform. dv_set_static for fixed-dep operators. Next: glyph-mask nodes + atlas
resource with fence leases in the presenter; estimated heights for unmeasured paragraphs.
Done: native/core/atlas ([doc](docs/ATLAS.md)), fence-watermark residency, size-class pages, reclamation;
core.atlas shadow-GPU oracle 6/6 mutants. Next: presenter integration (mask nodes, partial uploads, N frames in flight).
Done: ps_frames_* (3-frame ring, GENERAL-layout atlas, partial uploads, in-order fence retirement);
android.frames on Mali: 300 frames, 200 overlapped, 0 bad; fence-ignoring mutant gives 1527 bad pixels.
Next: glyph-mask nodes + view scene through the frame ring to the swapchain (needs Trough visible).
Done: mask nodes + deriveFrame: android.view renders 5 scrolled frames from derived masks/atlas/ring,
0 bad pixels; masks rasterised once (73), later frames 0 uploads, 2-7 ms build. Next: swapchain
presentation of the ring (visible), estimated heights for unmeasured paragraphs.
Done: scroll anchoring + estimate summaries (fused into level-0): jump to middle lays out 6 paragraphs
(was 504; 105-253 ms), edits above the anchor run no layout/scene. Next: swapchain presentation
of the frame ring (needs Trough visible), owner scroll-by-pixels.
Done: deriveScroll (exact anchor walk, one viewport write); 5 scrolls equal the reference.
Written: ps_frame_present + deriveSurfaceOpen + android.viewsurface (needs Trough visible; not run yet).
Done (2026-10-29, VM): demand levels + admission (DERIVE.md "Levels and admission", EXEC.md
Priorities): urgent/visible/background roots, per-level poll limits, job levels follow demand,
background capped at workers-1. Under a background flood, urgent jobs wait 0.1 ms p50 (1.9 ms
without the reserve). Fixed a latent bug: a full poll stopped walking roots, so later roots'
running jobs were cancelled. Pixel run of core.derive/core.exec pending (phone rebooted, Trough closed).
Then: root list (idle poll 33 -> 0.07 us), dv_unsettled + ex_settle_at (urgent settles while
background runs), and view prefetch (derivePrefetch, VIEW.md "Background prefetch",
android.view prefetch_scrolls step). Bionic lib + Java compile in the VM; bundle 34936388
-> Pixel bundle 79bdc5b7eedb: all 38 scenarios pass, including android.viewsurface (first run:
16.2 ms/frame over 120 frames, 0 bad pixels) and prefetch scrolls (0 pauses, 0.1-0.4 ms).
Then (2026-10-29 morning, all on the Pixel): display timing (VK_GOOGLE_display_timing), fling
oracle (prefetch flings skip 0 vsyncs), frame pacing (derivePace 1 learned grid / 2 choreographer
deadlines; input-to-display 75 -> 28-35 ms p50, VIEW.md "Frame pacing"). An out-of-range anchor hung
the scroll walk once: deriveViewport clamps now.
Mode 2 slack now starts at 1.5 refreshes. DerivedView + android.touch: real MotionEvents drag and
fling the paced view; touch-to-display is 46.6 ms p50, and the position equals the reference
(VIEW.md "Touch"). Caret + taps done (VIEW.md "Caret", android.caret: 24/24 taps = StaticLayout, caret exact, 0 bad
pixels; DerivedView taps). Range selection done (VIEW.md "Selection", android.selection: 160 random ranges = getSelectionPath
pixel-exact, incl. bidi whitespace rules). Paragraph identity done (VIEW.md "Paragraph identity", android.paragraphs: inserts/removes never
relayout others; anchoring holds). Next: IME editing in the derived view: an InputConnection over the
document (paragraphs joined by '\n'), Enter splits (deriveInsert), backspace at start merges
(deriveRemove), verified against BaseInputConnection over an Editable like android.ime; then
CursorAnchorInfo from caret nodes; then the rope-backed order (O(log n) inserts).
Done (2026-10-29 afternoon): IME over the derived document (INPUT.md "Document"): TextInput.Backend
(Draft | DocumentText); android.docime on Pixel bundle 7daed7e9f654: 16 seeds, 4800 ops, 2064 queries,
0 mismatches, final scenes equal. CursorAnchorInfo (INPUT.md "Cursor anchor", android.anchor; caret
records now 20 bytes with baseline + RTL; deriveCursorAnchor). core.exec's urgent p90 check now
measures up to 3 times (flaked once cold). Next: DerivedView as the editor (onCreateInputConnection
over DocumentText, IME edits wake the render thread, taps -> updateSelection, requestCursorUpdates ->
updateCursorAnchorInfo after frames), android.touch extended to type; then character bounds,
marker for ranges/offscreen carets, rope-backed order.
Done (evening): bundle 5f12a5595147 all offscreen scenarios pass (android.anchor: 80 markers, 12 clipped,
14 RTL, worst diff 0). DerivedView hosts the IME (INPUT.md "Editor view"; android.touch types "typed\n"
through the view's InputConnection; needs Trough visible, NOT RUN YET). Character bounds (INPUT.md
"Cursor anchor"; TextLayout.characterBounds ports TextLine.measureAllBounds). core.order: B+tree order
(docs/ORDER.md), 9/9 mutants, ASan clean; adopted in derive.c (commit 988279f): order cells per slot,
summaries per slot, O(log n) doc offsets. Pixel run of 988279f pending (loop3). Next: fix whatever the
Pixel finds; ask the user to foreground Trough for android.touch/viewsurface/surface/vulkan; then
Android accessibility for the derived view (AccessibilityNodeProvider: paragraphs as virtual text
nodes, selection/scroll actions, vs TextView's node info), CONSTRUCTION.md stage 4.
Done (night): accessibility (docs/ACCESSIBILITY.md, android.access vs EditText; commit bb100e0) and
or_position (id -> position via parent links, 9/9 mutants; 7b02695). loop3 never ran: Trough was not
responding (activity/process gone after 07:09; the control poller lives with the Activity, so a
service-only restart is silent). loop4 (bundle with order tree + accessibility) is running after the
user foregrounded Trough at 07:29.
Later: keys (docs/INPUT.md "Keys": platform TextKeyListener + ArrowKeyMovementMethod on an Editable
window, android.keys), accessibility text-change events, colour glyphs (docs/TEXT_RENDERING.md
"Colour glyphs": RGBA atlas + per-glyph kind, colour-face detection from font tables; lavapipe 0 bad).
Bundle 039653916966 (Trough visible): android.touch passed incl. typing through the view's
InputConnection; viewsurface/surface pass; access (bounds clipping) and keys (Selection vertical-move
memory) fixed in 86947da; vulkan surface-lost now needs_visible_app. User 2026-10-29: don't work on
keeping the VM->phone loop alive; it goes away when Trough is its own app.
Loop timing measured 2026-10-29: tools/hot.sh 69 s (native/build.sh 51 s, Java 11 s); phone activation
~25 s Java + ~2 min native self-build only when native sources changed (cached by tree hash); 46
scenarios = 176 s of work, 348 s wall (~4 s control-channel overhead each; build.native 51 s). Prefer
publishing with NO_SCENARIOS=1 tools/loop.sh, then tools/scenario.py <name> for the affected ones.

Done (2026-10-30): admission verified on the Pixel (core.exec: peak 100/100, 0 violations; 202
and 42 without). Operator costs (EXEC.md "Operator costs", android.cost): layout ~20 KB + ~750
B/unit (Java ~180 + native peers ~565, freed only by GC), masks <= 31 KB; model 24 KiB + 896
B/unit and 64 KiB per mask covers every sample (>= 14% headroom). Every deriver now admits
by bytes (budget maxMemory/4, reserve 1/4). android.anchor had failed since emoji joined the
corpus: trailing whitespace takes the paragraph's direction (UAX #9 L1), fixed in rtlAt.
Bundle 361ee3ad5d74: cost, derive, view, frames, paragraphs, caret, selection, docime, anchor,
access, keys pass. User 2026-10-30: stay in C (no Rust). Next candidate: native shaping
(HarfBuzz) to drop the GC-held native peers, proven pixel-identical by the existing oracles;
or budget from memory pressure (onTrimMemory) shared with atlas leases.

DECIDED (2026-10-28): the Java/native boundary is a bionic build of the same sources, loaded
in-process (docs/BIONIC.md). All store oracles pass as bionic programs on the Pixel; android.jni
types 3000 edits from Java at 10.9 us p50 per call. Self-hosted: the phone's clang rebuilds all
34 outputs byte-identically (bundle fd1a907464b2). Hot reload retires the app's draft keeper
(Drafts.java, tests/retire.sh passes). EditorView hosts IME input with eventfd change
notification (android.editor). Native presentation substrate: Vulkan swapchain on a Surface
from the bionic library, Mali-G710 / Vulkan 1.4 / 165 extensions, 60 Hz pacing
(docs/PRESENT.md). Next: the presenter proper (swapchain lifetime, scene pipelines, glyphs,
damage), or stage-3 work planning. Rectangle and glyph renderer with pixel oracles (lavapipe,
Mali exact); text = platform TextRunShaper + Canvas.drawGlyphs masks + Trough GPU composition,
pixel-identical to the platform across 7 scripts (docs/TEXT_RENDERING.md).
TextLayout (Bidi + LineBreaker + TextRunShaper) equals StaticLayout on 115 lines (android.layout).
Carets and taps follow Layout's rules (primary-caret levels, nearest cursor stop): 0/2132 caret and
0/7653 tap mismatches vs StaticLayout on bundle fc978451ba7c.
Presenter on a Surface verified (android.surface, bundle 231f03730cdb): byte-identical to offscreen before and
after a resize; the swapchain extent now comes from surfaceChanged, not the lagging currentExtent. The InputConnection adapter exists
(docs/INPUT.md): android.ime matches SDK 37's BaseInputConnection over 16000 random IME ops.

Latest (late night): `native/store/keeper`, the storage owner thread ([contract](docs/KEEPER.md)):
attach/poke/flush/run/stop over draft queues, backoff, external-revision rebasing after tk_run.
`core.keeper` passes locally (ASan/UBSan, TSan, 7/8 mutants, survivor equivalent). Next: the
Java/JNI binding and Android InputConnection adapter that drive it, or stage-3 work planning.

Latest (night): store schema v3. Writes declare referenced objects (checked to exist,
recorded atomically); `ts_object_collect` removes unreferenced objects (crash/failed-save
orphans) and refuses on stores migrated with untracked revisions. td_save_text declares its
chunks. See docs/TEXT.md "Chunk references and collection". Next: stage-3 work planning
(docs/CONSTRUCTION.md section 3), or the storage-owner worker that drives the draft queue.

Latest (evening): tx_text adopted. TDOC v2 documents store a chunk manifest (td_save_text /
td_load_text; v1 still read everywhere; 8 SIGKILL cycles recover exact text). The draft queue's
live value is a tx_text: O(log n) edits, O(1) captures and snapshots, a chunk-aware rebase diff
(mutants 5/5 after adding periodic and leaf-boundary cases). Next: chunk retention/GC tracing
manifests from history and captures, then stage-3 work planning.
Pixel bundle `3da4e42327b3...`: all 12 scenarios pass, including indexed SIGKILL recovery and
the chunk-aware draft cases. Two phone-only test-timing faults were fixed on the way (the
kill clock now starts after the first durable revision; child saves are incremental).

Latest (16:30 UTC): `native/store/text`, indexed text ([contract](docs/TEXT.md)): immutable
chunk tree, O(log n) edits, O(1) snapshots, line index, chunk-level durable manifest. `core.text`
passes locally (ASan/UBSan, TSan, 10/12 mutants, survivors equivalent) and on Pixel bundle
`ee9f13e5c01b...` with all 12 scenarios; 13.4 us/edit at 4M units vs 262 us flat.
Next: adopt it in documents (TDOC v2 manifest) and the draft queue (O(1) captures), then
chunk retention/GC, then stage-3 work planning.

Latest (15:40 UTC): `native/store/draft`, the input persistence queue ([contract](docs/DRAFTS.md)).
Interaction side never waits on SQLite; one in-flight capture coalesces later edits; acks never
replace live text; external revisions rebase through retained batch ranges or become explicit
conflicts. `core.drafts` passes locally (ASan/UBSan + invariant, TSan, 8/8 mutants) and on the
Pixel: bundle `5eadad0bf702...` passed every scenario including `build.native` (now 23 outputs).
The VM's /data/data/t.t/d root lacks libsqlite3-dev, so tests/native_repro.sh fails at store.c
in the VM; the phone's own rebuild is authoritative. Next: indexed text/range storage (per-edit
cost independent of document size), then work planning (stage 3).

ARM CI crash root cause: texec zeroed the final file-backed page only to `p_memsz`. Linux's
`padzero` clears the whole tail. Ubuntu glibc 2.39's minimal calloc allocates after `_end`
assuming zeros, so ELF note bytes became link_map pointers (`0x696e6900646c7472`). Runner
`40709414561` contains register/mapping evidence. Clear the full page tail. New independent
`tests/loader_pages.py` poisons non-load bytes: native Linux passed, old texec failed with 23,
fixed texec matches Linux for both small and multi-page BSS. CI now gates this before loader
integration. CI run `40710673505` at `40fbc2c`: all four jobs green, including the ARM texec
seccomp-simulated loader oracle. The Ubuntu integration crash is resolved by evidence.
References: [Linux ELF loading](https://codebrowser.dev/linux/linux/fs/binfmt_elf.c.html),
[glibc initial allocation](https://codebrowser.dev/glibc/glibc/elf/dl-minimal-malloc.c.html).

The next automation dependency is now implemented: `build.native` uses `NativeReproduction`
and the native supervisor to rebuild immutable bundle sources, run tfix and compare all 22
outputs byte-for-byte, with bounded memory/output and scratch/descendant cleanup. No candidate
installation. `SelfTest.nativeRepro` uses the same executor; the signed controller/loop include
the scenario. See [BUILDS.md](docs/BUILDS.md). All 25 protocol + 16 reproduction JVM checks and
the Android DEX build pass. Phone bundle `53c97e3ffb698f7ac79bae2a4f828f206a0d4a8dd6cd0c36373c684c55230f8a`
passed all output comparisons (native build 8515 ms); signed evidence in `out/evidence/`.
Continue remaining stage-2 ownership, indexed text and work planning after CI/loader verification.

Resumed after the user's VM interruption. User clarified free compute means **GitHub Actions
standard runners for this public repository**, not Codespaces. The mistakenly created unused
Codespace was deleted and the account's list confirmed empty. Do not create another for this
work. CI at `b02a407` exposed Ubuntu's fortified `write` warning in the supervisor; handle the
atomic exec-error pipe write and EINTR, preserving failed-command status if reporting fails.
Fortified ASan/UBSan process tests (both child enumeration paths) and all 25 JVM scenario
checks pass locally. Add ARM loader failure diagnostics to obtain evidence for its remaining
SIGSEGV; the previously saved loader failure is not resolved yet.

Resumed again at 14:09 UTC on the user's `continue`, followed by confirmation to use GitHub's
free compute. The earlier five-hour window below is historical, not an instruction to abandon
the resumed work. No subagents are authorised by the current session instructions.

Latest source: owned process supervision ([contract](docs/PROCESSES.md)). All native signed
scenarios now use `trough-run`: deadline/cancellation, subreaper adoption, process-group cleanup,
kernel-checked child enumeration, bounded output and a separate private result report.
Phone testing found inherited Android signal state breaking child signal behaviour; command
startup now resets signal actions/mask, with a regression oracle. `core.processes` passed on
Pixel bundle `a1ae3a6134c80267bb78ec2d8877bb3067ebd308301d3ddd9b6e2b25ce79e2c3`, including
escaped descendants and parent death; Android exercises the `/proc` scan fallback. Local
ASan/UBSan passes both enumeration paths; JVM protocol/process suite passes 25 checks.
Task/workspace phone checks also passed on bundle
`1519281040acf179b9b2e721cb8e76546cb8385237dc80df11efeb314e947657`.
Signed receipts are in ignored `out/evidence/`; source and reproduction commands are committed.

GitHub was behind at `8ddd95e` (latest old run 40516699566): APK job passed, ARM loader oracle
failed with SIGSEGV in dynamic execution. The new core changes had only VM/phone evidence.
Current CI separates loader ARM tests from the core ARM tests, includes sanitizer/process
oracles and uses only standard public-repository runners. Publish the checkpoint and inspect
fresh CI results; do not claim the old loader failure is fixed without evidence.
Next automation work: expose native build reproduction in the signed registry using the new
supervisor, then continue stage-2 text/work planning ownership. Full app remains unfinished.

Historical checkpoints from earlier in this continuation:

The user resumed after a VM interruption and authorised continuing the full Trough build.
At 06:58:35 UTC they said they were going to sleep and to continue until the five-hour limit
(11:58:35 UTC). Keep constructing/testing autonomously within the design; no routine user
testing or permission questions. Save source/evidence as work proceeds. Do not interpret this
checkpoint as completion of the full application.

Recovered uncommitted work extended the effect outbox with bounded metadata pagination and
owned request/outcome retrieval; immutable effect-kind matching and the store archive were
also pending. Those changes pass the existing 20,000-operation differential store oracle,
263-intent pagination/restart checks and 20 SIGKILL cycles, including ASan/UBSan.

New stage-2 components:

- `native/store/document`: canonical lossless UTF-16 documents, atomic pure edit batches,
  revision-checked durable saves, immutable historical reads and idempotent prepared Ask
  capsules with exact document/source/route-policy dependencies. [Contract](docs/DOCUMENTS.md).
- `native/store/stream`: stable named heads/entries, atomic append/tail publication, exact
  historical content/provenance and bounded snapshot pagination. [Contract](docs/STREAMS.md).
- Store schema v2 adds immutable key-to-record associations in the same record/effect commit.
  Regression cases cover migration from v1, rollback of allocated IDs even when the same ID
  appeared in an absence read, tombstone reservations and signed revision exhaustion.
- Object puts verify existing bytes, atomically repair corrupt same-size objects and sync
  directory entries on retries instead of acknowledging an unverified existing pathname.

Native build/archive/source inventory, CI, signed scenario registry, controller and interpreter
patching include `core.documents` and `core.streams`. Local document oracle: 4000 differential
edit batches, 571 stale saves, 40 reopens, quota/maximum-size and SIGKILL composition/capture.
Local stream oracle: 1200 appends across two streams, history/pagination/provenance, 12 reopens,
quota/corruption and 10 SIGKILL cycles. Both pass with ASan/UBSan. These are storage semantics,
not actual Android IME/rendering or live application replacement evidence.

Phone evidence so far: `core.documents` passed against bundle
`d9a7a12309003870e70a7442dadd8921a5221a22b1c22f24ad8936d177fcafdb`; signed receipt in
`out/evidence/core.documents.json` and `.sig`. This was before the stream/schema-v2 changes;
those still require publication/device verification at this checkpoint.

Update 07:30 UTC: source checkpoint `b944df7` was published as bundle
`bbd56708620936a106869a622bf7bb8add681db4b21ef977daa81dfc61699cb6`.
Both `core.store` (including schema-v2 migration/object repair) and `core.streams` passed
on the Pixel; signed receipts are in `out/evidence/core.store.json` and `core.streams.json`.

The next source checkpoint adds `native/store/task` and `workspace`: Ask now has one
atomic effect/stream acceptance; stable workspace roots publish draft + stream + key links
together. [Task contract](docs/TASKS.md), [workspace contract](docs/WORKSPACES.md).
Their oracles include full-store rollback, idempotent recovery without resetting edits,
missing-child errors, ten task SIGKILL cycles and a combined root -> Ask -> later composing
draft -> SIGKILL -> root-only recovery case. Both pass locally under ASan/UBSan; device
publication/verification is next. Executable interpreter fixing now inspects all bin outputs,
so adding a scenario cannot accidentally omit the phone's texec path.

Next automation issue found by inspection: `ScenarioProcess` only destroys its direct child,
although store scenarios fork and native builds spawn compiler descendants. Before exposing
build scenarios, add owned descendant cleanup with a native supervisor (process groups,
subreaper adoption and guarded child enumeration), then prove it on the phone. This is a
new work item, not an implemented capability at this checkpoint.

Next: finish verifying/publishing this stage-2 work, then continue the remaining ownership
contracts (workspace/artifact/task records, input persistence queue, indexed text/range
storage and work planning), preserving the existing loader and autonomous phone loop.
The final scene/browser/media/IME stack remains substantial work, not a completed product.

---

# Previous handoff: unified design and hot replacement, 2026-10-27

## Device evidence, 2026-10-27 night (Opus, building on Astra's tree)

Astra's tree is committed (83d6701) and running on the phone (Pixel 7a, SDK 37) from a fresh
install. `tools/loop.sh` publishes a bundle, waits for that exact bundle to answer over the
signed control channel, runs every advertised scenario and files signed receipts in
Download/trough/evidence/<bundle sha>/. core.publication, core.resources and core.replacement
all pass on the device (about 105 ms each), including immediately after two publishes 3 s apart.

Found by the loop and fixed (ea0273e): a bundle replaced while still booting entered late and ran
a stale Chat + control poller beside its successor (build-mismatch replies from an old bundle,
IOExceptions from its deleted directory). The APK host now owns recovery: newest verified bundle
first, last-known-good (settled 15 s) fallback, a bundle that kills the process before settling
is never reactivated (marker keyed by pid), crashes copied to Download/trough/crash-*.txt,
bundle checks continue behind other apps, split-view resizes no longer recreate the activity.

Stage 2 started (ff441a0): native/store, a SQLite record store (captured-read commits, revisions,
effect journal with issued -> unknown recovery, verified content-addressed objects). Oracle
core-store passes in the VM (ASan/UBSan, 6/7 mutants caught; fsync loss needs a power cut) and on
the phone (9.7 s). Not yet used by any product component.

Open blocker found by the store's build dependency: adding libsqlite3-dev to the root's package
list let apt take an openjdk-21 security update. Fixed on the way (bff51ca, latest execmap):
Android's /bin is a symlink to /system/bin, so execmap now runs the root's copy first; bionic
platform binaries exec'd from the root no longer inherit glibc's LD_LIBRARY_PATH/LD_PRELOAD.
Resolved by workaround (DESIGN.md "Workarounds before sacrifices"): maintainer scripts run under
Debian's fakechroot (shipped in the bundle's lib/), injected by execmap only when dpkg sets
DPKG_MAINTSCRIPT_NAME, with host paths (/data, /proc, /system, ...) excluded. Platform binaries
exec'd from scripts use libc's own execve/realpath, since fakechroot's re-add loader variables
(b7cfaf9). Oracles in tests/execmap.sh. Phone result: the package list
install (libsqlite3-dev + the openjdk 21.0.13 security update) exits 0; alternatives repaired;
diagnostics bundle then ran javac/java OK and the phone's native rebuild was byte-identical for
all 14 outputs including core-store (status-1793138827813.json).

Next construction dependency (unchanged from below): durable meaning and effects
(docs/CONSTRUCTION.md stage 2) on top of the stage-1 kernel, each with a phone scenario in the
same registry so loop.sh proves it on the device.


Read [DESIGN.md](DESIGN.md), [construction](docs/CONSTRUCTION.md), and
[hot replacement](docs/HOT_RELOAD.md) before continuing. The namespace investigation below is
preserved evidence, not the current task. The old chronological DESIGN.md is preserved in
[IMPLEMENTATION_HISTORY.md](docs/IMPLEMENTATION_HISTORY.md).

## User intent, including corrections during this work

The user wants a coherent end-state Trough, with Forge's final UI as the interaction/layout
reference. "Parallelisation" means harmony across representations, ownership, computation,
resources and presentation, not merely threads. No stopgap products or incremental debug-chat
patches. Build real internals against one design; automated verification is the builder's job,
not repeated user-operated testing. Do not claim a whole new browser can be completed in a
few days/weeks without evidence.

The user then asked specifically about Opus making hot reload the first foundation. Preserve
that decision and the working loader/self-build infrastructure. The design now explicitly
puts state/resource-preserving replacement alongside identity/ownership at the beginning.
Do not demote it to a final UI feature or discard working Opus mechanisms for an abstract rewrite.

## Latest clarification: seamless evolution and autonomous construction

The user wants effectively any application-level change to become seamless, treating hard
migration/handoff cases as valuable architecture work rather than settling for a small set of
compatible reloads. This is a target to expand with real continuity evidence, not a present
capability claim or a promise across every externally controlled platform failure.

The user will stay in the VM environment until Trough is substantially complete, keeping both
apps open in Android split view. The builder must control/test/build/refactor autonomously,
without routine user input. Define cases, build until passing, expand coverage and repeat.
Ultimately that same loop must be wholly owned within Trough, including future feature work.
The user acknowledges the large ground-up browser/native filesystem-or-port/runtime scope;
hardware changes are out of scope. Plan, implementation and end state must remain cohesive.

See [AUTONOMOUS_DEVELOPMENT.md](docs/AUTONOMOUS_DEVELOPMENT.md). This clarification updated
contracts only; it did not implement a remote controller, verify split-view access, deploy
another bundle or prove seamless resource migration. The next foundation work includes typed
scenario control/observation alongside resource leases and module activation/retirement.

## Changes and status

- DESIGN.md is an end-state architecture instead of contradictory chronological additions:
  one semantic model, specialised physical representations, explicit ownership and causality.
  Includes whole-product paths, trust, persistence/effects, rendering, measurement and completion.
- Construction and hot-replacement contracts distinguish planned components from implemented code.
  Compiler/engine selection, full document standards coverage and isolated-service topology remain
  major explicit dependencies. No WebView fallback or complete-browser claim.
- Initial callback-batching work in the old Chat was removed after the user's correction. Chat
  and its UI are unchanged. Experimental files are outside the repo at
  /tmp/trough-local-patch-experiment, not a product dependency.
- native/core/world.{h,c}: bounded owner-serialized publication kernel with exact read revisions,
  mandatory module revision capture, write-conflict validation, atomic output sets, unchanged-value
  pruning, generation-safe identities and failed-staging transaction poisoning. Allocation-free.
  Values are canonical immutable identities/scalars, not an implemented object/resource store.
- Native build emits lib/libtroughcore.a. Hot bundles include sources; Self/SelfTest and native
  reproduction inventory include the archive. It is not linked into the debug UI or a completed
  runtime yet. Existing loader, userland, provider protocols and UI behaviour were preserved.
- CI has portable sanitizer and arm64 kernel oracles. No commit, publish or phone reload occurred.

## Verification in this workspace

- tests/core_world.sh passed 10,000 differential completion interleavings (3991 valid publications,
  6009 correctly rejected stale results), plus atomicity, identity, capacity, no-op and exhaustion
  controls. AddressSanitizer + UndefinedBehaviorSanitizer run passed.
- Full native/build.sh passed, and hot/build.sh produced DEX with the Android API 35 SDK/D8.
- The root's actual GCC built the native outputs under tests/seccomp_sim; libtroughcore.a was
  byte-identical to the VM compiler output, SHA256
  8e9b485f9dc4c68a5923f92dadea70a21ad932f67f151d9d4b247a56caf97451.
- The root's GCC built the kernel oracle under the same simulated filter; that executable passed
  all checks under the filter. These runs used /tmp with host filesystem visibility. They are
  NOT the restricted phone-filesystem harness or a real-device run. The updated full
  tests/native_repro.sh was not executed in that restricted harness during this turn.
- Shell syntax and git diff --check passed. No latency, energy or whole-app speedup claim.

## Next construction dependency

Extend the semantic kernel with immutable resource leases and revision retirement, together with
module prepare/activation/quiescence contracts. These make state-preserving hot replacement
possible before product components depend on it. Develop against the complete design, not by
injecting isolated performance patches into Chat. See construction map for downstream obligations.

---

# Codex → Opus: namespace investigation, 2026-10-27

The user asked Codex to investigate the namespace/machine-code work where Opus stopped,
understand why it was attempted and why it stopped, and pursue maximum native performance.
The user explicitly redirected us away from implementing an apt mirror workaround.
Latest instruction: check whether there is any way around the namespace limitation; if
not, report back to Opus so the user can switch back. No root/kernel change was requested.

## Result

No supported way was found to give the current unrooted Trough app a real Debian-shaped
mount namespace on this phone. This is a device finding, not a project prohibition on
namespaces or native code. The performance objective remains viable without namespaces.

Opus's visible session replies say its safety classifier stopped the machine-code path
edit and the namespace attempt. The classifier's rationale is unavailable. That stop
was not a completed technical test and is not evidence that the approach was unusually
powerful. Its purpose was to let stock binaries resolve paths such as /usr/bin/sqv without
per-call userspace path translation.

## What is actually verified

Commit `3bb37b7` adds `native/nsprobe/nsprobe.c`, hot-bundle integration and an arm64 oracle
(`python3 tests/namespaces.py`). Each operation runs in a disposable child. The oracle
checks ordinary clone and distinguishes deliberately trapped chroot from kernel errno.
Both oracle controls passed; the signed bundle ran on the actual phone. Existing on-device
gcc/C/javac/Java checks also passed. No speedup was measured or claimed.

Phone evidence: `/mnt/shared/Download/trough/status-1793109991469.json`.

| Operation / observation | Actual phone result |
| --- | --- |
| Ordinary clone | success |
| NEWUSER, via unshare and clone | EINVAL (22), no SIGSYS |
| NEWNS, via unshare and clone | EPERM (1), no SIGSYS |
| NEWUSER + NEWNS, both interfaces | EINVAL (22), no SIGSYS |
| chroot | SIGSYS; our handler returns ENOSYS (38) |
| /proc/self/ns/user and pid | ENOENT |
| /proc/self/ns/mnt and net | present |
| App capability sets, including bounding | all zero |
| App seccomp | mode 2, one filter |
| Reading final kernel config from app | EACCES |

Phone kernel: `6.1.162-android14-11-g2ec90535fa34-ab15810641`.
VM kernel: 6.12; the VM successfully creates user namespaces. Do not use the VM result as
proof of phone support.

At the phone's exact common-kernel revision, [gki_defconfig](https://android.googlesource.com/kernel/common/+/2ec90535fa34/arch/arm64/configs/gki_defconfig)
does not enable USER_NS and explicitly disables PID_NS. [Kconfig](https://android.googlesource.com/kernel/common/+/2ec90535fa34/init/Kconfig)
defaults USER_NS to n; the disabled branch in [user_namespace.h](https://android.googlesource.com/kernel/common/+/2ec90535fa34/include/linux/user_namespace.h)
returns EINVAL for NEWUSER. Missing kernel support is strongly supported by source plus
device behavior; the phone's final merged runtime config was not readable, so don't claim
we extracted it.

## Remaining routes and their limits

1. **Root service / privileged launcher:** can potentially create a private mount namespace
   using the existing mount-namespace support, set up a root, then launch the workload.
   USER_NS is not required for a privileged mount namespace. It needs the relevant Linux
   capabilities and SELinux permission and must launch outside the app's inherited filter.
   Root availability and this launch path were not tested or implemented. A custom kernel
   is not inherently necessary for this route.
2. **Kernel with USER_NS enabled:** could make the unprivileged namespace route possible,
   subject to Android's remaining syscall/security policy. A config flag alone is not a
   promise that a production Android app can use it. No kernel was built or flashed.
3. **ADB / Shizuku in shell mode:** no supported solution found. Standard production adbd
   drops to shell without CAP_SYS_ADMIN/CAP_SYS_CHROOT; Shizuku inherits its startup
   authority. It cannot supply a kernel feature compiled out. This is source/documentation
   analysis; there was no connected adb device to run a separate shell probe.
4. **Join an existing mount namespace via setns / pass a namespace FD:** still requires
   capabilities; a namespace FD does not itself grant them. Existing Android mount
   namespaces don't provide an arbitrary app-controlled Debian root.
5. **Keep native execution and adapt paths at build/install time:** remains a performance
   option without syscall interception. The specific apt verifier launch is a source-level
   path choice in `methods/sqv.cc`; rebuilding that component for the Trough prefix can
   preserve its full verification behavior. This was not implemented here, per the user's
   requested namespace focus. Avoid claiming the entire userland is now portable or that
   every binary's hardcoded paths can be safely replaced in place.
6. **PRoot / VM:** compatibility alternatives already in the design, with their respective
   syscall-translation or guest resource costs. They don't satisfy a zero-interception
   namespace solution inside the current app.

References checked:
- [unshare requirements](https://man7.org/linux/man-pages/man2/unshare.2.html)
- [setns mount-namespace capability checks](https://man7.org/linux/man-pages/man2/setns.2.html)
- [seccomp filter inheritance](https://man7.org/linux/man-pages/man2/seccomp.2.html)
- [AOSP adbd privilege drop](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/daemon/main.cpp)
- [Shizuku authority and limitations](https://github.com/RikkaApps/Shizuku)
- [apt 3.0.3 sqv method source](https://raw.githubusercontent.com/Debian/apt/3.0.3/methods/sqv.cc)

No root, bootloader, kernel, Android policy, signature verification or model permissions
were changed. Do not conflate Opus's classifier interruption with the kernel limitation.
Do not resume probing unchanged NEWUSER flags hoping a different spelling makes them work.
Continue the user's native-performance design with these constraints visible.
