@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.yukisoffd.lyracode.filemanager

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.yukisoffd.lyracode.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal enum class MediaPreviewKind { IMAGE, AUDIO, VIDEO }

private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "dng")
private val audioExtensions = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "oga", "opus", "amr", "3ga", "ac3", "ec3")
private val videoExtensions = setOf("mp4", "m4v", "mkv", "webm", "mov", "3gp", "3g2", "m2ts", "mts", "mpg", "mpeg", "flv", "avi")

internal fun mediaPreviewKind(file: File): MediaPreviewKind? {
    val extension = file.extension.lowercase(Locale.ROOT)
    return when {
        extension in imageExtensions -> MediaPreviewKind.IMAGE
        extension in audioExtensions -> MediaPreviewKind.AUDIO
        extension in videoExtensions -> MediaPreviewKind.VIDEO
        extension == "ts" && looksLikeMpegTransportStream(file) -> MediaPreviewKind.VIDEO
        else -> null
    }
}

private fun looksLikeMpegTransportStream(file: File): Boolean = runCatching {
    val probeSize = MPEG_TS_PACKET_SIZE * MPEG_TS_PROBE_PACKETS
    val probe = file.inputStream().buffered().use { input ->
        val buffer = ByteArray(probeSize)
        var count = 0
        while (count < buffer.size) {
            val read = input.read(buffer, count, buffer.size - count)
            if (read <= 0) break
            count += read
        }
        if (count < probeSize) return@runCatching false
        buffer
    }
    (0 until MPEG_TS_PACKET_SIZE).any { offset ->
        (0 until MPEG_TS_PROBE_PACKETS).all { packet ->
            probe[offset + packet * MPEG_TS_PACKET_SIZE] == MPEG_TS_SYNC_BYTE
        }
    }
}.getOrDefault(false)

private const val MPEG_TS_PACKET_SIZE = 188
private const val MPEG_TS_PROBE_PACKETS = 3
private const val MPEG_TS_SYNC_BYTE: Byte = 0x47

@Composable
internal fun MediaPreviewScreen(
    file: File,
    kind: MediaPreviewKind,
    onClose: () -> Unit,
    onOpenExternal: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black,
    ) {
        when (kind) {
            MediaPreviewKind.IMAGE -> ImagePreview(file = file, onClose = onClose, onOpenExternal = onOpenExternal)
            MediaPreviewKind.AUDIO, MediaPreviewKind.VIDEO -> PlaybackPreview(
                file = file,
                video = kind == MediaPreviewKind.VIDEO,
                onClose = onClose,
                onOpenExternal = onOpenExternal,
            )
        }
    }
}

private data class DecodedImage(val bitmap: Bitmap? = null, val error: Throwable? = null)

@Composable
private fun ImagePreview(file: File, onClose: () -> Unit, onOpenExternal: () -> Unit) {
    val configuration = LocalConfiguration.current
    val maxDimension = max(configuration.screenWidthDp, configuration.screenHeightDp) * 3
    val decoded by produceState(DecodedImage(), file.absolutePath, maxDimension) {
        value = withContext(Dispatchers.IO) {
            runCatching { decodePreviewBitmap(file, maxDimension) }
                .fold(onSuccess = { DecodedImage(bitmap = it) }, onFailure = { DecodedImage(error = it) })
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val bitmap = decoded.bitmap
        when {
            bitmap != null -> Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = file.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(top = 56.dp),
            )
            decoded.error != null -> MediaError(
                message = stringResource(R.string.media_preview_image_failed),
                onOpenExternal = onOpenExternal,
            )
            else -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        }
        MediaTopBar(file.name, onClose)
    }
}

private fun decodePreviewBitmap(file: File, maxDimension: Int): Bitmap {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = ImageDecoder.createSource(file)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            val scale = min(1f, maxDimension.toFloat() / max(width, height).coerceAtLeast(1))
            if (scale < 1f) decoder.setTargetSize((width * scale).roundToInt(), (height * scale).roundToInt())
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sampleSize = 1
    while (max(bounds.outWidth, bounds.outHeight) / sampleSize > maxDimension) sampleSize *= 2
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeFile(file.absolutePath, options)
        ?: error("BitmapFactory could not decode ${file.name}")
}

@Composable
private fun PlaybackPreview(
    file: File,
    video: Boolean,
    onClose: () -> Unit,
    onOpenExternal: () -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val activity = remember(context) { context.findActivity() }
    val originalOrientation = remember(activity) {
        activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    val originalBrightness = remember(activity) { activity?.window?.attributes?.screenBrightness ?: -1f }
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember(audioManager) { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volume by remember(audioManager) {
        mutableFloatStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume)
    }
    var brightness by remember(activity) {
        mutableFloatStateOf(resolveBrightness(context, originalBrightness))
    }
    val player = remember(file.absolutePath) {
        ExoPlayer.Builder(context.applicationContext).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
            playWhenReady = true
        }
    }
    var isPlaying by remember(player) { mutableStateOf(player.isPlaying) }
    var duration by remember(player) { mutableStateOf(0L) }
    var position by remember(player) { mutableStateOf(0L) }
    var scrubPosition by remember(player) { mutableStateOf<Long?>(null) }
    var playbackSpeed by remember(player) { mutableFloatStateOf(1f) }
    var playerError by remember(player) { mutableStateOf<PlaybackException?>(null) }
    var controlsVisible by remember { mutableStateOf(true) }
    var gestureMessage by remember { mutableStateOf<String?>(null) }
    val lifecycleOwner = activity as? LifecycleOwner

    fun setVolumeLevel(value: Float) {
        val bounded = value.coerceIn(0f, 1f)
        volume = bounded
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (bounded * maxVolume).roundToInt(), 0)
    }

    fun setBrightnessLevel(value: Float) {
        val bounded = value.coerceIn(0.05f, 1f)
        brightness = bounded
        activity?.window?.let { window ->
            val attributes = window.attributes
            attributes.screenBrightness = bounded
            window.attributes = attributes
        }
    }

    DisposableEffect(player, lifecycleOwner) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) { isPlaying = value }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
                }
            }
            override fun onPlayerError(error: PlaybackException) { playerError = error }
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        player.addListener(listener)
        lifecycleOwner?.lifecycle?.addObserver(lifecycleObserver)
        onDispose {
            lifecycleOwner?.lifecycle?.removeObserver(lifecycleObserver)
            player.removeListener(listener)
            player.release()
            activity?.requestedOrientation = originalOrientation
            activity?.window?.let { window ->
                val attributes = window.attributes
                attributes.screenBrightness = originalBrightness
                window.attributes = attributes
            }
        }
    }

    LaunchedEffect(player) {
        while (isActive) {
            position = player.currentPosition.coerceAtLeast(0L)
            val playerDuration = player.duration
            if (playerDuration != C.TIME_UNSET && playerDuration > 0) duration = playerDuration
            delay(200)
        }
    }
    LaunchedEffect(gestureMessage) {
        if (gestureMessage != null) {
            delay(900)
            gestureMessage = null
        }
    }

    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    LaunchedEffect(activity, video, isLandscape) {
        setSystemBarsHidden(activity, hidden = video && isLandscape)
    }
    DisposableEffect(activity, video) {
        onDispose {
            if (video) setSystemBarsHidden(activity, hidden = false)
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (video) {
            AndroidView(
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        keepScreenOn = true
                        this.player = player
                    }
                },
                update = { it.player = player },
                modifier = Modifier.fillMaxSize(),
            )
            MediaGestureLayer(
                currentPosition = { player.currentPosition },
                duration = { duration },
                brightness = { brightness },
                volume = { volume },
                onSeek = { target ->
                    player.seekTo(target)
                    position = target
                    gestureMessage = context.getString(R.string.media_preview_seek_position, formatDuration(target))
                },
                onBrightness = { value ->
                    setBrightnessLevel(value)
                    gestureMessage = "${context.getString(R.string.media_preview_brightness)} ${(brightness * 100).roundToInt()}%"
                },
                onVolume = { value ->
                    setVolumeLevel(value)
                    gestureMessage = "${context.getString(R.string.media_preview_volume)} ${(volume * 100).roundToInt()}%"
                },
                onTogglePlayback = { if (player.isPlaying) player.pause() else player.play() },
                onToggleControls = { controlsVisible = !controlsVisible },
                onLongPressStart = {
                    player.setPlaybackSpeed(2f)
                    gestureMessage = context.getString(R.string.media_preview_long_press_speed)
                },
                onLongPressEnd = { player.setPlaybackSpeed(playbackSpeed) },
            )
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(bottom = 150.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color.White, modifier = Modifier.size(96.dp))
                Spacer(Modifier.height(20.dp))
                Text(file.name, color = Color.White, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.media_preview_audio), color = Color.White.copy(alpha = 0.65f))
            }
        }

        playerError?.let {
            MediaError(
                message = stringResource(R.string.media_preview_playback_failed, it.errorCodeName),
                onOpenExternal = onOpenExternal,
            )
        }

        AnimatedVisibility(visible = controlsVisible || !video) {
            MediaTopBar(file.name, onClose)
        }
        AnimatedVisibility(
            visible = controlsVisible || !video,
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            PlaybackControls(
                isPlaying = isPlaying,
                position = scrubPosition ?: position,
                duration = duration,
                speed = playbackSpeed,
                brightness = brightness,
                volume = volume,
                video = video,
                isLandscape = isLandscape,
                onPlayPause = { if (player.isPlaying) player.pause() else player.play() },
                onScrub = { scrubPosition = it },
                onScrubFinished = {
                    scrubPosition?.let(player::seekTo)
                    scrubPosition = null
                },
                onSpeed = {
                    playbackSpeed = it
                    player.setPlaybackSpeed(it)
                },
                onBrightness = ::setBrightnessLevel,
                onVolume = ::setVolumeLevel,
                onOrientation = {
                    activity?.requestedOrientation = if (isLandscape) {
                        ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }
                },
            )
        }
        gestureMessage?.let { message ->
            Surface(
                color = Color.Black.copy(alpha = 0.72f),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.align(Alignment.Center),
            ) {
                Text(message, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
            }
        }
    }
}

@Composable
private fun MediaTopBar(fileName: String, onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).background(Color.Black.copy(alpha = 0.62f)).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.media_preview_close), tint = Color.White)
        }
        Text(fileName, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PlaybackControls(
    isPlaying: Boolean,
    position: Long,
    duration: Long,
    speed: Float,
    brightness: Float,
    volume: Float,
    video: Boolean,
    isLandscape: Boolean,
    onPlayPause: () -> Unit,
    onScrub: (Long) -> Unit,
    onScrubFinished: () -> Unit,
    onSpeed: (Float) -> Unit,
    onBrightness: (Float) -> Unit,
    onVolume: (Float) -> Unit,
    onOrientation: () -> Unit,
) {
    var speedMenu by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.72f)).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Slider(
            value = position.coerceIn(0L, duration.coerceAtLeast(0L)).toFloat(),
            onValueChange = { onScrub(it.toLong()) },
            onValueChangeFinished = onScrubFinished,
            valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
            modifier = Modifier.fillMaxWidth().height(32.dp),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPlayPause) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = stringResource(if (isPlaying) R.string.media_preview_pause else R.string.media_preview_play),
                    tint = Color.White,
                )
            }
            Text("${formatDuration(position)} / ${formatDuration(duration)}", color = Color.White, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Box {
                TextButton(onClick = { speedMenu = true }) {
                    Icon(
                        Icons.Default.FastForward,
                        contentDescription = stringResource(R.string.media_preview_speed),
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(" ${formatSpeed(speed)}", color = Color.White)
                }
                DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { option ->
                        DropdownMenuItem(
                            text = { Text(formatSpeed(option), fontWeight = if (option == speed) FontWeight.Bold else FontWeight.Normal) },
                            onClick = { onSpeed(option); speedMenu = false },
                        )
                    }
                }
            }
            if (video) {
                IconButton(onClick = onOrientation) {
                    Icon(
                        if (isLandscape) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                        contentDescription = stringResource(if (isLandscape) R.string.media_preview_portrait else R.string.media_preview_landscape),
                        tint = Color.White,
                    )
                }
            }
        }
        if (!video || !isLandscape) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LevelControl(
                    icon = { Icon(Icons.Default.Brightness6, contentDescription = null, tint = Color.White) },
                    label = stringResource(R.string.media_preview_brightness),
                    value = brightness,
                    minimum = 0.05f,
                    onValue = onBrightness,
                )
                LevelControl(
                    icon = { Icon(Icons.Default.VolumeUp, contentDescription = null, tint = Color.White) },
                    label = stringResource(R.string.media_preview_volume),
                    value = volume,
                    minimum = 0f,
                    onValue = onVolume,
                )
            }
        }
    }
}

@Composable
private fun LevelControl(
    icon: @Composable () -> Unit,
    label: String,
    value: Float,
    minimum: Float,
    onValue: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onValue((value - 0.1f).coerceAtLeast(minimum)) }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.media_preview_decrease, label), tint = Color.White)
        }
        Row(
            modifier = Modifier.combinedClickable(onClick = { onValue((value + 0.1f).coerceAtMost(1f)) }, onLongClick = { onValue(minimum) }).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            icon()
            Text("${(value * 100).roundToInt()}%", color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
        IconButton(onClick = { onValue((value + 0.1f).coerceAtMost(1f)) }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.media_preview_increase, label), tint = Color.White)
        }
    }
}

private enum class DragMode { SEEK, BRIGHTNESS, VOLUME }

@Composable
private fun MediaGestureLayer(
    currentPosition: () -> Long,
    duration: () -> Long,
    brightness: () -> Float,
    volume: () -> Float,
    onSeek: (Long) -> Unit,
    onBrightness: (Float) -> Unit,
    onVolume: (Float) -> Unit,
    onTogglePlayback: () -> Unit,
    onToggleControls: () -> Unit,
    onLongPressStart: () -> Unit,
    onLongPressEnd: () -> Unit,
) {
    val latestPosition by rememberUpdatedState(currentPosition)
    val latestDuration by rememberUpdatedState(duration)
    val latestBrightness by rememberUpdatedState(brightness)
    val latestVolume by rememberUpdatedState(volume)
    val latestSeek by rememberUpdatedState(onSeek)
    val latestBrightnessChange by rememberUpdatedState(onBrightness)
    val latestVolumeChange by rememberUpdatedState(onVolume)
    val latestTogglePlayback by rememberUpdatedState(onTogglePlayback)
    val latestToggleControls by rememberUpdatedState(onToggleControls)
    val latestLongPressStart by rememberUpdatedState(onLongPressStart)
    val latestLongPressEnd by rememberUpdatedState(onLongPressEnd)
    var longPressActive by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { latestToggleControls() },
                    onDoubleTap = { latestTogglePlayback() },
                    onLongPress = {
                        longPressActive = true
                        latestLongPressStart()
                    },
                    onPress = {
                        tryAwaitRelease()
                        if (longPressActive) {
                            longPressActive = false
                            latestLongPressEnd()
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                var mode: DragMode? = null
                var start = Offset.Zero
                var total = Offset.Zero
                var startPosition = 0L
                var startBrightness = 0f
                var startVolume = 0f
                detectDragGestures(
                    onDragStart = { offset ->
                        mode = null
                        start = offset
                        total = Offset.Zero
                        startPosition = latestPosition()
                        startBrightness = latestBrightness()
                        startVolume = latestVolume()
                    },
                    onDragEnd = { mode = null },
                    onDragCancel = { mode = null },
                    onDrag = { change, dragAmount ->
                        total += dragAmount
                        if (mode == null && (abs(total.x) + abs(total.y)) > 12f) {
                            mode = if (abs(total.x) >= abs(total.y)) DragMode.SEEK
                            else if (start.x < size.width / 2f) DragMode.BRIGHTNESS else DragMode.VOLUME
                        }
                        when (mode) {
                            DragMode.SEEK -> {
                                val mediaDuration = latestDuration().coerceAtLeast(0L)
                                if (mediaDuration > 0L) {
                                    val seekRange = max(60_000L, mediaDuration / 4L)
                                    val target = (startPosition + total.x / size.width.coerceAtLeast(1) * seekRange).toLong().coerceIn(0L, mediaDuration)
                                    latestSeek(target)
                                }
                            }
                            DragMode.BRIGHTNESS -> latestBrightnessChange((startBrightness - total.y / size.height.coerceAtLeast(1)).coerceIn(0.05f, 1f))
                            DragMode.VOLUME -> latestVolumeChange((startVolume - total.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f))
                            null -> Unit
                        }
                        change.consume()
                    },
                )
            },
    )
}

@Composable
private fun MediaError(message: String, onOpenExternal: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, color = Color.White, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onOpenExternal) { Text(stringResource(R.string.file_open_external)) }
    }
}

private fun resolveBrightness(context: Context, windowBrightness: Float): Float {
    if (windowBrightness in 0f..1f) return windowBrightness.coerceAtLeast(0.05f)
    return runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
    }.getOrDefault(0.5f).coerceIn(0.05f, 1f)
}

private fun setSystemBarsHidden(activity: Activity?, hidden: Boolean) {
    val window = activity?.window ?: return
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    if (hidden) {
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    } else {
        controller.show(WindowInsetsCompat.Type.systemBars())
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun formatDuration(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, seconds)
    else "%02d:%02d".format(Locale.ROOT, minutes, seconds)
}

private fun formatSpeed(speed: Float): String = if (speed % 1f == 0f) "${speed.toInt()}.0×" else "${speed}×"
