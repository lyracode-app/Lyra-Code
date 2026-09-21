# PRoot（Lyra Code 适配版）

[![Travis build status](https://travis-ci.org/termux/proot.svg?branch=master)](https://travis-ci.org/termux/proot)

本仓库是 [termux/proot](https://github.com/termux/proot) 的分支，针对 Lyra Code 进行了适配。英文版本见 [README.md](README.md)。

## 分支说明

- `master` — 与上游 `termux/proot` master 保持完全同步，不含任何本地修改。
- `main` — 保存针对 Lyra Code 适配修改后的内容。

## 针对 Lyra Code 的适配

Lyra Code 最早在 3.7.0 版本使用 proot 功能时，采用的是 [RikkaHub](https://github.com/rikkahub) 的预编译库。为了排除依赖上游预编译产物可能带来的不确定性问题，从 3.7.1 版本开始改为使用自编译库。为确保出现问题时不至于无法回滚，预编译库仍作为备份保留在 Lyra Code 的源代码仓库中，但构建时不再被打包进 APK。

仓库附带的 `talloc-2.5.0` 并不属于本次适配的修改内容。talloc 2.5.0 以 LGPLv3 协议分发（见 `talloc-2.5.0/LICENSE`），之所以把它放进仓库，是为了编译时能直接使用经过测试的 `talloc.h` 头文件，无需在构建时额外获取源码。

## 编译

仓库已经附带 talloc 依赖，编译时你只需要 `cmake` 和 `ninja`，外加 Android NDK 工具链（构建目标为 arm64-v8a + x86_64 / Android API 24）：

```powershell
cmake -S android -B build/android-arm64 -G Ninja `
  -DCMAKE_TOOLCHAIN_FILE=<ndk>/build/cmake/android.toolchain.cmake `
  -DANDROID_ABI=arm64-v8a `
  -DANDROID_PLATFORM=android-24
cmake --build build/android-arm64
```

构建产物为 PRoot 可执行 ELF 及其独立 loader，文件名符合 Lyra 的打包约定：

- `dist/libproot_exec.so`
- `dist/libproot_loader.so`

更多细节以及与 RikkaHub 二进制的对比，见 [android/README.md](android/README.md) 和 [android/RIKKAHUB_COMPARISON.md](android/RIKKAHUB_COMPARISON.md)。

## 开源协议

- 上游 [termux/proot](https://github.com/termux/proot) 与 [PRoot](https://github.com/proot-me/PRoot)：GPLv2-or-later（见 `COPYING`）。
- 本仓库的适配修改：以 GPLv3 协议分发（见 `LICENSE`）。
- 附带的 `talloc-2.5.0`：LGPLv3 协议（见 `talloc-2.5.0/LICENSE`）。

See [android/README.md](android/README.md) for the dual-ABI build and the 7266fb3 link2symlink fix on main.
