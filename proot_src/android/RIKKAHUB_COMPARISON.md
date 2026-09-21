# RikkaHub binary comparison

Comparison date: 2026-08-21

Reference files are the RikkaHub-derived binaries currently stored in Lyra at
`app/src/debianRuntime/jniLibs/arm64-v8a`. The local candidates were built from
this PRoot fork with Android NDK r29, API 24, and talloc 2.5.0.

| Property | RikkaHub exec | Local exec | RikkaHub loader | Local loader |
| --- | ---: | ---: | ---: | ---: |
| Stripped bytes | 233392 | 238408 | 18136 | 18104 |
| ELF type | ET_DYN | ET_DYN | ET_EXEC | ET_EXEC |
| Architecture | AArch64 | AArch64 | AArch64 | AArch64 |
| Interpreter | `/system/bin/linker64` | `/system/bin/linker64` | none | none |
| Dynamic dependencies | `libc.so` | `libc.so` | none | none |
| Entry point | `0x14860` | `0x130a0` | `0x2000000000` | `0x2000000000` |
| Text bytes | 217905 | 222684 | 1128 | 1128 |

## Loader result

The two 1128-byte loader `.text` sections are byte-for-byte identical. Their
SHA-256 is:

`4cda9740ce6bf8f539a600c1c7242a23399737036b9c6582913eadd57a902c22`

The whole loader files differ only in non-runtime metadata such as compiler
comments. This is strong evidence that RikkaHub did not carry a private loader
logic patch and that the current fork still builds the compatible loader.

## Exec result

Both executables have the Android API 24 note, use the system linker, and need
only Bionic libc. The local executable is slightly larger because the fork is
newer and contains behavior absent from the reference binary, including the
newer netlink/AF_UNIX fallback. It contains no package-specific fallback loader
path: `PROOT_LOADER` takes precedence, followed by `libproot_loader.so` beside
`/proc/self/exe`, followed by an explicit error.

The exec comparison cannot prove that every RikkaHub source line was pristine,
because RikkaHub published binaries rather than the corresponding build source.
It does show no ABI or loader-protocol dependency that requires carrying their
prebuilt executable forever.

## Known compiler warnings

NDK r29/Clang 21 also reports several warnings in the newer fork that are not
caused by the Android build wrapper:

- `tracee/seccomp.c` has a path where `ret` may be read uninitialized.
- `extension/port_switch/port_switch.c` applies `sizeof` to an array parameter,
  which produces the pointer size rather than the caller's array size.
- `extension/sysvipc/sysvipc_shm.c` has a non-void path without a return and
  still uses deprecated `mktemp`.

These do not prevent arm64 compilation. They remain useful audit targets for
future fork maintenance and should be reconsidered whenever the toolchain or
upstream source is updated.

## Packaging decision

The Lyra-built pair was promoted to the packaged runtime after device testing
reported no usage regressions. The RikkaHub pair is retained only as an
unpackaged, hash-verified recovery reference under
`app/src/prootReference/rikkahub/arm64-v8a`.

Future source or toolchain updates should repeat at least:

1. Debian and Alpine arm64 rootfs startup.
2. `--root-id`, `--link2symlink`, and `--kill-on-exit` together.
3. Executing dynamically linked binaries and shell scripts.
4. Rootfs files on app-private and shared-storage paths.
5. Process cancellation and cleanup.
6. Android versions at the minimum supported API and a current API.
