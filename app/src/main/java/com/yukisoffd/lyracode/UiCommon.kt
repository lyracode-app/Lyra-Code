package com.yukisoffd.lyracode

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.provider.MediaStore
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.MediaController
import android.widget.VideoView
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.Switch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import com.yukisoffd.lyracode.ai.ChatRecord
import com.yukisoffd.lyracode.ai.AiResponseCache
import com.yukisoffd.lyracode.ai.OpenAiAgent
import com.yukisoffd.lyracode.ai.TodoItem
import com.yukisoffd.lyracode.ai.WebViewWebAgent
import com.yukisoffd.lyracode.data.ApiProfile
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.data.AuditEntry
import com.yukisoffd.lyracode.data.AuditLogStore
import com.yukisoffd.lyracode.data.BackupManager
import com.yukisoffd.lyracode.data.BackupOptions
import com.yukisoffd.lyracode.data.Conversation
import com.yukisoffd.lyracode.data.ConversationStore
import com.yukisoffd.lyracode.data.McpServerConfig
import com.yukisoffd.lyracode.data.McpToolDefinition
import com.yukisoffd.lyracode.data.SkillPack
import com.yukisoffd.lyracode.data.SshServerConfig
import com.yukisoffd.lyracode.data.WebDavServerConfig
import com.yukisoffd.lyracode.mcp.McpClientManager
import com.yukisoffd.lyracode.ssh.SshExecutor
import com.yukisoffd.lyracode.termux.TermuxExecutor
import com.yukisoffd.lyracode.webdav.TransferProgress
import com.yukisoffd.lyracode.webdav.WebDavClient
import com.yukisoffd.lyracode.workspace.GlobalFileManager
import com.yukisoffd.lyracode.workspace.NativeFileManager
import com.yukisoffd.lyracode.workspace.UploadedFile
import com.yukisoffd.lyracode.workspace.UploadedFileManager
import com.yukisoffd.lyracode.workspace.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.max
import kotlin.math.abs
import android.graphics.Canvas as AndroidCanvas

internal val LocalCodeFontFamily = staticCompositionLocalOf<FontFamily> { FontFamily.Monospace }

@Composable
internal fun LyraCodeTheme(
    darkMode: Boolean,
    dynamicColor: Boolean,
    fontScale: Float,
    settings: AppSettings,
    settingsRevision: Int,
    dynamicColorRevision: Int,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val textFontFamily = remember(settingsRevision, settings.textFontPath) {
        settings.textFontPath?.let { path ->
            runCatching { FontFamily(Typeface.createFromFile(path)) }.getOrNull()
        } ?: FontFamily.Default
    }
    val codeFontFamily = remember(settingsRevision, settings.codeFontPath) {
        settings.codeFontPath?.let { path ->
            runCatching { FontFamily(Typeface.createFromFile(path)) }.getOrNull()
        } ?: FontFamily.Monospace
    }
    val customBackground = remember(settingsRevision, settings.customThemeColorEnabled, settings.customThemeColor) {
        if (settings.customThemeColorEnabled) {
            runCatching { Color(android.graphics.Color.parseColor(settings.customThemeColor)) }.getOrNull()
        } else null
    }
    val lightSurface = customBackground?.let { lerp(it, Color.White, 0.72f) } ?: Color(0xFFFFFFFF)
    val lightSurfaceVariant = customBackground?.let { lerp(it, Color.White, 0.56f) } ?: Color(0xFFEDEDEB)
    val darkSurface = customBackground?.let { lerp(it, Color.Black, 0.55f) } ?: KimiCard
    val darkSurfaceVariant = customBackground?.let { lerp(it, Color.Black, 0.40f) } ?: KimiCardAlt
    val light = lightColorScheme(
        primary = Color(0xFF181818),
        secondary = Color(0xFF4E7DFF),
        background = customBackground ?: Color(0xFFF6F6F4),
        surface = lightSurface,
        surfaceVariant = lightSurfaceVariant,
        surfaceContainerLowest = customBackground?.let { lerp(it, Color.White, 0.82f) } ?: Color.White,
        surfaceContainerLow = customBackground?.let { lerp(it, Color.White, 0.76f) } ?: lightSurface,
        surfaceContainer = lightSurface,
        surfaceContainerHigh = customBackground?.let { lerp(it, Color.White, 0.64f) } ?: lightSurfaceVariant,
        surfaceContainerHighest = lightSurfaceVariant,
        onBackground = Color(0xFF111111),
        onSurface = Color(0xFF171717),
        onSurfaceVariant = Color(0xFF4F4F4F),
        outline = customBackground?.let { lerp(it, Color.Black, 0.42f) } ?: Color(0xFF797979),
        outlineVariant = customBackground?.let { lerp(it, Color.White, 0.38f) } ?: Color(0xFFC8C8C8),
    )
    val dark = darkColorScheme(
        primary = Color(0xFFE8E8E8),
        secondary = Color(0xFF70A4FF),
        background = customBackground ?: KimiBg,
        surface = darkSurface,
        surfaceVariant = darkSurfaceVariant,
        surfaceContainerLowest = customBackground?.let { lerp(it, Color.Black, 0.68f) } ?: KimiBg,
        surfaceContainerLow = customBackground?.let { lerp(it, Color.Black, 0.60f) } ?: darkSurface,
        surfaceContainer = darkSurface,
        surfaceContainerHigh = customBackground?.let { lerp(it, Color.Black, 0.48f) } ?: darkSurfaceVariant,
        surfaceContainerHighest = darkSurfaceVariant,
        onBackground = Color(0xFFF2F2F2),
        onSurface = Color(0xFFF0F0F0),
        onSurfaceVariant = Color(0xFFC8C8C8),
        outline = customBackground?.let { lerp(it, Color.White, 0.34f) } ?: KimiLine,
        outlineVariant = customBackground?.let { lerp(it, Color.Black, 0.25f) } ?: KimiLine,
    )
    val baseScheme = if (darkMode) dark else light
    val colorScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val dynamicScheme = remember(dynamicColorRevision, darkMode, context) {
            if (darkMode) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        if (customBackground == null) {
            dynamicScheme
        } else baseScheme.copy(
            primary = dynamicScheme.primary,
            onPrimary = dynamicScheme.onPrimary,
            primaryContainer = dynamicScheme.primaryContainer,
            onPrimaryContainer = dynamicScheme.onPrimaryContainer,
            inversePrimary = dynamicScheme.inversePrimary,
            secondary = dynamicScheme.secondary,
            onSecondary = dynamicScheme.onSecondary,
            secondaryContainer = dynamicScheme.secondaryContainer,
            onSecondaryContainer = dynamicScheme.onSecondaryContainer,
            tertiary = dynamicScheme.tertiary,
            onTertiary = dynamicScheme.onTertiary,
            tertiaryContainer = dynamicScheme.tertiaryContainer,
            onTertiaryContainer = dynamicScheme.onTertiaryContainer,
            error = dynamicScheme.error,
            onError = dynamicScheme.onError,
            errorContainer = dynamicScheme.errorContainer,
            onErrorContainer = dynamicScheme.onErrorContainer,
            surfaceTint = dynamicScheme.primary,
        )
    } else {
        baseScheme
    }
    val defaults = Typography()
    val typography = defaults.copy(
        displayLarge = defaults.displayLarge.copy(fontFamily = textFontFamily),
        displayMedium = defaults.displayMedium.copy(fontFamily = textFontFamily),
        displaySmall = defaults.displaySmall.copy(fontFamily = textFontFamily),
        headlineLarge = defaults.headlineLarge.copy(fontFamily = textFontFamily),
        headlineMedium = defaults.headlineMedium.copy(fontFamily = textFontFamily),
        headlineSmall = defaults.headlineSmall.copy(fontFamily = textFontFamily),
        titleLarge = defaults.titleLarge.copy(fontFamily = textFontFamily),
        titleMedium = defaults.titleMedium.copy(fontFamily = textFontFamily),
        titleSmall = defaults.titleSmall.copy(fontFamily = textFontFamily),
        bodyLarge = defaults.bodyLarge.copy(fontFamily = textFontFamily),
        bodyMedium = defaults.bodyMedium.copy(fontFamily = textFontFamily),
        bodySmall = defaults.bodySmall.copy(fontFamily = textFontFamily),
        labelLarge = defaults.labelLarge.copy(fontFamily = textFontFamily),
        labelMedium = defaults.labelMedium.copy(fontFamily = textFontFamily),
        labelSmall = defaults.labelSmall.copy(fontFamily = textFontFamily),
    )
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, fontScale.coerceIn(AppSettings.MIN_FONT_SCALE, AppSettings.MAX_FONT_SCALE)),
        LocalCodeFontFamily provides codeFontFamily,
    ) {
        MaterialTheme(colorScheme = colorScheme, typography = typography, content = content)
    }
}
internal val KimiBg = Color(0xFF0B0B0B)
internal val KimiCard = Color(0xFF202020)
internal val KimiCardAlt = Color(0xFF2A2A2A)
internal val KimiLine = Color(0xFF3A3A3A)
internal val KimiMuted = Color(0xFF8E8E8E)
internal val KimiGreen = Color(0xFF62B47B)
internal val KimiRed = Color(0xFFE06464)
internal val KimiBlue = Color(0xFF70A4FF)
internal val KimiCardShape = RoundedCornerShape(26.dp)
internal val KimiPillShape = RoundedCornerShape(999.dp)

@Composable
internal fun KimiCardBox(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = KimiCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
        ),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.14f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
internal fun KimiDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 52.dp)
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
    )
}

@Composable
internal fun KimiSectionLabel(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.titleSmall,
    )
}

@Composable
private fun kimiMenuAccent(seed: String): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when ((seed.hashCode() and Int.MAX_VALUE) % 3) {
        0 -> scheme.primaryContainer.copy(alpha = 0.72f) to scheme.onPrimaryContainer
        1 -> scheme.secondaryContainer.copy(alpha = 0.72f) to scheme.onSecondaryContainer
        else -> scheme.tertiaryContainer.copy(alpha = 0.72f) to scheme.onTertiaryContainer
    }
}

@Composable
internal fun KimiMenuRow(
    icon: String,
    title: String,
    value: String = "",
    onClick: () -> Unit = {},
) {
    val (iconContainer, iconContent) = kimiMenuAccent(title)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(iconContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(icon, color = iconContent, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (value.isNotBlank()) {
                Text(
                    value,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun KimiMenuRow(
    icon: ImageVector,
    title: String,
    value: String = "",
    enabled: Boolean = true,
    onDisabledClick: (() -> Unit)? = null,
    onClick: () -> Unit = {},
) {
    val (iconContainer, iconContent) = if (enabled) kimiMenuAccent(title) else
        MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled || onDisabledClick != null) { if (enabled) onClick() else onDisabledClick?.invoke() }
            .then(if (enabled) Modifier else Modifier.graphicsLayer { alpha = 0.38f })
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(iconContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(23.dp),
                tint = iconContent,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (value.isNotBlank()) {
                Text(
                    value,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun KimiMenuRowWithLongClick(
    icon: ImageVector,
    title: String,
    value: String = "",
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val (iconContainer, iconContent) = kimiMenuAccent(title)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(iconContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(23.dp),
                tint = iconContent,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (value.isNotBlank()) {
                Text(
                    value,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun PlusBadgeIcon(
    baseIcon: @Composable () -> Unit,
) {
    Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
            baseIcon()
        }
        Text(
            "+",
            modifier = Modifier
                .align(Alignment.TopEnd)
                .background(MaterialTheme.colorScheme.background, CircleShape)
                .padding(horizontal = 1.dp),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        )
    }
}

@Composable
internal fun KimiChip(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    Text(
        text,
        modifier = modifier
            .clip(KimiPillShape)
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f))
            .padding(horizontal = 14.dp, vertical = 7.dp),
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun TransientNotice(
    message: String,
    modifier: Modifier = Modifier,
    durationMillis: Long = 2400L,
    onDismiss: () -> Unit,
) {
    var visible by remember(message) { mutableStateOf(message.isNotBlank()) }
    LaunchedEffect(message) {
        if (message.isBlank()) return@LaunchedEffect
        visible = true
        kotlinx.coroutines.delay(durationMillis)
        visible = false
        kotlinx.coroutines.delay(220)
        onDismiss()
    }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = visible && message.isNotBlank(),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Surface(
                shape = KimiPillShape,
                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.94f),
                tonalElevation = 8.dp,
                shadowElevation = 8.dp,
            ) {
                Row(
                    Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.inverseOnSurface)
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ConfirmDeleteDialog(
    title: String,
    message: String,
    targetName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var remaining by remember(title, targetName) { mutableIntStateOf(3) }
    LaunchedEffect(title, targetName) {
        remaining = 3
        repeat(3) {
            kotlinx.coroutines.delay(1000)
            remaining--
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message, color = KimiMuted, style = MaterialTheme.typography.bodyMedium)
                Text(targetName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    if (remaining > 0) uiText(R.string.ui_please_wait) + remaining + uiText(R.string.ui_seconds_before_confirming_deletion) else uiText(R.string.ui_this_cannot_be_restored_automatically_please_confirm),
                    color = if (remaining > 0) KimiMuted else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Button(
                enabled = remaining <= 0,
                onClick = {
                    onConfirm()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text(if (remaining > 0) uiText(R.string.file_action_delete) + " ($remaining)" else uiText(R.string.file_delete_title))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(uiText(R.string.action_cancel)) }
        },
    )
}

