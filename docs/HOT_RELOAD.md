# Hot replacement is a founding contract

2026-10-27. The user asked why Opus built reload first and whether that remains the foundation.
**Yes.** Preserve the working loader and self-hosted build. Extend their lifetime/state contract
before building the new product's consumers; do not defer this until after the renderer exists.
This document specialises [DESIGN.md](../DESIGN.md), not an alternative architecture.

## What Opus established, and what remains

The current APK verifies development bundles and loads DEX entry points. The signed boot seeds
app-private source, builds Java with the phone's toolchain, loads the result through a separate
class loader and rebuilds native sources. Source identity avoids some unchanged builds. A
failed self-build can keep the prior running code, and startup has a bundle fallback. This is
valuable, working infrastructure; deleting it for a theoretical ideal would discard evidence.

It is not yet general state-preserving hot replacement. HotLoader stops the current entry and
clears the view tree before starting the next bundle. Main's self reload likewise stops the
running entry and reconstructs the UI; Chat closes its agent. Native replacement installs
files for subsequent process starts, not a proof that already mapped code, in-flight callbacks
and device resources were migrated atomically. Session resumption restores some context, not
all transient state. These limits define the extension work; they do not make the foundation
wrong or disposable.

## Three lifetimes

1. **Bootstrap/recovery lifetime.** The small APK host verifies and activates a trusted runtime,
   exposes actual platform mechanisms, and can recover without the replaceable program being
   healthy. It changes by APK update when its ABI, platform components or recovery mechanism
   genuinely need to change. "Everything reloads" is not a ban on updating the loader itself.
   Implemented (APK of 2026-10-27, `app/src/com/trough/app/HotLoader.java`): cold start runs
   the last bundle that stayed up 15 s (`files/hot/good`) without reading shared storage; a
   bundle that kills the process before settling is marked `files/hot/bad/<sha>` and never
   reactivated; uncaught crashes are copied to `Download/trough/crash-<ms>.txt`; bundle checks
   continue behind another app (1 s visible, 5 s hidden) so VM-driven scenarios need no focus.
2. **Runtime ownership lifetime.** Stores, resource arenas, effect receipts, module registry and
   scheduling/lifetime bookkeeping have explicit owners independent of a UI feature's code.
   These owners can themselves be replaced through coordinated migration; they are not all
   placed permanently into the APK or assumed magically immortal.
3. **Module execution lifetime.** One code revision with an explicit schema/ABI and leases on
   state, jobs, callbacks and device resources. Several revisions may coexist while old work
   retires, but only the current authorised revision may publish affected state.

The ownership lifetime often outlives the module revision using it. That is how typing,
playback, scroll anchors and tasks survive a code change without forcing all state into
serialized restart snapshots on every edit.

## Replacement protocol

`build -> validate -> prepare -> quiesce affected admission -> activate -> retire`

- **Build:** immutable candidate; identity covers sources, generated contracts, resources,
  compiler, flags, target ABI and dependency versions. Build off the interaction path under
  the same admission/resource rules as other background work.
- **Validate:** machine-run correctness, ABI/schema and resource-contract checks. A candidate
  cannot acquire irreversible effect authority simply because its entry point loaded.
- **Prepare:** load beside current code; migrate into privately owned candidate state; compute
  the affected domain/dependency closure. No mutation of published state during preparation.
  If live state advances while preparing, validate/rebase or retry the migration.
- **Quiesce affected admission:** stop issuing new work to old affected operators; revoke
  stale publication rights. Unaffected media, documents and task domains continue. Pure old
  work may finish without committing. External effects retain their receipts/owners.
- **Activate:** atomically publish the compatible code/schema/state revision and entry points
  for the affected consistency domain. A multi-domain ABI change needs coordinated activation,
  not several independent pointer assignments that leave mixed generations observable.
- **Retire:** old code and resources remain until there are no jobs, callbacks, object methods,
  destructor calls or device fences that can refer to them. Then release/unload. Where Android
  class-loader reclamation is GC-managed, dropping references is not a guarantee of immediate
  memory return; measure and bound retained generations.

An activation receipt distinguishes candidate validation from actual successful activation.
Keep a last-known-good recoverable image. A post-activation rollback must validate reverse
migration or recover from a compatible snapshot/journal; do not resume old code against state
that new code has already changed incompatibly. Failed preparation leaves the current system
running, rather than presenting a replacement error screen as the new application.

## Native performance and replaceability are compatible

The boundary is a module/plan unit, not every function call. Inside a compiled unit, fuse,
inline, vectorise and use whole-program optimisation. At a stable entry point, acquire a code
revision and immutable input/resource leases once for the work batch. Do not put reflective
lookup or an atomic "has code changed?" check inside every loop iteration.

Native candidate libraries have versioned identities. No overwriting executing instructions
or blindly preserving C struct addresses across a layout change. State has explicit schemas,
stable handles and owner lifetimes; code owns only what its retirement contract can reclaim.
GPU pipelines/resources and codec sessions also have generations and device completion fences.

The new `native/core/world` kernel captures a module revision as a mandatory input to each
transaction. Thus a job compiled/executed under old semantics cannot publish after that module
changes. This is one necessary reload law, not the entire activation or resource-retirement
implementation. The resource lease manager and module coordinator are the next dependencies
before product components use it.

## The Forge lesson: every change should feel like a CSS edit

User observation, 2026-10-30: in Forge a CSS change went live imperceptibly ("just boom, new
feature"), but a JS change made the screen flicker and reset to default states, which does not
scale. The difference is structural. CSS only restyled state that stayed alive; JS replaced the
code that *owned* state, so the state died with it. Trough generalises the CSS case to every change:
- **State is never owned by replaceable code.** Everything the user can perceive (scroll
  anchors, focus, selection, IME composition, expanded/collapsed and in-progress UI state, media
  position, in-flight turns, form input) lives in records owned by the runtime (store, scene,
  drafts). New code re-derives its outputs from the same records; it does not rebuild them.
- **No frame without content.** The old scene stays presented until the new revision's first
  complete frame is ready; then one publication swaps them. A reload never shows a blank,
  partial or default-state frame.
- **Identity is stable across revisions.** Records, scene nodes and view regions keep their IDs
  across code revisions, so anchors, focus and animations continue rather than restart.
- **Acceptance oracle (one per change class):** snapshot the observable state and the presented
  frames before and after a reload under load (typing, scrolling, media playing, an agent turn
  streaming); fail on any reset field, any frame missing content, or any input lost or reordered.
Host mechanism (docs/HOST.md, 2026-10-30): a bundle with `replace(...)` takes over from the
running one with no stop and no cleared screen; `Vault.objects` carries platform-typed objects
across generations. Large native state should live in a library loaded once per process with
`dlopen` (a C ABI, outside any class loader), so a reload swaps only the thin JNI layer: the
JNI library itself is bound to one class loader and cannot be shared between generations.
Today's chat console (hot/.../Chat.java) violates this: each reload rebuilds its views and feed
(drafts survive, scroll does not). It is a development instrument; the product surface on the
derived view/scene path must pass the oracle above, and the console should be retired onto it.

## Different changes need different scope

| Change | Replacement scope | Preserved when compatible |
| --- | --- | --- |
| Theme/layout rule | affected scene operators and caches | draft, playback, task/session state |
| Agent policy/adapter | its task operators and connection boundary | captured requests, durable history, issued-effect receipts |
| Text/layout implementation | text operators and derived caches | text revisions, selection, IME state under its contract |
| Media/inference implementation | affected device pipeline at a safe boundary | source intent, clocks/state where the new implementation can adopt them |
| State schema/core ABI | coordinated migration of affected owners/dependents | only state/resources whose compatibility is established |
| Manifest/platform host | APK update plus durable recovery | app data under a verified compatible migration |

The target is **effectively any application-level edit with user-observable continuity**.
An incompatible representation is a migration problem to solve, not automatic permission to
interrupt the experience. A new decoder can be prepared alongside the old one; a new schema can
be populated from a snapshot plus subsequent changes; a replacement renderer can prepare its
first coherent frame before taking ownership. These are mechanisms to implement and prove,
not claims that the present app already supports them.

Preserving experience does not require preserving the same internal objects. Define the
continuity envelope for each path: input acknowledgement and composition, audio continuity,
frame freshness, caret/anchor, task identity, accepted effects and retained data. Holding a
stale frame indefinitely or buffering input without a bound does not count as seamless.

Each change class must have a migration/overlap/handoff strategy, a resource budget and an
automated continuity case. Track the uncovered classes as architectural work. When preparation
cannot fit alongside the running version, plan bounded staged migration, shared immutable
resources or an explicit deferred activation while keeping the current version useful. Do not
trade seamlessness for unbounded memory, heat or a permanently retained old implementation.

Some boundaries are externally controlled: Android package/process replacement, unavailable
permissions, lost hardware/network resources and already-issued external effects. Distinguish
a demonstrated platform limit from an unimplemented handoff. Do not claim literal seamlessness
across every possible external failure, or treat a known limit as a blanket exemption for
ordinary application edits. Hardware changes remain out of scope.

This capability enables progressively more substantial live changes, including future automated
self-improvement experiments. Such experiments use the same independently checked semantics,
capability boundaries and activation evidence; a candidate cannot declare its own success by
weakening its evaluator. This is an architectural affordance, not a claim of recursive
self-improvement or authority for unbounded autonomous scope growth.

## Automatic proof obligations

Reload while typing/composing; while a scene is queued but not presented; while inference
owns an image; while a decoder owns a surface; while a tool waits for permission; while an
external effect has an unknown outcome; while the build inputs change; and while a previous
revision is still retiring. Include migration/build failure and process death at each durable
activation boundary. Verify preserved state, stale-work rejection, effect uniqueness where
supported, resource reclamation and bounded retained generations. These are automated builder
checks, not repeated user-operated tests.
