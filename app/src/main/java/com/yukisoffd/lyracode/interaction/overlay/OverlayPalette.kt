package com.yukisoffd.lyracode.interaction.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import com.yukisoffd.lyracode.data.AppSettings

/** The native IME-stable overlay uses the same MD3 surface, accent and shape tokens as LyraCodeTheme. */
internal class OverlayPalette(context: Context) {
    private val settings = AppSettings(context)
    private val dark = when (settings.themeMode) {
        AppSettings.THEME_DARK -> true
        AppSettings.THEME_LIGHT -> false
        else -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }
    private val custom = if (settings.customThemeColorEnabled) runCatching { Color.parseColor(settings.customThemeColor) }.getOrNull() else null
    private val dynamic = if (settings.dynamicColorEnabled && android.os.Build.VERSION.SDK_INT >= 31) {
        if (dark) androidx.compose.material3.dynamicDarkColorScheme(context) else androidx.compose.material3.dynamicLightColorScheme(context)
    } else null
    val surface = custom?.let { ColorUtils.blendARGB(it, if (dark) Color.BLACK else Color.WHITE, if (dark) .55f else .72f) }
        ?: dynamic?.surface?.toArgb() ?: if (dark) 0xff202020.toInt() else Color.WHITE
    val container = custom?.let { ColorUtils.blendARGB(it, if (dark) Color.BLACK else Color.WHITE, if (dark) .4f else .56f) }
        ?: dynamic?.surfaceVariant?.toArgb() ?: if (dark) 0xff2a2a2a.toInt() else 0xffededeb.toInt()
    val text = dynamic?.onSurface?.toArgb() ?: if (dark) 0xfff0f0f0.toInt() else 0xff171717.toInt()
    val muted = dynamic?.onSurfaceVariant?.toArgb() ?: if (dark) 0xffc8c8c8.toInt() else 0xff4f4f4f.toInt()
    val accent = dynamic?.secondary?.toArgb() ?: if (dark) 0xff70a4ff.toInt() else 0xff4e7dff.toInt()
}
