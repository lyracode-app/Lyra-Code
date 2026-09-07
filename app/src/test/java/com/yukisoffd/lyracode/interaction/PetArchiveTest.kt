package com.yukisoffd.lyracode.interaction

import com.yukisoffd.lyracode.interaction.pet.PetArchive
import com.yukisoffd.lyracode.interaction.pet.PetConfig
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PetArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private val manifest = """{"apiVersion":2,"id":"test.pet","name":"Pet","version":"1.0","author":"Test","entry":"logic/main.js","config":"settings.toml","controls":[{"key":"pose","label":"动作","type":"select","options":["idle","walk"],"default":"idle"}]}"""
    private fun entries(prefix: String = "") = linkedMapOf(
        "${prefix}manifest.json" to manifest,
        "${prefix}logic/main.js" to "export default {mount(){},onEvent(){},unmount(){}}",
        "${prefix}settings.toml" to "[pet]\nsize = 90\n[controls]\npose = \"walk\"",
        "${prefix}anywhere/frame.png" to "fixture"
    )
    private fun zip(entries: Map<String, String>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> entries.forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() } }
    }.toByteArray()
    private fun extract(entries: Map<String, String>): File = PetArchive.extract(ByteArrayInputStream(zip(entries)), File(temporary.newFolder(), "stage"))

    @Test fun acceptsRootOrWrappedDirectoryAndArbitraryResourceLayout() {
        for (prefix in listOf("", "MyDeskPet/")) {
            val root = extract(entries(prefix))
            assertTrue(File(root, "logic/main.js").isFile)
            assertEquals("fixture", File(root, "anywhere/frame.png").readText())
        }
    }
    @Test fun rejectsTraversalAmbiguousRootsAndDuplicatePaths() {
        for (bad in listOf("../outside", "/outside", "C:/outside", "foo\\evil", "%2e%2e/evil", "a/../evil")) {
            assertThrows(IllegalArgumentException::class.java) { extract(entries().apply { put(bad, "bad") }) }
        }
        assertThrows(IllegalArgumentException::class.java) { extract(entries("pet/").apply { put("outside.txt", "bad") }) }
        assertThrows(IllegalArgumentException::class.java) { extract(entries().apply { put("another/manifest.json", manifest) }) }
        assertThrows(IllegalArgumentException::class.java) { extract(entries().apply { put("MANIFEST.JSON", manifest) }) }
    }
    @Test fun missingEntrypointAndInvalidConfigNeverLeavePartialStage() {
        val stage = File(temporary.newFolder(), "stage")
        assertThrows(IllegalArgumentException::class.java) {
            PetArchive.extract(ByteArrayInputStream(zip(entries().apply { remove("logic/main.js") })), stage)
        }
        assertFalse(stage.exists())
        assertThrows(IllegalArgumentException::class.java) { extract(entries().apply { put("settings.toml", "[unsupported]\nfoo = 1") }) }
    }
    @Test fun rejectsInflationBeyondPerFileLimit() {
        val stage = File(temporary.newFolder(), "stage")
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("huge.png"))
            val buffer = ByteArray(8192)
            repeat(PetArchive.MAX_FILE_BYTES / buffer.size + 1) { zip.write(buffer) }
        }
        assertThrows(IllegalArgumentException::class.java) { PetArchive.extract(ByteArrayInputStream(output.toByteArray()), stage) }
        assertFalse(stage.exists())
    }
    @Test fun scalarConfigPreservesQuotedHashesAndRejectsAmbiguousValues() {
        val config = PetConfig.parse("""[pet]
size = 96 # comment
autoDock = false
[controls]
color = "#123456"
greeting = "Hello # pet"
""")
        assertEquals(96, config.getJSONObject("pet").getInt("size"))
        assertFalse(config.getJSONObject("pet").getBoolean("autoDock"))
        assertEquals("Hello # pet", config.getJSONObject("controls").getString("greeting"))
        for (text in listOf("[pet]\nsize=1\nsize=2", "[pet]\nsize=NaN", "[pet]\nsize=[1,2]", "[pet]\nname=\"x\" trailing")) {
            assertTrue(runCatching { PetConfig.parse(text) }.isFailure)
        }
    }
}
