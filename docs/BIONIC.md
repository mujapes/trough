# Bionic build: the store inside the app process

2026-10-28. This resolves the open question in HANDOFF.md: how Java reaches the native store
without per-keystroke IPC. DESIGN.md asks for "a small Java/native boundary exchanging
batches/handles". Everything else in `native/` is glibc code run through texec as separate
processes, and a glibc library cannot be loaded into the bionic app process. The same sources
are therefore built a second time against bionic.

## Decision

The same C sources (`native/store/*`) are also built against bionic, Android's C library:
- `native/bionic.sh` runs clang 19 `--target=aarch64-linux-android28`.
- Headers and crt objects come from Termux's `ndk-sysroot` r30, which is the NDK sysroot.
- SQLite is Debian's patched 3.46.1-7+deb13u2 source, amalgamated by its own Makefile and
  linked statically. It is the same release as the root's libsqlite3.
- `tools/bionic-deps.sh` fetches both and verifies them by pinned SHA-256.
- Link stubs are empty per-soname libraries giving `libc`, `libm`, `libdl` and `liblog` as
  `DT_NEEDED`; Android's linker resolves the symbols at load time. Every undefined symbol is
  a standard bionic export (85 in `libtrough-android.so`).
- The build is byte-for-byte deterministic, checked locally and by the CI `bionic-arm64` job.

Rejected:
- (a) A keeper process plus a Java mirror of the draft: two copies of the truth and IPC on
  every keystroke.
- (c) A freestanding core with Java-supplied allocator and threads: invasive, and
  unnecessary once (b) worked.

The same path is what in-process Vulkan, MediaCodec and NNAPI will need.

Outputs:
- `bin/android-core-*`: the store oracles as Android programs. They sit in `bin/` because the
  fixed APK marks only `bin/` executable.
- `lib/libtrough-android.so`: the JNI library (`native/android/jni.c`, `NativeCore.java`).

Self-hosted since 2026-10-28:
- The bundle carries the verified inputs in `native/bionic-inputs/`.
- `build.sh` runs `bionic.sh` when they are present and appends `outputs-bionic.list` to the
  inventory.
- clang-19 and lld-19 are in the root's package list.
- Pixel bundle `fd1a907464b2...`: `build.native` rebuilt all 34 outputs in 44 s with the
  phone's own clang, every one byte-identical to the VM build.
- Getting there exposed a stale root `tfix` from an old `rootfiles.zip`; bundles now carry a
  `tfix` built from current source.
- Scenario requests may now run up to 15 minutes.

Glibc-side tooling adjustments:
- `set_interp.py` and `tfix` leave a `/system/bin/linker*` interpreter alone.
- execmap recognises bionic programs anywhere by `PT_INTERP` and strips glibc's
  `LD_PRELOAD`/`LD_LIBRARY_PATH`, which bionic's linker would otherwise follow.

## Hot reload

Android binds a loaded library to one ClassLoader, and each hot bundle has a new one, so
each bundle loads the copy in its own directory. Old copies stay mapped until the process
restarts, about 1.3 MB each, for reloads that changed native code. The store has one owner
(flock): before a new bundle's keeper opens it, the old bundle must `tk_stop`, which saves
pending drafts. This is a module retirement obligation in HOT_RELOAD.md terms; the current
scenario keepers are scenario-local. Drafts survive the hand-over durably (flush, then
reattach), not as live in-memory queues.

## Evidence (Pixel bundle `b105930db4f1...`)

- All eight store oracles pass as bionic programs, including SIGKILL recovery and threads.
  Text scaling: 8.9 us per 1-unit edit at 4M units (glibc build: 9.4 us).
- `android.jni`, in-process from Java:
  - 3000 typed edits: per call (JNI, `ti_edit`, keeper poke) 10.9 us p50, 30 us p99,
    5.3 ms max (one outlier, not yet explained);
  - 4 coalesced saves; flush 15.7 ms;
  - live, durable and restart-recovered text exact;
  - library load 22 ms.

Follow-up (bundle `a27064c57747...`, 20000 edits, contention counters added): 5.2 us p50,
19.4 us p99, 1.43 ms max, one edit over 1 ms. The queue lock was found held 21 times (0.1%)
and the keeper's poke lock never. The earlier 5.3 ms outlier did not recur; slow edits point
to preemption or GC rather than lock inversion, so no priority-inheritance change was made.
`ti_state.contended` and `tk_stats.poke_contended` stay as permanent diagnostics. CI's
`bionic-arm64` job reproduces the build deterministically on Ubuntu from the same inputs.

