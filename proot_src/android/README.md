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

## Dual-ABI build (Lyra .6)

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
segments. The ARM64 loader text address is 0x5400000000; x86_64 uses
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

The .4 build adds all nine October upstream commits to main, in order:

- `a179d3e8a4e045aaa1fb8cc3284f23509d96d353`: preserve the requested access
  mode when reopening regular files and memfds through `/proc/self/fd`.
- `37c09de3b17e9446688a10b3de7e0fb405444c99`: move ARM64 loader, PIE and
  interpreter mappings to 336, 340 and 356 GiB for large mmap reservations.
- `bd6d55e122fb88708e5c445bc848a5a4bf7bc76a`: upstream contributor instructions.
- `c1cd48d5b93166ce79098bd38256dda994653b6c`: write full 32-bit getresuid
  and getresgid results, including Android secondary-user IDs.
- `b5af02e7ae31e57e1d21875c0854ea645a513952`: retain readable user-namespace
  maps and translate SO_PEERCRED for peers in other PRoot instances.
- `7fca80c8a70be9c542bcc9adbff72f8fc3249d0c`: translate fchmodat2 paths
  and preserve symlink-target permissions with AT_SYMLINK_NOFOLLOW.
- `60a9c15ccd477bb80f83f4d170737dc7b8394401`: resolve AT_EMPTY_PATH at
  AT_FDCWD against the guest cwd, including statx.
- `254202b844c61b0ce213043b1e3c2dea38377b1d`: translate open_tree and return
  ENOSYS for unsupported fd-based mount APIs, enabling libc fallback.
- `b8c1f5c5c6730dc9d521d5bd5ce1cfeedecde087`: translate Unix datagram
  destinations, preserve replies and sendmmsg lengths, and protect restarted sends.

Android CMake links the external ARM64 loader at the new LOADER_ADDRESS from
src/arch.h; the x86_64 address is unchanged. The embedded runtime version is
`5.1.107.100-lyra.6`. The Lyra external-loader lookup, API 24 baseline and
static talloc are preserved. No app launch arguments need to change.

## Lyra .5 upstream integration (2026-10-19)

All 21 commits from `9688d01` through `e2d843e`, inclusive, are applied on
main in upstream order. The complete SHA list is in UPSTREAM_COMMITS.md.
This update fixes descriptor queries, simulated hard-link counts, SysV shared
memory allocation through memfd, statx group mapping, and raw fork fallback
under Android's outer seccomp policy, including child register restoration.
It also carries the upstream USERLAND metadata fixes for chmod, ACLs, chown,
statx, empty-path fstatat, renameat2 and orphaned records. USERLAND stays
disabled, as in the previous Lyra packages; enabling it would change the
rootfs metadata format and is outside this integration.

Both ABI pairs retain the external-loader lookup, fixed loader addresses,
API 24 baseline, static talloc and 16 KB ELF alignment. The loader sources are
unchanged, so the rebuilt loader hashes are identical to the .4 packages.

## Lyra .6 upstream integration (2026-10-29)

The merge integrates all 40 upstream commits from `8bf810c` through
`b27d81a`, inclusive. See UPSTREAM_COMMITS.md for their full ordered SHAs
and the retained local contributor-policy file. Main and master have
separate histories, so the merge applies the delta after the already
integrated `e2d843e` baseline while recording master as the second parent.

This fixes simulated hard-link error propagation, failed unlink/rename
bookkeeping, broken chains, intermediate reservation, large link counts,
symlink linking and directory entry types. It also fixes hidden directory
listings, statx permission preservation under outer seccomp, command-path
lookup, failed-startup cleanup, long Unix-socket alias cleanup, dead-child
registration, PID reuse and execve from a non-leader thread.

Version: `5.1.107.100-lyra.6`. External-loader discovery, both fixed loader
addresses, Android API 24, static talloc, 16 KB alignment and disabled
USERLAND/libandroid-shmem are preserved. Runtime sources match master
except for the two pre-existing Lyra external-loader/Bionic adaptations.
The Android harness now uses upstream helpers for threaded exec, statx,
getdents, link, renameat2 and Unix sockets in both seccomp modes.

## Android runtime regression checks

After building, run against an explicitly selected connected device with API 28
or newer (Bionic's posix_spawn helper requires API 28; PRoot still targets 24):

```powershell
./android/test-runtime.ps1 -NdkPath G:/sdk/ndk/29.0.14206865 `
  -Adb G:/sdk/platform-tools/adb.exe -Serial emulator-5554
```

For current Android SELinux policies, use an installed debuggable Lyra APK
containing these exact binaries and PowerShell 7:

```powershell
./android/test-runtime.ps1 -NdkPath "$env:ANDROID_HOME/ndk/29.0.14206865" `
  -Adb "$env:ANDROID_HOME/platform-tools/adb.exe" -Serial emulator-5554 `
  -RunAs com.yukisoffd.lyracode.debug
```

RunAs copies helpers into an isolated application directory and executes the
installed native-library pair after verifying both SHA-256 values against dist.
This tests the application seccomp and SELinux context; adb shell may be denied
permission to create named Unix sockets in its temporary directory.

The script builds upstream helpers and Android regression helpers for the device
ABI, and tests the built binaries in a unique `/data/local/tmp/lyra-proot-*`
directory. It checks simulated hard-link execution (directly, through a symlink,
from a shell, and via execveat), O_NOFOLLOW/O_PATH descriptor names, fork, vfork,
system, 80 concurrent posix_spawn calls across four threads, and sibling-loader
discovery. It also checks full-width fake IDs, regular-file and memfd read-only
reopens, namespace-map reads, guest-cwd empty paths and statx, cross-instance
SO_PEERCRED, fd-mount fallback, fchmodat2 and Unix datagram sends/replies/restarts.
The latest helper additionally checks pipes, sockets, anonymous descriptors,
deleted files, fake IDs through fstat/fstatat/statx, simulated hard-link counts,
and SysV shared memory shared across mappings and fork. The upstream fork_regs
helper verifies parent and child registers with an explicitly trapping outer
filter on x86_64; ABIs without a raw fork syscall report a skip.
The ARM64 helper additionally reserves 256, 300 and 330 GiB with mmap.
Unavailable kernel features and denied memfd reopens are explicitly reported
as skipped. See VALIDATION.md for the checks performed for this revision and
the remaining architecture and host limitations.
Both seccomp modes run with a 90-second timeout. Test files remain
in the reported device directory for inspection. This does not simulate a
kernel that loses ptrace event messages; that needs an affected device/kernel.
