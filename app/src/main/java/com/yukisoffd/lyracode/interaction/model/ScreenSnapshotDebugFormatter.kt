package com.yukisoffd.lyracode.interaction.model

internal fun ScreenSnapshot.toDebugText(maxNodes: Int = 80): String = buildString {
    appendLine("snapshot_id=$snapshotId")
    appendLine("captured_at_ms=$capturedAtEpochMillis")
    appendLine("active_package=${activePackage ?: "<none>"}")
    appendLine("active_window_id=${activeWindowId ?: -1}")
    appendLine("display=${display.displayId} ${display.widthPixels}x${display.heightPixels} rotation=${display.rotation}")
    appendLine("ui_fingerprint=$uiFingerprint")
    appendLine("windows=${windows.size} nodes=${nodes.size} truncated=$truncated")
    windows.forEach { window ->
        append("window id=${window.windowId} display=${window.displayId} type=${window.type.name}")
        append(" layer=${window.layer} bounds=${window.bounds.toDebugString()}")
        append(" active=${window.active} focused=${window.focused}")
        window.title?.let { append(" title=\"").append(it).append('"') }
        appendLine()
    }
    nodes.take(maxNodes.coerceAtLeast(0)).forEach { node ->
        append("  ".repeat(node.depth.coerceIn(0, 16)))
        append(node.handle).append(" role=").append(node.role)
        append(" bounds=").append(node.bounds.toDebugString())
        node.resourceId?.let { append(" id=").append(it) }
        node.text?.let { append(" text=\"").append(it).append('"') }
        node.contentDescription?.let { append(" description=\"").append(it).append('"') }
        if (node.actions.isNotEmpty()) {
            append(" actions=").append(node.actions.sortedBy { it.name }.joinToString(",") { it.name })
        }
        val flags = buildList {
            if (!node.enabled) add("disabled")
            if (!node.visible) add("hidden")
            if (node.editable) add("editable")
            if (node.clickable) add("clickable")
            if (node.scrollable) add("scrollable")
            if (node.checked) add("checked")
            if (node.selected) add("selected")
            if (node.redacted) add("redacted")
        }
        if (flags.isNotEmpty()) append(" flags=").append(flags.joinToString(","))
        appendLine()
    }
    if (nodes.size > maxNodes) appendLine("... ${nodes.size - maxNodes} more nodes not shown")
}

private fun ScreenBounds.toDebugString(): String = "[$left,$top][$right,$bottom]"
