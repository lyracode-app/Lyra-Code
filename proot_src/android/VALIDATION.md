# Lyra .6 validation (2026-10-29)

## Source integration and compatibility

The merge records all 40 master commits from 8bf810c through b27d81a,
inclusive, as upstream history. UPSTREAM_COMMITS.md lists their full SHAs.
The delta is applied after e2d843e, the baseline already integrated on the
separate main history. AGENTS.md retains its existing main content under
the local contributor policy; its instruction-only upstream change is not
applied. No upstream runtime or test change is omitted.

Runtime sources match b27d81a except for the two pre-existing Lyra patches:
external-loader lookup in src/execve/enter.c and Bionic's string.h include
in src/extension/ashmem_memfd/ashmem_memfd.c. The Android source list still
covers every runtime translation unit. Launch arguments and rootfs storage
are unchanged. USERLAND and libandroid-shmem remain disabled.

The new upstream getdents helper needs an IFTODT fallback on Bionic; this
is a test-only portability adaptation. The Android harness compiles the
upstream threaded-exec, getdents, link, renameat2, statx and Unix-socket
helpers, in addition to its existing fork, descriptor and IPC regressions.
All 16 helper programs compile for both ABIs at test API 28.

## Android builds and source reproducibility

Both arm64-v8a and x86_64 build with NDK 29.0.14206865, Clang 21,
CMake 3.22.1, Ninja, Android API 24, -O2 and static talloc 2.5.0.
Version: `5.1.107.100-lyra.6`.

ELF inspection confirms the expected ELF64 architectures, PIE executables
using Bionic linker64 with only libc.so as a dynamic dependency, static
freestanding loaders, non-executable stacks and 16 KB LOAD alignment.
Loader entry points remain 0x5400000000 on ARM64 and 0x600000000000 on
x86_64. Loader hashes are unchanged from the .5 packages.

Fresh, separate builds from the Git-free proot_src mirror reproduce all
four packaged files byte-for-byte:

- arm64-v8a/libproot_exec.so: 8cd986e8e9505f20ef263095037d59ece8756b230e5730315dc142332a8032e2
- arm64-v8a/libproot_loader.so: 825d0362db3319c2b3ed3b75fe300287c0f9eeb7f2427ceb05a4a004ad33ed12
- x86_64/libproot_exec.so: e3c6a7bde6a8e4851b2ddc36965b5a6c3fe71b5e630731aa7869e046cce82a72
- x86_64/libproot_loader.so: 61b2dd1a858caddbab4647c519cfe0c23bbd2d47981358b9cc6ae818e79e6869

## Runtime and application checks

The installed Lyra debug APK passes test-runtime.ps1 -RunAs on an x86_64
Android API 37 emulator, kernel 6.12.58, with 16 KB pages. Installed native
library hashes are checked against dist before execution. Both seccomp and
no-seccomp modes finish with LYRA6_UPSTREAM_REGRESSIONS_OK; sibling-loader
discovery finishes with SIBLING_LOADER_OK.

The existing hard-link execution, execveat, O_NOFOLLOW/O_PATH, fork/vfork,
threaded posix_spawn, raw-fork register, fake-ID, descriptor, SysV shared
memory, fd-mount, fchmodat2 and Unix datagram regressions pass again.
New checks cover non-leader-thread execve, failed link/rename bookkeeping,
symlink linking, failed guest translation leaving host files untouched,
link counts across 10000, getdents/getdents64 hard-link types, hidden names,
ENOTDIR listings, statx target modes, socket-alias cleanup, failed-startup
binding preservation and command lookup through overlapping bindings.

Explicit skips: the emulator lacks user namespaces and denies memfd
reopening. SysV shared memory backed by memfd still passes. In no-seccomp
mode only, artificially trapped statx returns ENOSYS (helper exit 125),
so that combination is skipped as in the upstream test. Normal statx in
both modes and trapped statx in seccomp mode pass; target permissions are
also checked after the skipped call. Negative startup tests intentionally
print exec errors. Minimal-rootfs linker-configuration warnings are benign.

An adb-shell run passes the threaded-exec and fork checks, then cannot
bind a Unix socket under the shell SELinux policy. It is not counted as
a full passing run; the application-sandbox run above is the runtime result.

:app:verifyBundledProotRuntime and :app:assembleDebug pass. All four native
files extracted from the APK have exactly the pinned package hashes, and
adb installation succeeds. Version, hashes and source provenance are updated
in the Gradle checks, runtime README, license text and third-party notices.

The full application unit suite runs 683 tests: 676 pass, 4 fail, 3 skip.
The failures are in the unchanged PendingInputAttachmentReaperTest (two)
and PendingInputFileStoreTest (two). Their unchanged localPath/hasScheme
logic treats Windows drive-letter paths as URI schemes, so attachment
existence/deletion assertions fail on this host. These unrelated app
files and tests are not modified by the PRoot integration. The complete
unit suite is therefore not reported as passing.

No ARM64 Android device is connected. ARM64 runtime behavior and the
32-bit tracee paths are not covered. USERLAND remains disabled and its
optional metadata behavior is not runtime-tested. No Linux-host runtime
results are claimed.
