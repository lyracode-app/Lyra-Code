package com.yukisoffd.lyracode.ai

import org.json.JSONObject

internal fun JSONObject.toolCommandArgument(): String {
    val lines = optJSONArray("command_lines")
    if (lines != null && lines.length() > 0) {
        return buildString {
            for (index in 0 until lines.length()) {
                if (index > 0) append('\n')
                append(lines.optString(index))
            }
        }
    }
    return stringFieldOrNull("command") ?: error("A command tool requires command or command_lines.")
}

