package com.yukisoffd.lyracode.filemanager

import android.content.Context
import android.graphics.Typeface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.util.regex.RegexBackrefGrammar
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import org.eclipse.tm4e.core.registry.IThemeSource
import java.io.File
import java.util.regex.Pattern
import kotlin.math.max

internal class EditorHandle {
    var editor: CodeEditor? = null

    fun text(): String = editor?.text?.toString().orEmpty()

    fun undo() = editor?.undo()

    fun redo() = editor?.redo()

    fun search(
        query: String,
        regularExpression: Boolean,
        caseInsensitive: Boolean,
        wholeWord: Boolean,
    ): Result<Unit> = runCatching {
        val value = query
        val targetEditor = editor ?: error("文件编辑器尚未就绪。")
        targetEditor.searcher.stopSearch()
        if (value.isBlank()) {
            return@runCatching
        }
        val type = when {
            regularExpression -> EditorSearcher.SearchOptions.TYPE_REGULAR_EXPRESSION
            wholeWord -> EditorSearcher.SearchOptions.TYPE_WHOLE_WORD
            else -> EditorSearcher.SearchOptions.TYPE_NORMAL
        }
        val options = if (regularExpression) {
            Pattern.compile(
                value,
                if (caseInsensitive) Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE else 0,
            )
            EditorSearcher.SearchOptions(type, caseInsensitive, RegexBackrefGrammar.DEFAULT)
        } else {
            EditorSearcher.SearchOptions(type, caseInsensitive)
        }
        targetEditor.searcher.search(value, options)
    }

    fun nextMatch() = runCatching { editor?.searcher?.gotoNext() }

    fun previousMatch() = runCatching { editor?.searcher?.gotoPrevious() }

    fun matchedPositionCount(): Int = runCatching {
        editor?.searcher?.getMatchedPositionCount() ?: 0
    }.getOrDefault(0)

    fun replaceCurrentMatch(replacement: String, moveToNext: Boolean): Result<Unit> = runCatching {
        val targetEditor = editor ?: error("文件编辑器尚未就绪。")
        check(targetEditor.isEditable) { "文件处于只读模式，无法替换。" }
        check(targetEditor.searcher.hasQuery()) { "请先输入查找内容。" }
        if (!targetEditor.searcher.isMatchedPositionSelected()) {
            check(targetEditor.searcher.gotoNext()) { "没有可替换的匹配项。" }
        }
        targetEditor.searcher.replaceCurrentMatch(replacement)
        if (moveToNext) {
            targetEditor.postDelayed({ targetEditor.searcher.gotoNext() }, 120L)
        }
    }

    fun replaceAllMatches(replacement: String, onComplete: () -> Unit): Result<Unit> = runCatching {
        val targetEditor = editor ?: error("文件编辑器尚未就绪。")
        check(targetEditor.isEditable) { "文件处于只读模式，无法替换。" }
        check(targetEditor.searcher.hasQuery()) { "请先输入查找内容。" }
        targetEditor.searcher.replaceAll(replacement, onComplete)
    }

    fun jumpToLine(line: Int) {
        val target = (line - 1).coerceAtLeast(0)
        val content = editor?.text ?: return
        if (target < content.lineCount) editor?.setSelection(target, 0)
    }

    suspend fun applyAgentTextChange(expectedText: String, targetText: String) {
        val targetEditor = awaitEditor()
            ?: error("文件编辑器尚未就绪，已取消此次 AI 写入。")
        check(!targetEditor.isReleased) { "文件编辑器已关闭，已取消此次 AI 写入。" }
        val editorContent = targetEditor.text
        check(contentEquals(editorContent, expectedText)) {
            "编辑器内容已在 AI 读取后发生变化，为避免覆盖未保存修改，已取消此次写入。"
        }
        if (expectedText == targetText) return

        var prefix = 0
        val commonLimit = minOf(expectedText.length, targetText.length)
        while (prefix < commonLimit && expectedText[prefix] == targetText[prefix]) prefix++

        var suffix = 0
        while (
            suffix < expectedText.length - prefix &&
            suffix < targetText.length - prefix &&
            expectedText[expectedText.lastIndex - suffix] == targetText[targetText.lastIndex - suffix]
        ) {
            suffix++
        }

        val oldEnd = expectedText.length - suffix
        val replacement = targetText.substring(prefix, targetText.length - suffix)
        val changedCharacters = (oldEnd - prefix) + replacement.length
        val wasEditable = targetEditor.isEditable
        targetEditor.isEditable = false
        try {
            if (
                expectedText.length > MAX_INCREMENTAL_DOCUMENT_CHARS ||
                targetText.length > MAX_INCREMENTAL_DOCUMENT_CHARS ||
                changedCharacters > MAX_INCREMENTAL_EDIT_CHARS
            ) {
                applyAtomicTextChange(targetEditor, prefix, oldEnd, replacement, targetText)
                return
            }

            val chunkSize = max(1, changedCharacters / MAX_INCREMENTAL_STEPS)
            val stepDelay = if (changedCharacters < 2_000) 16L else 8L
            var step = 0
            var deleteEnd = oldEnd
            while (deleteEnd > prefix) {
                check(!targetEditor.isReleased) { "文件编辑器已关闭，已取消此次 AI 写入。" }
                val deleteStart = max(prefix, deleteEnd - chunkSize)
                targetEditor.text.delete(deleteStart, deleteEnd)
                if (++step % CURSOR_UPDATE_INTERVAL == 0 || deleteStart == prefix) {
                    moveCursorToIndex(targetEditor, deleteStart)
                }
                deleteEnd = deleteStart
                delay(stepDelay)
            }

            var inserted = 0
            while (inserted < replacement.length) {
                check(!targetEditor.isReleased) { "文件编辑器已关闭，已取消此次 AI 写入。" }
                val end = minOf(replacement.length, inserted + chunkSize)
                val insertionIndex = prefix + inserted
                val position = targetEditor.text.indexer.getCharPosition(insertionIndex)
                targetEditor.text.insert(position.line, position.column, replacement.substring(inserted, end))
                inserted = end
                if (++step % CURSOR_UPDATE_INTERVAL == 0 || inserted == replacement.length) {
                    moveCursorToIndex(targetEditor, prefix + inserted)
                }
                delay(stepDelay)
            }
            if (!contentEquals(targetEditor.text, targetText)) {
                applyAtomicTextChange(targetEditor, 0, targetEditor.text.length, targetText, targetText)
            }
        } finally {
            if (!targetEditor.isReleased) targetEditor.isEditable = wasEditable
        }
    }

    private fun applyAtomicTextChange(
        editor: CodeEditor,
        startIndex: Int,
        endIndex: Int,
        replacement: String,
        completeText: String,
    ) {
        check(!editor.isReleased) { "文件编辑器已关闭，已取消此次 AI 写入。" }
        if (replacement.length > MAX_ATOMIC_REPLACEMENT_CHARS) {
            editor.setText(completeText)
            moveCursorToIndexDeferred(editor, startIndex.coerceAtMost(completeText.length))
            return
        }
        val content = editor.text
        val safeStart = startIndex.coerceIn(0, content.length)
        val safeEnd = endIndex.coerceIn(safeStart, content.length)
        val start = content.indexer.getCharPosition(safeStart)
        val end = content.indexer.getCharPosition(safeEnd)
        content.beginBatchEdit()
        try {
            content.replace(start.line, start.column, end.line, end.column, replacement)
        } finally {
            content.endBatchEdit()
        }
        moveCursorToIndexDeferred(editor, (safeStart + replacement.length).coerceAtMost(content.length))
    }

    private fun contentEquals(content: CharSequence, expected: String): Boolean {
        if (content.length != expected.length) return false
        for (index in expected.indices) {
            if (content[index] != expected[index]) return false
        }
        return true
    }

    private suspend fun awaitEditor(): CodeEditor? {
        repeat(120) {
            editor?.let { return it }
            delay(16L)
        }
        return editor
    }

    private fun moveCursorToIndex(editor: CodeEditor, index: Int) {
        if (editor.isReleased) return
        val safeIndex = index.coerceIn(0, editor.text.length)
        val position = editor.text.indexer.getCharPosition(safeIndex)
        editor.setSelection(position.line, position.column)
        editor.ensurePositionVisible(position.line, position.column)
    }

    private fun moveCursorToIndexDeferred(editor: CodeEditor, index: Int) {
        editor.postDelayed(
            {
                if (!editor.isReleased) {
                    runCatching { moveCursorToIndex(editor, index) }
                }
            },
            32L,
        )
    }

    private companion object {
        const val MAX_INCREMENTAL_DOCUMENT_CHARS = 512 * 1024
        const val MAX_INCREMENTAL_EDIT_CHARS = 64 * 1024
        const val MAX_ATOMIC_REPLACEMENT_CHARS = 2 * 1024 * 1024
        const val MAX_INCREMENTAL_STEPS = 48
        const val CURSOR_UPDATE_INTERVAL = 4
    }
}

@Composable
internal fun SoraCodeEditor(
    file: File,
    initialText: String,
    languages: EditorLanguages,
    language: EditorLanguage?,
    wordWrap: Boolean,
    readOnly: Boolean,
    darkTheme: Boolean,
    handle: EditorHandle,
    modifier: Modifier = Modifier,
) {
    val contextHolder = androidx.compose.ui.platform.LocalContext.current
    val codeEditor = remember(file.absolutePath) {
        CodeEditorThemeRegistry.ensure(contextHolder)
        CodeEditor(contextHolder).apply {
            typefaceText = Typeface.MONOSPACE
            setTextSize(15f)
            isLineNumberEnabled = true
            isWordwrap = wordWrap
            isEditable = !readOnly
            setText(initialText)
        }.also { handle.editor = it }
    }
    LaunchedEffect(wordWrap) { codeEditor.isWordwrap = wordWrap }
    LaunchedEffect(readOnly) { codeEditor.isEditable = !readOnly }
    LaunchedEffect(codeEditor, darkTheme) { CodeEditorThemeRegistry.applyTheme(codeEditor, darkTheme) }
    LaunchedEffect(codeEditor, language) {
        try {
            if (language != null) withContext(Dispatchers.Default) { languages.load(language) }
            codeEditor.setEditorLanguage(language?.let { TextMateLanguage.create(it.scope, true) })
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            codeEditor.setEditorLanguage(null)
            Toast.makeText(contextHolder, error.localizedMessage, Toast.LENGTH_LONG).show()
        }
    }
    AndroidView(
        factory = { codeEditor },
        modifier = modifier.fillMaxSize(),
        onRelease = {
            handle.editor = null
            it.release()
        },
    )
}

private object CodeEditorThemeRegistry {
    @Volatile private var initialized = false

    @Synchronized
    fun ensure(context: Context) {
        if (initialized) return
        FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(context.applicationContext.assets))
        val registry = ThemeRegistry.getInstance()
        listOf("darcula" to true, "quietlight" to false).forEach { (name, dark) ->
            val path = "textmate/$name.json"
            registry.loadTheme(
                ThemeModel(
                    IThemeSource.fromInputStream(
                        FileProviderRegistry.getInstance().tryGetInputStream(path),
                        path,
                        null,
                    ),
                    name,
                ).apply { isDark = dark },
            )
        }
        GrammarRegistry.getInstance()
        initialized = true
    }

    fun applyTheme(editor: CodeEditor, dark: Boolean) {
        if (!initialized) return
        ThemeRegistry.getInstance().setTheme(if (dark) "darcula" else "quietlight")
        editor.colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
    }
}
