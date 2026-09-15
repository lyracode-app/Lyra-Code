# Bundled PRoot runtime

Every APK contains ARM64 and x86_64 executable/loader pairs built locally from
Soffd/proot main commit `817985a61d8cfd27532d15bc9a03540eb7dc561c`.
The source includes upstream commit `7266fb3e8516535682f5a9c8f3a7e70f6506eddb`
(directory-descriptor pinning for link2symlink and the path-length fix).
The local master branch remains at 7266fb3 and was not modified.

## Binary provenance

- Version: `5.1.107.91-lyra.2`
- NDK: `29.0.14206865`, Clang 21; CMake 3.22.1, Ninja
- Targets: `aarch64-linux-android24`, `x86_64-linux-android24`
- Static dependency: vendored talloc 2.5.0
- Flags: `-O2`, PIE executable, external static freestanding loader,
  `ARG_MAX=131072`, libandroid-shmem disabled, 16 KB-aligned ELF LOAD segments
- Source/build instructions: `proot/android/CMakeLists.txt`, `proot/android/build.ps1`

## Rebuild

The separate `proot/` Git checkout is ignored by the application repository.
Check out the source commit above, then run from the application repository:

```powershell
./proot/android/build.ps1 -NdkPath G:/sdk/ndk/29.0.14206865 `
  -CMake G:/sdk/cmake/3.22.1/bin/cmake.exe `
  -Ninja G:/sdk/cmake/3.22.1/bin/ninja.exe
```

Copy both stripped files from `proot/build/android-arm64-v8a/dist` to
`arm64-v8a/`, and both from `proot/build/android-x86_64/dist` to `x86_64/`.
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
- `arm64-v8a/libproot_exec.so`: `a0628a09b064d60a2281400224b19ac05b22619ab2aeb85e6d946b05b1412238`
- `arm64-v8a/libproot_loader.so`: `39f8d98f345bd2f0cff53b6a9ee54418cec340f4738d6bdd2fb03112654a6183`
- `x86_64/libproot_exec.so`: `c5d89b620e7afc3386ecfc3addfeaaaf5090d0a5170d48a418d2e856329aa6b2`
- `x86_64/libproot_loader.so`: `61b2dd1a858caddbab4647c519cfe0c23bbd2d47981358b9cc6ae818e79e6869`
