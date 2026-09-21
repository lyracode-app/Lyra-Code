# Android ARM64 and x86_64 build

This CMake build produces an executable PRoot ELF and its freestanding loader
with the `.so` names expected by Lyra's Android native-library packaging:

- `dist/libproot_exec.so`
- `dist/libproot_loader.so`

The `out` directory keeps unstripped intermediates for ELF debugging; use the
stripped files in `dist` for packaging and device tests.

It deliberately targets Android API 24, statically links talloc 2.5.0, and
does not enable `libandroid-shmem`. This matches the important build traits of
the currently bundled RikkaHub binaries while keeping the source and settings
auditable in the local PRoot fork.

Configure with the NDK Android toolchain, for example:

```powershell
cmake -S android -B build/android-arm64 -G Ninja `
  -DCMAKE_TOOLCHAIN_FILE=G:/sdk/ndk/29.0.14206865/build/cmake/android.toolchain.cmake `
  -DANDROID_ABI=arm64-v8a `
  -DANDROID_PLATFORM=android-24
cmake --build build/android-arm64
```

The loader is external. `PROOT_LOADER` supplied by the app takes precedence;
when it is absent, PRoot looks for `libproot_loader.so` beside `/proc/self/exe`.
There is no compiled package-specific or Termux fallback path. A missing loader
produces an explicit error instead of silently attempting another package's
private directory.

## Dual-ABI build (Lyra .2)

From the checkout root:

```powershell
./android/build.ps1 -NdkPath G:/sdk/ndk/29.0.14206865 `
  -CMake G:/sdk/cmake/3.22.1/bin/cmake.exe `
  -Ninja G:/sdk/cmake/3.22.1/bin/ninja.exe
```

Outputs are `build/android-arm64-v8a/dist` and `build/android-x86_64/dist`.
Use a fresh build directory when changing ABIs. Both targets use API 24,
NDK 29.0.14206865 (Clang 21), static talloc 2.5.0, `-O2`, `ARG_MAX=131072`,
and no libandroid-shmem. The executable is PIE; the loader is freestanding,
static, and has no Bionic dependency. NDK r29 produces 16 KB-aligned LOAD
segments. The ARM64 loader text address is 0x2000000000; x86_64 uses
0x600000000000, matching src/arch.h. Only ARM64 generates the pokedata
workaround offset. These packages support native 64-bit rootfs programs;
32-bit loaders and cross-architecture CPU emulation are not bundled.

The .2 build includes commit 7266fb3e8516535682f5a9c8f3a7e70f6506eddb
(link2symlink directory-descriptor pinning and path-length fix), cherry-picked
onto main. The master branch is untouched. The upstream regression is
`tests/test-8d3c07f5.sh` and requires a Linux/Android runtime with static busybox.