package com.yukisoffd.lyracode

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import com.yukisoffd.lyracode.data.AppSettings
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.min
import kotlin.math.abs
import android.graphics.Canvas as AndroidCanvas


@Composable
internal fun ProfileEditDialog(
    settings: AppSettings,
    nickname: String,
    avatarPath: String?,
    onDismiss: () -> Unit,
    onSaved: (String, String?) -> Unit,
    onServicesChanged: () -> Unit = {},
    onOpenAccount: (String) -> Unit = {},
    onOpenChatGptAccount: () -> Unit = {},
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var draftName by rememberSaveable(nickname) { mutableStateOf(nickname) }
    var selectedUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var zoom by rememberSaveable { mutableStateOf(1f) }
    var offsetX by rememberSaveable { mutableStateOf(0f) }
    var offsetY by rememberSaveable { mutableStateOf(0f) }
    val avatarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            selectedUri = uri
            zoom = 1f
            offsetX = 0f
            offsetY = 0f
        }
    }
    val previewBitmap = remember(selectedUri, avatarPath) {
        selectedUri?.let { decodeBitmap(context, it) }
            ?: avatarPath?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.getString(R.string.label_profile)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(128.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (previewBitmap != null) {
                        val frameSize = 128.dp
                        val aspect = previewBitmap.width.toFloat() / previewBitmap.height.toFloat()
                        val baseWidth = if (aspect >= 1f) frameSize * aspect else frameSize
                        val baseHeight = if (aspect >= 1f) frameSize else frameSize / aspect
                        val maxShiftX = with(density) { ((baseWidth * zoom - frameSize) / 2f).coerceAtLeast(0.dp).toPx() }
                        val maxShiftY = with(density) { ((baseHeight * zoom - frameSize) / 2f).coerceAtLeast(0.dp).toPx() }
                        Image(
                            bitmap = previewBitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .requiredWidth(baseWidth)
                                .requiredHeight(baseHeight)
                                .graphicsLayer(
                                    scaleX = zoom,
                                    scaleY = zoom,
                                    translationX = -offsetX * maxShiftX,
                                    translationY = -offsetY * maxShiftY,
                                )
                                .pointerInput(zoom) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        if (maxShiftX > 0f) {
                                            offsetX = (offsetX - dragAmount.x / maxShiftX).coerceIn(-1f, 1f)
                                        }
                                        if (maxShiftY > 0f) {
                                            offsetY = (offsetY - dragAmount.y / maxShiftY).coerceIn(-1f, 1f)
                                        }
                                    }
                                },
                            contentScale = ContentScale.FillBounds,
                        )
                    } else {
                        Text(draftName.take(1).ifBlank { "L" }, style = MaterialTheme.typography.headlineLarge)
                    }
                }
                OutlinedTextField(
                    value = draftName,
                    onValueChange = { draftName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(context.getString(R.string.label_nickname)) },
                    singleLine = true,
                )
                OutlinedButton(onClick = { avatarLauncher.launch("image/*") }, shape = KimiPillShape) {
                    Text(context.getString(R.string.action_select_avatar))
                }
                if (previewBitmap != null) {
                    Text(context.getString(R.string.label_crop_zoom), color = KimiMuted, style = MaterialTheme.typography.labelMedium)
                    Slider(value = zoom, onValueChange = { zoom = it }, valueRange = 1f..3f)
                    Text(context.getString(R.string.label_horizontal_position), color = KimiMuted, style = MaterialTheme.typography.labelMedium)
                    Slider(value = offsetX, onValueChange = { offsetX = it }, valueRange = -1f..1f)
                    Text(context.getString(R.string.label_vertical_position), color = KimiMuted, style = MaterialTheme.typography.labelMedium)
                    Slider(value = offsetY, onValueChange = { offsetY = it }, valueRange = -1f..1f)
                }
                KimiSectionLabel(context.getString(R.string.third_party_accounts))
                KimiCardBox { NewApiAccountSection(settings, onServicesChanged, onOpenAccount) }
                KimiCardBox { ChatGptAccountSection(settings, onOpenChatGptAccount) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val newAvatarPath = selectedUri?.let { saveCroppedAvatar(context, it, zoom, offsetX, offsetY) } ?: avatarPath
                    settings.userNickname = draftName
                    settings.userAvatarPath = newAvatarPath
                    onSaved(settings.userNickname, newAvatarPath)
                },
            ) { Text(context.getString(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(context.getString(R.string.action_cancel)) } },
    )
}

internal fun decodeBitmap(context: Context, uri: Uri): Bitmap? {
    return runCatching {
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    }.getOrNull()
}

internal fun saveCroppedAvatar(context: Context, uri: Uri, zoom: Float, offsetX: Float, offsetY: Float): String? {
    val source = decodeBitmap(context, uri) ?: return null
    val side = min(source.width, source.height)
    val cropSide = (side / zoom.coerceIn(1f, 3f)).toInt().coerceAtLeast(1)
    val maxLeft = (source.width - cropSide).coerceAtLeast(0)
    val maxTop = (source.height - cropSide).coerceAtLeast(0)
    val centerLeft = maxLeft / 2f
    val centerTop = maxTop / 2f
    val left = (centerLeft + offsetX.coerceIn(-1f, 1f) * centerLeft).toInt().coerceIn(0, maxLeft)
    val top = (centerTop + offsetY.coerceIn(-1f, 1f) * centerTop).toInt().coerceIn(0, maxTop)
    val cropped = Bitmap.createBitmap(source, left, top, cropSide, cropSide)
    val scaled = Bitmap.createScaledBitmap(cropped, 512, 512, true)
    val file = File(context.filesDir, "avatar.png")
    file.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
    if (cropped !== source) cropped.recycle()
    if (scaled !== cropped) scaled.recycle()
    return file.absolutePath
}

@Composable
internal fun ImageCropUploadDialog(
    uri: Uri,
    fixedCropAspectRatio: Float? = null,
    onDismiss: () -> Unit,
    onUseOriginal: () -> Unit,
    onCropped: (Uri) -> Unit,
) {
    val context = LocalContext.current
    val original = remember(uri) { decodeBitmap(context, uri) }
    var bitmap by remember(uri) { mutableStateOf(original) }
    val normalizedFixedAspect = remember(bitmap, fixedCropAspectRatio) {
        val target = fixedCropAspectRatio?.coerceIn(0.2f, 5f)
        val imageAspect = bitmap?.let { it.width.toFloat() / it.height.toFloat().coerceAtLeast(1f) } ?: 1f
        target?.let { (it / imageAspect).coerceIn(0.08f, 12f) }
    }
    var mode by remember { mutableStateOf(ImageEditMode.Crop) }
    var cropLeft by rememberSaveable(uri.toString()) { mutableStateOf(0.08f) }
    var cropTop by rememberSaveable(uri.toString()) { mutableStateOf(0.08f) }
    var cropWidth by rememberSaveable(uri.toString()) { mutableStateOf(0.84f) }
    var cropHeight by rememberSaveable(uri.toString()) { mutableStateOf(0.84f) }
    var appliedFixedAspect by rememberSaveable(uri.toString()) { mutableStateOf(-1f) }
    var brushColor by remember { mutableStateOf(Color(0xFFE53935)) }
    var brushWidth by remember { mutableStateOf(0.014f) }
    var strokes by remember(uri) { mutableStateOf<List<ImageEditStroke>>(emptyList()) }
    var redoStack by remember(uri) { mutableStateOf<List<ImageEditStroke>>(emptyList()) }
    var activeStroke by remember { mutableStateOf<ImageEditStroke?>(null) }
    var cropDragMode by remember { mutableStateOf("move") }
    val currentCropState = rememberUpdatedState(CropRectState(cropLeft, cropTop, cropWidth, cropHeight))
    fun resetCropRect() {
        val rect = initialCropRectForAspect(normalizedFixedAspect)
        cropLeft = rect.left
        cropTop = rect.top
        cropWidth = rect.width
        cropHeight = rect.height
    }
    LaunchedEffect(normalizedFixedAspect) {
        val aspect = normalizedFixedAspect
        if (aspect != null && abs(appliedFixedAspect - aspect) > 0.001f) {
            val rect = initialCropRectForAspect(aspect)
            cropLeft = rect.left
            cropTop = rect.top
            cropWidth = rect.width
            cropHeight = rect.height
            appliedFixedAspect = aspect
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text(context.getString(R.string.action_cancel), color = Color.White) }
                Spacer(Modifier.weight(1f))
                IconButton(
                    enabled = strokes.isNotEmpty(),
                    onClick = {
                        strokes.lastOrNull()?.let {
                            strokes = strokes.dropLast(1)
                            redoStack = redoStack + it
                        }
                    },
                ) {
                    Icon(
                        Icons.Default.Undo,
                        contentDescription = context.getString(R.string.action_undo),
                        tint = if (strokes.isNotEmpty()) Color.White else Color.Gray,
                    )
                }
                IconButton(
                    enabled = redoStack.isNotEmpty(),
                    onClick = {
                        redoStack.lastOrNull()?.let {
                            strokes = strokes + it
                            redoStack = redoStack.dropLast(1)
                        }
                    },
                ) {
                    Icon(
                        Icons.Default.Redo,
                        contentDescription = context.getString(R.string.action_redo),
                        tint = if (redoStack.isNotEmpty()) Color.White else Color.Gray,
                    )
                }
            }
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                val currentBitmap = bitmap
                if (currentBitmap != null) {
                    val aspect = currentBitmap.width.toFloat() / currentBitmap.height.toFloat()
                    val availableAspect = maxWidth.value / maxHeight.value.coerceAtLeast(1f)
                    val frameWidth = if (availableAspect > aspect) maxHeight * aspect else maxWidth
                    val frameHeight = if (availableAspect > aspect) maxHeight else maxWidth / aspect
                    Box(
                        Modifier
                            .requiredWidth(frameWidth)
                            .requiredHeight(frameHeight)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.DarkGray),
                    ) {
                        Image(
                            bitmap = currentBitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.FillBounds,
                        )
                        Canvas(
                            Modifier
                                .fillMaxSize()
                                .pointerInput(mode, brushColor, brushWidth) {
                                    var dragStartRect = CropRectState(0.08f, 0.08f, 0.84f, 0.84f)
                                    var totalDx = 0f
                                    var totalDy = 0f
                                    detectDragGestures(
                                        onDragStart = { start ->
                                            totalDx = 0f
                                            totalDy = 0f
                                            if (mode == ImageEditMode.Crop) {
                                                dragStartRect = currentCropState.value
                                                cropDragMode = cropHitMode(start, dragStartRect.left, dragStartRect.top, dragStartRect.width, dragStartRect.height, size.width, size.height)
                                            } else {
                                                val point = normalizedEditPoint(start, size.width.toFloat(), size.height.toFloat())
                                                activeStroke = ImageEditStroke(mode, brushColor.toArgb(), brushWidth, listOf(point))
                                            }
                                        },
                                        onDragEnd = {
                                            activeStroke?.let {
                                                strokes = strokes + it
                                                redoStack = emptyList()
                                            }
                                            activeStroke = null
                                            cropDragMode = "move"
                                        },
                                        onDragCancel = {
                                            activeStroke = null
                                            cropDragMode = "move"
                                        },
                                    ) { change, dragAmount ->
                                        change.consume()
                                        if (mode == ImageEditMode.Crop) {
                                            totalDx += dragAmount.x / size.width.toFloat().coerceAtLeast(1f)
                                            totalDy += dragAmount.y / size.height.toFloat().coerceAtLeast(1f)
                                            val updated = updateCropRect(
                                                dragStartRect.left,
                                                dragStartRect.top,
                                                dragStartRect.width,
                                                dragStartRect.height,
                                                totalDx,
                                                totalDy,
                                                cropDragMode,
                                            )
                                            val adjusted = normalizedFixedAspect
                                                ?.let { constrainCropRectAspect(updated, it) }
                                                ?: updated
                                            cropLeft = adjusted.left
                                            cropTop = adjusted.top
                                            cropWidth = adjusted.width
                                            cropHeight = adjusted.height
                                        } else {
                                            val point = normalizedEditPoint(change.position, size.width.toFloat(), size.height.toFloat())
                                            activeStroke = activeStroke?.copy(points = activeStroke!!.points + point)
                                        }
                                    }
                                },
                        ) {
                            val allStrokes = activeStroke?.let { strokes + it } ?: strokes
                            allStrokes.forEach { drawEditStroke(it, size) }
                            drawCropOverlay(cropLeft, cropTop, cropWidth, cropHeight, size)
                        }
                    }
                } else {
                    Text(context.getString(R.string.notice_cannot_preview_image), color = Color.White)
                }
            }
            ImageEditToolbar(
                mode = mode,
                onModeChange = { mode = it },
                brushColor = brushColor,
                onBrushColorChange = { brushColor = it },
                brushWidth = brushWidth,
                onBrushWidthChange = { brushWidth = it },
                onRotate = {
                    bitmap = bitmap?.let { rotateBitmap90(it) }
                    resetCropRect()
                    strokes = emptyList()
                    redoStack = emptyList()
                },
                onReset = {
                    resetCropRect()
                    strokes = emptyList()
                    redoStack = emptyList()
                    bitmap = original
                },
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 52.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onUseOriginal, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(context.getString(R.string.action_upload_original))
                }
                Button(
                    onClick = {
                        val edited = bitmap?.let {
                            saveEditedUploadImage(context, it, cropLeft, cropTop, cropWidth, cropHeight, strokes)
                        }
                        edited?.let(onCropped) ?: onUseOriginal()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(context.getString(R.string.action_done))
                }
            }
        }
    }
}

internal fun saveTemporaryUploadImage(context: Context, bitmap: Bitmap): Uri? = runCatching {
    val dir = File(context.cacheDir, "upload_crop").apply { mkdirs() }
    val file = File(dir, "camera_${System.currentTimeMillis()}.jpg")
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    Uri.fromFile(file)
}.getOrNull()

internal enum class ImageEditMode {
    Crop,
    Brush,
    Mosaic,
}

internal data class ImageEditPoint(val x: Float, val y: Float)

internal data class ImageEditStroke(
    val mode: ImageEditMode,
    val color: Int,
    val width: Float,
    val points: List<ImageEditPoint>,
)

internal data class CropRectState(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

@Composable
internal fun ImageEditToolbar(
    mode: ImageEditMode,
    onModeChange: (ImageEditMode) -> Unit,
    brushColor: Color,
    onBrushColorChange: (Color) -> Unit,
    brushWidth: Float,
    onBrushWidthChange: (Float) -> Unit,
    onRotate: () -> Unit,
    onReset: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val context = LocalContext.current
            ImageModeChip(context.getString(R.string.label_crop), mode == ImageEditMode.Crop) { onModeChange(ImageEditMode.Crop) }
            ImageModeChip(context.getString(R.string.label_brush), mode == ImageEditMode.Brush) { onModeChange(ImageEditMode.Brush) }
            ImageModeChip(context.getString(R.string.label_mosaic), mode == ImageEditMode.Mosaic) { onModeChange(ImageEditMode.Mosaic) }
            Spacer(Modifier.weight(1f))
            KimiChip(context.getString(R.string.action_rotate), onClick = onRotate)
            KimiChip(context.getString(R.string.action_reset), onClick = onReset)
        }
        if (mode == ImageEditMode.Brush || mode == ImageEditMode.Mosaic) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (mode == ImageEditMode.Brush) {
                    listOf(Color.White, Color.Black, Color(0xFFE53935), Color(0xFFFFB300), Color(0xFF43A047), Color(0xFF039BE5)).forEach { color ->
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(color)
                                .border(if (color == brushColor) 3.dp else 1.dp, Color.White, CircleShape)
                                .clickable { onBrushColorChange(color) },
                        )
                    }
                }
                val contextThickness = LocalContext.current
                Text(contextThickness.getString(R.string.label_thickness), color = Color.White, style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = brushWidth,
                    onValueChange = onBrushWidthChange,
                    valueRange = 0.006f..0.04f,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
internal fun ImageModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier
            .clip(KimiPillShape)
            .background(if (selected) KimiBlue else Color(0xFF242424))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
    )
}

internal fun normalizedEditPoint(offset: Offset, width: Float, height: Float): ImageEditPoint {
    return ImageEditPoint(
        x = (offset.x / width.coerceAtLeast(1f)).coerceIn(0f, 1f),
        y = (offset.y / height.coerceAtLeast(1f)).coerceIn(0f, 1f),
    )
}

internal fun cropHitMode(point: Offset, left: Float, top: Float, width: Float, height: Float, frameWidth: Int, frameHeight: Int): String {
    val x = point.x / frameWidth.toFloat().coerceAtLeast(1f)
    val y = point.y / frameHeight.toFloat().coerceAtLeast(1f)
    val right = left + width
    val bottom = top + height
    val edge = 0.06f
    val nearLeft = abs(x - left) < edge
    val nearRight = abs(x - right) < edge
    val nearTop = abs(y - top) < edge
    val nearBottom = abs(y - bottom) < edge
    return when {
        nearLeft && nearTop -> "top_left"
        nearRight && nearTop -> "top_right"
        nearLeft && nearBottom -> "bottom_left"
        nearRight && nearBottom -> "bottom_right"
        nearLeft -> "left"
        nearRight -> "right"
        nearTop -> "top"
        nearBottom -> "bottom"
        else -> "move"
    }
}

internal fun updateCropRect(left: Float, top: Float, width: Float, height: Float, dx: Float, dy: Float, mode: String): CropRectState {
    val minSize = 0.12f
    var l = left
    var t = top
    var w = width
    var h = height
    fun move() {
        l = (l + dx).coerceIn(0f, 1f - w)
        t = (t + dy).coerceIn(0f, 1f - h)
    }
    fun leftEdge() {
        val newLeft = (l + dx).coerceIn(0f, l + w - minSize)
        w += l - newLeft
        l = newLeft
    }
    fun rightEdge() {
        w = (w + dx).coerceIn(minSize, 1f - l)
    }
    fun topEdge() {
        val newTop = (t + dy).coerceIn(0f, t + h - minSize)
        h += t - newTop
        t = newTop
    }
    fun bottomEdge() {
        h = (h + dy).coerceIn(minSize, 1f - t)
    }
    when (mode) {
        "left" -> leftEdge()
        "right" -> rightEdge()
        "top" -> topEdge()
        "bottom" -> bottomEdge()
        "top_left" -> { topEdge(); leftEdge() }
        "top_right" -> { topEdge(); rightEdge() }
        "bottom_left" -> { bottomEdge(); leftEdge() }
        "bottom_right" -> { bottomEdge(); rightEdge() }
        else -> move()
    }
    return CropRectState(l.coerceIn(0f, 1f - w), t.coerceIn(0f, 1f - h), w.coerceIn(minSize, 1f), h.coerceIn(minSize, 1f))
}

internal fun initialCropRectForAspect(aspect: Float?): CropRectState {
    val target = aspect?.coerceIn(0.08f, 12f) ?: 1f
    val maxSize = 0.86f
    val minSize = 0.12f
    val (width, height) = if (target >= 1f) {
        val width = maxSize
        val height = (width / target).coerceIn(minSize, maxSize)
        width to height
    } else {
        val height = maxSize
        val width = (height * target).coerceIn(minSize, maxSize)
        width to height
    }
    return CropRectState(
        left = ((1f - width) / 2f).coerceIn(0f, 1f - width),
        top = ((1f - height) / 2f).coerceIn(0f, 1f - height),
        width = width,
        height = height,
    )
}

internal fun constrainCropRectAspect(rect: CropRectState, aspect: Float): CropRectState {
    val minSize = 0.12f
    val target = aspect.coerceIn(0.08f, 12f)
    val centerX = rect.left + rect.width / 2f
    val centerY = rect.top + rect.height / 2f
    var width = rect.width.coerceIn(minSize, 1f)
    var height = rect.height.coerceIn(minSize, 1f)
    if (width / height > target) {
        width = height * target
    } else {
        height = width / target
    }
    if (width > 1f) {
        width = 1f
        height = (width / target).coerceIn(minSize, 1f)
    }
    if (height > 1f) {
        height = 1f
        width = (height * target).coerceIn(minSize, 1f)
    }
    val left = (centerX - width / 2f).coerceIn(0f, 1f - width)
    val top = (centerY - height / 2f).coerceIn(0f, 1f - height)
    return CropRectState(left, top, width, height)
}

internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCropOverlay(left: Float, top: Float, width: Float, height: Float, canvasSize: Size) {
    val x = canvasSize.width * left
    val y = canvasSize.height * top
    val w = canvasSize.width * width
    val h = canvasSize.height * height
    drawRect(Color.Black.copy(alpha = 0.42f), topLeft = Offset.Zero, size = Size(canvasSize.width, y))
    drawRect(Color.Black.copy(alpha = 0.42f), topLeft = Offset(0f, y + h), size = Size(canvasSize.width, canvasSize.height - y - h))
    drawRect(Color.Black.copy(alpha = 0.42f), topLeft = Offset(0f, y), size = Size(x, h))
    drawRect(Color.Black.copy(alpha = 0.42f), topLeft = Offset(x + w, y), size = Size(canvasSize.width - x - w, h))
    drawRect(Color.White, topLeft = Offset(x, y), size = Size(w, h), style = Stroke(width = 3f))
    val corner = min(w, h).coerceAtMost(46f)
    listOf(
        Offset(x, y) to Pair(1f, 1f),
        Offset(x + w, y) to Pair(-1f, 1f),
        Offset(x, y + h) to Pair(1f, -1f),
        Offset(x + w, y + h) to Pair(-1f, -1f),
    ).forEach { (origin, dir) ->
        drawLine(Color.White, origin, origin + Offset(corner * dir.first, 0f), strokeWidth = 7f)
        drawLine(Color.White, origin, origin + Offset(0f, corner * dir.second), strokeWidth = 7f)
    }
}

internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawEditStroke(stroke: ImageEditStroke, canvasSize: Size) {
    if (stroke.points.isEmpty()) return
    val color = Color(stroke.color)
    val pxWidth = stroke.width * min(canvasSize.width, canvasSize.height)
    if (stroke.mode == ImageEditMode.Mosaic) {
        stroke.points.forEach { point ->
            val side = pxWidth * 3.5f
            drawRect(
                Color.Gray.copy(alpha = 0.7f),
                topLeft = Offset(point.x * canvasSize.width - side / 2f, point.y * canvasSize.height - side / 2f),
                size = Size(side, side),
            )
        }
        return
    }
    stroke.points.zipWithNext().forEach { (a, b) ->
        drawLine(
            color = color,
            start = Offset(a.x * canvasSize.width, a.y * canvasSize.height),
            end = Offset(b.x * canvasSize.width, b.y * canvasSize.height),
            strokeWidth = pxWidth,
        )
    }
}

internal fun rotateBitmap90(source: Bitmap): Bitmap {
    val matrix = Matrix().apply { postRotate(90f) }
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
}

internal fun saveEditedUploadImage(
    context: Context,
    source: Bitmap,
    cropLeft: Float,
    cropTop: Float,
    cropWidthFraction: Float,
    cropHeightFraction: Float,
    strokes: List<ImageEditStroke>,
): Uri? {
    val editable = source.copy(Bitmap.Config.ARGB_8888, true)
    strokes.forEach { stroke ->
        if (stroke.mode == ImageEditMode.Mosaic) {
            val side = (stroke.width * min(editable.width, editable.height) * 3.5f).toInt().coerceAtLeast(8)
            stroke.points.forEach { point ->
                applyMosaicBlock(editable, (point.x * editable.width).toInt(), (point.y * editable.height).toInt(), side)
            }
        }
    }
    val canvas = AndroidCanvas(editable)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    strokes.filter { it.mode == ImageEditMode.Brush }.forEach { stroke ->
        paint.color = stroke.color
        paint.strokeWidth = stroke.width * min(editable.width, editable.height)
        stroke.points.zipWithNext().forEach { (a, b) ->
            canvas.drawLine(a.x * editable.width, a.y * editable.height, b.x * editable.width, b.y * editable.height, paint)
        }
    }
    val cropWidth = (editable.width * cropWidthFraction.coerceIn(0.05f, 1f)).toInt().coerceIn(1, editable.width)
    val cropHeight = (editable.height * cropHeightFraction.coerceIn(0.05f, 1f)).toInt().coerceIn(1, editable.height)
    val left = (editable.width * cropLeft.coerceIn(0f, 1f)).toInt().coerceIn(0, (editable.width - cropWidth).coerceAtLeast(0))
    val top = (editable.height * cropTop.coerceIn(0f, 1f)).toInt().coerceIn(0, (editable.height - cropHeight).coerceAtLeast(0))
    val cropped = Bitmap.createBitmap(editable, left, top, cropWidth, cropHeight)
    val dir = File(context.cacheDir, "uploads").apply { mkdirs() }
    val file = File(dir, "image_${System.currentTimeMillis()}.jpg")
    file.outputStream().use { cropped.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    if (cropped !== editable) cropped.recycle()
    if (editable !== source) editable.recycle()
    return Uri.fromFile(file)
}

internal fun applyMosaicBlock(bitmap: Bitmap, centerX: Int, centerY: Int, side: Int) {
    val half = side / 2
    val left = (centerX - half).coerceIn(0, bitmap.width - 1)
    val top = (centerY - half).coerceIn(0, bitmap.height - 1)
    val right = (centerX + half).coerceIn(left + 1, bitmap.width)
    val bottom = (centerY + half).coerceIn(top + 1, bitmap.height)
    var r = 0L
    var g = 0L
    var b = 0L
    var count = 0L
    var y = top
    while (y < bottom) {
        var x = left
        while (x < right) {
            val color = bitmap.getPixel(x, y)
            r += android.graphics.Color.red(color)
            g += android.graphics.Color.green(color)
            b += android.graphics.Color.blue(color)
            count++
            x += 2
        }
        y += 2
    }
    if (count == 0L) return
    val avg = android.graphics.Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
    y = top
    while (y < bottom) {
        var x = left
        while (x < right) {
            bitmap.setPixel(x, y, avg)
            x++
        }
        y++
    }
}

