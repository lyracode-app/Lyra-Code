# Bundled PRoot runtime

Every APK contains ARM64 and x86_64 executable/loader pairs built locally from
Soffd/proot main commit `ad19f51716f6560075f1e6e40f1ebe82f2f239c6`.
The source retains `7266fb3` (link-directory pinning) and includes these five
upstream commits, cherry-picked in order with their original IDs recorded:

- `8f467b0991d74f277eb841362abfbf8755942f97`: simulated hard-link executable paths.
- `a396094c7ccc1a64a64efb71811860d0284fda5a`: O_NOFOLLOW descriptor paths.
- `58c3b42849fac502c6c60a45c4d2a82da9e9f8d4`: fork syscall-exit stops.
- `8b9941505dcd7da9054942c4b742d9f687f8eb91`: child registration without event PIDs.
- `d4d2a19081c3c07f75250e4ce2980b9fa2f5720f`: seccomp flags without event messages.

Remote master was verified at `d4d2a19` on 2026-09-26. The local master branch
was not modified. `proot_src/` is the Git-free source copy of this main revision.

## Binary provenance

- Version: `5.1.107.91-lyra.3`
- NDK: `29.0.14206865`, Clang 21; CMake 3.22.1, Ninja
- Targets: `aarch64-linux-android24`, `x86_64-linux-android24`
- Static dependency: vendored talloc 2.5.0
- Flags: `-O2`, PIE executable, external static freestanding loader,
  `ARG_MAX=131072`, libandroid-shmem disabled, 16 KB-aligned ELF LOAD segments
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
- `arm64-v8a/libproot_exec.so`: `3b641ecd1b04ba0d1d798cd602f3d4aa11010d052d2b9fb3e7f166390105a340`
- `arm64-v8a/libproot_loader.so`: `39f8d98f345bd2f0cff53b6a9ee54418cec340f4738d6bdd2fb03112654a6183`
- `x86_64/libproot_exec.so`: `3b4fd8359bddff1b569785f65ea3f370647f9dde8e11cb8091508944ad3179d1`
- `x86_64/libproot_loader.so`: `61b2dd1a858caddbab4647c519cfe0c23bbd2d47981358b9cc6ae818e79e6869`
