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
