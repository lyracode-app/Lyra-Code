package com.yukisoffd.lyracode.filemanager

import android.content.res.AssetManager
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.DefaultGrammarDefinition
import org.eclipse.tm4e.core.registry.IGrammarSource
import org.json.JSONObject
import java.io.File

internal data class EditorLanguage(
    val id: String,
    val label: String?,
    val scope: String,
    val grammar: String,
    val configuration: String?,
    val extensions: List<String>,
    val dependencies: List<String>,
)

internal class EditorLanguages(private val assets: AssetManager) {
    val byId: Map<String, EditorLanguage>
    val choices: List<EditorLanguage>

    init {
        val catalog = assets.open("textmate/languages.json").bufferedReader().use { JSONObject(it.readText()) }
            .getJSONArray("languages")
        byId = (0 until catalog.length()).map { index ->
            val item = catalog.getJSONObject(index)
            EditorLanguage(
                item.getString("name"), item.optString("label").ifBlank { null },
                item.getString("scopeName"), item.getString("grammar"),
                item.optString("languageConfiguration").ifBlank { null },
                item.strings("extensions"), item.strings("dependencies"),
            )
        }.associateBy { it.id }
        choices = byId.values.filter { it.label != null }.sortedBy { it.label?.lowercase() }
    }

    fun forFile(file: File) = choices.firstOrNull { file.extension.lowercase() in it.extensions }

    fun load(language: EditorLanguage) = synchronized(registryLock) {
        val registry = GrammarRegistry.getInstance()
        val visited = mutableSetOf<String>()
        fun visit(item: EditorLanguage) {
            if (!visited.add(item.id) || item.scope in loadedScopes) return
            item.dependencies.forEach { visit(byId.getValue(it)) }
            assets.open(item.grammar).use { stream ->
                val source = IGrammarSource.fromInputStream(stream, item.grammar, Charsets.UTF_8)
                registry.loadGrammar(DefaultGrammarDefinition.withLanguageConfiguration(source, item.configuration, item.id, item.scope))
            }
            loadedScopes.add(item.scope)
        }
        visit(language)
    }

    private fun JSONObject.strings(name: String): List<String> {
        val array = optJSONArray(name) ?: return emptyList()
        return List(array.length()) { array.getString(it) }
    }

    private companion object {
        val registryLock = Any()
        val loadedScopes = mutableSetOf<String>()
    }
}
