package com.yukisoffd.lyracode.interaction.action

import com.yukisoffd.lyracode.interaction.model.DeviceActionStatus
import com.yukisoffd.lyracode.interaction.model.ScreenSnapshot

internal object ActionVerifier {
    fun verify(
        before: ScreenSnapshot,
        after: ScreenSnapshot,
        expectedPackage: String,
    ): DeviceActionStatus = when {
        after.activePackage != expectedPackage -> DeviceActionStatus.PACKAGE_CHANGED
        after.uiFingerprint == before.uiFingerprint -> DeviceActionStatus.NO_CHANGE
        else -> DeviceActionStatus.SUCCEEDED
    }
}
