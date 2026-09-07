package com.yukisoffd.lyracode.interaction.pet

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** ZIP extraction stays inside a fresh staging directory; callers publish only validated packages. */
internal object PetArchive {
    const val MAX_ARCHIVE_BYTES = 64 * 1024 * 1024
    const val MAX_EXPANDED_BYTES = 128 * 1024 * 1024
    const val MAX_FILE_BYTES = 16 * 1024 * 1024
    const val MAX_FILES = 2048

    fun path(value: String): String {
        require(value.isNotEmpty() && value.length <= 240 && !value.startsWith('/') &&
            value.none { it == '\\' || it == ':' || it == '%' || it == '?' || it == '#' || it.code < 32 } &&
            value.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "无效的宠物包资源路径：$value" }
        return value
    }

    fun extract(input: InputStream, stage: File): File {
        require(!stage.exists())
        check(stage.mkdirs())
        try {
            var total = 0L; var count = 0
            val seen = mutableSetOf<String>()
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++count <= MAX_FILES) { "宠物包文件数量超过 $MAX_FILES" }
                    val name = path(entry.name.removeSuffix("/"))
                    require(seen.add(name.lowercase())) { "宠物包包含重复路径" }
                    val target = File(stage, name)
                    require(target.canonicalPath.startsWith(stage.canonicalPath + File.separator))
                    if (entry.isDirectory) check(target.mkdirs() || target.isDirectory) else {
                        check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
                        var size = 0L
                        target.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val n = zip.read(buffer); if (n < 0) break
                                size += n; total += n
                                require(size <= MAX_FILE_BYTES && total <= MAX_EXPANDED_BYTES) { "宠物包解压后过大" }
                                output.write(buffer, 0, n)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            val manifests = stage.walkTopDown().filter { it.isFile && it.name == "manifest.json" }.toList()
            require(manifests.size == 1) { "ZIP 必须包含且仅包含一个 manifest.json" }
            val root = manifests.single().parentFile!!
            require(stage.walkTopDown().filter { it.isFile }.all { it.canonicalPath.startsWith(root.canonicalPath + File.separator) }) {
                "所有文件必须位于 manifest.json 所在目录内"
            }
            require(!File(root, "__lyra__").exists()) { "__lyra__ 为宿主保留目录" }
            require(manifests.single().length() <= DevicePetStore.MAX_BYTES)
            val manifest = validateManifest(manifests.single().readText())
            if (manifest.getInt("apiVersion") == 2) {
                require(File(root, manifest.getString("entry")).isFile) { "找不到入口脚本" }
                manifest.optString("config").takeIf { it.isNotEmpty() }?.let {
                    val config = File(root, path(it))
                    require(config.isFile && config.length() <= 256 * 1024) { "配置文件缺失或超过 256 KiB" }
                    PetConfig.parse(config.readText())
                }
            }
            return root
        } catch (e: Exception) { stage.deleteRecursively(); throw e }
    }

    fun validateManifest(text: String): JSONObject {
        val json = JSONObject(text)
        if (json.optInt("apiVersion") == 1) return DevicePetStore.validate(text)
        require(json.getInt("apiVersion") == 2) { "不支持的桌宠 API 版本" }
        require(Regex("[a-zA-Z][a-zA-Z0-9_.-]{0,79}").matches(json.getString("id"))) { "无效的宠物包 id" }
        require(json.getString("name").length in 1..80)
        require(json.getString("version").length in 1..40)
        require(json.getString("author").length in 1..120)
        require(path(json.getString("entry")).endsWith(".js")) { "入口必须为 JavaScript 模块" }
        json.optString("config").takeIf { it.isNotEmpty() }?.let { require(path(it).endsWith(".toml")) }
        // Reuse the same control schema for legacy and packaged pets.
        DevicePetStore.validate(JSONObject(json.toString()).put("apiVersion", 1).put("html", "package").toString())
        return json
    }
}

/** Deliberately small TOML profile: scalar keys in [pet] and [controls]. Reject unsupported syntax. */
internal object PetConfig {
    fun parse(text: String): JSONObject {
        val result = JSONObject(); var section = ""
        text.lineSequence().forEachIndexed { index, raw ->
            var quoted = false; var escaped = false
            val line = buildString {
                for (ch in raw) {
                    if (ch == '#' && !quoted) break
                    append(ch)
                    if (ch == '"' && !escaped) quoted = !quoted
                    escaped = ch == '\\' && !escaped
                }
            }.trim()
            if (line.isEmpty()) return@forEachIndexed
            if (line in listOf("[pet]", "[controls]")) {
                section = line.substring(1, line.length - 1)
                require(!result.has(section)) { "配置分区重复：$section" }; result.put(section, JSONObject())
            } else {
                val match = Regex("([A-Za-z][A-Za-z0-9_]*)\\s*=\\s*(.+)").matchEntire(line)
                require(match != null && section.isNotEmpty()) { "config.toml 第 ${index + 1} 行不符合标量配置协议" }
                val (key, token) = match.destructured
                val value: Any = when {
                    token.startsWith('"') -> org.json.JSONTokener(token).let { reader ->
                        val decoded = reader.nextValue(); require(decoded is String && reader.nextClean() == '\u0000'); decoded
                    }
                    token == "true" -> true
                    token == "false" -> false
                    else -> token.toDoubleOrNull()?.takeIf { it.isFinite() } ?: error("配置值只支持字符串、数字和布尔值")
                }
                val target = result.getJSONObject(section)
                require(!target.has(key)) { "配置键重复：$key" }; target.put(key, value)
            }
        }
        return result
    }
}
