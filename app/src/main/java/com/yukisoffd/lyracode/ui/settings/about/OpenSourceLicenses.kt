package com.yukisoffd.lyracode

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private const val LYRA_SOURCE = "https://github.com/lyracode-app/Lyra-Code/blob/main"
private const val X11_ASSET_LICENSES = "lyra_x11/licenses"
private const val TERMUX_X11_SOURCE = "https://github.com/termux/termux-x11/blob/f1d9834236341a5b3723cd7e4fe65601673a2d5a"
private const val ANDROIDX_LICENSE = "https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt"
private const val GPL_V3_ASSET = "org/scilab/forge/jlatexmath/licences/GPL-3.0.txt"
private const val GPL_V2_ASSET = "licenses/tigervnc-LICENSE.txt"
private const val APACHE_LICENSE_ASSET = "licenses/apache-2.0-LICENSE.txt"
private const val FONT_LICENSES = "org/scilab/forge/jlatexmath/fonts/licences"
private const val MESA_LICENSES = "licenses/mesa-LICENSES.txt"

private data class LicenseNotice(
    val name: String,
    val license: String,
    @param:StringRes val note: Int,
    val url: String? = null,
    val readText: ((Context) -> String?)? = null,
) {
    init {
        require(url == null || readText != null) { "Missing offline license for $name" }
    }
}

private fun assetLicenses(vararg paths: String): (Context) -> String? = { context ->
    paths.mapNotNull { context.readLicenseAsset(it) }.joinToString("\n\n").ifBlank { null }
}

private fun Context.readLicenseAsset(path: String): String? = runCatching {
    assets.open(path).bufferedReader().use { it.readText() }
}.getOrNull()

private fun apache(
    name: String,
    @StringRes note: Int,
    url: String = ANDROIDX_LICENSE,
    asset: String = "licenses/androidx-LICENSE.txt",
) = LicenseNotice(name, "Apache License 2.0", note, url, assetLicenses(asset))

private val jakartaMailLicense = assetLicenses("licenses/jakarta-mail-LICENSE.md", "licenses/jakarta-mail-NOTICE.md")

private val applicationNotices = listOf(
    LicenseNotice("Lyra Code", "GNU AGPL v3", R.string.license_lyra_note, "$LYRA_SOURCE/LICENSE",
        assetLicenses("licenses/lyra-code-LICENSE.txt")),
    apache("Android Gradle Plugin", R.string.ui_android_build_toolchain,
        "https://android.googlesource.com/platform/tools/base/+/mirror-goog-studio-main/", APACHE_LICENSE_ASSET),
    apache("Kotlin", R.string.ui_main_development_language_and_compiler,
        "https://github.com/JetBrains/kotlin/blob/v2.2.21/license/LICENSE.txt", APACHE_LICENSE_ASSET),
    apache("AndroidX Core KTX", R.string.ui_android_kotlin_extensions_and_compatibility_layer),
    apache("AndroidX Activity Compose", R.string.ui_compose_activity_integration),
    apache("AndroidX Browser", R.string.license_browser_note),
    apache("Jetpack Compose UI", R.string.ui_declarative_ui_framework),
    apache("Jetpack Compose Material 3", R.string.ui_material_design_3_components),
    apache("Jetpack Compose Material Icons Extended", R.string.ui_ui_icon_library),
    apache("AndroidX DocumentFile", R.string.ui_saf_workspace_file_access),
    apache("AndroidX Security Crypto", R.string.ui_local_encrypted_storage_for_sensitive_configuration),
    apache("AndroidX WorkManager", R.string.license_work_note),
    apache("AndroidX Media3 / ExoPlayer", R.string.license_media_note,
        "https://github.com/androidx/media/blob/1.10.1/LICENSE", APACHE_LICENSE_ASSET),
    apache("Kotlinx Coroutines", R.string.ui_async_tasks_and_streaming_requests,
        "https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/LICENSE.txt", "licenses/coroutines-LICENSE.txt"),
    apache("OkHttp", R.string.ui_http_sse_compatible_reading_and_mcp_streamable_http_communication,
        "https://github.com/square/okhttp/blob/parent-4.12.0/LICENSE.txt", APACHE_LICENSE_ASSET),
    LicenseNotice("Android Mail / Jakarta Mail for Android 1.6.7", "Eclipse Public License 2.0",
        R.string.ui_provides_imap_message_reading_folder_and_draft_management_smtp,
        "https://github.com/eclipse-ee4j/mail/blob/1.6.7/LICENSE.md", jakartaMailLicense),
    LicenseNotice("Jakarta Activation for Android 1.6.7", "Eclipse Public License 2.0",
        R.string.license_activation_note, "https://central.sonatype.com/artifact/com.sun.mail/android-activation/1.6.7",
        jakartaMailLicense),
    apache("JetBrains Markdown / RikkaHub Markdown fork", R.string.ui_markdown_gfm_ast_parsing_with_tables_lists_and_math,
        "https://github.com/rikkahub/markdown/blob/66a56dffca/LICENSE", "licenses/rikkahub-markdown-LICENSE.txt"),
    LicenseNotice("markdown-it 14.1.0", "MIT", R.string.license_markdown_it_note,
        "https://github.com/markdown-it/markdown-it/blob/14.1.0/LICENSE", assetLicenses("licenses/markdown-it-LICENSE.txt")),
    LicenseNotice("KaTeX 0.16.11", "MIT", R.string.license_katex_note,
        "https://github.com/KaTeX/KaTeX/blob/v0.16.11/LICENSE", assetLicenses("licenses/katex-LICENSE.txt")),
    LicenseNotice("KaTeX fonts", "MIT", R.string.license_katex_note,
        "https://github.com/KaTeX/katex-fonts/blob/master/LICENSE", assetLicenses("licenses/katex-fonts-LICENSE.txt")),
    apache("AndroidSVG 1.4", R.string.ui_native_svg_rendering,
        "https://github.com/BigBadaboom/androidsvg/blob/Release_1.4/LICENSE", "licenses/androidsvg-LICENSE.txt"),
    LicenseNotice("Mermaid 11.17.2", "MIT", R.string.tool_create_chart_desc,
        "https://github.com/mermaid-js/mermaid/blob/develop/LICENSE", assetLicenses("charts/MERMAID-LICENSE")),
    LicenseNotice("Schemdraw 0.23 symbol geometry", "MIT", R.string.tool_get_circuit_symbols_desc,
        "https://github.com/cdelker/schemdraw/blob/master/LICENSE.txt", assetLicenses("charts/SCHEMDRAW-LICENSE")),
    LicenseNotice("Apache ECharts 6.1.0", "Apache License 2.0 / BSD", R.string.tool_create_chart_desc,
        "https://github.com/apache/echarts/blob/6.1.0/LICENSE",
        assetLicenses("charts/ECHARTS-LICENSE", "charts/ECHARTS-NOTICE", "charts/ECHARTS-D3-LICENSE")),
    LicenseNotice("LaTeX.js 0.13.1", "MIT", R.string.license_latex_preview_note,
        "https://github.com/michael-brade/LaTeX.js/blob/master/LICENSE", assetLicenses("latex-preview/LICENSE")),
    LicenseNotice("Marked", "MIT", R.string.license_marked_note,
        "https://github.com/markedjs/marked/blob/master/LICENSE", assetLicenses("web-access/marked-LICENSE.txt")),
    LicenseNotice("DOMPurify", "Apache-2.0 OR MPL-2.0", R.string.license_dompurify_note,
        "https://github.com/cure53/DOMPurify/blob/main/LICENSE", assetLicenses("web-access/dompurify-LICENSE.txt")),
    LicenseNotice("JSch / mwiede fork", "BSD 3-Clause and bundled notices", R.string.ui_ssh_connections_and_remote_command_execution,
        "https://github.com/mwiede/jsch/blob/jsch-0.2.21/LICENSE.txt",
        assetLicenses("licenses/jsch-LICENSE.txt", "licenses/jsch-LICENSE.JZlib.txt", "licenses/jsch-LICENSE.jBCrypt.txt")),
    LicenseNotice("Shizuku API / Provider", "MIT", R.string.license_shizuku_note,
        "https://github.com/RikkaApps/Shizuku-API/blob/master/LICENSE", assetLicenses("licenses/shizuku-LICENSE.txt")),
    LicenseNotice("PRoot for Android / Soffd fork", "GPL-2.0-or-later; GPLv3 selected", R.string.license_proot_note,
        "https://github.com/Soffd/proot/blob/main/LICENSE") { context ->
        val gpl = context.readLicenseAsset(GPL_V3_ASSET)
        LicenseTexts.PROOT_GPL_NOTICE + "\n\n" + (gpl ?: "https://www.gnu.org/licenses/gpl-3.0.txt")
    },
    LicenseNotice("talloc 2.5.0", "GNU LGPL v3", R.string.license_talloc_note,
        "https://github.com/Soffd/proot/blob/main/talloc-2.5.0/LICENSE",
        assetLicenses("licenses/talloc-LICENSE.txt", GPL_V3_ASSET)),
    LicenseNotice("Debian Trixie rootfs / debuerreotype", "debuerreotype: Apache 2.0", R.string.license_debian_rootfs_note,
        "https://github.com/debuerreotype/docker-debian-artifacts/blob/master/LICENSE",
        assetLicenses("licenses/debuerreotype-LICENSE.txt")),
    LicenseNotice("JLaTeXMath Android / Soffd fork", "GPL v2 or later with linking exception; GPL v3 selected",
        R.string.ui_local_latex_math_rendering_source_is_kept_under_third,
        "https://github.com/Soffd/jlatexmath-android/blob/android/LICENSE",
        assetLicenses("licenses/jlatexmath-LICENSE.txt", GPL_V3_ASSET)),
    LicenseNotice("JLaTeXMath core fonts", "OFL / Knuth / permissive terms / Public Domain",
        R.string.ui_math_rendering_fonts_full_font_licenses_are_distributed_under,
        "https://github.com/lyracode-app/Lyra-Code/tree/main/third_party/jlatexmath/src/main/assets/org/scilab/forge/jlatexmath/fonts/licences",
        assetLicenses("licenses/jlatexmath-LICENSE.txt", "$FONT_LICENSES/OFL.txt", "$FONT_LICENSES/Knuth_License.txt",
            "$FONT_LICENSES/License_for_dsrom.txt")),
    LicenseNotice("Unicode 16.0 East Asian Width data", "Unicode License v3", R.string.license_unicode_note,
        "https://www.unicode.org/license.txt", assetLicenses("licenses/UNICODE-LICENSE.txt")),
    LicenseNotice("DeepSeek V4 tokenizer data", "MIT", R.string.license_tokenizer_note,
        "https://huggingface.co/deepseek-ai/DeepSeek-V4-Pro/blob/main/LICENSE",
        assetLicenses("licenses/deepseek-v4-tokenizer-LICENSE.txt")),
    LicenseNotice("JSON-java / org.json", "Public Domain", R.string.license_test_only_note,
        "https://github.com/stleary/JSON-java/blob/20250517/LICENSE", assetLicenses("licenses/json-java-LICENSE.txt")),
    LicenseNotice("JUnit", "Eclipse Public License 1.0", R.string.ui_unit_test_framework_used_only_for_test_builds,
        "https://github.com/junit-team/junit4/blob/r4.13.2/LICENSE-junit.txt", assetLicenses("licenses/junit-LICENSE.txt")),
)

private val editorNotices = listOf(
    LicenseNotice("Sora Editor / language-textmate", "GNU LGPL 2.1 or later", R.string.license_sora_editor_note,
        "https://github.com/Rosemoe/sora-editor/blob/0.24.4/LICENSE", assetLicenses("licenses/sora-editor-LICENSE.txt")),
    LicenseNotice("Sora Editor demo TextMate grammars and themes", "Individual upstream licenses",
        R.string.license_textmate_assets_note,
        "https://github.com/Rosemoe/sora-editor/tree/main/app/src/main/assets/textmate") { context ->
        context.readLicenseAsset("textmate/licenses/sora-demo-upstream-LICENSES.txt")?.let { upstream ->
            LicenseTexts.TEXTMATE_ASSET_NOTICES + "\n\n" + upstream
        }
    },
    LicenseNotice("tm-grammars 1.32.22", "Individual grammar licenses", R.string.license_grammar_note,
        "https://github.com/shikijs/textmate-grammars-themes/blob/main/LICENSE",
        assetLicenses("textmate/licenses/tm-grammars-LICENSE", "textmate/licenses/tm-grammars-NOTICE")),
    LicenseNotice("Cangjie TextMate grammar", "MIT", R.string.license_grammar_note,
        "https://github.com/ide4cj/cangjie-vscode/blob/9ce59bc791811a4f6b33ee47ad4e84082f8b54c7/LICENSE-MIT",
        assetLicenses("textmate/licenses/cangjie.txt")),
    LicenseNotice("DeviceTree TextMate grammar", "MIT", R.string.license_grammar_note,
        "https://github.com/trond-snekvik/vscode-devicetree/blob/a1c56379d75437575b8482a465c3077b138bfdb1/LICENSE",
        assetLicenses("textmate/licenses/dts.txt")),
    LicenseNotice("PHP HTML TextMate grammar / Visual Studio Code", "MIT", R.string.license_grammar_note,
        "https://github.com/microsoft/vscode/blob/c9e45a55a0bfce9628d281fb38e43de7fd138818/LICENSE.txt",
        assetLicenses("textmate/licenses/php-html.txt")),
    LicenseNotice("Ren’Py TextMate grammar", "MIT", R.string.license_grammar_note,
        "https://github.com/coodeneko/renpy-language-vscode/blob/6db481e34f4be3cca54c5d8d5be28b0c7c1d9d21/LICENSE.MD",
        assetLicenses("textmate/licenses/renpy.txt")),
    LicenseNotice("Smali TextMate grammar", "MIT", R.string.license_grammar_note,
        "https://github.com/CrazyWolf2014/vscode-smali/blob/6704ba89518dc9610fae8ea4166b87e8d24a7ab8/LICENSE.md",
        assetLicenses("textmate/licenses/smali.txt")),
)

private val brandNotices = listOf(
    LicenseNotice("Lobe Icons", "MIT", R.string.license_lobe_note,
        "https://github.com/lobehub/lobe-icons/blob/master/LICENSE", assetLicenses("licenses/lobe-icons-LICENSE.txt")),
    LicenseNotice("Simple Icons", "CC0 1.0 Universal", R.string.ui_repository_svg_icons_on_the_about_page,
        "https://github.com/simple-icons/simple-icons/blob/develop/LICENSE.md", assetLicenses("licenses/simple-icons-LICENSE.md")),
    LicenseNotice("Third-party brand marks", "Respective trademark owners", R.string.license_trademarks_note),
)

private fun x11Notice(name: String, license: String, asset: String, url: String?) =
    LicenseNotice(name, license, R.string.license_x11_dependency_note, url, assetLicenses("$X11_ASSET_LICENSES/$asset"))

private fun xorgLibrary(name: String, repository: String, noticeFile: String, revision: String) =
    x11Notice(name, "X.Org upstream terms", "xserver/$noticeFile",
        "https://gitlab.freedesktop.org/xorg/lib/$repository/-/blob/$revision/COPYING")

private val x11Notices = listOf(
    LicenseNotice("Lyra X11", "GNU GPL v3", R.string.license_x11_module_note,
        "https://github.com/lyracode-app/lyra_x11/blob/9a6c5da1c8a3e4d0a8755290448beadb529f7399/LICENSE",
        assetLicenses("licenses/lyra-x11-LICENSE.txt")),
    x11Notice("Termux-X11 DDX", "GNU GPL v3", "xserver/termux-x11-LICENSE", "$TERMUX_X11_SOURCE/LICENSE"),
    LicenseNotice("InputXKB", "GPL-2.0-or-later", R.string.license_x11_dependency_note,
        "$TERMUX_X11_SOURCE/lorie/src/main/cpp/lorie/InputXKB.c",
        assetLicenses("licenses/inputxkb-LICENSE.txt", GPL_V2_ASSET)),
    x11Notice("X.Org Server", "X.Org upstream terms", "xserver/xserver-COPYING",
        "https://gitlab.freedesktop.org/xorg/xserver/-/blob/65d790bd208ec380b196eb98f144abb0b32e334d/COPYING"),
    LicenseNotice("xorgproto 2026.1", "Individual X.Org protocol licenses", R.string.license_x11_dependency_note,
        "https://gitlab.freedesktop.org/xorg/proto/xorgproto/-/tree/xorgproto-2026.1") { context ->
        val directory = "$X11_ASSET_LICENSES/xserver"
        context.assets.list(directory).orEmpty().filter { it.startsWith("xorgproto-COPYING-") }.sorted()
            .mapNotNull { name -> context.readLicenseAsset("$directory/$name")?.let { "$name\n\n$it" } }
            .joinToString("\n\n").ifBlank { null }
    },
    xorgLibrary("libX11", "libx11", "libx11-COPYING", "6c75545a1deb51f5903992c52af6bc35cc9bc103"),
    xorgLibrary("libXau", "libxau", "libxau-COPYING", "a9c65683e68b3a4349afee5d7673b393fb924d2e"),
    xorgLibrary("libxcvt", "libxcvt", "libxcvt-COPYING", "dd8631c61465cc0de5e476c7a98e56528d62b163"),
    xorgLibrary("libXdmcp", "libxdmcp", "libxdmcp-COPYING", "1192d3bc407348ff316bd3bffc791b3ac73f591b"),
    xorgLibrary("libXfont", "libxfont", "libxfont-COPYING", "975cc6526e892c6fad8fa49d0fef58565fbaa003"),
    xorgLibrary("libfontenc", "libfontenc", "libfontenc-COPYING", "780d1f6f192a331de7b298a96e23ebc44d2a884b"),
    xorgLibrary("libxkbfile", "libxkbfile", "libxkbfile-COPYING", "42e5dedd7fd3c7c73f3870a8751893c03c1afc69"),
    xorgLibrary("libxshmfence", "libxshmfence", "libxshmfence-COPYING", "89f064748554e11832a3ec783945e1f4c7fe846e"),
    xorgLibrary("xtrans", "libxtrans", "libxtrans-COPYING", "cf05ba4a10c90da2c63805a5375e983b174e28b0"),
    x11Notice("xkbcomp", "X.Org upstream terms", "xserver/xkbcomp-COPYING",
        "https://gitlab.freedesktop.org/xorg/app/xkbcomp/-/blob/2c7789785981ad1fce3858c726615b49293f7de0/COPYING"),
    x11Notice("xkeyboard-config 2.42", "Individual keyboard data licenses", "xserver/xkeyboard-config-COPYRIGHT",
        "https://gitlab.freedesktop.org/xkeyboard-config/xkeyboard-config/-/blob/xkeyboard-config-2.42/COPYING"),
    x11Notice("pixman", "MIT", "xserver/pixman-COPYING",
        "https://gitlab.freedesktop.org/pixman/pixman/-/blob/9cc163c9da0fb4da430641715313d95a6ec466d9/COPYING"),
    x11Notice("libtirpc", "BSD-style upstream terms", "xserver/libtirpc-COPYING",
        "https://github.com/alisw/libtirpc/blob/5ca4ca92f629d9d83e83544b9239abaaacf0a527/COPYING"),
    x11Notice("bzip2", "bzip2 license", "xserver/bzip2-LICENSE",
        "https://gitlab.com/bzip2/bzip2/-/blob/6a8690fc8d26c815e798c588f796eabe9d684cf0/LICENSE"),
    LicenseNotice("libepoxy", "MIT", R.string.license_x11_dependency_note,
        "https://github.com/anholt/libepoxy/blob/1.5.10/COPYING",
        assetLicenses("$X11_ASSET_LICENSES/libepoxy.txt", "$X11_ASSET_LICENSES/xserver/libepoxy-COPYING")),
    x11Notice("virglrenderer 1.3.0", "MIT and upstream notices", "virglrenderer.txt",
        "https://gitlab.freedesktop.org/virgl/virglrenderer/-/blob/ca50e008863837e094747a69974dde3ae148aeaa/COPYING"),
    x11Notice("Termux package patches", "Apache-2.0 / per-package licenses", "termux-packages.txt",
        "https://github.com/termux/termux-packages/blob/master/LICENSE.md"),
    x11Notice("ANGLE", "BSD 3-Clause and third-party notices", "angle.txt",
        "https://chromium.googlesource.com/angle/angle/+/f09a19cebdaf04bddcd3101e9783207cb5cf3e13/LICENSE"),
    x11Notice("Vulkan-Headers", "Apache-2.0 OR MIT", "vulkan-headers.txt",
        "https://github.com/KhronosGroup/Vulkan-Headers/blob/main/LICENSE.md"),
    x11Notice("gl4es", "MIT", "gl4es.txt", "https://github.com/ptitSeb/gl4es/blob/master/LICENSE"),
    x11Notice("DXT1/DXT3/DXT5 texture decompression", "MIT", "dxt-decompress.txt",
        "https://github.com/Anteru/dxt-decompress/blob/master/decompress.c"),
    LicenseNotice("TigerVNC / Xvnc", "GPL-2.0 and upstream notices", R.string.license_guest_packages_note,
        "https://github.com/TigerVNC/tigervnc/blob/master/LICENCE.TXT", assetLicenses(GPL_V2_ASSET)),
    LicenseNotice("Mesa / Zink / Venus / Turnip", "MIT and upstream notices", R.string.license_guest_packages_note,
        "https://docs.mesa3d.org/license.html", assetLicenses("licenses/mesa-license.rst", MESA_LICENSES)),
    LicenseNotice("PulseAudio", "LGPL-2.1-or-later and upstream notices", R.string.license_guest_packages_note,
        "https://gitlab.freedesktop.org/pulseaudio/pulseaudio/-/blob/master/LICENSE",
        assetLicenses("licenses/pulseaudio-LICENSE.txt", "licenses/pulseaudio-LGPL.txt", GPL_V2_ASSET)),
    LicenseNotice("PipeWire", "MIT and upstream notices", R.string.license_guest_packages_note,
        "https://gitlab.freedesktop.org/pipewire/pipewire/-/blob/master/COPYING",
        assetLicenses("licenses/pipewire-COPYING.txt", "licenses/pipewire-LICENSE.txt")),
    LicenseNotice("Mesa for Android containers / Turnip packages", "Individual package licenses",
        R.string.license_guest_packages_note, "https://github.com/lfdevs/mesa-for-android-container/releases",
        assetLicenses(MESA_LICENSES)),
)

@Composable
internal fun OpenSourceLicensesScreen() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var selectedNotice by remember { mutableStateOf<LicenseNotice?>(null) }

    selectedNotice?.let { notice ->
        val licenseText = remember(context, notice) { notice.readText?.invoke(context) }
        Dialog(
            onDismissRequest = { selectedNotice = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize().navigationBarsPadding()
                    .padding(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 34.dp),
                shape = RoundedCornerShape(28.dp),
                tonalElevation = 6.dp,
            ) {
                Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(notice.name, style = MaterialTheme.typography.titleMedium)
                            Text(notice.license, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        }
                        IconButton(onClick = { selectedNotice = null }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close))
                        }
                    }
                    KimiDivider()
                    SelectionContainer {
                        Text(
                            licenseText ?: stringResource(R.string.license_offline_unavailable),
                            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }

    val groups = listOf(
        R.string.license_group_application to applicationNotices,
        R.string.license_group_editor to editorNotices,
        R.string.license_group_brands to brandNotices,
        R.string.license_group_x11 to x11Notices,
    )
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        groups.forEach { (heading, notices) ->
            item { KimiSectionLabel(stringResource(heading)) }
            items(notices, key = { it.name }) { notice ->
                val readOffline = notice.readText != null
                val bodyAction = if (readOffline) {
                    Modifier.clickable(onClickLabel = stringResource(R.string.license_view_offline), role = Role.Button) {
                        selectedNotice = notice
                    }
                } else {
                    Modifier
                }
                KimiCardBox {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.weight(1f).then(bodyAction),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(Icons.Default.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(notice.name, style = MaterialTheme.typography.titleSmall)
                                Text(notice.license, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Text(stringResource(notice.note), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
                            }
                            if (readOffline) {
                                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = KimiMuted)
                            }
                        }
                        notice.url?.let { url ->
                            FilledTonalIconButton(onClick = { runCatching { uriHandler.openUri(url) } }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = stringResource(R.string.license_open_externally, notice.name),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
