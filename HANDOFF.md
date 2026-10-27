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
