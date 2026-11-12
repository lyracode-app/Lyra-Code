package com.yukisoffd.lyracode

import com.yukisoffd.lyracode.workspace.UploadedFile
import com.yukisoffd.lyracode.workspace.WorkspaceFileReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRequestCompositionTest {
    private fun upload(
        name: String,
        content: String = "",
        size: Int = content.length,
        uri: String = "/uploads/$name",
        mimeType: String = "text/plain",
        mediaKind: String = "text",
    ) = UploadedFile(
        name = name,
        content = content,
        size = size,
        uri = uri,
        mimeType = mimeType,
        mediaKind = mediaKind,
    )

    private fun workspaceFile(name: String, relativePath: String, size: Long = 0L) = WorkspaceFileReference(
        name = name,
        relativePath = relativePath,
        uri = "content://workspace/$relativePath",
        size = size,
    )

    @Test
    fun textOnlyCompositionTrimsSurroundingWhitespace() {
        assertEquals("hello world", composeUserInput("  hello world \n", emptyList()))
    }

    @Test
    fun blankCompositionProducesEmptyInput() {
        assertEquals("", composeUserInput("   \n\t ", emptyList()))
    }

    @Test
    fun workspaceReferenceMarkerWrapsTheSelectedFiles() {
        val marker = workspaceReferenceMarker(listOf(workspaceFile("a.kt", "src/a.kt", 12L)))

        assertTrue(marker.startsWith("<lyra_workspace_refs_v1>"))
        assertTrue(marker.endsWith("</lyra_workspace_refs_v1>"))
        val payload = JSONObject(marker.removePrefix("<lyra_workspace_refs_v1>").removeSuffix("</lyra_workspace_refs_v1>"))
        assertEquals(
            "Prioritize these workspace files explicitly selected by the user. Every path is workspace-relative.",
            payload.getString("instruction"),
        )
        val files = payload.getJSONArray("files")
        assertEquals(1, files.length())
        val file = files.getJSONObject(0)
        assertEquals("a.kt", file.getString("name"))
        assertEquals("src/a.kt", file.getString("path"))
        assertEquals(12, file.getInt("size"))
    }

    @Test
    fun editorContextMarkerWrapsTheEditedPath() {
        val marker = editorContextMarker("/sdcard/notes.md")

        assertTrue(marker.startsWith("<lyra_editor_context_v1>"))
        assertTrue(marker.endsWith("</lyra_editor_context_v1>"))
        val payload = JSONObject(marker.removePrefix("<lyra_editor_context_v1>").removeSuffix("</lyra_editor_context_v1>"))
        assertEquals(2, payload.length())
        assertEquals("/sdcard/notes.md", payload.getString("path"))
        assertEquals(
            "This is the Android shared-storage file currently open in the user's editor. Read it with global_read_file/global_read_file_lines as needed; prefer global_edit_file for precise changes and preserve the user-approval flow. Workspace tools may be used for related files when a workspace is selected.",
            payload.getString("instruction"),
        )
    }

    @Test
    fun uploadedAttachmentMarkerIncludesTextOnlyForTextKind() {
        val textMarker = uploadedAttachmentMarker(upload("notes.txt", content = "hello"))
        assertTrue(textMarker.startsWith("<lyra_attachment_v1>"))
        assertTrue(textMarker.endsWith("</lyra_attachment_v1>"))
        val textPayload = JSONObject(textMarker.removePrefix("<lyra_attachment_v1>").removeSuffix("</lyra_attachment_v1>"))
        assertEquals("notes.txt", textPayload.getString("name"))
        assertEquals("text", textPayload.getString("kind"))
        assertEquals("text/plain", textPayload.getString("mime_type"))
        assertEquals(5, textPayload.getInt("size"))
        assertEquals("/uploads/notes.txt", textPayload.getString("uri"))
        assertEquals("hello", textPayload.getString("text"))

        val imageMarker = uploadedAttachmentMarker(
            upload("photo.png", size = 64, mimeType = "image/png", mediaKind = "image"),
        )
        val imagePayload = JSONObject(imageMarker.removePrefix("<lyra_attachment_v1>").removeSuffix("</lyra_attachment_v1>"))
        assertEquals("image", imagePayload.getString("kind"))
        assertEquals(64, imagePayload.getInt("size"))
        assertFalse(imagePayload.has("text"))
    }

    @Test
    fun compositionOrdersTextThenWorkspaceThenEditorThenUploads() {
        val workspace = listOf(workspaceFile("a.kt", "src/a.kt", 12L))
        val uploads = listOf(upload("notes.txt", content = "hello"))

        val composed = composeUserInput(
            text = "  fix this  ",
            uploads = uploads,
            workspaceFiles = workspace,
            editorContextPath = "/sdcard/notes.md",
        )

        assertEquals(
            listOf(
                "fix this",
                workspaceReferenceMarker(workspace),
                editorContextMarker("/sdcard/notes.md"),
                uploadedAttachmentMarker(uploads.single()),
            ).joinToString("\n\n"),
            composed,
        )
    }

    @Test
    fun compositionSkipsLeadingSeparatorWhenTextIsBlank() {
        val workspace = listOf(workspaceFile("a.kt", "src/a.kt", 12L))

        assertEquals(
            workspaceReferenceMarker(workspace),
            composeUserInput("   ", emptyList(), workspace, editorContextPath = ""),
        )
    }

    @Test
    fun workspaceReferencesAreDeduplicatedByPathKeepingTheFirstReference() {
        val files = listOf(
            workspaceFile("first.kt", "src/shared.kt"),
            workspaceFile("other.kt", "src/other.kt"),
            workspaceFile("second.kt", "src/shared.kt"),
        )

        val parsed = workspaceFilesFrom(composeUserInput("", emptyList(), files, editorContextPath = ""))

        assertEquals(listOf("first.kt", "other.kt"), (0 until parsed.length()).map { parsed.getJSONObject(it).getString("name") })
    }

    @Test
    fun workspaceReferencesAreCappedAtTwentyFour() {
        val files = (1..25).map { workspaceFile("file$it.kt", "src/file$it.kt") }

        val parsed = workspaceFilesFrom(composeUserInput("", emptyList(), files, editorContextPath = ""))

        assertEquals(24, parsed.length())
        assertEquals("file24.kt", parsed.getJSONObject(23).getString("name"))
    }

    @Test
    fun captureChatRequestSnapshotsComposedInputAndAttachmentMetadata() {
        val uploads = listOf(upload("notes.txt", content = "hello"), upload("photo.png", size = 64, mimeType = "image/png", mediaKind = "image"))
        val workspace = listOf(workspaceFile("a.kt", "src/a.kt", 12L))

        val request = captureChatRequest(
            rawText = " fix this ",
            uploads = uploads,
            workspaceFiles = workspace,
            forcedSkillIds = listOf("skill-a", "skill-b"),
            editorContextPath = "/sdcard/notes.md",
        )

        assertEquals(" fix this ", request.rawText)
        assertEquals(
            composeUserInput(" fix this ", uploads, workspace, "/sdcard/notes.md"),
            request.composedInput,
        )
        assertEquals(listOf("skill-a", "skill-b"), request.forcedSkillIds)
        assertEquals(
            listOf(
                CapturedChatAttachment("notes.txt", 5, "text/plain", "text", "/uploads/notes.txt"),
                CapturedChatAttachment("photo.png", 64, "image/png", "image", "/uploads/photo.png"),
            ),
            request.attachments,
        )
    }

    @Test
    fun captureChatRequestCopiesForcedSkillIdsSnapshot() {
        val forcedSkillIds = mutableListOf("skill-a")

        val request = captureChatRequest("hi", emptyList(), emptyList(), forcedSkillIds, "")
        forcedSkillIds.clear()

        assertEquals(listOf("skill-a"), request.forcedSkillIds)
    }

    @Test
    fun fallbackTitleUsesFirstLineOfComposedTextWithoutMarkers() {
        val composed = "  first line \nsecond line\n" + uploadedAttachmentMarker(upload("notes.txt", content = "hello"))

        assertEquals("first line", fallbackConversationTitle(composed, "New chat"))
    }

    @Test
    fun fallbackTitleCollapsesWhitespaceAndTruncatesToThirtySixCharacters() {
        assertEquals("a".repeat(36), fallbackConversationTitle("a".repeat(40), "New chat"))
        assertEquals("hello", fallbackConversationTitle("hello \n\t world", "New chat"))
    }

    @Test
    fun fallbackTitleUsesFirstWorkspaceFileNameWhenTextIsEmpty() {
        val composed = workspaceReferenceMarker(listOf(workspaceFile("a.kt", "src/a.kt"), workspaceFile("b.kt", "src/b.kt")))

        assertEquals("@a.kt", fallbackConversationTitle(composed, "New chat"))
    }

    @Test
    fun fallbackTitleUsesAttachmentNameWhenThereIsNoWorkspaceReference() {
        val composed = uploadedAttachmentMarker(upload("photo.png", size = 64, mimeType = "image/png", mediaKind = "image"))

        assertEquals("上传附件：photo.png", fallbackConversationTitle(composed, "New chat"))
    }

    @Test
    fun fallbackTitleUsesProvidedDefaultWhenNothingRemains() {
        assertEquals("New chat", fallbackConversationTitle("   ", "New chat"))
    }

    private fun workspaceFilesFrom(composedInput: String): org.json.JSONArray {
        val start = "<lyra_workspace_refs_v1>"
        val end = "</lyra_workspace_refs_v1>"
        assertTrue("missing workspace marker in: $composedInput", composedInput.startsWith(start))
        assertTrue("missing workspace marker end in: $composedInput", composedInput.endsWith(end))
        return JSONObject(composedInput.removePrefix(start).removeSuffix(end)).getJSONArray("files")
    }
}
