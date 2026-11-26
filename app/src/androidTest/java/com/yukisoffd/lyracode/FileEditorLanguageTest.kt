package com.yukisoffd.lyracode

import android.content.Intent
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.filemanager.PrivilegedFileOperations
import com.yukisoffd.lyracode.filemanager.fileShareIntent
import com.yukisoffd.lyracode.filemanager.latexPreviewHtml
import com.yukisoffd.lyracode.system.SystemCommandExecutor
import com.yukisoffd.lyracode.widgets.WidgetWebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.filemanager.EditorLanguages
import com.yukisoffd.lyracode.filemanager.fileChecksums
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FileEditorLanguageTest {
    @Test fun bundledGrammarsCanTokenizeAndResolveEmbeddedLanguages() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(assets))
        val catalog = EditorLanguages(assets)
        fun scopes(filename: String, text: String): List<String> {
            val language = requireNotNull(catalog.forFile(File(filename)))
            catalog.load(language)
            return GrammarRegistry.getInstance().findGrammar(language.scope)!!
                .tokenizeLine(text).tokens.flatMap { it.scopes }
        }
        val html = scopes("index.HTML", "<style>a { color: red }</style><script>const x = 1</script>")
        assertTrue(html.toString(), "source.css" in html && "source.js" in html)
        val php = scopes("index.php", "<p><?php echo 42; ?></p>")
        assertTrue(php.toString(), php.any { it.endsWith(".php") && it.startsWith("constant.numeric") })
        assertTrue("entity.name.function.kotlin" in scopes("mixed.java", "fun sample() = 1"))
        assertTrue("storage.type.primitive.java" in scopes("mixed.kt", "public static int sample() { return 1; }"))
        catalog.choices.forEach { language ->
            assertTrue(language.label, scopes("test." + language.extensions.first(), "x = 1").isNotEmpty())
        }
    }

    @Test fun shareCarriesAReadableStreamAndMimeType() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("share-test", ".txt", context.cacheDir)
        try {
            file.writeText("shared bytes")
            val settings = AppSettings(context)
            val operations = PrivilegedFileOperations(context, settings, SystemCommandExecutor(context, settings))
            val intent = fileShareIntent(context, file, operations)
            assertEquals(Intent.ACTION_SEND, intent.action)
            assertEquals("text/plain", intent.type)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            val uri = intent.clipData!!.getItemAt(0).uri
            assertEquals("content", uri.scheme)
            assertEquals("shared bytes", context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
        } finally {
            file.delete()
        }
    }

    @Test fun latexDocumentRendersHeadingAndMathInPreviewWebView() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val html = latexPreviewHtml(context, """\documentclass{article}\begin{document}\section{Preview sample}Math \(x^2\).\end{document}""")
        val ready = CompletableDeferred<Unit>()
        val content = CompletableDeferred<String>()
        withContext(Dispatchers.Main) {
            val view = WidgetWebView(context, html, onReady = { ready.complete(Unit) }, onError = {
                ready.completeExceptionally(AssertionError(it))
            })
            try {
                withTimeout(15000) { ready.await() }
                view.evaluateJavascript(
                    "document.querySelector('main h2')?.textContent + ' ' + document.querySelector('math annotation')?.textContent"
                ) { content.complete(it) }
                val rendered = withTimeout(5000) { content.await() }
                assertTrue(rendered, rendered.contains("Preview sample x^2"))
            } finally {
                view.release()
            }
        }
    }

    @Test fun digestsUseTheSameCompleteByteStream() = runBlocking {
        val hashes = fileChecksums("abc".byteInputStream())
        assertEquals("900150983cd24fb0d6963f7d28e17f72", hashes["MD5"])
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hashes["SHA-256"])
    }
}
