package com.yukisoffd.lyracode.interaction.pet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object DevicePetStore {
    const val MAX_BYTES = 2 * 1024 * 1024
    fun packageFile(context: Context) = File(context.filesDir, "desktop-pet.json")
    private fun activeFile(context: Context) = File(context.filesDir, "desktop-pet-active.json")
    private fun packages(context: Context) = File(context.filesDir, "desktop-pet-packages").apply { mkdirs() }
    private fun root(context: Context): File? = runCatching {
        val path = JSONObject(activeFile(context).readText()).getString("root")
        File(packages(context), PetArchive.path(path)).also { require(it.isDirectory) }
    }.getOrNull()
    fun version(context: Context) = "${activeFile(context).lastModified()}:${packageFile(context).lastModified()}"
    fun defaultManifest(context: Context) = PetArchive.validateManifest(context.assets.open("desktop-pet/default/manifest.json").bufferedReader().use { it.readText() })
    fun resource(context: Context, path: String, builtin: Boolean = false): InputStream {
        PetArchive.path(path)
        val directory = if (builtin) null else root(context)
        return if (directory != null) File(directory, path).inputStream() else context.assets.open("desktop-pet/default/$path")
    }
    fun resourceSource(context: Context, builtin: Boolean = false): (String) -> InputStream {
        val directory = if (builtin) null else root(context)
        return { path ->
            PetArchive.path(path)
            if (directory != null) File(directory, path).inputStream() else context.assets.open("desktop-pet/default/$path")
        }
    }
    fun files(context: Context, builtin: Boolean = false): List<String> {
        val directory = if (builtin) null else root(context)
        if (directory != null) return directory.walkTopDown().filter { it.isFile }.map { it.relativeTo(directory).invariantSeparatorsPath }.sorted().toList()
        fun list(path: String): List<String> {
            val children = context.assets.list("desktop-pet/default/$path").orEmpty()
            return if (children.isEmpty()) listOf(path) else children.flatMap { list(if (path.isEmpty()) it else "$path/$it") }
        }
        return list("").sorted()
    }
    fun defaults(context: Context, manifest: JSONObject, builtin: Boolean = false): JSONObject = runCatching {
        manifest.optString("config").takeIf { it.isNotEmpty() }?.let { path ->
            resource(context, path, builtin).bufferedReader().use { PetConfig.parse(it.readText()) }
        } ?: JSONObject()
    }.getOrDefault(JSONObject())
    fun effectiveOptions(context: Context, manifest: JSONObject, builtin: Boolean = false): JSONObject {
        val defaults = defaults(context, manifest, builtin)
        val result = defaults.optJSONObject("pet") ?: JSONObject()
        val saved = options(context)
        saved.keys().forEach { key -> if (key != "controls") result.put(key, saved.get(key)) }
        val controls = defaults.optJSONObject("controls") ?: JSONObject()
        saved.optJSONObject("controls")?.let { values -> values.keys().forEach { controls.put(it, values.get(it)) } }
        return result.put("controls", controls)
    }
    private fun optionsFile(context: Context) = File(context.filesDir, "desktop-pet-options.json")
    fun options(context: Context): JSONObject = runCatching { JSONObject(optionsFile(context).readText()) }.getOrDefault(JSONObject())
    fun saveOptions(context: Context, options: JSONObject) {
        val file = optionsFile(context)
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(options.toString()); check(temp.renameTo(file))
    }
    fun load(context: Context): JSONObject = runCatching {
        root(context)?.let { PetArchive.validateManifest(File(it, "manifest.json").readText()) }
            ?: if (packageFile(context).exists()) validate(packageFile(context).readText()) else defaultManifest(context)
    }.getOrElse { defaultManifest(context) }
    fun validate(text: String): JSONObject {
        require(text.toByteArray().size <= MAX_BYTES) { "桌宠脚本不能超过 2 MiB" }
        val json = JSONObject(text)
        require(json.getInt("apiVersion") == 1) { "不支持的桌宠 API 版本" }
        require(json.getString("name").length in 1..80)
        require(json.getString("html").isNotBlank())
        val controls = json.optJSONArray("controls") ?: JSONArray()
        require(controls.length() <= 16)
        val ids = mutableSetOf<String>()
        for (i in 0 until controls.length()) {
            val control = controls.getJSONObject(i)
            val key = control.getString("key")
            require(Regex("[a-zA-Z][a-zA-Z0-9_]{0,39}").matches(key) && ids.add(key))
            require(control.getString("label").length in 1..80)
            require(control.getString("type") in setOf("range", "boolean", "color", "select", "text"))
            if (control.getString("type") == "range") {
                val min = control.getDouble("min"); val max = control.getDouble("max")
                require(min.isFinite() && max.isFinite() && max > min && max - min <= 10000)
                require(control.getDouble("default") in min..max)
            }
            if (control.getString("type") == "select") {
                val choices = control.getJSONArray("options")
                require(choices.length() in 1..32)
                val values = (0 until choices.length()).map { choices.getString(it).also { value -> require(value.length in 1..80) } }
                require(values.distinct().size == values.size && control.getString("default") in values)
            }
            if (control.getString("type") == "text") require(control.getString("default").length <= 200)
        }
        return json
    }
    fun install(context: Context, text: String) {
        validate(text)
        val target = packageFile(context)
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeText(text); check(temporary.renameTo(target))
        activeFile(context).delete()
        val options = options(context).put("controls", JSONObject())
        saveOptions(context, options)
    }
    fun installZip(context: Context, input: InputStream) {
        val base = packages(context)
        val id = java.util.UUID.randomUUID().toString()
        val archive = File(base, "$id.zip")
        val stage = File(base, id)
        try {
            input.use { source -> archive.outputStream().use { output ->
                val buffer = ByteArray(8192); var total = 0L
                while (true) { val n = source.read(buffer); if (n < 0) break; total += n
                    require(total <= PetArchive.MAX_ARCHIVE_BYTES) { "ZIP 超过 64 MiB" }; output.write(buffer, 0, n) }
            } }
            val directory = archive.inputStream().use { PetArchive.extract(it, stage) }
            val pointer = activeFile(context)
            val temp = File(pointer.parentFile, "${pointer.name}.tmp")
            temp.writeText(JSONObject().put("root", directory.relativeTo(base).invariantSeparatorsPath).toString())
            check(temp.renameTo(pointer))
            packageFile(context).delete()
            saveOptions(context, options(context).put("controls", JSONObject()))
            // Published directories remain immutable; keep only the active package and previous one
            // until the next import so an existing WebView can finish in-flight resource reads.
            base.listFiles()?.filter { it.isDirectory && it != stage }?.sortedByDescending { it.lastModified() }?.drop(1)?.forEach { it.deleteRecursively() }
        } catch (e: Exception) {
            if (root(context)?.canonicalPath?.startsWith(stage.canonicalPath + File.separator) != true && root(context) != stage) stage.deleteRecursively()
            throw e
        } finally { archive.delete() }
    }
    fun exportZip(context: Context, output: OutputStream) {
        ZipOutputStream(output).use { zip ->
            val manifest = load(context)
            if (manifest.optInt("apiVersion") == 1) {
                zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.toString(2).toByteArray()); zip.closeEntry()
            } else for (path in files(context)) {
                zip.putNextEntry(ZipEntry(path)); resource(context, path).use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
    }
    fun reset(context: Context) {
        activeFile(context).delete(); packageFile(context).delete()
        saveOptions(context, options(context).put("controls", JSONObject()))
    }
    fun controls(script: JSONObject, options: JSONObject): JSONObject = JSONObject().apply {
        val schema = script.optJSONArray("controls") ?: JSONArray()
        val saved = options.optJSONObject("controls") ?: JSONObject()
        for (i in 0 until schema.length()) {
            val c = schema.getJSONObject(i); val key = c.getString("key")
            put(key, when (c.getString("type")) {
                "range" -> saved.optDouble(key, c.getDouble("default")).coerceIn(c.getDouble("min"), c.getDouble("max"))
                "boolean" -> saved.optBoolean(key, c.optBoolean("default"))
                "text" -> saved.optString(key, c.getString("default")).take(200)
                "select" -> saved.optString(key, c.getString("default")).let { value ->
                    val choices = c.getJSONArray("options")
                    if ((0 until choices.length()).any { choices.getString(it) == value }) value else c.getString("default")
                }
                else -> saved.optString(key, c.optString("default", "#70A4FF")).takeIf { Regex("#[0-9a-fA-F]{6}").matches(it) } ?: "#70A4FF"
            })
        }
    }
}
