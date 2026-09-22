package com.yukisoffd.lyracode

import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.ViewGroup
import androidx.activity.ComponentDialog
import androidx.activity.addCallback
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat

/** A non-floating window: system bars and content share the same background and bounds. */
@Composable
internal fun AuditDetailWindow(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val composition = rememberCompositionContext()
    val currentContent by rememberUpdatedState(content)
    val currentDismiss by rememberUpdatedState(onDismiss)
    val background = MaterialTheme.colorScheme.background
    val dialog = remember(context) { ComponentDialog(context, R.style.AuditLogWindowTheme) }
    DisposableEffect(dialog, composition) {
        val view = ComposeView(context).apply {
            setParentCompositionContext(composition)
            setContent { currentContent() }
        }
        dialog.setContentView(view)
        dialog.setCanceledOnTouchOutside(false)
        val back = dialog.onBackPressedDispatcher.addCallback { currentDismiss() }
        dialog.show()
        dialog.window!!.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        onDispose {
            back.remove()
            dialog.dismiss()
            view.disposeComposition()
        }
    }
    SideEffect {
        val window = dialog.window!!
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setBackgroundDrawable(ColorDrawable(background.toArgb()))
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = background.toArgb()
        if (Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = background.luminance() >= 0.5f
            isAppearanceLightNavigationBars = background.luminance() >= 0.5f
        }
    }
}
