# Trough -- instructions for any coding agent (Codex, Claude, others)

**Read [DESIGN.md](DESIGN.md) before changing anything.** It is the source of truth for what
Trough is and every decision made so far, with dates. If your change contradicts it, either
stop and ask, or update DESIGN.md in the same commit and say why.

## Ground rules
- Trough is ONE Android app replacing the Forge VM. Target device: Pixel (Tensor G2, arm64,
  Android 17), sideloaded, targetSdk 28 (needed to exec binaries from app storage).
- Native code holds knobs, never policy. Every permission is declared up front in
  app/AndroidManifest.xml so behaviour can hot-reload without reinstalls.
- Optimise for energy per task, measured on the device (see DESIGN.md "Measurement").
  Never claim a speedup without numbers from tools/ or bench output.
- Every engine/port change is gated on its oracle (test262, web-platform-tests, golden
  screenshots, or differential tests vs the reference). No oracle, no merge.
- Never commit secrets, tokens, keystores or personal email addresses.

## Layout
- `app/` -- Android app (Java, no Gradle). Build: `tools/build.sh` -> `out/trough.apk`.
- `native/` -- C/asm built as static arm64 executables, shipped as lib*.so and exec'd.
- `.github/workflows/` -- CI on GitHub's free runners (repo is public: unlimited minutes).

## Results from the phone
The app writes bench/diagnostic JSON to the phone's Download/trough/ folder. In the Forge VM
that is /mnt/shared/Download/trough/.
