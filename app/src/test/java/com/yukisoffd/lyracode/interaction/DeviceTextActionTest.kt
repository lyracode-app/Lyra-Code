package com.yukisoffd.lyracode.interaction

import com.yukisoffd.lyracode.interaction.action.TextActionVerifier
import com.yukisoffd.lyracode.interaction.agent.DeviceInteractionToolProvider
import com.yukisoffd.lyracode.interaction.model.*
import com.yukisoffd.lyracode.interaction.policy.*
import com.yukisoffd.lyracode.interaction.session.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class DeviceTextActionTest {
    @After fun cleanup() = ManualControlController.stop()

    @Test fun payloadIsBoundedAndPreviewCannotHideControlCharacters() {
        for (text in listOf(null, "", "x".repeat(501), "abc\u0000", "abc\u202e", "abc\u2066"))
            assertFalse(TextInputPolicy.isValidText(text))
        assertTrue(TextInputPolicy.isValidText("  第一行\n第二行\t  "))
        assertTrue(TextInputPolicy.isValidText("x".repeat(500)))
    }

    @Test fun policyRequiresEditableOrdinaryAdvertisedNonSensitiveField() {
        assertEquals(DevicePolicyDecision.Allowed, policy(node()))
        for (blocked in listOf(
            node().copy(editable = false), node().copy(enabled = false), node().copy(visible = false),
            node().copy(actions = emptySet()), node().copy(textFingerprint = null),
            node().copy(inputType = 0), node().copy(inputType = 2), // Unknown and numeric input are not ordinary text.
            node().copy(inputType = 0x81), node().copy(inputType = 0x91), node().copy(inputType = 0xe1),
            node().copy(password = true), node().copy(accessibilityDataSensitive = true),
            node().copy(hintText = "请输入验证码"), node().copy(resourceId = "example.app:id/api_key"),
            node().copy(contentDescription = "Payment amount"),
            node().copy(packageName = "other.app"),
        )) assertTrue("Policy accepted unsafe metadata: ${blocked.hintText}", policy(blocked) is DevicePolicyDecision.Blocked)
        // The value of a draft is data, not the action label.
        assertEquals(DevicePolicyDecision.Allowed, policy(node("Do not send this draft")))
        assertEquals(DevicePolicyDecision.Allowed, policy(node().copy(hintText = "提交表单")))
    }

    @Test fun completeTextFingerprintIncludesWhitespaceAndSuffixOutsidePreview() {
        assertNotEquals(TextInputPolicy.fingerprint("a b"), TextInputPolicy.fingerprint("a  b"))
        assertNotEquals(TextInputPolicy.fingerprint("a".repeat(120) + "b"), TextInputPolicy.fingerprint("a".repeat(120) + "c"))
        val before = snapshot("before", node())
        val text = "a".repeat(400)
        val after = snapshot("after", node(text))
        assertEquals(DeviceActionStatus.SUCCEEDED, TextActionVerifier.verify(selection(before, text), before, after))
        assertEquals(DeviceActionStatus.TEXT_MISMATCH,
            TextActionVerifier.verify(selection(before, text + "b"), before, after))
    }

    @Test fun unrelatedPageChangeCannotProveTextWasWritten() {
        val before = snapshot("before", node())
        assertEquals(DeviceActionStatus.TEXT_MISMATCH,
            TextActionVerifier.verify(selection(before, "draft"), before, snapshot("unrelated_change", node())))
        // Replacing a field with its existing exact value is verified without claiming page progress.
        assertEquals(DeviceActionStatus.SUCCEEDED,
            TextActionVerifier.verify(selection(before, "old"), before, before))
    }

    @Test fun verificationRejectsMissingAmbiguousForeignAndSensitiveTargets() {
        val before = snapshot("before", node())
        val selection = selection(before, "draft")
        val after = snapshot("after", node("draft"))
        assertEquals(DeviceActionStatus.PACKAGE_CHANGED, TextActionVerifier.verify(selection, before, after.copy(activePackage = "other.app")))
        assertEquals(DeviceActionStatus.STALE, TextActionVerifier.verify(selection, before, after.copy(nodes = emptyList())))
        assertEquals(DeviceActionStatus.AMBIGUOUS, TextActionVerifier.verify(selection, before, after.copy(nodes = after.nodes + after.nodes)))
        assertEquals(DeviceActionStatus.BLOCKED, TextActionVerifier.verify(selection, before, snapshot("after", node("draft").copy(redacted = true))))
        assertEquals(DeviceActionStatus.STALE, TextActionVerifier.verify(selection, before, after.copy(display = after.display.copy(rotation = 1))))
    }

    @Test fun textIsBoundToSingleUseApprovalAndOldRequestCannotAuthorizeNewText() {
        start()
        assertTrue(ManualControlController.selectAgentTarget("before", "field", ManualDeviceAction.SET_TEXT, "first draft"))
        val old = ManualControlController.state.value.selection!!
        assertTrue(ManualControlController.selectAgentTarget("before", "field", ManualDeviceAction.SET_TEXT, "second draft"))
        val current = ManualControlController.state.value.selection!!
        assertNotEquals(old.requestId, current.requestId)
        assertNull(ManualControlController.beginExecution("before", "field", old.requestId))
        assertEquals("second draft", ManualControlController.beginExecution("before", "field", current.requestId)!!.first.inputText)
        assertNull(ManualControlController.beginExecution("before", "field", current.requestId))
        assertFalse(current.toString().contains("second draft"))
        ManualControlController.stop()
        assertFalse(ManualControlController.dispatchIfCurrent(current) { fail("Text dispatched after stop"); true })
    }

    @Test fun invalidPayloadBlockedButUnrelatedSensitiveFieldDoesNotBlockDraft() {
        start()
        assertFalse(ManualControlController.select("field", ManualDeviceAction.SET_TEXT))
        assertFalse(ManualControlController.select("field", ManualDeviceAction.SET_TEXT, "x".repeat(501)))
        ManualControlController.publish(snapshot("sensitive", node()).let { it.copy(nodes = it.nodes + node().copy(handle = "secret", password = true)) })
        assertTrue(ManualControlController.select("field", ManualDeviceAction.SET_TEXT, "draft"))
        assertNotNull(ManualControlController.state.value.selection)
    }

    @Test fun providerWaitsForConfirmedExactTextAndReportsVerifiedResult() = runBlocking {
        val provider = start()
        provider.execute("device_observe", JSONObject())
        val text = "保留空格  与换行\n普通草稿"
        val action = async { provider.execute("device_set_text", args(text)) }
        withTimeout(2000) { while (ManualControlController.state.value.selection == null) delay(10) }
        assertFalse(action.isCompleted)
        val selection = ManualControlController.state.value.selection!!
        assertEquals(text, selection.inputText)
        val before = ManualControlController.beginExecution("before", "field", selection.requestId)!!.second
        ManualControlController.finish(DeviceActionResult(DeviceActionStatus.SUCCEEDED, ManualDeviceAction.SET_TEXT,
            "example.app", "example.app", "field", "node_action:2097152", before.uiFingerprint,
            "after", selection.requestId), snapshot("after", node(text)))
        val result = JSONObject(withTimeout(2000) { action.await() })
        assertTrue(result.getBoolean("text_verified"))
        assertFalse(result.toString().contains(text))
    }

    @Test fun providerRejectsOversizeAndRejectedDraftDoesNotExecute() = runBlocking {
        val provider = start()
        provider.execute("device_observe", JSONObject())
        assertTrue(provider.execute("device_set_text", args("x".repeat(501))).startsWith("ERROR:"))
        assertNull(ManualControlController.state.value.selection)
        val action = async { runCatching { provider.execute("device_set_text", args("draft")) } }
        withTimeout(2000) { while (ManualControlController.state.value.selection == null) delay(10) }
        val pending = ManualControlController.state.value.selection!!
        ManualControlController.rejectSelection(pending.snapshotId, pending.elementHandle, pending.requestId)
        assertTrue(withTimeout(2000) { action.await() }.getOrThrow().contains("USER_DECLINED"))
        assertNull(ManualControlController.beginExecution("before", "field", pending.requestId))
    }

    private fun args(text: String) = JSONObject().put("snapshot_id", "before").put("element_handle", "field").put("text", text)
    private fun start(): DeviceInteractionToolProvider {
        ManualControlController.start(durationMillis = 30_000)
        ManualControlController.publish(snapshot("before", node()))
        ManualControlController.updateChat(DeviceChatState(running = true), "example.app")
        return DeviceInteractionToolProvider(1, "example.app", ManualControlController.state.value.activeUntilEpochMillis,
            ExecutionBudget({ 0 }), {}, {}, {})
    }
    private fun policy(node: SemanticNode) = DeviceActionPolicy.evaluate("example.app", node, ManualDeviceAction.SET_TEXT)
    private fun selection(before: ScreenSnapshot, text: String) = ManualActionSelection(before.snapshotId, "field", ManualDeviceAction.SET_TEXT, "example.app", inputText = text)
    private fun snapshot(id: String, node: SemanticNode) = ScreenSnapshot(id, System.currentTimeMillis(), ScreenDisplay(0, 0, 1080, 1920),
        "example.app", 1, emptyList(), listOf(node), id, false)
    private fun node(text: String = "old") = SemanticNode("field", 1, null, 0, "text_input", "EditText", "example.app",
        text.take(120), null, "example.app:id/draft", ScreenBounds(10, 10, 300, 100), setOf(SemanticAction.SET_TEXT),
        true, true, true, true, false, false, true, false, false, false, false, false, false,
        hintText = "普通草稿", inputType = 1, textFingerprint = TextInputPolicy.fingerprint(text))
}
