package com.yukisoffd.lyracode

import com.yukisoffd.lyracode.workspace.UploadedFile
import com.yukisoffd.lyracode.workspace.WorkspaceFileReference
import org.json.JSONArray
import org.json.JSONObject

internal data class CapturedChatAttachment(
    val name: String,
    val size: Int,
    val mimeType: String,
    val mediaKind: String,
    val uri: String,
)

internal data class CapturedChatRequest(
    val rawText: String,
    val composedInput: String,
    val forcedSkillIds: List<String>,
    val attachments: List<CapturedChatAttachment>,
)

internal fun captureChatRequest(
    rawText: String,
    uploads: List<UploadedFile>,
    workspaceFiles: List<WorkspaceFileReference>,
    forcedSkillIds: List<String>,
    editorContextPath: String,
): CapturedChatRequest = CapturedChatRequest(
    rawText = rawText,
    composedInput = composeUserInput(rawText, uploads, workspaceFiles, editorContextPath),
    forcedSkillIds = forcedSkillIds.toList(),
    attachments = uploads.map { file ->
        CapturedChatAttachment(
            name = file.name,
            size = file.size,
            mimeType = file.mimeType,
            mediaKind = file.mediaKind,
            uri = file.uri,
        )
    },
)

internal fun composeUserInput(
    text: String,
    uploads: List<UploadedFile>,
    workspaceFiles: List<WorkspaceFileReference> = emptyList(),
    editorContextPath: String = "",
): String {
    return buildString {
        val cleanText = text.trim()
        if (cleanText.isNotBlank()) append(cleanText)
        workspaceFiles.distinctBy { it.relativePath }.take(24).takeIf { it.isNotEmpty() }?.let { files ->
            if (isNotBlank()) append("\n\n")
            append(workspaceReferenceMarker(files))
        }
        editorContextPath.takeIf { it.isNotBlank() }?.let { path ->
            if (isNotBlank()) append("\n\n")
            append(editorContextMarker(path))
        }
        uploads.forEach { file ->
            if (isNotBlank()) append("\n\n")
            append(uploadedAttachmentMarker(file))
        }
    }
}

internal fun workspaceReferenceMarker(files: List<WorkspaceFileReference>): String {
    val payload = JSONObject()
        .put("instruction", "Prioritize these workspace files explicitly selected by the user. Every path is workspace-relative.")
        .put("files", JSONArray().also { array ->
            files.forEach { file ->
                array.put(JSONObject().put("name", file.name).put("path", file.relativePath).put("size", file.size))
            }
        })
    return "$WORKSPACE_REFERENCE_MARKER_START$payload$WORKSPACE_REFERENCE_MARKER_END"
}

internal fun editorContextMarker(path: String): String {
    val payload = JSONObject()
        .put("path", path)
        .put(
            "instruction",
            "This is the Android shared-storage file currently open in the user's editor. Read it with global_read_file/global_read_file_lines as needed; prefer global_edit_file for precise changes and preserve the user-approval flow. Workspace tools may be used for related files when a workspace is selected.",
        )
    return "$EDITOR_CONTEXT_MARKER_START$payload$EDITOR_CONTEXT_MARKER_END"
}

internal fun uploadedAttachmentMarker(file: UploadedFile): String {
    val payload = JSONObject()
        .put("name", file.name)
        .put("kind", file.mediaKind)
        .put("mime_type", file.mimeType)
        .put("size", file.size)
        .put("uri", file.uri)
    if (file.mediaKind == "text") {
        payload.put("text", file.content)
    }
    return "$ATTACHMENT_MARKER_START$payload$ATTACHMENT_MARKER_END"
}

internal fun fallbackConversationTitle(composedInput: String, defaultTitle: String): String {
    val markerRegex = Regex("<lyra_attachment_v1>([\\s\\S]*?)</lyra_attachment_v1>")
    val workspaceRegex = Regex("<lyra_workspace_refs_v1>([\\s\\S]*?)</lyra_workspace_refs_v1>")
    val editorRegex = Regex("<lyra_editor_context_v1>([\\s\\S]*?)</lyra_editor_context_v1>")
    val workspaceTitle = workspaceRegex.find(composedInput)?.let { match ->
        runCatching { JSONObject(match.groupValues[1]).optJSONArray("files")?.optJSONObject(0)?.optString("name") }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { "@$it" }
    }
    val attachmentTitle = markerRegex.find(composedInput)?.let { match ->
        runCatching { JSONObject(match.groupValues[1]).optString("name") }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { "上传附件：$it" }
    }
    return editorRegex.replace(workspaceRegex.replace(markerRegex.replace(composedInput, ""), ""), "")
        .lineSequence()
        .firstOrNull()
        .orEmpty()
        .replace(Regex("""\s+"""), " ")
        .trim()
        .take(36)
        .ifBlank { workspaceTitle ?: attachmentTitle ?: defaultTitle }
}

private const val ATTACHMENT_MARKER_START = "<lyra_attachment_v1>"
private const val ATTACHMENT_MARKER_END = "</lyra_attachment_v1>"
private const val WORKSPACE_REFERENCE_MARKER_START = "<lyra_workspace_refs_v1>"
private const val WORKSPACE_REFERENCE_MARKER_END = "</lyra_workspace_refs_v1>"
private const val EDITOR_CONTEXT_MARKER_START = "<lyra_editor_context_v1>"
private const val EDITOR_CONTEXT_MARKER_END = "</lyra_editor_context_v1>"
