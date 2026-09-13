package com.yukisoffd.lyracode.ai

import com.yukisoffd.lyracode.workspace.GlobalFileManager
import com.yukisoffd.lyracode.workspace.NativeFileManager
import com.yukisoffd.lyracode.workspace.WorkspaceFile
import kotlinx.coroutines.delay
import org.json.JSONObject

internal class AgentFileToolHandler(
    private val nativeFileManager: NativeFileManager,
    private val globalFileManager: GlobalFileManager,
    private val onFileEdit: suspend (AgentFileMutation) -> AgentFileEditResult,
    private val onFileMutation: suspend (AgentFileMutation) -> Unit,
    private val onFileActivity: suspend (AgentFileActivity?) -> Unit,
) {
    suspend fun writeFileWithDiff(path: String, content: String): ToolExecution {
        val before = nativeFileManager.readFileForEdit(path).getOrNull().orEmpty()
        return withFileActivity(path, globalStorage = false, operation = "write", content = before) {
            val editorApplied = applyFileChangeInEditor(path, before, content, globalStorage = false)
            val message = nativeFileManager.writeFile(path, content).getOrThrow()
            val after = nativeFileManager.readFileForEdit(path).getOrNull().orEmpty()
            onFileMutation(
                AgentFileMutation(path, after, globalStorage = false, beforeContent = before, editorApplied = editorApplied),
            )
            appendDiff(message, path, before, after)
        }
    }
    
    suspend fun appendFileWithDiff(path: String, content: String): ToolExecution {
        val before = nativeFileManager.readFileForEdit(path).getOrNull().orEmpty()
        return withFileActivity(path, globalStorage = false, operation = "append", content = before) {
            val expectedAfter = before + content
            val editorApplied = applyFileChangeInEditor(path, before, expectedAfter, globalStorage = false)
            val message = nativeFileManager.appendFile(path, content).getOrThrow()
            val after = nativeFileManager.readFileForEdit(path).getOrNull().orEmpty()
            onFileMutation(
                AgentFileMutation(path, after, globalStorage = false, beforeContent = before, editorApplied = editorApplied),
            )
            appendDiff(message, path, before, after)
        }
    }
    
    suspend fun globalWriteFileWithDiff(path: String, content: String): ToolExecution {
        val before = globalFileManager.readFileForEdit(path).getOrNull().orEmpty()
        return withFileActivity(path, globalStorage = true, operation = "write", content = before) {
            val editorApplied = applyFileChangeInEditor(path, before, content, globalStorage = true)
            val message = globalFileManager.writeFile(path, content).getOrThrow()
            val after = globalFileManager.readFileForEdit(path).getOrNull().orEmpty()
            onFileMutation(
                AgentFileMutation(path, after, globalStorage = true, beforeContent = before, editorApplied = editorApplied),
            )
            appendDiff(message, path, before, after)
        }
    }
    
    suspend fun globalAppendFileWithDiff(path: String, content: String): ToolExecution {
        val before = globalFileManager.readFileForEdit(path).getOrNull().orEmpty()
        return withFileActivity(path, globalStorage = true, operation = "append", content = before) {
            val expectedAfter = before + content
            val editorApplied = applyFileChangeInEditor(path, before, expectedAfter, globalStorage = true)
            val message = globalFileManager.appendFile(path, content).getOrThrow()
            val after = globalFileManager.readFileForEdit(path).getOrNull().orEmpty()
            onFileMutation(
                AgentFileMutation(path, after, globalStorage = true, beforeContent = before, editorApplied = editorApplied),
            )
            appendDiff(message, path, before, after)
        }
    }
    
    suspend fun editFileWithDiff(args: JSONObject, globalStorage: Boolean): ToolExecution {
        val path = args.getString("path")
        val before = if (globalStorage) {
            globalFileManager.readFileForEdit(path).getOrThrow()
        } else {
            nativeFileManager.readFileForEdit(path).getOrThrow()
        }
        return withFileActivity(path, globalStorage, operation = "edit", content = before) {
            val usesLineRange = args.has("start_line") || args.has("end_line")
            val usesExactMatch = args.has("old_content") || args.has("old_content_lines")
            require(usesLineRange.xor(usesExactMatch)) {
                "Choose exactly one edit mode: start_line/end_line or old_content/old_content_lines."
            }
            val newContent = args.toolTextArgument("new_content")
            val after = if (usesLineRange) {
                val startLine = args.getInt("start_line")
                applyLineRangeReplacement(
                    source = before,
                    startLine = startLine,
                    endLine = args.optInt("end_line", startLine),
                    newContent = newContent,
                )
            } else {
                applyExactTextReplacement(
                    source = before,
                    oldContent = args.toolTextArgument("old_content"),
                    newContent = newContent,
                    expectedReplacements = args.optInt("expected_replacements", 1),
                )
            }
            require(after != before) { "The edit would not change the file, so no write was performed. Re-read the target context and correct the edit." }
            val editorApplied = applyFileChangeInEditor(path, before, after, globalStorage)
            val message = if (globalStorage) {
                globalFileManager.writeFile(path, after).getOrThrow()
            } else {
                nativeFileManager.writeFile(path, after).getOrThrow()
            }
            onFileMutation(
                AgentFileMutation(path, after, globalStorage, beforeContent = before, editorApplied = editorApplied),
            )
            appendDiff(message, path, before, after)
        }
    }
    
    suspend fun applyFileChangeInEditor(
        path: String,
        before: String,
        after: String,
        globalStorage: Boolean,
    ): Boolean {
        val result = onFileEdit(
            AgentFileMutation(
                path = path,
                content = after,
                globalStorage = globalStorage,
                beforeContent = before,
            ),
        )
        if (result.handled && !result.applied) {
            error(result.message.ifBlank { "The file editor could not apply the change; the disk write was cancelled. Re-read the current file and retry with exact context." })
        }
        return result.applied
    }
    
    suspend fun readFileWithActivity(path: String, globalStorage: Boolean): ToolExecution {
        val content = if (globalStorage) {
            globalFileManager.readFile(path).getOrThrow()
        } else {
            nativeFileManager.readFile(path).getOrThrow()
        }
        return withFileActivity(path, globalStorage, operation = "read", content = content) {
            ToolExecution(content)
        }
    }
    
    suspend fun readFileLines(args: JSONObject, globalStorage: Boolean): String {
        val path = args.getString("path")
        val startLine = args.optInt("start_line", 1).coerceAtLeast(1)
        val lineCount = args.optInt("line_count", 200).coerceIn(1, 1_000)
        val content = if (globalStorage) {
            globalFileManager.readFileForEdit(path).getOrThrow()
        } else {
            nativeFileManager.readFileForEdit(path).getOrThrow()
        }
        val lines = content.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        return withFileActivity(path, globalStorage, operation = "read", content = content) {
            if (startLine > lines.size) {
                return@withFileActivity "FILE_LINES path=$path total_lines=${lines.size}\nRequested start_line $startLine is outside the file. Retry with a line number from 1 to ${lines.size}."
            }
            val endExclusive = (startLine - 1 + lineCount).coerceAtMost(lines.size)
            val body = buildString {
                for (index in startLine - 1 until endExclusive) {
                    append(index + 1).append("| ").append(lines[index]).append('\n')
                    if (length >= 240_000) {
                        append("...output reached the 240000-character limit; retry with a smaller line_count.\n")
                        break
                    }
                }
            }
            "FILE_LINES path=$path range=$startLine-$endExclusive total_lines=${lines.size}\n$body"
        }
    }
    
    suspend fun <T> withFileActivity(
        path: String,
        globalStorage: Boolean,
        operation: String,
        content: String?,
        block: suspend () -> T,
    ): T {
        onFileActivity(AgentFileActivity(path, globalStorage, operation, content))
        return try {
            delay(90L)
            block()
        } finally {
            onFileActivity(null)
        }
    }
    
    fun deleteWithDiff(path: String): ToolExecution {
        val before = nativeFileManager.readFile(path).getOrNull().orEmpty()
        val message = nativeFileManager.delete(path).getOrThrow()
        return appendDiff(message, path, before, "")
    }
    
    fun renameMoveWithDiff(from: String, to: String): ToolExecution {
        val before = nativeFileManager.readFile(from).getOrNull().orEmpty()
        val message = nativeFileManager.renameMove(from, to).getOrThrow()
        val after = nativeFileManager.readFile(to).getOrNull().orEmpty()
        return appendDiff(message, to, before, after)
    }
    
    fun appendDiff(message: String, path: String, before: String, after: String): ToolExecution {
        val diff = FileDiff.from(path, before, after)
        return ToolExecution(message, listOf(diff))
    }
    
    fun requiresCommandApproval(command: String): Boolean {
        val lowered = command.lowercase()
        val readOnlyCommands = listOf("pwd", "ls", "cat", "head", "tail", "grep", "find", "awk")
        val first = lowered.trim().split(Regex("\\s+")).firstOrNull().orEmpty().substringAfterLast("/")
        if (first !in readOnlyCommands) return true
        val mutatingFragments = listOf(
            ">", ">>", "| tee", " rm ", " mv ", " cp ", " mkdir ", " touch ", " chmod ", " sed -i",
            "pip install", "npm install", "pnpm install", "yarn add", "apt ", "pkg ", "git ",
            "python ", "python3 ", "node ",
        )
        val padded = " $lowered "
        return mutatingFragments.any { padded.contains(it) }
    }
    
    fun isFileSearchCommand(command: String): Boolean {
        val lowered = command.lowercase()
        return FILE_SEARCH_COMMAND_PATTERNS.any { it.containsMatchIn(lowered) }
    }
    
    fun globalSearchFiles(query: String): ToolExecution {
        val cleanQuery = query.trim()
        require(cleanQuery.isNotBlank()) { "Search query must not be empty." }
        val result = globalFileManager.searchFiles(cleanQuery, GLOBAL_SEARCH_RESULT_LIMIT).getOrThrow()
        return ToolExecution(
            "GLOBAL_SEARCH_FILES_RESULT\n" +
                "root=/storage/emulated/0\n" +
                "query=$cleanQuery\n" +
                "limit=$GLOBAL_SEARCH_RESULT_LIMIT\n" +
                "note=These results are outside the workspace and use absolute shared-storage paths. Read them with global_read_file/global_read_file_lines and modify them only with matching global_* tools.\n" +
                result.toAgentText(),
        )
    }
}

internal fun List<WorkspaceFile>.toAgentText(): String {
    if (isEmpty()) return "(empty)"
    return joinToString("\n") {
        val type = if (it.directory) "dir " else "file"
        "$type\t${it.size}\t${it.path}"
    }
}

internal fun List<WorkspaceFile>.toSearchAgentText(query: String, path: String, workspaceDisplayName: String): String {
    val cleanPath = path.trim().ifBlank { "." }
    if (isEmpty()) {
        return "SEARCH_EMPTY\n" +
            "query=$query\n" +
            "path=$cleanPath\n" +
            "workspace=$workspaceDisplayName\n" +
            "note=Only the authorized workspace was searched. If the target may be outside it, use global_search_files for Android shared storage."
    }
    return toAgentText()
}
private const val GLOBAL_SEARCH_RESULT_LIMIT = 120

private val FILE_SEARCH_COMMAND_PATTERNS = listOf(
    Regex("""(^|[;&|()\n]\s*)find\s+.+\s-(i)?name\s+"""),
    Regex("""(^|[;&|()\n]\s*)fd\s+"""),
    Regex("""(^|[;&|()\n]\s*)fdfind\s+"""),
    Regex("""(^|[;&|()\n]\s*)locate\s+"""),
)
