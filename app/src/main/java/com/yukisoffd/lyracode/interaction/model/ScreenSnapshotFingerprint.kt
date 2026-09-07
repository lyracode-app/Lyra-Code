package com.yukisoffd.lyracode.interaction.model

import java.security.MessageDigest

internal object ScreenSnapshotFingerprint {
    fun create(
        display: ScreenDisplay,
        activePackage: String?,
        activeWindowId: Int?,
        windows: List<SemanticWindow>,
        nodes: List<SemanticNode>,
    ): String {
        val canonical = buildString {
            append(display.displayId).append('|')
            append(display.rotation).append('|')
            append(display.widthPixels).append('x').append(display.heightPixels).append('|')
            append(activePackage.orEmpty()).append('|').append(activeWindowId ?: -1).append('\n')
            windows.forEach { window ->
                append("w|").append(window.windowId).append('|').append(window.displayId).append('|')
                append(window.type.name).append('|').append(window.layer).append('|')
                append(window.bounds.asCanonicalString()).append('|')
                append(window.active).append('|').append(window.focused).append('|')
                append(window.accessibilityFocused).append('|').append(window.title.orEmpty()).append('\n')
            }
            nodes.forEach { node ->
                // Handles contain the snapshot version, so they are intentionally excluded.
                append("n|").append(node.windowId).append('|').append(node.depth).append('|')
                append(node.role).append('|').append(node.className.orEmpty()).append('|')
                append(node.packageName.orEmpty()).append('|').append(node.text.orEmpty()).append('|')
                append(node.contentDescription.orEmpty()).append('|').append(node.resourceId.orEmpty()).append('|')
                append(node.bounds.asCanonicalString()).append('|')
                append(node.actions.sortedBy { it.name }.joinToString(",") { it.name }).append('|')
                append(node.enabled).append('|').append(node.visible).append('|').append(node.editable).append('|')
                append(node.hintText).append('|').append(node.inputType).append('|').append(node.textFingerprint).append('|')
                append(node.clickable).append('|').append(node.longClickable).append('|')
                append(node.scrollable).append('|').append(node.focusable).append('|')
                append(node.checkable).append('|').append(node.checked).append('|').append(node.selected).append('|')
                append(node.password).append('|').append(node.accessibilityDataSensitive).append('|')
                append(node.redacted).append('\n')
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(FINGERPRINT_LENGTH)
    }

    private fun ScreenBounds.asCanonicalString(): String = "$left,$top,$right,$bottom"

    private const val FINGERPRINT_LENGTH = 24
}
