package com.yukisoffd.lyracode

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

/** One owner for system-bar space across all main-activity pages. */
@Composable
internal fun MainActivityWindow(activity: ComponentActivity, content: @Composable () -> Unit) {
    val background = MaterialTheme.colorScheme.background
    val darkBackground = background.luminance() < 0.5f
    DisposableEffect(activity, background, darkBackground) {
        activity.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ) { darkBackground },
            navigationBarStyle = SystemBarStyle.auto(background.toArgb(), background.toArgb()) { darkBackground },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.window.isStatusBarContrastEnforced = false
        }
        onDispose { }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(background)
            // Padding consumes these insets, so nested Scaffold/TopAppBar do not add them again.
            // Keep IME handling with the existing keyboard-avoidance implementation.
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
    ) {
        content()
    }
}
