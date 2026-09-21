# PRoot (Lyra Code fork)

[![Travis build status](https://travis-ci.org/termux/proot.svg?branch=master)](https://travis-ci.org/termux/proot)

This repository is a fork of [termux/proot](https://github.com/termux/proot) adapted for Lyra Code. See [README-zh-cn.md](README-zh-cn.md) for the Chinese version.

## Branches

- `master` — kept fully in sync with upstream `termux/proot` master, with no local modifications.
- `main` — holds the adaptations made for Lyra Code.

## Adaptations for Lyra Code

Lyra Code first integrated PRoot functionality in version 3.7.0 using the
prebuilt binaries published by [RikkaHub](https://github.com/rikkahub). To
eliminate the uncertainty of depending on a third-party build, starting with
version 3.7.1 Lyra Code switched to a self-built PRoot library. The RikkaHub
prebuilt binaries are retained as a backup in the Lyra Code source repository
so that a rollback remains possible, but they are no longer packaged into the
APK.

The bundled `talloc-2.5.0` is not part of the PRoot modifications. Talloc 2.5.0
is distributed under the LGPLv3 license (see `talloc-2.5.0/LICENSE`); it is
vendored here so that the build can use a tested `talloc.h` header without
fetching sources at build time.

## Building

The repository already includes the vendored talloc dependency. You only need
`cmake` and `ninja`, plus an Android NDK toolchain (the build targets
arm64-v8a + x86_64 / Android API 24):

```powershell
cmake -S android -B build/android-arm64 -G Ninja `
  -DCMAKE_TOOLCHAIN_FILE=<ndk>/build/cmake/android.toolchain.cmake `
  -DANDROID_ABI=arm64-v8a `
  -DANDROID_PLATFORM=android-24
cmake --build build/android-arm64
```

The build produces the executable PRoot ELF and its freestanding loader with
the `.so` names expected by Lyra's packaging:

- `dist/libproot_exec.so`
- `dist/libproot_loader.so`

See [android/README.md](android/README.md) and
[android/RIKKAHUB_COMPARISON.md](android/RIKKAHUB_COMPARISON.md) for details
and the comparison against the RikkaHub binaries.

## License

- Upstream [termux/proot](https://github.com/termux/proot) and
  [PRoot](https://github.com/proot-me/PRoot): GPLv2-or-later (see `COPYING`).
- Modifications in this fork: distributed under the GPLv3 license (see `LICENSE`).
- Bundled `talloc-2.5.0`: LGPLv3 (see `talloc-2.5.0/LICENSE`).

See [android/README.md](android/README.md) for the dual-ABI build and the 7266fb3 link2symlink fix on main.
