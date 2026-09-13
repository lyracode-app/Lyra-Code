package com.yukisoffd.lyracode

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsMenuNavigationTest {
    @Test
    fun standardMenuRowKeepsTrailingLambdaBoundToOnClick() {
        val source = File("src/main/java/com/yukisoffd/lyracode/UiCommon.kt")
            .readText()
            .replace("\r\n", "\n")
        val signatureStart = source.indexOf("internal fun KimiMenuRow(\n    icon: ImageVector")
        assertTrue("Vector KimiMenuRow signature is missing", signatureStart >= 0)
        val signatureEnd = source.indexOf("\n) {", signatureStart)
        assertTrue("Vector KimiMenuRow signature is incomplete", signatureEnd > signatureStart)
        val signature = source.substring(signatureStart, signatureEnd)

        assertTrue(signature.trimEnd().endsWith("onClick: () -> Unit = {},"))
        assertFalse(signature.contains("onLongClick"))
        assertTrue(source.contains("internal fun KimiMenuRowWithLongClick("))
    }

    @Test
    fun firstEasterEggWarningRequiresItsExplicitConfirmationButton() {
        val source = File(
            "src/main/java/com/yukisoffd/lyracode/ui/settings/about/SettingsAboutScreens.kt",
        ).readText()
        val blockStart = source.indexOf("UpdateManifestEasterEggPrompt.FIRST_WARNING -> AlertDialog(")
        val blockEnd = source.indexOf("UpdateManifestEasterEggPrompt.CHOICE -> AlertDialog(", blockStart)
        assertTrue(blockStart >= 0 && blockEnd > blockStart)
        val warningBlock = source.substring(blockStart, blockEnd)

        assertTrue(warningBlock.contains("onDismissRequest = {}"))
        assertTrue(warningBlock.contains("TextButton(onClick = ::resumeEasterEggAfterPrompt)"))
        assertTrue(warningBlock.contains("R.string.easter_egg_first_warning_confirm"))
    }
}
