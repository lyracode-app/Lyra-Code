package com.yukisoffd.lyracode.ai

import org.json.JSONArray
import org.json.JSONObject

/** Keep pure requests free of tool schemas and tool-choice parameters, without building schemas. */
internal fun JSONObject.addModelTools(
    pureMode: Boolean,
    automaticChoice: Boolean = false,
    definitions: () -> JSONArray,
) {
    if (pureMode) return
    put("tools", definitions())
    if (automaticChoice) put("tool_choice", "auto")
}
