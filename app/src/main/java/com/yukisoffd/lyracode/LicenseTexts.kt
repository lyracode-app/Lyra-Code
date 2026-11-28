package com.yukisoffd.lyracode

internal object LicenseTexts {
    const val PROOT_GPL_NOTICE = """PRoot for Android — source, copyright, and license notice

Lyra Code distributes the following ARM64 and x86_64 PRoot object-code files inside the APK:
- lib/arm64-v8a/libproot_exec.so
- lib/arm64-v8a/libproot_loader.so
- lib/x86_64/libproot_exec.so
- lib/x86_64/libproot_loader.so

PRoot copyright remains with STMicroelectronics and the respective PRoot contributors. The source files state that PRoot may be redistributed and modified under GNU GPL version 2 or, at the recipient's option, any later version (SPDX: GPL-2.0-or-later).

Lyra Code exercises the "any later version" option and conveys these PRoot binaries under GNU GPL version 3 when distributing them with the AGPL-v3 Lyra Code application. This selection does not relicense upstream source that a recipient chooses to use under GPLv2; it records the GPLv3 option used for this distribution.

Corresponding source and upstream chain:
- Maintained corresponding-source fork: https://github.com/Soffd/proot
- Immediate upstream: https://github.com/termux/proot
- Original upstream: https://github.com/proot-me/proot

Binary provenance for this build:
- Source: https://github.com/Soffd/proot
- Source commit: 851fc3c4cc81b9852467ba2890d0cf621033424a (main, including master through 3c6912e)
- Build targets: aarch64-linux-android24, x86_64-linux-android24
- PRoot version: 5.1.107.100-lyra.7
- Toolchain: Android NDK 29.0.14206865; CMake 3.22.1; static talloc 2.5.0
- Configuration: external loader, ARG_MAX=131072, libandroid-shmem disabled
- arm64-v8a/libproot_exec.so SHA-256: 7ef9a2ea7130791f66f8e0d903fea69043a83175791269b8414130abf78725e7
- arm64-v8a/libproot_loader.so SHA-256: 825d0362db3319c2b3ed3b75fe300287c0f9eeb7f2427ceb05a4a004ad33ed12
- x86_64/libproot_exec.so SHA-256: 97fcc734d0a882f7ee8b5ccba39d4cc977f41b8fc2d18ffaf483bb793b05ce9a
- x86_64/libproot_loader.so SHA-256: 61b2dd1a858caddbab4647c519cfe0c23bbd2d47981358b9cc6ae818e79e6869

The executable statically links talloc 2.5.0, copyright its respective Samba contributors, under LGPL-3.0-or-later. Its source and license material are included with the maintained corresponding source. The resulting combined binary is conveyed under GPLv3 as part of this AGPL-v3 application distribution.

The packaged binaries are built locally from the maintained Soffd/proot corresponding source. Verified RikkaHub-derived binaries remain in the source tree only as an unpackaged recovery reference and are not included in the APK.

PRoot is provided without warranty. The complete GNU GPL version 3 terms selected for this distribution follow.
"""

    const val TEXTMATE_ASSET_NOTICES = """Sora Editor demo TextMate assets

Lyra Code includes TextMate grammar, language-configuration, and color-theme files copied from the official Sora Editor demo application for Java, Kotlin, Python, JavaScript, HTML, XML, Markdown, and Lua editing.

These data files originate from several upstream language-extension and theme projects. Copyright remains with their respective authors. They are distributed under their individual upstream licenses, primarily permissive MIT-style terms; the Sora Editor LGPL does not replace those asset licenses.

Included themes: Ayu Dark, Darcula, Quiet Light, and Solarized Dark.

When redistributing the source assets, retain upstream copyright and license notices and review the corresponding upstream extension/theme license for each updated or replaced asset. Source bundle: https://github.com/Rosemoe/sora-editor/tree/main/app/src/main/assets/textmate"""
}
