package com.yukisoffd.lyracode.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsFoldedRepliesTest {
    @Test
    fun `keeps both supported values`() {
        assertEquals(
            AppSettings.FOLDED_REPLIES_ANSWERS,
            AppSettings.normalizeFoldedReplies(AppSettings.FOLDED_REPLIES_ANSWERS),
        )
        assertEquals(
            AppSettings.FOLDED_REPLIES_ALL,
            AppSettings.normalizeFoldedReplies(AppSettings.FOLDED_REPLIES_ALL),
        )
    }

    @Test
    fun `ignores case and surrounding whitespace`() {
        assertEquals(AppSettings.FOLDED_REPLIES_ALL, AppSettings.normalizeFoldedReplies("  ALL  "))
        assertEquals(AppSettings.FOLDED_REPLIES_ANSWERS, AppSettings.normalizeFoldedReplies(" Answers "))
    }

    @Test
    fun `falls back to answers for a value it does not know`() {
        // A preference restored from a newer build, or edited by hand, must not switch the
        // transcript to showing every reply.
        for (value in listOf("", "   ", "final", "all-replies", "{}", "null")) {
            assertEquals(value, AppSettings.FOLDED_REPLIES_ANSWERS, AppSettings.normalizeFoldedReplies(value))
        }
    }
}
