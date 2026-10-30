# Bundled PRoot runtime

Every APK contains ARM64 and x86_64 executable/loader pairs built locally from
Soffd/proot main commit `75ef424c5d241d2c90a6312f4935d18977165375`.
The source retains `7266fb3` (link-directory pinning) and includes these previous five
upstream commits, cherry-picked in order with their original IDs recorded:

- `8f467b0991d74f277eb841362abfbf8755942f97`: simulated hard-link executable paths.
- `a396094c7ccc1a64a64efb71811860d0284fda5a`: O_NOFOLLOW descriptor paths.
- `58c3b42849fac502c6c60a45c4d2a82da9e9f8d4`: fork syscall-exit stops.
- `8b9941505dcd7da9054942c4b742d9f687f8eb91`: child registration without event PIDs.
- `d4d2a19081c3c07f75250e4ce2980b9fa2f5720f`: seccomp flags without event messages.

The October update additionally includes all nine requested upstream commits:

- `a179d3e`: preserve read-only reopens of regular files and memfds.
- `37c09de`: leave a contiguous ARM64 address range for large mmap reservations.
- `bd6d55e`: upstream contributor instructions.
- `c1cd48d`: full-width getresuid/getresgid for secondary Android users.
- `b5af02e`: readable namespace maps and cross-instance SO_PEERCRED.
- `7fca80c`: fchmodat2 translation and symlink permissions.
- `60a9c15`: AT_EMPTY_PATH resolves against the guest cwd.
- `254202b`: open_tree translation and fd-mount ENOSYS fallback.
- `b8c1f5c`: Unix datagram destinations, replies and restarted sends.

The October 19 update also integrates all 21 master commits from `9688d01`
through `e2d843e`, including both endpoints. They cover descriptor stat queries,
hard-link counts, memfd-backed SysV shared memory, statx group mapping, raw
fork fallback under Android seccomp, and USERLAND ownership/permission records.
USERLAND remains disabled to preserve the existing rootfs metadata behavior.
The full SHA list is in `proot_src/android/UPSTREAM_COMMITS.md`.

The October 29 update merges all 40 master commits from `8bf810c` through
`b27d81a`, inclusive, preserving the complete upstream history. It fixes
hard-link bookkeeping, error propagation, broken chains, large link counts,
symlink linking and directory types; hidden listings; statx modes under outer
seccomp; command lookup; failed-startup and Unix-socket cleanup; and tracee
PID reuse, early child deaths and execve from a non-leader thread. The local
contributor-policy file is retained, as recorded in the upstream commit list.

Remote master was verified at `b27d81a68d765f3462e807f4a14e01a03f8e89ea`
on 2026-10-29. The local master branch was not modified. `proot_src/` is the
Git-free source copy of this main revision, including the Bionic-compatible
getdents helper and expanded Android runtime regressions. The four packaged
files are reproduced byte-for-byte by independent builds of this mirror.

## Binary provenance

- Version: `5.1.107.100-lyra.6`
- NDK: `29.0.14206865`, Clang 21; CMake 3.22.1, Ninja
- Targets: `aarch64-linux-android24`, `x86_64-linux-android24`
- Static dependency: vendored talloc 2.5.0
- Flags: `-O2`, PIE executable, external static freestanding loader,
  `ARG_MAX=131072`, libandroid-shmem disabled, 16 KB-aligned ELF LOAD segments
- ARM64 loader/PIE/interpreter addresses: `0x5400000000`, `0x5500000000`,
  `0x5900000000`; x86_64 loader remains `0x600000000000`
- Source/build instructions: `proot/android/CMakeLists.txt`, `proot/android/build.ps1`

## Rebuild

The separate `proot/` Git checkout is ignored by the application repository.
The tracked `proot_src/` copy contains the corresponding source, dependencies
and build scripts. Rebuild it from the application repository:

```powershell
./proot_src/android/build.ps1 -NdkPath G:/sdk/ndk/29.0.14206865 `
  -CMake G:/sdk/cmake/3.22.1/bin/cmake.exe `
  -Ninja G:/sdk/cmake/3.22.1/bin/ninja.exe
```

Copy both stripped files from `proot_src/build/android-arm64-v8a/dist` to
`arm64-v8a/`, and both from `proot_src/build/android-x86_64/dist` to `x86_64/`.
If a local copy includes build caches from another path, configure in fresh
directories; CMake caches cannot be moved between `proot/` and `proot_src/`.
Update hashes here, in `app/build.gradle.kts`, `LicenseTexts.kt`, and
`THIRD_PARTY_NOTICES.md`. Run:

```powershell
./gradlew.bat :app:verifyBundledProotRuntime :app:testDebugUnitTest :app:assembleDebug
```

Android selects and extracts its matching ABI into `nativeLibraryDir`.
The app verifies the ELF64 architecture of both extracted files and selects
matching ARM64 or amd64 Debian downloads and rootfs validation. `.so` names
are for Android packaging: PRoot is executed as a process, not loaded via JNI.
Windows Android emulators use the Android x86_64 binaries, not Windows DLLs.
No 32-bit rootfs loader or cross-architecture CPU emulator is bundled.

The pinned amd64 Debian seed is from debuerreotype/docker-debian-artifacts
commit `bae6d64d90b4068b09ff9d8b564c2773ef5d8d83`, OCI platform linux/amd64,
layer SHA-256 `27ee9a8250487842a26b1ffa1215982ba9ae27010bce1997d52f9f8628578d17`.
The existing ARM64 seed and installed user rootfs directories are preserved.

The RikkaHub binaries under `app/src/prootReference` remain unpackaged references.
Keep the corresponding source and build material available to binary recipients
under the selected GPLv3 terms; talloc is LGPL-3.0-or-later.

## SHA-256
- `arm64-v8a/libproot_exec.so`: `8cd986e8e9505f20ef263095037d59ece8756b230e5730315dc142332a8032e2`
- `arm64-v8a/libproot_loader.so`: `825d0362db3319c2b3ed3b75fe300287c0f9eeb7f2427ceb05a4a004ad33ed12`
- `x86_64/libproot_exec.so`: `e3c6a7bde6a8e4851b2ddc36965b5a6c3fe71b5e630731aa7869e046cce82a72`
- `x86_64/libproot_loader.so`: `61b2dd1a858caddbab4647c519cfe0c23bbd2d47981358b9cc6ae818e79e6869`
