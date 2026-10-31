package com.yukisoffd.lyracode.ssh

// Indexed colors stay symbolic in the screen buffer so changing themes also recolors scrollback.
internal const val TERMINAL_DEFAULT_FG = 256
internal const val TERMINAL_DEFAULT_BG = 257
const val DEFAULT_TERMINAL_BACKGROUND: Int = 0xff0c0c0c.toInt()
const val DEFAULT_TERMINAL_FOREGROUND: Int = 0xffcccccc.toInt()

internal enum class TerminalPalette(
    val id: String,
    val label: String,
    val foreground: Int,
    val background: Int,
    private val ansi: IntArray,
) {
    // https://github.com/microsoft/terminal/blob/main/src/tools/ColorTool/schemes/campbell.ini
    CAMPBELL("campbell", "Campbell", DEFAULT_TERMINAL_FOREGROUND, DEFAULT_TERMINAL_BACKGROUND, intArrayOf(
        0x0c0c0c, 0xc50f1f, 0x13a10e, 0xc19c00, 0x0037da, 0x881798, 0x3a96dd, 0xcccccc,
        0x767676, 0xe74856, 0x16c60c, 0xf9f1a5, 0x3b78ff, 0xb4009e, 0x61d6d6, 0xf2f2f2,
    )),
    SOLARIZED_DARK("solarized_dark", "Solarized Dark", 0xff839496.toInt(), 0xff002b36.toInt(), intArrayOf(
        0x073642, 0xdc322f, 0x859900, 0xb58900, 0x268bd2, 0xd33682, 0x2aa198, 0xeee8d5,
        0x002b36, 0xcb4b16, 0x586e75, 0x657b83, 0x839496, 0x6c71c4, 0x93a1a1, 0xfdf6e3,
    ));

    fun resolve(color: Int): Int = when (color) {
        TERMINAL_DEFAULT_FG -> foreground
        TERMINAL_DEFAULT_BG -> background
        in 0..15 -> 0xff000000.toInt() or ansi[color]
        in 16..231 -> {
            val cube = color - 16
            fun component(n: Int) = if (n == 0) 0 else 55 + n * 40
            rgb(component(cube / 36), component(cube / 6 % 6), component(cube % 6))
        }
        in 232..255 -> (8 + (color - 232) * 10).let { rgb(it, it, it) }
        else -> color // Explicit 24-bit colors are independent of the selected palette.
    }

    companion object {
        fun fromId(id: String) = entries.firstOrNull { it.id == id } ?: CAMPBELL
        fun rgb(red: Int, green: Int, blue: Int): Int = 0xff000000.toInt() or
            (red.coerceIn(0, 255) shl 16) or (green.coerceIn(0, 255) shl 8) or blue.coerceIn(0, 255)
    }
}
