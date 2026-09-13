package com.yukisoffd.lyracode.interaction

import com.yukisoffd.lyracode.interaction.agent.DeviceInteractionToolProvider
import com.yukisoffd.lyracode.interaction.model.*
import com.yukisoffd.lyracode.interaction.session.*
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class DeviceAgentSessionTest {
    @After fun cleanup() = ManualControlController.stop()

    @Test fun budgetStopsTimeRoundsActionsAndRepeatedActions() {
        var now = 0L
        val time = ExecutionBudget({ now }, timeoutMillis = 100)
        now = 100
        assertThrows(IllegalStateException::class.java) { time.checkTime() }
        val rounds = ExecutionBudget({ 0 }, maxRounds = 1)
        rounds.round()
        assertThrows(IllegalStateException::class.java) { rounds.round() }
        val actions = ExecutionBudget({ 0 }, maxActions = 1)
        actions.action("first")
        assertThrows(IllegalStateException::class.java) { actions.action("second") }
        val repeats = ExecutionBudget({ 0 })
        repeats.action("same"); repeats.action("same")
        assertThrows(IllegalStateException::class.java) { repeats.action("same") }
    }

    @Test fun onlyScopedSemanticToolsAreAdvertised() {
        val provider = start()
        val definitions = provider.definitions()
        val names = (0 until definitions.length()).map { definitions.getJSONObject(it).getJSONObject("function").getString("name") }
        assertEquals(setOf("device_observe", "device_activate", "device_scroll", "device_set_text", "device_wait_for_change", "device_finish"), names.toSet())
        assertFalse(definitions.toString().contains("coordinate"))
        assertFalse(definitions.toString().contains("run_command"))
    }

    @Test fun geminiSchemasUseTheExistingProviderDialect() {
        val tools = start().definitions()
        for (index in 0 until tools.length()) {
            val source = tools.getJSONObject(index).getJSONObject("function").getJSONObject("parameters")
            val schema = com.yukisoffd.lyracode.ai.toGeminiSchema(source)
            assertEquals("OBJECT", schema.getString("type"))
            assertFalse(schema.has("additionalProperties"))
            val properties = schema.getJSONObject("properties")
            properties.keys().forEach { name -> assertEquals("STRING", properties.getJSONObject(name).getString("type")) }
        }
    }

    @Test fun observationIsBoundedAndMarkedUntrusted() = runBlocking {
        val provider = start()
        ManualControlController.publish(snapshot("two", nodes = List(90) { node("two:$it").copy(text = "x".repeat(500)) }))
        val result = JSONObject(provider.execute("device_observe", JSONObject()))
        assertEquals("UNTRUSTED_SCREEN_CONTENT", result.getString("classification"))
        assertEquals(64, result.getJSONArray("nodes").length())
        assertEquals(160, result.getJSONArray("nodes").getJSONObject(0).getString("text").length)
        assertTrue(result.getBoolean("truncated"))
    }

    @Test fun sensitiveFieldIsRedactedWithoutEndingObservation() = runBlocking {
        val provider = start()
        ManualControlController.publish(snapshot("secret", nodes = listOf(node("secret").copy(password = true))))
        val observation = JSONObject(provider.execute("device_observe", JSONObject()))
        assertEquals("[已遮蔽]", observation.getJSONArray("nodes").getJSONObject(0).getString("text"))
        assertEquals(0, observation.getJSONArray("nodes").getJSONObject(0).getJSONArray("actions").length())
        assertTrue(ManualControlController.state.value.chat.running)
    }

    @Test fun actionWaitsForExactConfirmationAndVerifiedResult() = runBlocking {
        val provider = start()
        provider.execute("device_observe", JSONObject())
        val result = async { provider.execute("device_activate", arguments()) }
        withTimeout(2000) {
            while (ManualControlController.state.value.selection == null) delay(10)
        }
        assertFalse(result.isCompleted)
        assertEquals(ManualControlStatus.TARGET_SELECTED, ManualControlController.state.value.status)
        assertNull(ManualControlController.beginExecution("obsolete", "first:0"))
        val (selection, before) = ManualControlController.beginExecution("first", "first:0")!!
        var calls = 0
        assertTrue(ManualControlController.dispatchIfCurrent(selection) { calls++; true })
        ManualControlController.markVerifying()
        ManualControlController.finish(DeviceActionResult(DeviceActionStatus.SUCCEEDED, selection.action,
            "example.app", "example.app", selection.elementHandle, "node_action", before.uiFingerprint, "after", requestId = selection.requestId), snapshot("after"))
        assertTrue(withTimeout(2000) { result.await() }.contains("SUCCEEDED"))
        assertEquals(1, calls)
    }

    @Test fun stopInvalidatesAlreadyQueuedActionAndLateResult() {
        start()
        ManualControlController.select("first:0", ManualDeviceAction.ACTIVATE)
        val (selection, before) = ManualControlController.beginExecution()!!
        ManualControlController.stop()
        assertFalse(ManualControlController.dispatchIfCurrent(selection) { fail("Action ran after stop"); true })
        ManualControlController.finish(DeviceActionResult(DeviceActionStatus.SUCCEEDED, selection.action,
            "example.app", "example.app", selection.elementHandle, "node_action", before.uiFingerprint))
        assertEquals(ManualControlStatus.CANCELLED, ManualControlController.state.value.status)
        assertNull(ManualControlController.state.value.latestSnapshot)
    }

    @Test fun pauseInvalidatesQueuedAction() {
        start()
        ManualControlController.select("first:0", ManualDeviceAction.ACTIVATE)
        val selection = ManualControlController.beginExecution()!!.first
        ManualControlController.clearSelection()
        assertFalse(ManualControlController.dispatchIfCurrent(selection) { fail("Action ran after pause"); true })
    }

    @Test fun oldRequestCannotApproveOrCompleteReselectedSameNode() {
        start()
        ManualControlController.select("first:0", ManualDeviceAction.ACTIVATE)
        val old = ManualControlController.beginExecution()!!.first
        ManualControlController.clearSelection()
        ManualControlController.select("first:0", ManualDeviceAction.ACTIVATE)
        val fresh = ManualControlController.state.value.selection!!
        assertNotEquals(old.requestId, fresh.requestId)
        assertNull(ManualControlController.beginExecution("first", "first:0", old.requestId))
        ManualControlController.rejectSelection("first", "first:0", old.requestId)
        assertEquals(fresh, ManualControlController.state.value.selection)
        ManualControlController.beginExecution("first", "first:0", fresh.requestId)
        assertFalse(ManualControlController.dispatchIfCurrent(old) { fail("Old queued action dispatched"); true })
        ManualControlController.finish(DeviceActionResult(DeviceActionStatus.SUCCEEDED, old.action,
            "example.app", "example.app", old.elementHandle, "node_action", "first", requestId = old.requestId))
        assertEquals(fresh, ManualControlController.state.value.selection)
    }

    @Test fun appSwitchContinuesButNeverRestoresOldHandles() {
        start()
        ManualControlController.publish(snapshot("other", "other.app"))
        ManualControlController.publish(snapshot("back", "example.app"))
        assertEquals(ManualControlStatus.READY, ManualControlController.state.value.status)
        assertEquals("back", ManualControlController.state.value.latestSnapshot?.snapshotId)
        assertTrue(ManualControlController.state.value.chat.running)
        assertFalse(ManualControlController.select("first:0", ManualDeviceAction.ACTIVATE))
    }

    @Test fun staleRejectionDoesNotRejectNewTarget() {
        start()
        ManualControlController.select("first:0", ManualDeviceAction.ACTIVATE)
        ManualControlController.rejectSelection("obsolete", "first:0")
        assertNotNull(ManualControlController.state.value.selection)
        ManualControlController.rejectSelection("first", "first:0")
        assertNull(ManualControlController.state.value.selection)
    }

    @Test fun rejectionStopsToolRatherThanAuthorizingRepeat() = runBlocking {
        val provider = start()
        provider.execute("device_observe", JSONObject())
        val action = async {
            runCatching { provider.execute("device_activate", arguments()) }
        }
        withTimeout(2000) { while (ManualControlController.state.value.selection == null) delay(10) }
        ManualControlController.rejectSelection("first", "first:0")
        assertTrue(withTimeout(2000) { action.await() }.getOrThrow().contains("USER_DECLINED"))
        assertTrue(ManualControlController.state.value.chat.running)
    }

    @Test fun genericToolsAreRejectedAndFinishClosesDeviceCapabilities() = runBlocking {
        val provider = start()
        assertEquals("ERROR: TOOL_NOT_AVAILABLE_IN_DEVICE_SESSION", provider.execute("run_command", JSONObject()))
        provider.execute("device_finish", JSONObject().put("summary", "已完成观察"))
        assertTrue(provider.finished)
        assertTrue(runCatching { provider.execute("device_observe", JSONObject()) }.isFailure)
    }

    @Test fun safeClickDispatchesWithoutUserApprovalAndKeepsVerification() = runBlocking {
        val provider = start()
        var dispatched = 0
        com.yukisoffd.lyracode.interaction.service.ManualControlCommandBridge.attach { id, handle, token ->
            val (selection, before) = ManualControlController.beginExecution(id, handle, token)!!
            assertTrue(ManualControlController.dispatchIfCurrent(selection) { dispatched++; true })
            ManualControlController.finish(DeviceActionResult(DeviceActionStatus.SUCCEEDED, selection.action,
                "example.app", "example.app", handle, "node_action", before.uiFingerprint, "after", requestId = selection.requestId), snapshot("after"))
        }
        try {
            provider.execute("device_observe", JSONObject())
            assertTrue(withTimeout(2000) { provider.execute("device_activate", arguments()) }.contains("SUCCEEDED"))
            assertEquals(1, dispatched)
        } finally { com.yukisoffd.lyracode.interaction.service.ManualControlCommandBridge.detach() }
    }

    @Test fun financialActionSoftBlocksAndNextObservationStillWorks() = runBlocking {
        val provider = start()
        ManualControlController.publish(snapshot("financial", nodes = listOf(node("financial:0").copy(text = "确认转账"))))
        provider.execute("device_observe", JSONObject())
        val result = JSONObject(withTimeout(2000) { provider.execute("device_activate",
            JSONObject().put("snapshot_id", "financial").put("element_handle", "financial:0")) })
        assertEquals("FINANCIAL_OPERATION", result.getString("code"))
        assertTrue(result.getBoolean("conversation_continues"))
        assertNull(ManualControlController.state.value.selection)
        assertFalse(provider.finished)
        assertTrue(provider.execute("device_observe", JSONObject()).contains("UNTRUSTED_SCREEN_CONTENT"))
    }

    @Test fun clearContextKeepsOverlaySessionAndInvalidatesQueuedAction() {
        start()
        val session = ManualControlController.state.value.sessionId
        ManualControlController.select("first:0", ManualDeviceAction.ACTIVATE)
        val selection = ManualControlController.beginExecution()!!.first
        DeviceTaskCoordinator.clearContext()
        assertTrue(ManualControlController.state.value.isActive())
        assertEquals(session, ManualControlController.state.value.sessionId)
        assertTrue(ManualControlController.state.value.chat.messages.isEmpty())
        assertFalse(ManualControlController.state.value.chat.running)
        assertFalse(ManualControlController.dispatchIfCurrent(selection) { error("Cleared action dispatched") })
    }

    @Test fun financeNavigationAllowedButPurchaseAndTransactionConfirmationBlocked() {
        val policy = com.yukisoffd.lyracode.interaction.policy.DeviceActionPolicy
        fun allowed(node: SemanticNode) = policy.evaluate(node.packageName!!, node, ManualDeviceAction.ACTIVATE) is
            com.yukisoffd.lyracode.interaction.policy.DevicePolicyDecision.Allowed
        assertTrue(DeviceTaskCoordinator.isAgentPackageAllowed("com.example.bank.trading"))
        assertTrue(allowed(node("icon").copy(packageName = "com.android.launcher", text = "支付宝")))
        assertTrue(allowed(node("search").copy(packageName = "com.example.bank", resourceId = "com.example.bank:id/search", text = "搜索")))
        assertFalse(allowed(node("buy").copy(text = "买入")))
        val confirm = node("confirm").copy(text = "确认")
        assertTrue(policy.evaluate("example.app", confirm, ManualDeviceAction.ACTIVATE,
            listOf(confirm, node("title").copy(text = "转账金额"))) is com.yukisoffd.lyracode.interaction.policy.DevicePolicyDecision.Blocked)
    }

    private fun start(): DeviceInteractionToolProvider {
        ManualControlController.start(durationMillis = 30_000)
        ManualControlController.publish(snapshot("first"))
        ManualControlController.updateChat(DeviceChatState(running = true), "example.app")
        return DeviceInteractionToolProvider(123, "example.app", ManualControlController.state.value.activeUntilEpochMillis,
            ExecutionBudget({ 0 }), {}, {}, {})
    }
    private fun arguments() = JSONObject().put("snapshot_id", "first").put("element_handle", "first:0")
    private fun snapshot(id: String, pkg: String = "example.app", nodes: List<SemanticNode> = listOf(node("$id:0"))) =
        ScreenSnapshot(id, System.currentTimeMillis(), ScreenDisplay(0, 0, 1080, 1920), pkg, 1,
            emptyList(), nodes, id, false)
    private fun node(handle: String) = SemanticNode(handle, 1, null, 0, "button", "Button", "example.app",
        "Open details", null, "example.app:id/details", ScreenBounds(10, 10, 100, 100), setOf(SemanticAction.ACTIVATE),
        true, true, false, true, false, false, true, false, false, false, false, false, false)
}
