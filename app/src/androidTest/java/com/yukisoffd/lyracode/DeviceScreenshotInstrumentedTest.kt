package com.yukisoffd.lyracode

import android.app.UiAutomation
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Base64
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.interaction.fixture.DeviceTextFixtureActivity
import com.yukisoffd.lyracode.interaction.perception.DeviceScreenshotSource
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses only the synthetic fixture, never uploads a screenshot or reads a user's app. */
@RunWith(AndroidJUnit4::class)
class DeviceScreenshotInstrumentedTest {
    @Test fun capturesOnlyTargetWindowAndRejectsSecureWindow() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val resolver = instrumentation.targetContext.contentResolver
        val key = android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        val original = android.provider.Settings.Secure.getString(resolver, key).orEmpty()
        val component = "${instrumentation.targetContext.packageName}/com.yukisoffd.lyracode.interaction.service.LyraAccessibilityService"
        assertTrue("Enable Lyra accessibility before running screenshot integration tests", component in original.split(':'))
        automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
        try {
            // Instrumentation force-stops the target process, leaving this already-enabled service
            // in Android's crashed set. Rebind it without changing any other enabled service.
            android.provider.Settings.Secure.putString(resolver, key, original.split(':').filterNot { it == component }.joinToString(":"))
            SystemClock.sleep(350)
        } finally {
            android.provider.Settings.Secure.putString(resolver, key, original)
            automation.dropShellPermissionIdentity()
        }
        ActivityScenario.launch(DeviceTextFixtureActivity::class.java).use { scenario ->
            withTimeout(8000) { while (DeviceScreenshotSource.service == null) delay(100) }
            SystemClock.sleep(600)
            val root = automation.rootInActiveWindow
            assertEquals(instrumentation.targetContext.packageName, root.packageName.toString())
            val windowId = root.windowId
            val frame = withTimeout(5000) { DeviceScreenshotSource.capture(windowId) }
            val bytes = Base64.decode(frame.dataUrl.substringAfter(','), Base64.NO_WRAP)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertNotNull(bitmap)
            assertEquals(bitmap.width, frame.width)
            assertEquals(bitmap.height, frame.height)
            assertTrue(frame.width > 0 && frame.height > 0)
            bitmap.recycle()
            scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            SystemClock.sleep(1500)
            val denied = runCatching { withTimeout(5000) { DeviceScreenshotSource.capture(windowId) } }
            assertTrue("Secure windows must never have a screenshot fallback", denied.isFailure)
            assertFalse(denied.exceptionOrNull() is TimeoutCancellationException)
        }
    }
}
