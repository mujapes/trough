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
   clang). Native, zero overhead. Claude Code runs here on node.
   Prefix: /data/data/<trough pkg>/files/usr (packages rebuilt for this prefix, or a
   path-rewrite shim until they are).
2. **glibc-direct** -- stock Debian arm64 binaries launched through glibc's own ld.so with an
   explicit library path (glibc-runner approach). Native speed, no ptrace. Hardcoded
   /usr,/etc paths fixed by patchelf or an LD_PRELOAD path rewriter.
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

## Web content
Decision 2026-10-27: build our own engine. Staged plan and honest risk notes in
docs/engine.md once started. System WebView stays as the fallback renderer until the
engine wins on the content that matters (YouTube-class pages are the hard bar: DRM/EME).

## Order
1. Spike: bionic node + `claude` running from an APK; measure RAM / start / git status vs VM.
2. Trough shell APK with all permissions + bridge + hot reload.
3. Native gaze.
4. Userland layers 2 and 3.
5. Port Forge UI; retire the VM.
