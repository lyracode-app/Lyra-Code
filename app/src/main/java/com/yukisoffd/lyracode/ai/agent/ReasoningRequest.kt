package com.yukisoffd.lyracode.ai

import com.yukisoffd.lyracode.data.ApiProfile
import com.yukisoffd.lyracode.data.AppSettings
import org.json.JSONObject

/** An explicit user choice must not be silently discarded based on a model's display name.
 * This runs before per-model customization, whose explicit overrides/removals still win. */
internal fun applyReasoningDepth(request: JSONObject, profile: ApiProfile, depth: String) {
    if (depth == AppSettings.REASONING_AUTO || depth !in AppSettings.reasoningDepthValues) return
    when (profile.apiFormat) {
        ApiProfile.API_FORMAT_OPENAI -> {
            if (profile.useResponsesApi) {
                val reasoning = request.optJSONObject("reasoning") ?: JSONObject()
                reasoning.put("effort", depth)
                if (!reasoning.has("summary")) reasoning.put("summary", "auto")
                request.put("reasoning", reasoning)
            } else request.put("reasoning_effort", depth)
        }
        ApiProfile.API_FORMAT_ANTHROPIC -> {
            val config = request.optJSONObject("output_config") ?: JSONObject()
            request.put("output_config", config.put("effort", depth))
        }
    }
}
