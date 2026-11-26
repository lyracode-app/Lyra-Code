package com.yukisoffd.lyracode.filemanager

import android.content.Context
import android.util.Base64
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yukisoffd.lyracode.AuditDetailWindow
import com.yukisoffd.lyracode.RichMarkdownContent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import com.yukisoffd.lyracode.widgets.rememberHtmlOpener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal fun previewLanguageFor(file: File): String = when (file.extension.lowercase()) {
    "html", "htm" -> "html"
    "md", "markdown", "mdown" -> "markdown"
    "svg" -> "svg"
    "tex", "latex", "sty", "cls" -> "latex"
    else -> ""
}

@Composable
internal fun rememberFilePreviewOpener(): (String, suspend () -> String) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val openHtml = rememberHtmlOpener()
    var markdown by remember { mutableStateOf<String?>(null) }
    markdown?.let { MarkdownFilePreview(it) { markdown = null } }
    return { language, load ->
        when (language) {
            "markdown" -> scope.launch {
                try {
                    markdown = withContext(Dispatchers.IO) { load() }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    Toast.makeText(context, error.localizedMessage, Toast.LENGTH_LONG).show()
                }
            }
            "latex" -> openHtml { latexPreviewHtml(context, load()) }
            "svg" -> openHtml { svgPreviewHtml(load()) }
            "html" -> openHtml(load)
        }
    }
}

@Composable
internal fun MarkdownFilePreview(source: String, onDismiss: () -> Unit) {
    AuditDetailWindow(onDismiss = onDismiss) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding()) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.ArrowBack, stringResource(android.R.string.cancel))
                }
                RichMarkdownContent(source, Modifier.verticalScroll(rememberScrollState()).padding(16.dp))
            }
        }
    }
}

internal fun svgPreviewHtml(source: String): String {
    val encoded = Base64.encodeToString(source.toByteArray(), Base64.NO_WRAP)
    return """<!doctype html><meta name="viewport" content="width=device-width">
        <style>body{margin:0;display:grid;min-height:100vh;place-items:center}img{max-width:100%;max-height:100vh}</style>
        <img alt="SVG" src="data:image/svg+xml;base64,$encoded">"""
}

internal fun latexPreviewHtml(context: Context, source: String): String {
    val style = context.assets.open("latex-preview/document.css").bufferedReader().use { it.readText() }
    val latex = context.assets.open("latex-preview/latex.min.js").bufferedReader().use { it.readText() }
    val encoded = Base64.encodeToString(source.toByteArray(), Base64.NO_WRAP)
    return """<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width">
        <style>$style
        body{max-width:48em;margin:2em auto;padding:0 1em;overflow-wrap:anywhere}
        .hbox{display:inline-block}.vbox{display:inline-flex;flex-direction:column}pre{white-space:pre-wrap}</style>
        <script>$latex
        </script><main></main><script>
        const source = new TextDecoder().decode(Uint8Array.from(atob('$encoded'), c => c.charCodeAt(0)));
        const output = document.querySelector('main');
        try {
          const result = latexjs.parse(source, {generator: new latexjs.HtmlGenerator({hyphenate:false})});
          output.append(result.domFragment());
        } catch (error) {
          const message = document.createElement('pre');
          message.textContent = error.message;
          output.append(message);
        }
        </script>"""
}
