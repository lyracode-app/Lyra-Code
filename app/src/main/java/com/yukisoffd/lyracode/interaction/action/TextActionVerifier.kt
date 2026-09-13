package com.yukisoffd.lyracode.interaction.action

import com.yukisoffd.lyracode.interaction.model.*
import com.yukisoffd.lyracode.interaction.policy.DeviceActionPolicy
import com.yukisoffd.lyracode.interaction.policy.DevicePolicyDecision
import com.yukisoffd.lyracode.interaction.policy.TextInputPolicy

internal object TextActionVerifier {
    fun verify(selection: ManualActionSelection, before: ScreenSnapshot, after: ScreenSnapshot): DeviceActionStatus {
        if (after.activePackage != selection.expectedPackage) return DeviceActionStatus.PACKAGE_CHANGED
        if (before.display != after.display) return DeviceActionStatus.STALE
        val expected = before.nodes.singleOrNull { it.handle == selection.elementHandle } ?: return DeviceActionStatus.STALE
        val text = selection.inputText?.takeIf(TextInputPolicy::isValidText) ?: return DeviceActionStatus.BLOCKED
        val candidates = after.nodes.filter {
            it.windowId == expected.windowId && it.packageName == selection.expectedPackage &&
                it.className == expected.className &&
                if (expected.resourceId != null) it.resourceId == expected.resourceId else it.bounds == expected.bounds
        }
        if (candidates.isEmpty()) return DeviceActionStatus.STALE
        val actual = candidates.singleOrNull() ?: return DeviceActionStatus.AMBIGUOUS
        if (DeviceActionPolicy.evaluate(selection.expectedPackage, actual, ManualDeviceAction.SET_TEXT) !is DevicePolicyDecision.Allowed)
            return DeviceActionStatus.BLOCKED
        return if (actual.textFingerprint == TextInputPolicy.fingerprint(text)) DeviceActionStatus.SUCCEEDED
            else DeviceActionStatus.TEXT_MISMATCH
    }
}
