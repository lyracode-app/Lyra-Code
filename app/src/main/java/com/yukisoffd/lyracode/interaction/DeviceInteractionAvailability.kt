package com.yukisoffd.lyracode.interaction

import android.os.Build

internal object DeviceInteractionAvailability {
    const val MIN_SUPPORTED_SDK = 35

    fun isSupported(): Boolean = isSupportedSdk(Build.VERSION.SDK_INT)

    fun isSupportedSdk(sdkInt: Int): Boolean = sdkInt >= MIN_SUPPORTED_SDK
}
