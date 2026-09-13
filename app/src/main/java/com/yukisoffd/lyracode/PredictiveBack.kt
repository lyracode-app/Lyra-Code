package com.yukisoffd.lyracode

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Stable
internal class PredictiveBackGestureState {
    var progress by mutableFloatStateOf(0f)
        private set
    private var swipeEdge by mutableIntStateOf(BackEventCompat.EDGE_LEFT)

    val direction: Float
        get() = if (swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f

    val isInProgress: Boolean
        get() = progress > 0f

    fun update(event: BackEventCompat) {
        val next = event.progress.coerceIn(0f, 1f)
        progress = if (next >= progress) {
            progress + (next - progress) * 0.72f
        } else {
            next
        }
        swipeEdge = event.swipeEdge
    }

    suspend fun complete() {
        if (progress >= 1f) return
        val duration = (90f + 160f * (1f - progress)).roundToInt().coerceIn(90, 220)
        animate(
            initialValue = progress,
            targetValue = 1f,
            animationSpec = tween(durationMillis = duration, easing = FastOutSlowInEasing),
        ) { value, _ -> progress = value }
    }

    suspend fun cancel() {
        animate(progress, 0f) { value, _ -> progress = value }
    }

    fun reset() {
        progress = 0f
    }
}

@Composable
internal fun rememberPredictiveBackGestureState(
    enabled: Boolean,
    retainCompletedState: Boolean = false,
    onBack: () -> Unit,
): PredictiveBackGestureState {
    val state = remember { PredictiveBackGestureState() }
    LaunchedEffect(enabled) {
        if (!enabled) state.reset()
    }
    PredictiveBackHandler(enabled = enabled) { events ->
        var committed = false
        try {
            events.collect(state::update)
            state.complete()
            committed = true
            onBack()
        } catch (_: CancellationException) {
            withContext(NonCancellable) { state.cancel() }
        } finally {
            if (committed && !retainCompletedState) state.reset()
        }
    }
    return state
}

internal fun Modifier.predictiveBackTransform(state: PredictiveBackGestureState): Modifier =
    graphicsLayer {
        val progress = state.progress
        val visualProgress = progress * (0.65f + 0.35f * progress)
        translationX = size.width * visualProgress * state.direction
        shadowElevation = 12.dp.toPx() * progress
    }
