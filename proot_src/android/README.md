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

## Dual-ABI build (Lyra .3)

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

The .3 build retains the 7266fb3 link-directory pinning fix and cherry-picks
these five master commits onto main, in order:

- `8f467b0991d74f277eb841362abfbf8755942f97`: report the simulated hard link
  through `/proc/self/exe`, including execution through symbolic links.
- `a396094c7ccc1a64a64efb71811860d0284fda5a`: preserve descriptor paths for
  simulated hard links opened with `O_NOFOLLOW` and `O_PATH`.
- `58c3b42849fac502c6c60a45c4d2a82da9e9f8d4`: request syscall-exit stops for
  fork-family calls to avoid hangs on affected Android kernels.
- `8b9941505dcd7da9054942c4b742d9f687f8eb91`: recover child registration when
  the kernel loses the PID in a ptrace event message.
- `d4d2a19081c3c07f75250e4ce2980b9fa2f5720f`: recover seccomp filter flags
  when the kernel loses event messages.

The Lyra external-loader lookup, API 24 baseline, static talloc and ABI-specific
loader addresses are preserved. No app launch arguments need to change.

## Android runtime regression checks

After building, run against an explicitly selected connected device with API 28
or newer (Bionic's posix_spawn helper requires API 28; PRoot still targets 24):

```powershell
./android/test-runtime.ps1 -NdkPath G:/sdk/ndk/29.0.14206865 `
  -Adb G:/sdk/platform-tools/adb.exe -Serial emulator-5554
```

The script builds three upstream helpers and a fork/thread helper for the device
ABI, and tests the built binaries in a unique `/data/local/tmp/lyra-proot-*`
directory. It checks simulated hard-link execution (directly, through a symlink,
from a shell, and via execveat), O_NOFOLLOW/O_PATH descriptor names, fork, vfork,
system, 80 concurrent posix_spawn calls across four threads, and sibling-loader
discovery. Both seccomp modes run with a 90-second timeout. Test files remain
in the reported device directory for inspection. This does not simulate a
kernel that loses ptrace event messages; that needs an affected device/kernel.
