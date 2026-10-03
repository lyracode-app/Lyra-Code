package com.yukisoffd.lyracode.ai

import org.json.JSONArray
import org.json.JSONObject

data class UserQuestionRequest(
    val conversationId: Long,
    val title: String,
    val question: String,
    val options: List<String>,
    val recommendedOptions: List<String> = emptyList(),
)

data class UserQuestionAnswer(
    val status: String,
    val selectedOptions: List<String> = emptyList(),
    val freeText: String = "",
) {
    fun toAgentJson(): String = JSONObject()
        .put("schema", "lyra_user_question_answer_v1")
        .put("status", status)
        .put("selected_options", JSONArray(selectedOptions))
        .put("free_text", freeText)
        .put(
            "instruction",
            when (status) {
                STATUS_ANSWERED -> "Use the user's selected options and free-text details to continue the task."
                STATUS_TIMED_OUT -> "The question was withdrawn after 10 minutes without user interaction. Continue using the available context and your best judgment."
                else -> "The question could not be shown or the task was interrupted. Continue only if it is safe to do so."
            },
        )
        .toString()

    companion object {
        const val STATUS_ANSWERED = "answered"
        const val STATUS_TIMED_OUT = "timed_out"
        const val STATUS_UNAVAILABLE = "unavailable"
        const val STATUS_INTERRUPTED = "interrupted"
    }
}

internal fun parseUserQuestionRequest(conversationId: Long, args: JSONObject): UserQuestionRequest {
    val title = args.optString("title").trim()
    require(title.isNotBlank()) { "title is required and must briefly identify what the user is being asked." }
    require(title.length <= MAX_USER_QUESTION_TITLE_CHARS) {
        "title must be concise and no longer than $MAX_USER_QUESTION_TITLE_CHARS characters."
    }
    val question = args.optString("question").trim()
    require(question.isNotBlank()) { "question is required." }
    require(question.length <= MAX_USER_QUESTION_BODY_CHARS) {
        "question must be no longer than $MAX_USER_QUESTION_BODY_CHARS characters."
    }
    val options = buildList {
        val rawOptions = args.optJSONArray("options") ?: JSONArray()
        for (index in 0 until rawOptions.length()) {
            val option = rawOptions.optString(index).trim()
            if (option.isNotBlank() && option !in this) add(option)
        }
    }
    require(options.size <= MAX_USER_QUESTION_OPTIONS) {
        "options may contain at most $MAX_USER_QUESTION_OPTIONS unique non-empty items."
    }
    require(options.all { it.length <= MAX_USER_QUESTION_OPTION_CHARS }) {
        "each option must be no longer than $MAX_USER_QUESTION_OPTION_CHARS characters."
    }
    val recommendedOptions = buildList {
        val rawRecommendations = args.optJSONArray("recommended_options") ?: JSONArray()
        for (index in 0 until rawRecommendations.length()) {
            val option = rawRecommendations.optString(index).trim()
            if (option.isNotBlank() && option !in this) add(option)
        }
    }
    require(recommendedOptions.all { it in options }) {
        "recommended_options must contain only exact option texts from options."
    }
    return UserQuestionRequest(
        conversationId = conversationId,
        title = title,
        question = question,
        options = options,
        recommendedOptions = recommendedOptions,
    )
}

private const val MAX_USER_QUESTION_TITLE_CHARS = 80
private const val MAX_USER_QUESTION_BODY_CHARS = 600
private const val MAX_USER_QUESTION_OPTIONS = 12
private const val MAX_USER_QUESTION_OPTION_CHARS = 160
