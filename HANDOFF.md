# Current handoff: unified design and hot replacement, 2026-10-27

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
Still failing: openjdk-21-jre-headless postinst does `mkdir /etc/.java` (absolute, read-only on
Android; no mount namespace). The JRE/JDK are unpacked but unconfigured; javac still works and
libsqlite3-dev is configured, but DebRoot.packages retries apt on every boot until this passes.
Decision needed: DESIGN.md says "No open/stat/chdir path rewriting". Recommended: path
translation in execmap scoped to dpkg maintainer scripts (env DPKG_MAINTSCRIPT_PACKAGE, inherited
by their children): absolute /etc /var /usr /opt paths that are absent or read-only on the device
resolve under the root, for the open/stat/mkdir/rename/unlink/chmod/access/opendir families, with
an oracle on the phone-filesystem harness. Revise DESIGN.md in the same commit.

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
