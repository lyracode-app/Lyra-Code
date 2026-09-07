package com.yukisoffd.lyracode.interaction.policy

/** Parse a small Android command grammar; no shell expansion, pipes, scripts or filesystem access. */
internal object DeviceShellPolicy {
    data class Command(val text: String, val destructive: Boolean, val coordinateAction: Boolean)
    fun parse(raw: String, width: Int, height: Int): Command? {
        val text = raw.trim()
        val words = text.split(Regex(" +"))
        fun point(x: String, y: String) = x.toIntOrNull()?.let { it in 0 until width } == true &&
            y.toIntOrNull()?.let { it in 0 until height } == true
        if (Regex("input tap [0-9]+ [0-9]+").matches(text) && point(words[2], words[3])) return Command(text, false, true)
        if (Regex("input swipe [0-9]+ [0-9]+ [0-9]+ [0-9]+ [0-9]+").matches(text) &&
            point(words[2], words[3]) && point(words[4], words[5]) && words[6].toIntOrNull() in 50..2000) return Command(text, false, true)
        if (text in setOf("input keyevent KEYCODE_BACK", "input keyevent KEYCODE_HOME", "input keyevent KEYCODE_APP_SWITCH",
                "wm size", "wm density", "pm list packages -3")) return Command(text, false, false)
        if (Regex("monkey -p [a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+ -c android.intent.category.LAUNCHER 1").matches(text)) {
            if (protectedPackage(words[2])) return null
            return Command(text, false, false)
        }
        if (Regex("pm uninstall --user 0 [a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+").matches(text)) {
            if (protectedPackage(words.last())) return null
            return Command(text, true, false)
        }
        return null
    }
    private fun protectedPackage(name: String) = name.startsWith("com.yukisoffd.lyracode") ||
        !com.yukisoffd.lyracode.interaction.session.DeviceTaskCoordinator.isAgentPackageAllowed(name) ||
        name.startsWith("com.android.") || name.startsWith("com.google.android.")
}
