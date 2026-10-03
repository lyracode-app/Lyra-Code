package com.yukisoffd.lyracode.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserQuestionTest {
    @Test
    fun parsesOpenQuestionWithoutOptions() {
        val request = parseUserQuestionRequest(
            conversationId = 42L,
            args = JSONObject()
                .put("title", "出行偏好")
                .put("question", "这次旅行最看重什么？"),
        )

        assertEquals(42L, request.conversationId)
        assertEquals("出行偏好", request.title)
        assertTrue(request.options.isEmpty())
        assertTrue(request.recommendedOptions.isEmpty())
    }

    @Test
    fun trimsAndDeduplicatesMultiSelectOptions() {
        val request = parseUserQuestionRequest(
            conversationId = 7L,
            args = JSONObject()
                .put("title", "交通方式")
                .put("question", "你更倾向哪些交通方式？")
                .put("options", JSONArray(listOf(" 飞机 ", "火车", "飞机", ""))),
        )

        assertEquals(listOf("飞机", "火车"), request.options)
        assertTrue(request.recommendedOptions.isEmpty())
    }

    @Test
    fun trimsAndDeduplicatesRecommendationsWithoutChangingOptions() {
        val request = parseUserQuestionRequest(
            conversationId = 7L,
            args = JSONObject()
                .put("title", "交通方式")
                .put("question", "你更倾向哪些交通方式？")
                .put("options", JSONArray(listOf(" 飞机 ", "火车", "公交")))
                .put("recommended_options", JSONArray(listOf(" 火车 ", "公交", "火车", ""))),
        )

        assertEquals(listOf("飞机", "火车", "公交"), request.options)
        assertEquals(listOf("火车", "公交"), request.recommendedOptions)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsRecommendationOutsideAvailableOptions() {
        parseUserQuestionRequest(
            conversationId = 1L,
            args = JSONObject()
                .put("title", "交通方式")
                .put("question", "请选择。")
                .put("options", JSONArray(listOf("火车")))
                .put("recommended_options", JSONArray(listOf("飞机"))),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsRecommendationsForAnOpenQuestion() {
        parseUserQuestionRequest(
            conversationId = 1L,
            args = JSONObject()
                .put("title", "出行偏好")
                .put("question", "这次旅行最看重什么？")
                .put("recommended_options", JSONArray(listOf("舒适"))),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingTitle() {
        parseUserQuestionRequest(
            conversationId = 1L,
            args = JSONObject().put("question", "请选择。"),
        )
    }

    @Test
    fun timedOutResultTellsAgentToContinue() {
        val result = JSONObject(
            UserQuestionAnswer(status = UserQuestionAnswer.STATUS_TIMED_OUT).toAgentJson(),
        )

        assertEquals("timed_out", result.getString("status"))
        assertTrue(result.getString("instruction").contains("best judgment"))
    }
}
