package com.yukisoffd.lyracode.interaction.action

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.yukisoffd.lyracode.interaction.policy.TextInputPolicy

/** Native replacement only: no clipboard, key injection, IME submit or gesture fallback. */
internal object NativeTextAction {
    fun perform(node: AccessibilityNodeInfo, text: String): Boolean {
        if (!TextInputPolicy.isValidText(text) || !node.isEditable || node.isPassword ||
            !node.isEnabled || !node.isVisibleToUser ||
            (android.os.Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive) ||
            !TextInputPolicy.isOrdinaryText(node.inputType) ||
            TextInputPolicy.isSensitive(node.inputType, node.hintText?.toString(),
                node.contentDescription?.toString(), node.viewIdResourceName?.substringAfterLast('/'))) return false
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        })
    }
}
