package com.yukisoffd.lyracode.browser

import org.junit.Assert.*
import org.junit.Test

class CustomTabBrowserPolicyTest {
    @Test fun capableDefaultWinsEvenWhenItIsNotAllowlisted() {
        val capable = setOf("com.example.browser", "com.android.chrome", "com.microsoft.emmx")
        val probed = mutableListOf<String>()
        val selected = customTabBrowserCandidates("com.example.browser") { probed.add(it); it in capable }.first()
        assertEquals("com.example.browser", selected)
        assertEquals(listOf("com.example.browser"), probed)
    }

    @Test fun unsupportedDefaultUsesChromeThenEdgeThenFirefox() {
        val capable = setOf("org.mozilla.firefox", "com.microsoft.emmx", "com.android.chrome")
        assertEquals(listOf("com.android.chrome", "com.microsoft.emmx", "org.mozilla.firefox"),
            customTabBrowserCandidates("com.example.unsupported", capable::contains).toList())
        assertEquals("com.microsoft.emmx",
            customTabBrowserCandidates("com.example.unsupported") { it in capable && it != "com.android.chrome" }.first())
        assertEquals("org.mozilla.firefox",
            customTabBrowserCandidates("com.example.unsupported") { it == "org.mozilla.firefox" }.first())
    }

    @Test fun capableFirefoxDefaultStaysAheadOfChromeAndIsNotRetriedTwice() {
        val capable = setOf("org.mozilla.firefox", "com.android.chrome")
        assertEquals(listOf("org.mozilla.firefox", "com.android.chrome"),
            customTabBrowserCandidates("org.mozilla.firefox", capable::contains).toList())
    }

    @Test fun noCapableProviderLeavesBrowserResolutionToAndroid() {
        assertTrue(customTabBrowserCandidates("com.example.thirdparty") { false }.none())
        assertTrue(customTabBrowserCandidates(null) { false }.none())
        assertTrue(customTabBrowserCandidates("android") { false }.none())
    }

    @Test fun unknownNonDefaultProviderIsNotForcedWithoutAnAllowlistMatch() {
        assertTrue(customTabBrowserCandidates("com.example.unsupported") { it == "com.example.unknown" }.none())
    }

    @Test fun missingDefaultCanStillUseAnInstalledAllowlistedBrowser() {
        assertEquals("com.android.chrome", customTabBrowserCandidates(null) { it == "com.android.chrome" }.first())
    }
}
