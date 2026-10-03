package com.yukisoffd.lyracode

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.yukisoffd.lyracode.data.AppSettings
import kotlin.math.roundToInt

private val ReasoningViolet = Color(0xFF9175FF)
private val ReasoningBlue = Color(0xFF679BFF)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReasoningDepthSelector(
    selectedDepth: String,
    modelName: String,
    onDepthSelected: (String) -> Unit,
) {
    val options = AppSettings.reasoningDepthValues
    var position by remember(selectedDepth) {
        mutableFloatStateOf(options.indexOf(selectedDepth).coerceAtLeast(0).toFloat())
    }
    val level = position.roundToInt().coerceIn(options.indices)
    val label = reasoningDepthLabel(options[level])
    val englishLabel = reasoningDepthEnglishLabel(options[level])
    val bilingual = reasoningDepthLabel(AppSettings.REASONING_AUTO) in listOf("自动", "自動")
    val title = stringResource(R.string.label_reasoning_depth)
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val accent = if (dark) ReasoningViolet else Color(0xFF6D50D5)
    val intensity = animateFloatAsState(level.toFloat() / options.lastIndex, tween(280), label = "reasoning-intensity")
    // Auto has no animation clock. The phase is read only by drawing modifiers,
    // so the moving light never recomposes the sheet or its text every frame.
    // This Compose version only refreshes infinite animations when their endpoints
    // change. Key by the live preview level so a new duration applies during dragging.
    val phase = key(level) {
        if (level > 0) {
            rememberInfiniteTransition(label = "reasoning-flow").animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(durationMillis = 5200 - level * 820, easing = LinearEasing),
                ),
                label = "reasoning-flow-phase",
            )
        } else {
            remember { mutableFloatStateOf(0f) }
        }
    }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (modelName.isNotBlank()) {
                Text(
                    modelName,
                    Modifier.weight(1f),
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Default.Lightbulb, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
        }
        Text(
            if (bilingual) "$label · $englishLabel" else label,
            color = colors.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Slider(
            value = position,
            onValueChange = { position = it },
            onValueChangeFinished = {
                onDepthSelected(options[position.roundToInt().coerceIn(options.indices)])
            },
            valueRange = 0f..options.lastIndex.toFloat(),
            steps = options.size - 2,
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = title
                stateDescription = label
            },
            thumb = {
                Box(
                    Modifier.size(34.dp).drawWithCache {
                        val fill = Brush.verticalGradient(listOf(Color.White, Color(0xFFE4E9FF)))
                        val radius = CornerRadius(8.dp.toPx())
                        onDrawBehind { drawRoundRect(fill, cornerRadius = radius) }
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.SwapHoriz, contentDescription = null, tint = Color(0xFF655D89), modifier = Modifier.size(19.dp))
                }
            },
            track = { state ->
                Box(
                    Modifier.fillMaxWidth().height(34.dp).drawWithCache {
                        val radius = CornerRadius(size.height / 2f)
                        val outline = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, radius)) }
                        val inactive = Brush.verticalGradient(listOf(colors.surfaceVariant, colors.surfaceVariant.copy(alpha = 0.5f)))
                        val fill = Brush.horizontalGradient(listOf(ReasoningViolet, ReasoningBlue, Color(0xFFA1ABFF)))
                        val sheen = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.24f), Color.Transparent, accent.copy(alpha = 0.20f)))
                        // Cache each streak's shader; only its translation changes per frame.
                        val streaks = List(level * 4) { index ->
                            val length = (12 + index % 4 * 7 + level.toFloat() / options.lastIndex * 18).dp.toPx()
                            val thickness = (0.8f + index % 3 * 0.35f).dp.toPx()
                            val trailColors = listOf(Color.White.copy(alpha = 0.9f), Color.White.copy(alpha = 0.3f), Color.Transparent)
                            Triple(length, thickness, Brush.horizontalGradient(
                                if (rtl) trailColors.reversed() else trailColors,
                                startX = 0f,
                                endX = length,
                            ))
                        }
                        onDrawBehind {
                            val power = intensity.value
                            val fraction = (state.value / options.lastIndex).coerceIn(0f, 1f)
                            val activeWidth = size.width * fraction
                            drawRoundRect(accent.copy(alpha = power * 0.10f), cornerRadius = radius, style = Stroke((3 + power * 5).dp.toPx()))
                            drawRoundRect(inactive, cornerRadius = radius)
                            withTransform({ if (rtl) scale(-1f, 1f) }) {
                                clipPath(outline) {
                                    clipRect(right = activeWidth) {
                                        drawRect(fill, alpha = 0.65f + power * 0.35f)
                                        drawRect(sheen)
                                        if (level > 0 && activeWidth > 0f) {
                                            val travel = phase.value
                                            streaks.forEachIndexed { index, (length, thickness, tail) ->
                                                val progress = (travel * (1 + index % 2) + index * 0.618034f) % 1f
                                                // Keep the light moving physically right-to-left,
                                                // including when the slider's track is mirrored for RTL.
                                                val x = if (rtl) {
                                                    progress * (activeWidth + length) - length
                                                } else {
                                                    activeWidth - progress * (activeWidth + length)
                                                }
                                                val y = size.height * (0.19f + (index * 0.271828f % 1f) * 0.62f)
                                                translate(left = x, top = y) {
                                                    drawRoundRect(tail, Offset(0f, -thickness * 2), Size(length, thickness * 5), CornerRadius(thickness * 3), alpha = 0.08f + power * 0.10f)
                                                    drawRoundRect(tail, size = Size(length, thickness), cornerRadius = CornerRadius(thickness), alpha = 0.4f + power * 0.6f)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            drawRoundRect(lerp(colors.outlineVariant, Color.White, power * 0.65f).copy(alpha = 0.6f), cornerRadius = radius, style = Stroke(1.dp.toPx()))
                        }
                    },
                )
            },
        )
        Row(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                val selected = index == level
                Column(
                    Modifier.weight(1f).heightIn(min = 48.dp)
                        .selectable(selected = selected, role = Role.RadioButton) {
                            position = index.toFloat()
                            onDepthSelected(option)
                        }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    val labelColor = if (selected) accent else colors.onSurfaceVariant
                    Text(reasoningDepthLabel(option), color = labelColor, style = MaterialTheme.typography.labelSmall, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                    if (bilingual) {
                        Text(reasoningDepthEnglishLabel(option), color = labelColor.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("$level", color = accent, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("/ ${options.lastIndex}", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Row(Modifier.clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                repeat(options.lastIndex) { index ->
                    Box(
                        Modifier.size(10.dp).drawWithCache {
                            val radius = CornerRadius(3.dp.toPx())
                            onDrawBehind {
                                val lit = index < level
                                if (lit) drawRoundRect(accent.copy(alpha = 0.10f + intensity.value * 0.12f), cornerRadius = radius, style = Stroke(4.dp.toPx()))
                                drawRoundRect(if (lit) lerp(ReasoningViolet, if (dark) Color.White else ReasoningBlue, intensity.value * 0.75f) else colors.outlineVariant, cornerRadius = radius)
                            }
                        },
                    )
                }
            }
        }
        Text(stringResource(R.string.reasoning_depth_hint), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}
