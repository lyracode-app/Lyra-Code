package com.yukisoffd.lyracode

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.PinnableContainer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity

/** Pin only recently visible rich blocks. Plain history remains lazy and each chat owns its pins. */
internal class RichPreviewPins {
    private class Pin(val handle: PinnableContainer.PinnedHandle, val owners: MutableSet<Any> = mutableSetOf())
    private val pins = LinkedHashMap<PinnableContainer, Pin>()

    fun visit(owner: Any, item: PinnableContainer) {
        val pin = pins.remove(item) ?: Pin(item.pin())
        pin.owners.add(owner)
        pins[item] = pin
        if (pins.size > 4) pins.remove(pins.keys.first())?.handle?.release()
    }

    fun forget(owner: Any, item: PinnableContainer) {
        val pin = pins[item] ?: return
        if (pin.owners.remove(owner) && pin.owners.isEmpty()) pins.remove(item)?.handle?.release()
    }
}

internal val LocalRichPreviewPins = staticCompositionLocalOf<RichPreviewPins?> { null }
internal val LocalPreviewVisible = compositionLocalOf { true }

@Composable
internal fun RichPreviewItem(content: @Composable () -> Unit) {
    val pins = LocalRichPreviewPins.current
    val item = LocalPinnableContainer.current
    if (pins == null || item == null) {
        content()
        return
    }
    val owner = remember(pins, item) { Any() }
    var visible by remember(owner) { mutableStateOf(false) }
    DisposableEffect(owner) { onDispose { pins.forget(owner, item) } }
    Box(Modifier.onGloballyPositioned {
        val nowVisible = !it.boundsInWindow().isEmpty
        if (nowVisible && !visible) pins.visit(owner, item)
        visible = nowVisible
    }) {
        CompositionLocalProvider(LocalPreviewVisible provides visible, content = content)
    }
}

/** Recycled previews reserve their measured space only while their async content is loading. */
@Composable
internal fun previewLoadingHeight(identity: Any, loading: Boolean): Modifier {
    val density = LocalDensity.current
    var pixels by rememberSaveable(identity, LocalConfiguration.current.screenWidthDp, density.density) {
        mutableIntStateOf(0)
    }
    return if (loading) Modifier.heightIn(min = with(density) { pixels.toDp() })
    else Modifier.onSizeChanged { pixels = it.height }
}
