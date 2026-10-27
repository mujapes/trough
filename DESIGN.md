# Trough

One APK that replaces Forge's VM + daemon + phone app. Started 2026-10-27.

## Why
Measured on Forge, 2026-10-27: the Android VM reserves ~2GB RAM, keeps a 19GB rootfs image
plus a 7.8GB backup image in phone storage, is CPU-starved and network-stalled by Android in
the background, and was killed twice in five minutes under heat. It buys exactly one thing:
a stock Linux userland. Trough brings that userland into the app process tree instead.

## Userland: layered fallback, fastest first
Every command resolves through the layers in order; the first that has the tool runs it.

1. **bionic** -- Termux-style packages built for Android (node, python, git, rg, ffmpeg,
   clang). Native, zero overhead. NOTE (web check 2026-10-27): Claude Code no longer ships an
   Android/node build -- it is a linux-arm64 glibc binary, so it runs on layer 2.
   Prefix: /data/data/<trough pkg>/files/usr (packages rebuilt for this prefix, or a
   path-rewrite shim until they are).
2. **glibc-direct** -- stock Debian arm64 binaries launched through glibc's own ld.so with an
   explicit library path (glibc-runner approach). Native speed, no ptrace. Hardcoded
   /usr,/etc paths fixed by patchelf or an LD_PRELOAD path rewriter.
   Generalised as `trough-apt`: fetch stock Debian arm64 .debs, unpack into our prefix, patch
   interpreter/rpath, redirect /etc,/usr,/tmp. A universal automatic port -- no hand porting,
   same ISA. This is how Claude's own `apt install`s keep working.
3. **proot** -- full stock Debian rootfs for anything else. Compute native, every path syscall
   ptrace-translated (2-5x slower on fs-heavy work). Last resort only.

Same arm64 ISA everywhere: no emulation at any layer.

## Android hurdles (sideloaded, so all solvable)
- exec from app storage: targetSdk 28, and/or ship binaries as lib*.so in nativeLibraryDir.
- Phantom process killer (Android 12+): user toggles "Disable child process restrictions"
  once; app runs a foreground service.
- App seccomp filter: avoid the few blocked syscalls (node/git/python are fine).

## Everything native is declared up front
Rebuilds are the thing being designed away. The first APK carries every permission and every
native subsystem we can foresee (camera, mic, sensors, accessibility, overlay, notification
listener, storage, foreground service, all of Forge's 315 permissions as a starting point),
plus a generic reflective bridge (Forge's forgeJava + dexmaker pattern). After that, all
behaviour is JS/data and hot-reloads. Native code holds knobs, never policy.

## Gaze: native, not WASM
Today: CameraX -> Java crop -> JS bridge -> MediaPipe WASM in a WebView (GPU delegate
requested, ~220ms/frame). Native path: camera frames stay in a HardwareBuffer, LiteRT runs the
landmark model on the GPU delegate or the Tensor TPU via NNAPI, no bridge copy. Target
~10-20ms/frame (estimate; measure).

## Viewer: native per-site renderers, layered fallback (decided 2026-10-27)
Each site we use gets a purpose-built native view that fetches the site's data and renders it
ourselves, never running the site's own JS. Fallbacks are costlier but always available, so the
product is strictly better than WebView alone.

  L0 site-native   YouTube, Shorts, Twitter/X first; TikTok, Instagram, Snapchat next.
                   Data from the site's API/stream URLs; video via MediaCodec straight to a
                   SurfaceView (hardware overlay plane: decode -> display, no GPU composite).
  L1 trough-native Our own UI: scripts compiled ahead of time to native code, drawn on the
                   GPU (Vulkan/AHardwareBuffer). No DOM, no JIT, no warm-up.
  L2 doc engine    HTML/CSS for static and dynamic pages, our own layout + JS engine tuned to
                   how we use it (AOT for known code, fast interpreter for the rest).
  L3 WebView       Everything else. Shrinks as L0-L2 grow.

Beating V8: not head-on for arbitrary site JS. We win by not running that JS at all (L0) and
by compiling the JS we write ahead of time (L1). Borrow parsers/compilers from existing
open-source Rust/Go work (oxc, swc, Boa, tsgo) instead of starting from zero.

Risks to manage, not reasons to stop: site private APIs churn (extractors need upkeep), and
third-party clients can get accounts banned -- Snapchat bans them aggressively. Use the
user's own sessions; keep WebView fallback for any site that starts refusing us.

## Hot reload: everything, including native
- Java: JVMTI RedefineClasses (debuggable build) for loaded classes, new class loaders for new.
- C/C++/Rust: compiled on-device, loaded as versioned .so, function pointers swapped, state kept.
- Scripts/UI: trivial. Only the manifest is fixed, so it declares everything up front.

## Order
1. Spike: bionic node + `claude` running from an APK; measure RAM / start / git status vs VM.
2. Trough shell APK with all permissions + bridge + hot reload.
3. Native gaze.
4. Userland layers 2 and 3.
5. Port Forge UI; retire the VM.

## Optimisation rules
- The metric is energy per frame / per task, not peak speed: the phone's real ceiling is
  thermal (the 10-27 crash started as heat). Measure joules before and after.
- Claude is the compiler, inside a verified loop (superoptimisation, cf. AlphaDev's sort
  routines merged into libc++): generate candidates (asm, intrinsics, layouts, algorithms) ->
  measure on the phone's own PMU and energy counters -> prove equivalence (differential
  testing vs reference; formal checks where possible) -> keep only winners. Applies to all
  code, not just kernels. Target: Tensor G2 (Cortex-X1/A78/A55, ARMv8.2, no SVE).
- The edge over a compiler is semantics: known invariants, no-alias facts and value ranges
  across the whole program, which a compiler must assume away. Exploit them first; instruction
  selection is the last 10-30%.
- PMU access needs a one-time adb toggle (security.perf_harden) -- verify when we get there.
- WebView fallback stays but is metered: every fallback is counted and shown, so the debt is
  visible and drives what gets a native renderer next.
- AI-written ports are only as good as their oracle (an AI Rust port of ScanCode benchmarked
  faster but returned wrong results). Every engine piece is gated on its conformance suite:
  test262 for JS, web-platform-tests for HTML/CSS/DOM, golden screenshots per site view.

## Sandboxing policy (2026-10-27)
Most real use is web scrolling, so arbitrary web content is first-class, not a fallback.
- Trusted code (our UI, L0 site renderers that parse data and never run site JS): no sandbox,
  single process, zero IPC.
- Arbitrary third-party JS: ONE lightweight isolated process for all of it (not Chrome's
  process-per-site). Trough holds Claude credentials, the userland and Shizuku shell-level
  power, so an engine exploit without isolation would own all of that.
- Harden beyond Chrome, not below it (AI-driven exploitation makes this matter more each year):
  memory-safe engine code (Rust) where untrusted input is parsed, strict IPC schema between the
  isolated JS process and everything else, no userland/Shizuku/credential access from it.
- Component-level native acceleration: recognise reusable pieces of known sites (video
  players, feeds, comment threads, embedded posts) and swap in native implementations, so
  partial wins land long before a whole site is native.
- Bigger wins than dropping isolation: block ads/trackers (often half a page's JS), cache
  compiled code across visits, and give the most-scrolled sites L0 native renderers.

## adb without Wi-Fi (2026-10-27)
Android drops adb on reboot and wireless debugging requires a real Wi-Fi association (own
hotspot does not count, tested). No non-root way to persist adbd across reboots is known.
Minimise what needs it instead:
- One adb session grants Trough permissions that SURVIVE reboots: WRITE_SECURE_SETTINGS,
  DUMP, READ_LOGS, PACKAGE_USAGE_STATS (pm grant / appops). Covers most of what adb was for.
- Per-boot needs (Shizuku, perf_harden for PMU access) come back with one Wi-Fi moment:
  another phone's hotspot, public Wi-Fi, or a ~GBP 5 ESP32 AP in the van (permanent fix).
- Root (Pixel bootloader unlock + Magisk) removes the problem entirely; costs a data wipe
  and Play Integrity. User's call.

## Measurement without adb (2026-10-27)
The kernel PMU (perf_event) is gated by security.perf_harden, settable only from shell. The
optimisation loop does not depend on it:
1. Time: CNTVCT_EL0 is readable from user space with no permission (verified: 24.576 MHz,
   40.7 ns/tick). Pin the thread to an X1 core (sched_setaffinity), hold clocks steady with
   ADPF (PerformanceHintManager), read cpufreq; cycles/iter = ticks x (freq / 24.576 MHz) over
   10^6+ iterations, median of runs. Sub-cycle resolution per iteration.
2. Energy: Pixel ODPM rails (per CPU cluster, uJ) via SystemHealthManager power monitors
   (API 35+, verify on device) or the permanent DUMP grant -> powerstats. This is the metric.
3. Static: llvm-mca with Cortex-X1/A78/A55 scheduling models predicts cycles and the binding
   bottleneck per instruction block. Runs anywhere (VM, Oracle, Actions).
4. Hardware PMU (instructions, cache/branch misses): only when a Wi-Fi moment allows, used to
   calibrate 1-3, never required by the loop.

## Device findings (Pixel 7a, GS201/Tensor G2, Android 17 / SDK 37), 2026-10-27
- exec of a binary shipped in the APK works (process started) -- but static glibc died with
  SIGSYS (exit 159) before main: a startup syscall is trapped by the app seccomp filter.
  native/probe (no libc) names the trapped calls; suspects: rseq, set_robust_list.
- All 31 power monitors are readable by a plain app with no adb: per-rail CPU BIG/MID/LITTLE,
  GPU, TPU, DDR, SLC, display, modem... Readings did not move over an 8 ms window; measure
  over seconds.
- GitHub's free arm64 runner (Cobalt 100) measured 3.39 GHz with the same bench; its
  CNTFRQ is 1 GHz (phone: 24.576 MHz). bench handles both.
- The APK is now only HotLoader; everything else ships as signed bundles via tools/hot.sh.
- Probe result (run 2): Android traps set_robust_list, rseq, futex_waitv for apps. Static glibc
  calls the first two before main. Fixed two ways, both verified in the VM under
  tests/seccomp_sim (same filter): native/texec (SIGSYS -> -ENOSYS handler + its own ELF
  loader, also usable as PT_INTERP so exec chains work) and tools/patch_svc.py (rewrites
  those svc sites in Trough's own glibc to an inline -ENOSYS -- needed because threads that
  block all signals, e.g. node's libuv pool, are killed before any handler runs).
  Under the simulated filter: ls, git, python3, node 20 and claude 2.1.311 all run.
- Run 4: threadtest PASSED on the phone (dynamic glibc 2.41, 4 threads, all signals blocked)
  -> the glibc-direct layer works on device. Power rails refresh every ~20 s for apps
  (CPU(BIG): 1.14 J over 20.5 s idle-ish); energy needs windows spanning refreshes.
- Large binaries ship as payloads: Download/trough/payload/<sha256>, hash listed in the
  signed manifest, verified while streaming into app storage (hot/Payloads.java).

## Trough root: package "t.t" (2026-10-27)
Stock binaries hard-code paths. Rewriting them in place (never moving bytes: patchelf's
layout change segfaulted claude, a Bun single-file executable) needs replacement paths no
longer than the originals. Package "t.t" makes the app's data dir /data/data/t.t (15 chars):
  PT_INTERP /lib/ld-linux-aarch64.so.1 -> /data/data/t.t/x  (texec)        tools/set_interp.py
  glibc     /etc/resolv.conf           -> /data/data/t.t/r  (resolv.conf)  tools/patch_paths.py
  glibc lib dir /data/data/t.t/l, put first on LD_LIBRARY_PATH by texec.
Rejected: /proc/self/fd/NN paths kept open by texec -- broke whenever a parent closed all fds
before exec (Python subprocess's default).
VM simulation (tests/seccomp_sim + hidden nsswitch.conf, exact /data/data/t.t layout), all
exec'd directly: threadtest; dnstest via python subprocess with env cleared and fds closed;
claude --version; claude -p with a fake key -> DNS to 1.1.1.1:53, HTTPS to
api.anthropic.com, server rejects the key. Everything but real credentials works.

## trough-apt progress (2026-10-27, VM simulation under the phone's filter)
- Stock apt-get resolved and downloaded into the isolated root d/ (libc6 pre-seeded as
  installed, since Trough ships its own glibc). Stock dpkg installed as a normal user with
  --admindir/--instdir/--force-not-root/--force-script-chrootless/--force-bad-path.
- tools/tfix.py points every installed ELF at texec. jq installed this way ran under the
  filter, loading only Trough's patched glibc (l/) and dpkg's libs (d/usr/lib).
- apt and dpkg installed into d/ themselves (30 packages). Trough's own apt-get then fetched
  InRelease over HTTPS under the filter.
- Blocker: apt's sqv method runs /usr/bin/sqv by a fixed path with no config key, and apt
  has no configurable verifier. Never work around this with trusted=yes. Not pursued by
  Claude (its own safety checks stopped both attempts; this records that, it is not a project
  rule for other agents): rewriting machine code that computes the path, and namespace/chroot
  sandboxing of Trough's processes. Chosen route: Trough verifies Debian's
  signed InRelease itself, calling the root's sqv by its real path with Debian's keyring,
  and only then hands apt the verified index from a local mirror directory.

### Namespace investigation (Codex, 2026-10-27)
- User explicitly asks to investigate the namespace/machine-code work and why Opus stopped,
  with native performance as the goal; do not substitute work on the apt mirror route.
- Opus's visible session replies attribute the two stopped approaches to a safety classifier.
  No classifier rationale is available. This was not evidence of technical impossibility.
- Added `native/nsprobe/nsprobe.c`, shipped by `tools/hot.sh`, run via `SelfTest.namespaces`.
  Every operation runs in a disposable child. SIGSYS is reported separately from errno.
- Actual Pixel report `status-1793109701994.json`: user namespace unshare -> EINVAL (22),
  mount namespace -> EPERM (1), combined user+mount -> EINVAL (22); chroot -> SIGSYS
  (probe returns ENOSYS). C/Java compilation and execution still pass in the same report.
  This establishes that these calls fail in the app, not why USER_NS returns EINVAL.
- Next investigation: kernel configuration and proc namespace interfaces on the phone,
  clone versus unshare, and available supported launch contexts. VM probes are not a
  substitute: the VM accepted CLONE_NEWUSER and combined user+mount where the phone failed.
- Fixed `hot.sh` falsely printing publication success after shared-folder copy failures:
  standalone copy/rename commands now honor `set -e`.
- Extended device report `status-1793109991469.json`: ordinary clone succeeds; clone with
  NEWUSER also returns EINVAL, clone with NEWNS returns EPERM, combined returns EINVAL.
  `/proc/self/ns/user` and `/proc/self/ns/pid` do not exist; mnt and net do. All capability
  sets (including bounding) are zero, seccomp mode is 2. App reads of config.gz are denied.
- The phone's own uname is `6.1.162-android14-11-g2ec90535fa34-ab15810641`, unlike the VM's
  6.12 kernel. Checked the [exact common-kernel revision's configuration](https://android.googlesource.com/kernel/common/+/2ec90535fa34/arch/arm64/configs/gki_defconfig):
  it does not enable USER_NS and explicitly disables PID_NS. The matching
  [Kconfig](https://android.googlesource.com/kernel/common/+/2ec90535fa34/init/Kconfig)
  defaults USER_NS to n; [user_namespace.h](https://android.googlesource.com/kernel/common/+/2ec90535fa34/include/linux/user_namespace.h)
  returns EINVAL for CLONE_NEWUSER when it is disabled. This strongly supports missing
  kernel support, but the final running kernel config was not readable from the app.
- Conclusion for the current unprivileged app: no working user/mount-namespace root path.
  A kernel with USER_NS enabled (or a sufficiently privileged, policy-permitted launcher
  for mount namespaces) is a different deployment prerequisite, not an app assembly tweak.
  No root, bootloader, kernel, or Android security policy was changed. Namespaces can remove
  filesystem translation from compatibility paths; they do not accelerate CPU instructions
  over the already native glibc-direct execution layer. No speedup is claimed here.
- `python3 tests/namespaces.py` passes both native and deliberate SIGSYS controls; CI runs
  it on arm64. The latest signed diagnostic bundle was built and observed running on the
  phone; gcc/C/javac/Java checks still all passed. No package-manager implementation added.
- Follow-up requested by user: root-backed mount namespaces remain a possible deployment
  route without USER_NS; ordinary adb/Shizuku shell mode does not supply the needed
  capabilities. Joining an existing namespace also requires capabilities. No supported
  namespace workaround was found inside the present unrooted app. Adapting binaries to
  app-private paths at build time remains a native execution option without per-syscall
  path interception. See [HANDOFF.md](HANDOFF.md) for evidence, source references, limits
  and the report to Opus. No new deployment route was implemented or authorized.

## MILESTONE 2026-10-27 12:55 -- claude runs natively inside Trough on the phone
Pixel 7a, Android 17, package t.t, no VM, no proot, no root:
- probe: set_robust_list, rseq, futex_waitv trapped (unchanged); stock static glibc 159.
- exec'd directly via PT_INTERP /data/data/t.t/x: threadtest ok (145 ms); dnstest ok via
  /data/data/t.t/r; claude --version 323 ms; claude -p with a fake key reached
  api.anthropic.com and was refused.
- Login moved encrypted (tools/send_creds.py -> hot/Creds.java), shared copy deleted.
  `claude -p "Reply with exactly: trough-ok" --model haiku` -> "trough-ok" in 8.3 s.
Next: Trough's own chat UI over claude's stream-json; a separate long-lived token for
Trough (claude setup-token) so the VM and phone stop sharing one refresh token; trough-apt
signature verification.

## Direction: ground up, not a transplant (2026-10-27, user correction)
The VM-era architecture -- a Python daemon serving HTTP on localhost, a token, a WebView client,
SSE event streams -- existed because the code lived in a VM and the UI lived on the phone.
Trough owns its filesystem and processes, so that plumbing is skeuomorphic and is NOT ported.
A run of Forge's daemon + WebView inside Trough was built and reverted the same day.
- Trough talks to claude directly: one child process, stream-json over pipes, in Trough's own
  code. No server, no port, no token, no event bus.
- The UI is native (L1): the Forge design the user lives in (deck shell, dock, Flow modules:
  teleprompter, beam, working status, power keys, model selector) is the SPEC, rebuilt as
  native views, not loaded as HTML.
- NO WEBVIEW, anywhere, including arbitrary web content (user, explicit, 10-27; this
  supersedes the "L3 WebView fallback" and "metered fallback" lines above). Arbitrary pages
  are rendered by Trough's own engine, built from the ground up to run faster than WebView
  on this phone. Known cost, accepted: DRM (Widevine/EME) content cannot play.
- Forge itself was a stack of transplants -- each feature patched onto the last thing to
  reach new functionality with the least work, until performance drowned. Trough's rule is
  the opposite: build each piece for what it is, from the ground up.
- Forge's Android shell ideas that are about capability, not plumbing, carry over: every
  component declared up front, an AppComponentFactory so components come from hot code,
  the reflective bridge pattern. They are rewritten, not copied.

## Working rules (user, 10-27)
- No stopgaps, no "easiest version first". Each task in this doc is built as the real thing,
  from the ground up, starting with its hardest core. A quick native chat screen was exactly
  the kind of interim piece this rules out; it stays only as a debug console.
- Hot reload is a capability of Trough itself: Trough rebuilds and reloads its own code while
  running, from source it holds. The VM-published signed bundle is only a development and test
  channel, not the architecture. An APK update is fine when the native side genuinely needs it.
- The VM may drive Trough remotely for testing; the user does not touch the phone for tests.

## Self-hosting toolchain (2026-10-27, VM simulation under the phone filter)
Trough must build itself, so the compilers live in the Debian root d/ and run via texec.
- JDK 21 (stock Debian): javac + java run from d/usr/lib/jvm. Its 20 absolute symlinks into
  /etc made the first VM pass hollow; tfix now re-roots absolute links and fails on any that
  would dangle on the phone.
- GCC 14 + binutils + libc6-dev (34 packages, imported from the VM's verified install with the
  files dpkg lists, zero download). tools/gcc_specs.py writes GCC's specs: -dynamic-linker
  /data/data/t.t/x, -isysroot (in cpp_unique_options -- cpp_options is not used by cc1, which
  silently fell back to the VM's /usr/include once), --sysroot and startfile prefix in d/.
  A threaded C program compiled under the filter runs directly; strace shows the toolchain
  opens nothing outside /data/data/t.t (only the test harness's own env/sim do).
Next: ship d/ (toolchain) to the phone and build + run C and Java there; then Trough builds
its own bundle from its own source and reloads it.
- ON THE PHONE (10-27 13:58): root payload installed (3391 files, 1433 links, 11 s);
  gcc built a threaded C program in 181 ms and it ran; javac 628 ms, java ran. Trough's own
  toolchain works natively on the device. Next: Trough builds its own code from its own source
  and reloads itself.

## Self-hosted reload (2026-10-27)
Trough holds its own Java source and rebuilds itself from it on the phone; the signed bundle is
the seed and the fallback, not the architecture (Working rules).
- Bundle carries src/ + build.sh; android.jar and d8.jar ship as payloads. hot/build.sh is the
  ONE build definition: tools/hot.sh uses it in the VM, Self.java runs it on the phone with
  /system/bin/sh and the root's javac + java (d8).
- Boot (Main.start, always the signed copy): root + payloads, seed files/src from the bundle
  when the bundle's source changed (local edits moved to files/src.<time>, never deleted),
  build into files/self/<key> (key = hash of source tree + build inputs; unchanged tree = no
  compile), load beside the bundle (parent = APK loader, so the built classes replace the
  bundle's) and enter Main.run. Any failure -> the bundle's own run, so Trough always comes up.
- Reload: `/reload` in the chat dock, or anything holding the boot's Runnable. The boot builds
  files/src; on success it stops what runs and enters the new build; on failure it keeps the
  old one and says so. Claude resumes its session (.trough-session), so a reload keeps context.
- The boot/loader itself only changes through a signed bundle, like HotLoader through an APK.
- Trust: files/src and files/self are app-private; only Trough and what it runs can write them.
- Oracle: tests/selfbuild.sh (VM, javac/java under the simulated filter, plus a broken-source
  negative control). Device proof: SelfTest.selfEdit copies files/src, adds a class returning a
  fresh nonce, cold-builds, loads it and calls it.
- Lambdas are out: javac against android.jar has no LambdaMetafactory.
- ON THE PHONE (10-27, status-1793110967871.json): boot seeded files/src, cold self build
  4210 ms (javac + d8, root's JDK under the real filter), and Trough is now running the code it
  built ("running": "self a281fa10c69b"). selfEdit: edited copy built in 4466 ms, loaded, and
  returned the expected nonce. gcc/C/javac/Java selftests still pass.
Next: reload on Claude's edits without a typed command (Trough's claude editing files/src is
the normal way Trough changes); native code (bin/) rebuilt the same way with the root's gcc.
