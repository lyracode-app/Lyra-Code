package com.yukisoffd.lyracode

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp

/** Connected M3 buttons: 2 dp gaps, 8 dp inner corners and round selected buttons. */
@Composable
internal fun MaterialButtonGroup(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = MaterialButtonGroup(options, setOf(selected), onSelect, modifier, enabled, multiSelect = false)

@Composable
internal fun MaterialButtonGroup(
    options: List<Pair<String, String>>,
    selected: Set<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    multiSelect: Boolean = true,
) {
    if (options.isEmpty()) return
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val colors = MaterialTheme.colorScheme
    val roundCorner = (with(density) { measurer.measure("Ag", style, softWrap = false).size.height.toDp() } +
        24.dp).coerceAtLeast(48.dp) / 2
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // Keep every label readable at large font sizes; narrow groups can scroll as a unit.
        val widths = options.map { (value, label) ->
            with(density) { measurer.measure(label, style, softWrap = false).size.width.toDp() } +
                24.dp + if (value in selected) 24.dp else 0.dp
        }
        val extra = ((maxWidth - widths.fold(0.dp) { sum, width -> sum + width } -
            2.dp * (options.size - 1)) / options.size).coerceAtLeast(0.dp)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            options.forEachIndexed { index, (value, label) ->
                val checked = value in selected
                val start by animateDpAsState(
                    if (checked || index == 0) roundCorner else 8.dp, label = "groupStartCorner",
                )
                val end by animateDpAsState(
                    if (checked || index == options.lastIndex) roundCorner else 8.dp, label = "groupEndCorner",
                )
                val shape = RoundedCornerShape(topStart = start, bottomStart = start, topEnd = end, bottomEnd = end)
                val container by animateColorAsState(
                    if (!enabled) colors.onSurface.copy(alpha = 0.12f)
                    else if (checked) colors.primary else colors.secondaryContainer,
                    label = "groupContainer",
                )
                val content by animateColorAsState(
                    if (!enabled) colors.onSurface.copy(alpha = 0.38f)
                    else if (checked) colors.onPrimary else colors.onSecondaryContainer,
                    label = "groupContent",
                )
                val selectionModifier = if (multiSelect) {
                    Modifier.toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onSelect(value) })
                } else {
                    Modifier.selectable(checked, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(value) })
                }
                Surface(
                    modifier = Modifier.width(widths[index] + extra).heightIn(min = 48.dp)
                        .clip(shape).then(selectionModifier),
                    shape = shape,
                    color = container,
                    contentColor = content,
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        if (checked) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(label, style = style, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}
