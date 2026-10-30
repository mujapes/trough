# Trough -- instructions for any coding agent (Codex, Claude, others)

Trough is the user's operating system, running inside one Android app that is never closed
(DESIGN.md, redesigned 2026-10-30). Several models build it in turn; the repository is the
shared memory. Start here, in this order:
1. [HANDOFF.md](HANDOFF.md) "Start here": what is running, what is verified, what is next.
2. [DESIGN.md](DESIGN.md): what Trough is and every decision so far, with dates.
3. [docs/HOST.md](docs/HOST.md): the fixed APK and the bundle entry points.
4. [docs/CONSTRUCTION.md](docs/CONSTRUCTION.md): build order and status; the docs/ contract for
   whatever subsystem you touch.

**Read [DESIGN.md](DESIGN.md) before changing anything.** It is the source of truth for what
Trough is and every decision made so far, with dates. If your change contradicts it, either
stop and ask, or update DESIGN.md in the same commit and say why.

Current cross-model handoff: read [HANDOFF.md](HANDOFF.md). The current construction contract is
[docs/CONSTRUCTION.md](docs/CONSTRUCTION.md); historical device evidence lives in
[docs/IMPLEMENTATION_HISTORY.md](docs/IMPLEMENTATION_HISTORY.md).

## Unified design (user clarification, 2026-10-27)
- Parallelisation means harmony across the complete system: shared meaning, compatible
  representations, ownership, causal dependencies and removed work; not merely more threads.
- No stopgap products, incremental chat-UI patches or repeated user-operated testing. Build
  the actual end-state components in dependency order. Automated verification is our job.
- Autonomous build/test/refinement is a core product capability: read
  [docs/AUTONOMOUS_DEVELOPMENT.md](docs/AUTONOMOUS_DEVELOPMENT.md). The VM remains the user's
  environment until Trough is substantially complete; both apps run in split view. Build
  real control/observation, require no routine user taps/tests, and ultimately move the same
  loop wholly into Trough. Do not assume the control harness already exists.
- Target seamlessness for effectively every application-level edit. Expand automated
  continuity coverage and migration strategies rather than accepting only easy reloads.
- Acknowledge the full browser/filesystem/runtime scope; no scope-cutting to declare an early
  demo complete. Hardware modification is out of scope. Keep design, code and evidence aligned.
- Forge's final UI is the interaction/layout reference; its architecture is not transplanted.
- Preserve Opus's working hot-loader/self-build foundation. State/resource-preserving replacement
  is a founding contract: read [docs/HOT_RELOAD.md](docs/HOT_RELOAD.md), not a final UI feature.
- Workarounds before sacrifices: when a platform limit blocks something, search existing
  practice (Debian, Termux, upstream tools) and build a workaround before dropping capability,
  skipping updates or narrowing scope. See DESIGN.md "Workarounds before sacrifices".
- No WebView fallback. Do not claim the document engine or whole app is complete because a
  primitive, debug screen or self-hosted build works.

## Ground rules
- Trough is the OS, in ONE Android app replacing the Forge VM. Target device: Pixel 7 (Tensor G2,
  arm64, Mali-G710, Android 17), sideloaded, targetSdk 28 (needed to exec binaries from app storage).
- The fixed APK (the host) exposes platform mechanisms; replaceable modules own policy (including
  native modules). Manifest declarations do not replace runtime grants or Android restrictions.
  The host is meant to be final: prefer a bundle-side solution; change the host only for a new
  declaration or lifecycle hook, and say so in HANDOFF.md (the user must install the APK).
- No unwatched model turns: automation, tests and prediction never start a model turn unless the
  user is watching (Trough visible) or explicitly asked. Never auto-approve model actions in tests.
- Optimise for energy per task, measured on the device (see DESIGN.md "Measurement").
  Never claim a speedup without numbers from tools/ or bench output.
- Every engine/port change is gated on its oracle (test262, web-platform-tests, golden
  screenshots, or differential tests vs the reference). No oracle, no merge.
- Never commit secrets, tokens, keystores or personal email addresses.

## Layout
- `app/` -- the host APK (Java, no Gradle). Build in the VM:
  `FRAMEWORK_RES=/usr/share/android-framework-res/framework-res.apk tools/build.sh` -> `out/trough.apk`
  (docs/HOST.md). Copy it to `/mnt/shared/Download/trough/trough-os.apk` for the user to install.
- `hot/` -- the bundle's Java (Java 8 source: no lambdas or method references). Publish with
  `tools/hot.sh`; the phone activates it within seconds. `tools/loop.sh` runs every scenario.
- `native/` -- C11 core (store, derive, exec, blobs, atlas, order, present), built for glibc
  (userland executables) and bionic (`libtrough-android.so`, loaded in-process via JNI).
- `tests/` -- oracles; see each docs/ contract for which applies.
- `.github/workflows/` -- CI on GitHub's free runners (repo is public: unlimited minutes).

## Results from the phone
The app writes bench/diagnostic JSON to the phone's Download/trough/ folder. In the Forge VM
that is /mnt/shared/Download/trough/.
