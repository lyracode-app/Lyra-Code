package com.yukisoffd.lyracode.interaction

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceInteractionAvailabilityTest {
    @Test
    fun experimentIsLimitedToAndroid15AndNewer() {
        assertFalse(DeviceInteractionAvailability.isSupportedSdk(34))
        assertTrue(DeviceInteractionAvailability.isSupportedSdk(35))
        assertTrue(DeviceInteractionAvailability.isSupportedSdk(37))
    }
}
