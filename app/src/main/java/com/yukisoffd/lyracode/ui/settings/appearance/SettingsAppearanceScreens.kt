package com.yukisoffd.lyracode

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.data.FontLibraryItem
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt



@Composable
internal fun ThemeSettings(
    settings: AppSettings,
    themeMode: String,
    dynamicColorEnabled: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
    predictiveBackEnabled: Boolean,
    onPredictiveBackChange: (Boolean) -> Unit,
    refreshRateMode: String,
    onRefreshRateModeChange: (String) -> Unit,
    fontScaleMode: String,
    customFontScale: Float,
    onOpenThemeModeSettings: () -> Unit,
    onOpenFontSettings: () -> Unit,
    onOpenRefreshRateSettings: () -> Unit,
    onOpenChatBackgroundSettings: () -> Unit,
    onOpenStreamingOutputSettings: () -> Unit,
    onOpenAppIconSettings: () -> Unit,
) {
    val hasBackground = !settings.chatBackgroundPath.isNullOrBlank()
    KimiCardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.width(36.dp).size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(uiText(R.string.label_dynamic_color), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(checked = dynamicColorEnabled, onCheckedChange = onDynamicColorChange)
        }
        KimiDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Gesture,
                contentDescription = null,
                modifier = Modifier.width(36.dp).size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(R.string.predictive_back_title),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            Switch(checked = predictiveBackEnabled, onCheckedChange = onPredictiveBackChange)
        }
        KimiDivider()
        KimiMenuRow(Icons.Default.Palette, uiText(R.string.title_theme_mode), if (settings.customThemeColorEnabled) uiText(R.string.custom_theme_color_value, settings.customThemeColor) else themeName(themeMode), onClick = onOpenThemeModeSettings)
        KimiDivider()
        KimiMenuRow(Icons.Default.Apps, stringResource(R.string.app_icon_title), stringResource(AppIcon.fromId(settings.appIconId).title), onClick = onOpenAppIconSettings)
        KimiDivider()
        KimiMenuRow(Icons.Default.FormatSize, uiText(R.string.ui_fonts_and_size), fontScaleName(fontScaleMode, customFontScale), onClick = onOpenFontSettings)
        KimiDivider()
        KimiMenuRow(Icons.Default.Speed, uiText(R.string.title_refresh_rate), refreshRateName(refreshRateMode), onClick = onOpenRefreshRateSettings)
        KimiDivider()
        KimiMenuRow(
            Icons.Default.Animation,
            stringResource(R.string.streaming_output_title),
            streamingAnimationModeName(settings.streamingAnimationMode),
            onClick = onOpenStreamingOutputSettings,
        )
        KimiDivider()
        KimiMenuRow(
            Icons.Default.Image,
            uiText(R.string.menu_chat_background),
            if (hasBackground) uiText(R.string.background_set) else uiText(R.string.background_solid),
            onClick = onOpenChatBackgroundSettings,
        )
    }
}

internal fun streamingAnimationModeName(mode: String): String = when (AppSettings.normalizeStreamingAnimationMode(mode)) {
    AppSettings.STREAMING_ANIMATION_FADE -> uiText(R.string.streaming_fade_title)
    else -> uiText(R.string.streaming_typewriter_title)
}

@Composable
internal fun StreamingOutputSettings(settings: AppSettings, controller: ChatController) {
    var selected by remember { mutableStateOf(settings.streamingAnimationMode) }
    KimiCardBox {
        StreamingAnimationOptionRow(
            icon = Icons.Default.Keyboard,
            title = stringResource(R.string.streaming_typewriter_title),
            subtitle = stringResource(R.string.streaming_typewriter_desc),
            value = AppSettings.STREAMING_ANIMATION_TYPEWRITER,
            selected = selected,
        ) { value ->
            selected = value
            settings.streamingAnimationMode = value
            controller.settingsRevision.intValue++
        }
        KimiDivider()
        StreamingAnimationOptionRow(
            icon = Icons.Default.Gradient,
            title = stringResource(R.string.streaming_fade_title),
            subtitle = stringResource(R.string.streaming_fade_desc),
            value = AppSettings.STREAMING_ANIMATION_FADE,
            selected = selected,
        ) { value ->
            selected = value
            settings.streamingAnimationMode = value
            controller.settingsRevision.intValue++
        }
    }
}

@Composable
private fun StreamingAnimationOptionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    value: String,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onSelect(value) }.padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.width(36.dp).size(24.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = KimiMuted, style = MaterialTheme.typography.bodySmall)
        }
        if (value == selected) Icon(Icons.Default.Check, contentDescription = stringResource(R.string.streaming_selected), tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
internal fun ChatBackgroundSettings(settings: AppSettings) {
    val context = LocalContext.current
    var backgroundRevision by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf("") }
    var cropBackgroundUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val screenAspect = remember {
        val metrics = context.resources.displayMetrics
        metrics.widthPixels.toFloat() / metrics.heightPixels.toFloat().coerceAtLeast(1f)
    }
    val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) cropBackgroundUri = uri
    }
    cropBackgroundUri?.let { uri ->
        ImageCropUploadDialog(
            uri = uri,
            fixedCropAspectRatio = screenAspect,
            onDismiss = { cropBackgroundUri = null },
            onUseOriginal = {
                settings.saveChatBackground(uri).fold(
                    onSuccess = {
                        notice = uiText(R.string.notice_background_saved)
                        backgroundRevision++
                    },
                    onFailure = { notice = it.message.orEmpty().ifBlank { uiText(R.string.notice_save_failed) } },
                )
                cropBackgroundUri = null
            },
            onCropped = { cropped ->
                settings.saveChatBackground(cropped).fold(
                    onSuccess = {
                        notice = uiText(R.string.notice_background_saved)
                        backgroundRevision++
                    },
                    onFailure = { notice = it.message.orEmpty().ifBlank { uiText(R.string.notice_save_failed) } },
                )
                cropBackgroundUri = null
            },
        )
    }
    val backgroundPath = remember(backgroundRevision) { settings.chatBackgroundPath }
    val hasBackground = !backgroundPath.isNullOrBlank()
    val backgroundPreview = remember(backgroundPath) {
        backgroundPath?.let { path -> BitmapFactory.decodeFile(path)?.asImageBitmap() }
    }
    var maskOpacity by remember(backgroundPath, backgroundRevision) {
        mutableStateOf(settings.chatBackgroundMaskOpacity.coerceIn(0f, 1f))
    }

    KimiCardBox {
        KimiMenuRow(
            Icons.Default.Image,
            uiText(R.string.ui_upload_background),
            if (hasBackground) uiText(R.string.background_set) else uiText(R.string.background_solid),
        ) {
            backgroundLauncher.launch("image/*")
        }
        if (hasBackground) {
            KimiDivider()
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (backgroundPreview != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Image(
                            bitmap = backgroundPreview,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background.copy(alpha = 1f - maskOpacity)),
                        )
                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(12.dp),
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.84f),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ) {
                            Text(
                                uiText(R.string.title_background_preview),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            uiText(R.string.ui_mask_opacity_1_s, (maskOpacity * 100f).toInt().coerceIn(0, 100)),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            uiText(R.string.mask_opacity_hint),
                            color = KimiMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Slider(
                    value = maskOpacity,
                    onValueChange = { value ->
                        maskOpacity = value.coerceIn(0f, 1f)
                        settings.chatBackgroundMaskOpacity = maskOpacity
                    },
                    valueRange = 0f..1f,
                )
            }
            KimiDivider()
            KimiMenuRow(Icons.Default.DeleteOutline, uiText(R.string.action_remove_background), uiText(R.string.remove_background_hint)) {
                settings.clearChatBackground()
                notice = uiText(R.string.notice_restored_solid)
                backgroundRevision++
            }
        }
        if (notice.isNotBlank()) {
            Text(notice, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun ThemeModeSettings(
    settings: AppSettings,
    controller: ChatController,
    themeMode: String,
    onOpenCustomThemeColor: () -> Unit,
    onThemeModeChange: (String) -> Unit,
) {
    var customEnabled by remember { mutableStateOf(settings.customThemeColorEnabled) }
    KimiCardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(uiText(R.string.ui_custom_theme_color), style = MaterialTheme.typography.titleMedium)
                Text(uiText(R.string.ui_when_enabled_the_selected_color_coordinates_the_interface_background), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = customEnabled,
                onCheckedChange = {
                    customEnabled = it
                    settings.customThemeColorEnabled = it
                    controller.settingsRevision.intValue++
                },
            )
        }
        if (customEnabled) Text(
            uiText(R.string.ui_custom_theme_color_controls_light_dark_contrast_turn_it),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
        KimiDivider()
        KimiMenuRow(
            Icons.Default.ColorLens,
            uiText(R.string.ui_set_custom_theme_color),
            uiText(R.string.ui_current_color_1_s, AppSettings.normalizeHexColor(settings.customThemeColor)),
            onClick = onOpenCustomThemeColor,
        )
    }
    KimiCardBox {
        Text(uiText(R.string.title_theme_mode), style = MaterialTheme.typography.titleMedium)
        Text(
            if (customEnabled) uiText(R.string.ui_turn_off_custom_theme_color_to_select_a_theme) else uiText(R.string.theme_mode_desc),
            color = KimiMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        KimiDivider()
        ThemeOptionRow(uiText(R.string.theme_name_system), AppSettings.THEME_SYSTEM, themeMode, onThemeModeChange, !customEnabled)
        KimiDivider()
        ThemeOptionRow(uiText(R.string.theme_name_light), AppSettings.THEME_LIGHT, themeMode, onThemeModeChange, !customEnabled)
        KimiDivider()
        ThemeOptionRow(uiText(R.string.theme_name_dark), AppSettings.THEME_DARK, themeMode, onThemeModeChange, !customEnabled)
    }
}

@Composable
internal fun CustomThemeColorSettings(settings: AppSettings, controller: ChatController) {
    var colorText by remember { mutableStateOf(sanitizeThemeColorInput(settings.customThemeColor)) }
    var confirmSave by remember { mutableStateOf(false) }
    var savedNotice by remember { mutableStateOf("") }
    val inputValid = colorText.matches(Regex("#[0-9A-F]{6}"))
    val normalized = if (inputValid) colorText else AppSettings.normalizeHexColor(settings.customThemeColor)

    KimiCardBox {
        Text(uiText(R.string.ui_choose_theme_color), style = MaterialTheme.typography.titleMedium)
        Text(
            uiText(R.string.ui_the_color_ring_and_input_only_update_the_preview),
            color = KimiMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        ThemeColorRing(normalized) { selected ->
            colorText = selected
            savedNotice = ""
        }
        OutlinedTextField(
            value = colorText,
            onValueChange = { value ->
                colorText = sanitizeThemeColorInput(value)
                savedNotice = ""
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(uiText(R.string.ui_hex_color_code)) },
            supportingText = {
                Text(if (inputValid) colorText else uiText(R.string.ui_enter_a_6_digit_color_code_for_example_f6f6f4))
            },
            isError = !inputValid,
            singleLine = true,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { colorText = sanitizeThemeColorInput(settings.customThemeColor) }) {
                Text(uiText(R.string.ui_discard_changes))
            }
            Button(onClick = { confirmSave = true }, enabled = inputValid) {
                Text(uiText(R.string.ui_save_theme_color))
            }
        }
        if (savedNotice.isNotBlank()) Text(savedNotice, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
    }

    if (confirmSave) AlertDialog(
        onDismissRequest = { confirmSave = false },
        title = { Text(uiText(R.string.ui_apply_theme_color)) },
        text = { Text(uiText(R.string.ui_theme_color_will_change_to_1_s_interface_colors, colorText)) },
        confirmButton = {
            TextButton(
                onClick = {
                    settings.customThemeColor = colorText
                    controller.settingsRevision.intValue++
                    savedNotice = uiText(R.string.ui_theme_color_updated_to_1_s, colorText)
                    confirmSave = false
                },
            ) { Text(uiText(R.string.ui_apply)) }
        },
        dismissButton = { TextButton(onClick = { confirmSave = false }) { Text(uiText(R.string.action_cancel)) } },
    )
}

internal fun sanitizeThemeColorInput(value: String): String {
    val candidate = if (value.count { it == '#' } > 1) value.substringAfterLast('#') else value.removePrefix("#")
    val digits = candidate.filter { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }.take(6).uppercase(Locale.ROOT)
    return "#$digits"
}

@Composable
private fun ThemeColorRing(color: String, onColorSelected: (String) -> Unit) {
    val selectedColor = remember(color) { runCatching { Color(android.graphics.Color.parseColor(color)) }.getOrDefault(Color.White) }
    val ringOutline = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(210.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset -> selectThemeRingColor(offset, size.width, size.height, onColorSelected) }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset -> selectThemeRingColor(offset, size.width, size.height, onColorSelected) },
                    onDrag = { change, _ -> selectThemeRingColor(change.position, size.width, size.height, onColorSelected) },
                )
            },
    ) {
        val radius = size.minDimension * 0.38f
        drawCircle(
            brush = Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red), center),
            radius = radius,
            style = Stroke(width = 30.dp.toPx()),
        )
        drawCircle(selectedColor, radius = radius * 0.52f)
        drawCircle(ringOutline, radius = radius * 0.52f, style = Stroke(1.dp.toPx()))
    }
}
private fun selectThemeRingColor(offset: Offset, width: Int, height: Int, onColorSelected: (String) -> Unit) {
    val center = Offset(width / 2f, height / 2f)
    val angle = (Math.toDegrees(kotlin.math.atan2((offset.y - center.y).toDouble(), (offset.x - center.x).toDouble())) + 360.0) % 360.0
    val argb = android.graphics.Color.HSVToColor(floatArrayOf(angle.toFloat(), 0.82f, 0.92f))
    onColorSelected(String.format("#%06X", 0xFFFFFF and argb))
}
@Composable
internal fun LanguageSettings(
    languageMode: String,
    onLanguageModeChange: (String) -> Unit,
) {
    KimiCardBox {
        Text(uiText(R.string.detail_language), style = MaterialTheme.typography.titleMedium)
        Text(
            uiText(R.string.ui_follows_the_system_language_by_default_if_no_matching),
            color = KimiMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        KimiDivider()
        LanguageOptionRow(uiText(R.string.theme_name_system), uiText(R.string.ui_use_device_language_first), AppSettings.LANGUAGE_SYSTEM, languageMode, onLanguageModeChange)
        KimiDivider()
        LanguageOptionRow(uiText(R.string.language_autonym_simplified_chinese), uiText(R.string.ui_simplified_chinese), AppSettings.LANGUAGE_ZH_CN, languageMode, onLanguageModeChange)
        KimiDivider()
        LanguageOptionRow(uiText(R.string.language_autonym_traditional_chinese), uiText(R.string.ui_traditional_chinese), AppSettings.LANGUAGE_ZH_TW, languageMode, onLanguageModeChange)
        KimiDivider()
        LanguageOptionRow(uiText(R.string.language_autonym_english), uiText(R.string.ui_english), AppSettings.LANGUAGE_EN, languageMode, onLanguageModeChange)
    }
}

@Composable
internal fun RefreshRateSettings(
    refreshRateMode: String,
    onRefreshRateModeChange: (String) -> Unit,
) {
    KimiCardBox {
        Text(uiText(R.string.title_refresh_rate), style = MaterialTheme.typography.titleMedium)
        Text(
            uiText(R.string.refresh_rate_desc),
            color = KimiMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        KimiDivider()
        RefreshRateOptionRow(uiText(R.string.refresh_rate_follow_system), AppSettings.REFRESH_RATE_SYSTEM, refreshRateMode, onRefreshRateModeChange)
        KimiDivider()
        RefreshRateOptionRow("30 Hz", AppSettings.REFRESH_RATE_30, refreshRateMode, onRefreshRateModeChange)
        KimiDivider()
        RefreshRateOptionRow("60 Hz", AppSettings.REFRESH_RATE_60, refreshRateMode, onRefreshRateModeChange)
        KimiDivider()
        RefreshRateOptionRow("90 Hz", AppSettings.REFRESH_RATE_90, refreshRateMode, onRefreshRateModeChange)
        KimiDivider()
        RefreshRateOptionRow("120 Hz", AppSettings.REFRESH_RATE_120, refreshRateMode, onRefreshRateModeChange)
    }
}

@Composable
internal fun ThemeOptionRow(title: String, value: String, selected: String, onSelect: (String) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled) { onSelect(value) }
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Palette,
            contentDescription = null,
            modifier = Modifier.width(36.dp).size(24.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(title, modifier = Modifier.weight(1f), color = if (enabled) MaterialTheme.colorScheme.onSurface else KimiMuted, style = MaterialTheme.typography.titleMedium)
        if (value == selected && enabled) {
            Icon(Icons.Default.Check, contentDescription = uiText(R.string.streaming_selected), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
internal fun LanguageOptionRow(
    title: String,
    subtitle: String,
    value: String,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onSelect(value) }
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Language,
            contentDescription = null,
            modifier = Modifier.width(36.dp).size(24.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = KimiMuted, style = MaterialTheme.typography.bodySmall)
        }
        if (value == selected) {
            Icon(Icons.Default.Check, contentDescription = uiText(R.string.streaming_selected), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
internal fun RefreshRateOptionRow(title: String, value: String, selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onSelect(value) }
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Speed,
            contentDescription = null,
            modifier = Modifier.width(36.dp).size(24.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        if (value == selected) {
            Icon(Icons.Default.Check, contentDescription = uiText(R.string.streaming_selected), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
internal fun FontSizeSettings(
    settings: AppSettings,
    controller: ChatController,
    fontScaleMode: String,
    customFontScale: Float,
    onFontScaleModeChange: (String) -> Unit,
    onCustomFontScaleChange: (Float) -> Unit,
    onOpenFontLibrary: () -> Unit,
) {
    val fontSettingsRevision = controller.settingsRevision.intValue
    val currentTextFontName = remember(fontSettingsRevision) { settings.textFontName?.takeIf { settings.textFontPath != null } }
    val currentCodeFontName = remember(fontSettingsRevision) { settings.codeFontName?.takeIf { settings.codeFontPath != null } }
    val currentDensity = LocalDensity.current
    val activeFontScale = currentDensity.fontScale
    val initialScale = remember(fontScaleMode, customFontScale) {
        when (fontScaleMode) {
            AppSettings.FONT_SCALE_SMALL -> 0.9f
            AppSettings.FONT_SCALE_LARGE -> 1.12f
            AppSettings.FONT_SCALE_EXTRA_LARGE -> 1.25f
            AppSettings.FONT_SCALE_CUSTOM -> customFontScale
            else -> 1.0f
        }.coerceIn(AppSettings.MIN_FONT_SCALE, AppSettings.MAX_FONT_SCALE)
    }
    var draftScale by remember(fontScaleMode, customFontScale) { mutableStateOf(initialScale) }
    val followSystem = fontScaleMode == AppSettings.FONT_SCALE_SYSTEM
    val previewScale = if (followSystem) activeFontScale.coerceIn(AppSettings.MIN_FONT_SCALE, AppSettings.MAX_FONT_SCALE) else draftScale
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 18.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            modifier = Modifier.padding(bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f),
                ) {
                    CompositionLocalProvider(LocalDensity provides Density(currentDensity.density, previewScale)) {
                        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                            Text(uiText(R.string.title_preview_font), style = MaterialTheme.typography.titleMedium)
                            Text(fontScaleLabel(previewScale), color = KimiMuted, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            CompositionLocalProvider(LocalDensity provides Density(currentDensity.density, previewScale)) {
                Text(uiText(R.string.font_preview_text), style = MaterialTheme.typography.titleLarge)
                Text(
                    uiText(R.string.font_preview_hint),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        KimiCardBox {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(uiText(R.string.theme_name_system), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = followSystem,
                    onCheckedChange = { checked ->
                        if (checked) {
                            onFontScaleModeChange(AppSettings.FONT_SCALE_SYSTEM)
                        } else {
                            onFontScaleModeChange(AppSettings.FONT_SCALE_CUSTOM)
                            onCustomFontScaleChange(draftScale)
                        }
                    },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("A", style = MaterialTheme.typography.titleMedium)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(fontScaleLabel(draftScale), color = KimiMuted, style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = draftScale,
                        onValueChange = { draftScale = it.coerceIn(AppSettings.MIN_FONT_SCALE, AppSettings.MAX_FONT_SCALE) },
                        onValueChangeFinished = {
                            val finalScale = (draftScale / AppSettings.FONT_SCALE_STEP).roundToInt() * AppSettings.FONT_SCALE_STEP
                            val committedScale = finalScale.coerceIn(AppSettings.MIN_FONT_SCALE, AppSettings.MAX_FONT_SCALE)
                            draftScale = committedScale
                            onFontScaleModeChange(AppSettings.FONT_SCALE_CUSTOM)
                            onCustomFontScaleChange(committedScale)
                        },
                        valueRange = AppSettings.MIN_FONT_SCALE..AppSettings.MAX_FONT_SCALE,
                        steps = 0,
                        enabled = !followSystem,
                    )
                }
                Text("A", style = MaterialTheme.typography.headlineSmall)
            }
        }
        KimiCardBox {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onOpenFontLibrary).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.FontDownload, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(uiText(R.string.ui_font_library), style = MaterialTheme.typography.titleMedium)
                    Text(uiText(R.string.ui_import_preview_and_switch_fonts_in_the_font_library), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.Default.ChevronRight, contentDescription = uiText(R.string.ui_open_font_library), tint = KimiMuted)
            }
            KimiDivider()
            FontSelectionSummary(
                title = uiText(R.string.ui_text_font),
                currentName = currentTextFontName,
                onClear = {
                    settings.clearFont(codeFont = false)
                    controller.settingsRevision.intValue++
                },
            )
            FontSelectionSummary(
                title = uiText(R.string.ui_code_font),
                currentName = currentCodeFontName,
                onClear = {
                    settings.clearFont(codeFont = true)
                    controller.settingsRevision.intValue++
                },
            )
        }
    }
}

@Composable
internal fun FontLibrarySettings(
    settings: AppSettings,
    controller: ChatController,
) {
    var fontRevision by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf("") }
    var previewItem by remember { mutableStateOf<FontLibraryItem?>(null) }
    val fontLibrary = remember(fontRevision) { settings.fontLibrary() }
    val fontMimeTypes = arrayOf(
        "font/ttf", "font/otf", "font/ttc", "font/collection", "application/x-font-ttf",
        "application/x-font-opentype", "application/x-font-ttc", "application/octet-stream",
    )
    val fontLibraryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            settings.importFonts(uris).fold(
                onSuccess = { imported ->
                    notice = uiText(R.string.ui_imported_1_s_fonts, imported.size)
                    fontRevision++
                },
                onFailure = { error -> notice = error.message.orEmpty().ifBlank { "字体导入失败" } },
            )
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            KimiCardBox {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(uiText(R.string.ui_font_library), style = MaterialTheme.typography.titleMedium)
                        Text(
                            uiText(R.string.ui_import_multiple_ttf_otf_and_ttc_fonts_preview_each),
                            color = KimiMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OutlinedButton(onClick = { fontLibraryLauncher.launch(fontMimeTypes) }, shape = KimiPillShape) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(uiText(R.string.ui_import_fonts), maxLines = 1)
                    }
                }
            }
        }
        if (notice.isNotBlank()) {
            item { Text(notice, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
        }
        if (fontLibrary.isEmpty()) {
            item {
                KimiCardBox {
                    Text(uiText(R.string.ui_the_font_library_is_empty_tap_import_fonts_to), color = KimiMuted, style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            items(fontLibrary, key = { it.id }) { item ->
                FontLibraryRow(
                    item = item,
                    textSelected = settings.textFontPath == item.path,
                    codeSelected = settings.codeFontPath == item.path,
                    onPreview = { previewItem = item },
                    onSelectText = {
                        settings.selectFont(item, codeFont = false)
                        notice = uiText(R.string.ui_text_font_changed_to_1_s, item.name)
                        fontRevision++
                        controller.settingsRevision.intValue++
                    },
                    onSelectCode = {
                        settings.selectFont(item, codeFont = true)
                        notice = uiText(R.string.ui_code_font_changed_to_1_s, item.name)
                        fontRevision++
                        controller.settingsRevision.intValue++
                    },
                    onDelete = {
                        settings.deleteFont(item)
                        notice = uiText(R.string.ui_removed_from_font_library_1_s, item.name)
                        fontRevision++
                        controller.settingsRevision.intValue++
                    },
                )
            }
        }
    }
    previewItem?.let { item ->
        val previewFamily = remember(item.path) {
            runCatching { FontFamily(Typeface.createFromFile(item.path)) }.getOrDefault(FontFamily.Default)
        }
        AlertDialog(
            onDismissRequest = { previewItem = null },
            title = { Text(uiText(R.string.ui_font_preview)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(item.name, color = KimiMuted, style = MaterialTheme.typography.bodySmall)
                    Text(
                        uiText(R.string.ui_the_quick_brown_fox_jumps_over_the_lazy_dog),
                        fontFamily = previewFamily,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        uiText(R.string.ui_regular_bold_italic),
                        fontFamily = previewFamily,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { previewItem = null }) { Text(uiText(R.string.cd_close)) } },
        )
    }
}
@Composable
private fun FontSelectionSummary(
    title: String,
    currentName: String?,
    onClear: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                currentName ?: uiText(R.string.ui_system_default),
                color = KimiMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (currentName != null) TextButton(onClick = onClear) { Text(uiText(R.string.ui_restore_default), maxLines = 1) }
    }
}

@Composable
private fun FontLibraryRow(
    item: FontLibraryItem,
    textSelected: Boolean,
    codeSelected: Boolean,
    onPreview: () -> Unit,
    onSelectText: () -> Unit,
    onSelectCode: () -> Unit,
    onDelete: () -> Unit,
) {
    val itemFamily = remember(item.path) {
        runCatching { FontFamily(Typeface.createFromFile(item.path)) }.getOrDefault(FontFamily.Default)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.name, fontFamily = itemFamily, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(item.extension, color = KimiMuted, style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = onPreview) { Icon(Icons.Default.Visibility, contentDescription = uiText(R.string.ui_preview)) }
                IconButton(onClick = onDelete) { Icon(Icons.Default.DeleteOutline, contentDescription = uiText(R.string.ui_remove_from_font_library)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onSelectText) {
                    if (textSelected) Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(if (textSelected) uiText(R.string.ui_text_font_current) else uiText(R.string.ui_use_for_text))
                }
                TextButton(onClick = onSelectCode) {
                    if (codeSelected) Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(if (codeSelected) uiText(R.string.ui_code_font_current) else uiText(R.string.ui_use_for_code))
                }
            }
        }
    }
}
internal fun fontScaleLabel(scale: Float): String = when {
    scale < 0.65f -> uiText(R.string.font_scale_minimum, (scale * 100).roundToInt())
    scale < 0.8f -> uiText(R.string.font_scale_extra_small, (scale * 100).roundToInt())
    scale < 0.95f -> uiText(R.string.font_scale_small, (scale * 100).roundToInt())
    scale < 1.08f -> uiText(R.string.font_scale_standard, (scale * 100).roundToInt())
    scale < 1.35f -> uiText(R.string.font_scale_large, (scale * 100).roundToInt())
    scale < 1.65f -> uiText(R.string.font_scale_extra_large, (scale * 100).roundToInt())
    scale < 2.1f -> uiText(R.string.font_scale_very_large, (scale * 100).roundToInt())
    else -> uiText(R.string.font_scale_maximum, (scale * 100).roundToInt())
}

internal fun themeName(mode: String): String = when (mode) {
    AppSettings.THEME_LIGHT -> uiText(R.string.theme_name_light)
    AppSettings.THEME_DARK -> uiText(R.string.theme_name_dark)
    else -> uiText(R.string.theme_name_system)
}

internal fun languageName(mode: String): String = when (AppSettings.normalizeLanguageMode(mode)) {
    AppSettings.LANGUAGE_ZH_CN -> uiText(R.string.ui_simplified_chinese)
    AppSettings.LANGUAGE_ZH_TW -> uiText(R.string.ui_traditional_chinese)
    AppSettings.LANGUAGE_EN -> uiText(R.string.ui_english)
    else -> uiText(R.string.theme_name_system)
}

internal fun refreshRateName(mode: String): String = when (mode) {
    AppSettings.REFRESH_RATE_30 -> "30 Hz"
    AppSettings.REFRESH_RATE_60 -> "60 Hz"
    AppSettings.REFRESH_RATE_90 -> "90 Hz"
    AppSettings.REFRESH_RATE_120 -> "120 Hz"
    else -> uiText(R.string.refresh_rate_smart)
}

internal fun fontScaleName(mode: String, customFontScale: Float): String = when (mode) {
    AppSettings.FONT_SCALE_SMALL -> uiText(R.string.font_name_small)
    AppSettings.FONT_SCALE_NORMAL -> uiText(R.string.font_name_normal)
    AppSettings.FONT_SCALE_LARGE -> uiText(R.string.font_name_large)
    AppSettings.FONT_SCALE_EXTRA_LARGE -> uiText(R.string.font_name_extra_large)
    AppSettings.FONT_SCALE_CUSTOM -> uiText(R.string.font_scale_custom, (customFontScale * 100).roundToInt())
    else -> uiText(R.string.font_name_system)
}


