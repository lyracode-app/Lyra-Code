# Third-Party Notices

This document summarizes the main third-party components used by Lyra Code. It is provided for release compliance review and does not replace the original license files or notices shipped by each component.

## Runtime / Application Dependencies

| Component | License | Notes |
| --- | --- | --- |
| Android Gradle Plugin | Apache License 2.0 | Build tool. |
| Kotlin / Kotlin Gradle tooling | Apache License 2.0 | Language and build tooling. |
| AndroidX Core KTX | Apache License 2.0 | Runtime Android support library. |
| AndroidX Activity Compose | Apache License 2.0 | Compose Activity integration. |
| Jetpack Compose UI / Material 3 / Icons Extended | Apache License 2.0 | UI framework and icons. |
| AndroidX DocumentFile | Apache License 2.0 | Storage Access Framework helper. |
| AndroidX Security Crypto | Apache License 2.0 | Encrypted preferences. |
| Kotlinx Coroutines Android | Apache License 2.0 | Coroutine runtime. |
| OkHttp | Apache License 2.0 | HTTP client. |
| Android Mail / Jakarta Mail API for Android 1.6.7 (`com.sun.mail:android-mail`) | Eclipse Public License 2.0 | Controls Lyra Code's IMAP message reading, folder and draft management, SMTP sending, and MIME processing. The packaged JAR retains its `META-INF/LICENSE.md` and `META-INF/NOTICE.md`. |
| Jakarta Activation fork for Android (`com.sun.mail:android-activation`) | Eclipse Public License 2.0 | MIME data handlers used by mail attachments. The packaged JAR retains its license and notice resources. |
| mwiede JSch | BSD/ISC-style licenses; includes bundled license files | SSH client. Check upstream `LICENSE.txt`, `LICENSE.JZlib.txt`, and `LICENSE.jBCrypt.txt`. |
| JetBrains Markdown / RikkaHub fork | Apache License 2.0 | Markdown parser/rendering support. |
| AndroidSVG 1.4 | Apache License 2.0 | Native static SVG parsing and rendering for assistant messages and media previews. Copyright 2013 Paul LeBeau, Cave Rock Software Ltd. |
| Mermaid 11.17.2 | MIT; distributable retains transitive dependency notices | Offline diagram rendering. Pinned npm distributable and original license are packaged in `app/src/main/assets/charts`. |
| Schemdraw 0.23 symbol geometry | MIT | Selected circuit symbol geometry is generated into the offline catalog by `tools/generate_circuit_symbols.py`. Python and Schemdraw are regeneration tools, not APK runtime dependencies. Preserve the copyright notice and `app/src/main/assets/charts/SCHEMDRAW-LICENSE`; both native and browser catalog snapshots retain provenance. Lyra's functional symbols and renderer remain AGPL-3.0. |
| Apache ECharts 6.1.0 | Apache License 2.0; bundled D3 portions retain BSD notices | Offline data-chart rendering. Original `LICENSE`, `NOTICE` and D3 license are packaged in `app/src/main/assets/charts`. |
| Sora Editor (`io.github.rosemoe:editor`, `language-textmate`) | GNU LGPL 2.1 or later | Android code editor, line numbers, search, wrapping, and TextMate integration. Preserve the LGPL notice and allow replacement/relinking of the library as required by the license. |
| Sora Editor demo TextMate themes and language bundles | Upstream component licenses, primarily MIT-style; distributed by the Sora Editor project | Syntax/theme assets for Java, Kotlin, Python, JavaScript, HTML, XML, Markdown, and Lua were copied from the official Sora Editor demo assets. Retain upstream notices when redistributing source assets. |
| JLaTeXMath Android / local fork | GPL v2 or later with a special linking exception; distributed under GPL v3 for this combination | LaTeX rendering. Its independent-module linking exception and GPL v2-or-later grant permit combination with Lyra Code under AGPL v3. Keep `third_party/jlatexmath/LICENSE`, `COPYING`, `src/main/assets/org/scilab/forge/jlatexmath/licences/GPL-3.0.txt`, and the font licenses with distributions. |
| Bundled JLaTeXMath core fonts | SIL OFL 1.1, Knuth/Computer Modern terms, permissive terms, or Public Domain, depending on the font | The optional upstream `jlatexmath-font-greek` module and its GPL-2.0-only fonts are excluded from this repository and every build. See `third_party/jlatexmath/LICENSE` and `third_party/jlatexmath/src/main/assets/org/scilab/forge/jlatexmath/fonts/licences/`. |
| PRoot for Android | GNU GPL 2.0 or later; GPL v3 selected for this distribution | Every APK packages the repository-local ARM64 and x86_64 executables and loaders in `app/src/debianRuntime/jniLibs`, built from Soffd/proot main commit 75ef424c5d241d2c90a6312f4935d18977165375 with NDK 29.0.14206865, API 24, and static talloc 2.5.0. Builds never fetch these artifacts from the network and fail if their fixed SHA-256 values do not match. Corresponding source is maintained at `Soffd/proot`; its immediate upstream is `termux/proot`, whose upstream is `proot-me/proot`. PRoot source notices permit GPL version 2 or any later version, and Lyra Code exercises the GPLv3 option when distributing it with this AGPL-v3 application. The in-app Open Source Licenses page contains the source chain, artifact hashes, copyright notice, and complete GPLv3 terms. |
| RikkaHub workspace runtime artifacts | GNU AGPL 3.0 | The pinned PRoot binaries and the Android `nativeLibraryDir` execution approach were obtained from RikkaHub's workspace module. The PRoot binaries retain their own GPL-2.0-or-later terms; RikkaHub's AGPL-3.0 license covers its own project material and does not replace the PRoot license. |
| Debian Trixie ARM64 / amd64 root filesystems (on-demand download) | Per-package Debian licenses | The pinned rootfs is produced by the official `debuerreotype/docker-debian-artifacts` project. It is not stored in the APK; supported users can request a verified download. It contains many independently licensed Debian packages whose package copyright/license information must be preserved. |

## Test-Only Dependencies

The web companion additionally packages the repository's existing Marked (MIT) and DOMPurify (Apache-2.0 or MPL-2.0) distributions for offline Markdown rendering and sanitization. Their original notices are retained in `app/src/main/assets/web-access/marked-LICENSE.txt` and `dompurify-LICENSE.txt`.

| Component | License | Notes |
| --- | --- | --- |
| JUnit | Eclipse Public License 1.0 | Test dependency. |
| AndroidX Test / Espresso | Apache License 2.0 | Android instrumentation tests. |
| org.json JSON-java | JSON License | Declared as `testImplementation`; not intended to be packaged in the Android app. The JSON License contains the well-known "Good, not Evil" use restriction, so avoid shipping it in AGPL builds unless reviewed separately. |

## Compliance Notes

- Apache License 2.0 dependencies can generally be combined into GPLv3/AGPLv3 works, but keep required notices and license texts.
- PRoot is `GPL-2.0-or-later`, not `GPL-2.0-only`. This distribution selects GPLv3 for compatibility with Lyra Code's AGPLv3 license. Keep the `Soffd/proot` corresponding-source fork, upstream chain, exact binary hashes, and full GPLv3 terms available to every binary recipient.
- The local JLaTeXMath module is the main copyleft-sensitive component. Its local `LICENSE` says GPL v2 or later and includes a special linking exception for independent modules; Lyra Code distributes the library under GPL v3 when combining it with the AGPL-v3 application. Preserve the upstream notices and exception when modifying it.
- Do not add the optional upstream `jlatexmath-font-greek` module or any of `jlm_fcmbipg.ttf`, `jlm_fcmripg.ttf`, `jlm_fcmrpg.ttf`, `jlm_fcsropg.ttf`, `jlm_fcmbpg.ttf`, `jlm_fcsbpg.ttf`, `jlm_fctrpg.ttf`, or `jlm_fcsrpg.ttf`. Those fonts are GPL-2.0-only and are rejected by the JLaTeXMath pre-build verification task.
- Lyra Code original code is licensed only under AGPL-3.0. Distributors must also comply with all applicable third-party licenses or replace those components with separately licensed alternatives.

### PRoot 5.1.107.100-lyra.6 binary checksums

- arm64-v8a/libproot_exec.so SHA-256: 8cd986e8e9505f20ef263095037d59ece8756b230e5730315dc142332a8032e2
- arm64-v8a/libproot_loader.so SHA-256: 825d0362db3319c2b3ed3b75fe300287c0f9eeb7f2427ceb05a4a004ad33ed12
- x86_64/libproot_exec.so SHA-256: e3c6a7bde6a8e4851b2ddc36965b5a6c3fe71b5e630731aa7869e046cce82a72
- x86_64/libproot_loader.so SHA-256: 61b2dd1a858caddbab4647c519cfe0c23bbd2d47981358b9cc6ae818e79e6869

Rebuild instructions: app/src/debianRuntime/jniLibs/README.md. Corresponding source is included in `proot_src/`, retaining 7266fb3, the previous fixes through d4d2a19, and all nine earlier October commits: a179d3e, 37c09de, bd6d55e, c1cd48d, b5af02e, 7fca80c, 60a9c15, 254202b and b8c1f5c. The October 19 update integrates all 21 upstream commits from 9688d01 through e2d843e, inclusive; their complete hashes are recorded in `proot_src/android/UPSTREAM_COMMITS.md`. USERLAND remains disabled. The ARM64 external loader remains at 0x5400000000. RikkaHub binaries are unpackaged recovery references only.
