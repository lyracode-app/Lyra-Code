package com.yukisoffd.lyracode.ai

import com.yukisoffd.lyracode.data.ChatMessage
import com.yukisoffd.lyracode.data.MediaGenerationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaGenerationRoutingTest {
    @Test
    fun `recognizes common media generation model families`() {
        assertEquals(MediaGenerationKind.IMAGE, mediaGenerationKindForModel("gpt-image-2"))
        assertEquals(MediaGenerationKind.IMAGE, mediaGenerationKindForModel("black-forest-labs/FLUX.1-schnell"))
        assertEquals(MediaGenerationKind.VIDEO, mediaGenerationKindForModel("sora-2"))
        assertEquals(MediaGenerationKind.VIDEO, mediaGenerationKindForModel("wan2.1-t2v-turbo"))
        assertEquals(MediaGenerationKind.AUDIO, mediaGenerationKindForModel("gpt-4o-mini-tts"))
        assertEquals(MediaGenerationKind.AUDIO, mediaGenerationKindForModel("stable-audio-open"))
        assertEquals(MediaGenerationKind.MUSIC, mediaGenerationKindForModel("suno-v4"))
    }

    @Test
    fun `does not classify understanding or transcription models as generators`() {
        assertFalse(isMediaGenerationModel("gpt-4o"))
        assertFalse(isMediaGenerationModel("gemini-2.5-pro-vision"))
        assertFalse(isMediaGenerationModel("video-understanding-model"))
        assertFalse(isMediaGenerationModel("whisper-large-v3"))
        assertFalse(isMediaGenerationModel("image-embedding-v2"))
    }

    @Test
    fun `family matching uses model token boundaries`() {
        assertTrue(isMediaGenerationModel("vendor/imagen-4-ultra"))
        assertFalse(isMediaGenerationModel("vendor/imaginary-chat"))
        assertFalse(isMediaGenerationModel("audition-language-model"))
    }

    @Test
    fun `each media kind has a distinct Agent tool`() {
        assertEquals("generate_image", mediaGenerationToolName(MediaGenerationKind.IMAGE))
        assertEquals("generate_video", mediaGenerationToolName(MediaGenerationKind.VIDEO))
        assertEquals("generate_music", mediaGenerationToolName(MediaGenerationKind.MUSIC))
        assertEquals("generate_audio", mediaGenerationToolName(MediaGenerationKind.AUDIO))
        assertEquals(MediaGenerationKind.VIDEO, mediaGenerationKindForTool("generate_video"))
        assertEquals(null, mediaGenerationKindForTool("generate_media"))
    }

    @Test
    fun `media input keeps only latest real user request`() {
        val history = listOf(
            message(1L, "system", "LYRA_STATIC_AGENT_PROTOCOL_V7"),
            message(2L, "user", "生成一张旧的图片"),
            message(3L, "assistant", "old result"),
            message(4L, RUNTIME_CONTEXT_ROLE, "LYRA_RUNTIME_CONTEXT_SNAPSHOT_V1"),
            message(5L, "user", "生成一条狗在草地上奔跑的图片"),
            message(6L, RUNTIME_CONTEXT_ROLE, "new runtime context"),
            message(7L, "assistant", ""),
        )

        val selected = selectMediaGenerationInput(history, excludeMessageId = 7L)

        assertEquals(1, selected.size)
        assertEquals(5L, selected.single().id)
        assertEquals("生成一条狗在草地上奔跑的图片", selected.single().content)
    }

    private fun message(id: Long, role: String, content: String): ChatMessage = ChatMessage(
        id = id,
        conversationId = 1L,
        role = role,
        content = content,
        thinking = "",
        profileId = "profile",
        model = "gpt-image-2",
        toolCallId = null,
        rawJson = null,
        createdAt = id,
    )
}
