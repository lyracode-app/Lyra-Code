package com.yukisoffd.lyracode

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.interaction.action.*
import com.yukisoffd.lyracode.interaction.fixture.DeviceTextFixtureActivity
import com.yukisoffd.lyracode.interaction.model.*
import com.yukisoffd.lyracode.interaction.perception.*
import com.yukisoffd.lyracode.interaction.policy.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceTextInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation

    @Test fun nativeReplacementVerifiesFullTextBeyondSnapshotPreview() = withFixture {
        val before = snapshot()
        val expected = before.nodes.single { it.resourceId?.endsWith("/device_fixture_draft") == true }
        val text = "这是用户明确确认的普通草稿。".repeat(25)
        assertTrue(text.length in 121..500)
        val resolution = resolver().resolve(expected, context.packageName, ManualDeviceAction.SET_TEXT) as NodeResolution.Resolved
        assertTrue(NativeTextAction.perform(resolution.node, text))
        instrumentation.waitForIdleSync()
        automation.waitForIdle(300, 5000)
        val after = snapshot()
        assertEquals(DeviceActionStatus.SUCCEEDED, TextActionVerifier.verify(selection(before, expected, text), before, after))
        assertEquals(text, automation.rootInActiveWindow.findAccessibilityNodeInfosByViewId(expected.resourceId!!).single().text.toString())
        assertTrue(after.nodes.single { it.resourceId == expected.resourceId }.text!!.length < text.length)
    }

    @Test fun acceptedButFilteredTextIsNotReportedAsSuccess() = withFixture {
        val before = snapshot()
        val expected = before.nodes.single { it.resourceId?.endsWith("/device_fixture_filtered") == true }
        val resolution = resolver().resolve(expected, context.packageName, ManualDeviceAction.SET_TEXT) as NodeResolution.Resolved
        assertTrue(NativeTextAction.perform(resolution.node, "abcdefghij"))
        instrumentation.waitForIdleSync()
        automation.waitForIdle(300, 5000)
        assertEquals(DeviceActionStatus.TEXT_MISMATCH,
            TextActionVerifier.verify(selection(before, expected, "abcdefghij"), before, snapshot()))
    }

    @Test fun textChangedAfterObservationCannotBeOverwrittenByOldApproval() = withFixture { activity ->
        val before = snapshot()
        val expected = before.nodes.single { it.resourceId?.endsWith("/device_fixture_draft") == true }
        activity.onActivity { it.findViewById<android.widget.EditText>(R.id.device_fixture_draft).setText("用户刚刚修改的内容") }
        automation.waitForIdle(300, 5000)
        assertEquals(NodeResolution.NotFound, resolver().resolve(expected, context.packageName, ManualDeviceAction.SET_TEXT))
    }

    @Test fun passwordOtpAndDisabledFieldsAreRejectedByNativePath() = withFixture(sensitive = true) {
        val snap = snapshot()
        for (id in listOf("device_fixture_password", "device_fixture_otp", "device_fixture_disabled")) {
            val node = snap.nodes.singleOrNull { it.resourceId?.endsWith("/$id") == true }
            assertNotNull("Missing fixture metadata for $id; retained IDs=${snap.nodes.map { it.resourceId }}", node)
            node!!
            assertTrue(DeviceActionPolicy.evaluate(context.packageName, node, ManualDeviceAction.SET_TEXT) is DevicePolicyDecision.Blocked)
            val native = automation.rootInActiveWindow.findAccessibilityNodeInfosByViewId(node.resourceId!!).single()
            assertFalse(NativeTextAction.perform(native, "test"))
        }
        assertTrue(snap.nodes.single { it.resourceId?.endsWith("/device_fixture_otp") == true }.redacted)
    }

    private fun resolver() = SemanticNodeResolver { listOfNotNull(automation.rootInActiveWindow) }
    private fun selection(before: ScreenSnapshot, node: SemanticNode, text: String) =
        ManualActionSelection(before.snapshotId, node.handle, ManualDeviceAction.SET_TEXT, context.packageName, inputText = text)
    private fun snapshot(): ScreenSnapshot = (ActionSnapshotSource(context) { automation.rootInActiveWindow }
        .capture() as SnapshotCaptureResult.Success).snapshot

    private fun withFixture(sensitive: Boolean = false, test: (ActivityScenario<DeviceTextFixtureActivity>) -> Unit) {
        val oldInfo = automation.serviceInfo
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        try {
            ActivityScenario.launch<DeviceTextFixtureActivity>(Intent(context, DeviceTextFixtureActivity::class.java)
                .putExtra("sensitive", sensitive)).use { activity ->
                automation.waitForIdle(300, 5000)
                test(activity)
            }
        } finally { automation.serviceInfo = oldInfo }
    }
}
