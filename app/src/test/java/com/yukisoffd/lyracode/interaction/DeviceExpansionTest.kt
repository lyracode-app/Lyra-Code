package com.yukisoffd.lyracode.interaction

import com.yukisoffd.lyracode.interaction.model.*
import com.yukisoffd.lyracode.interaction.session.*
import com.yukisoffd.lyracode.interaction.policy.*
import com.yukisoffd.lyracode.interaction.pet.DevicePetStore
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class DeviceExpansionTest {
    @After fun cleanup() { ManualControlController.stop() }
    @Test fun defaultSessionAndBudgetSurviveTwoMinutesAndHaveUniqueIdentity() {
        ManualControlController.start()
        val first = ManualControlController.state.value
        assertEquals(Long.MAX_VALUE, first.activeUntilEpochMillis)
        assertTrue(first.isActive(System.currentTimeMillis() + 86400000))
        var now = 0L
        val budget = ExecutionBudget({ now })
        now = 86400000; budget.checkTime(); budget.round()
        ManualControlController.start()
        assertNotEquals(first.sessionId, ManualControlController.state.value.sessionId)
    }
    @Test fun deletionNeedsTwoDistinctTokensAndCannotReplayFirstConfirmation() {
        start()
        assertTrue(ManualControlController.select("delete", ManualDeviceAction.ACTIVATE))
        val first = ManualControlController.state.value.selection!!
        assertNull(ManualControlController.beginExecution("one", "delete", first.confirmationToken))
        val second = ManualControlController.state.value.selection!!
        assertEquals(2, second.confirmationStage)
        assertNotEquals(first.confirmationToken, second.confirmationToken)
        assertNull(ManualControlController.beginExecution("one", "delete", first.confirmationToken))
        assertNull(ManualControlController.beginExecution())
        assertNotNull(ManualControlController.beginExecution("one", "delete", second.confirmationToken))
        assertNull(ManualControlController.beginExecution("one", "delete", second.confirmationToken))
    }
    @Test fun deleteConfirmationAndClearDataAreGatedByTwoStages() {
        for (label in listOf("确认删除", "Clear data", "清空回收站", "移除")) {
            val target = node().copy(text = label, resourceId = "example.app:id/action")
            assertTrue(label, DeviceActionPolicy.requiresSecondConfirmation(target, ManualDeviceAction.ACTIVATE))
            assertTrue(label, DeviceActionPolicy.evaluate("example.app", target, ManualDeviceAction.ACTIVATE) is DevicePolicyDecision.Allowed)
        }
    }
    @Test fun safeConfirmationIsAutomaticButDeleteDialogRequiresTwoStages() {
        val confirm = node().copy(text = "确定", resourceId = "example.app:id/confirm")
        assertFalse(DeviceActionPolicy.requiresSecondConfirmation(confirm, ManualDeviceAction.ACTIVATE))
        assertTrue(DeviceActionPolicy.requiresSecondConfirmation(confirm, ManualDeviceAction.ACTIVATE,
            listOf(confirm, node().copy(handle = "title", text = "是否删除此文件？"))))
    }
    @Test fun deletionCannotOverrideFinancialOrPasswordPolicy() {
        for (label in listOf("Delete and pay", "Delete password", "删除并付款", "卸载并转账", "Delete wallet", "删除钱包", "Confirm payment", "确认付款")) {
            val node = node().copy(text = label)
            assertFalse(DeviceActionPolicy.requiresSecondConfirmation(node, ManualDeviceAction.ACTIVATE))
            assertTrue(DeviceActionPolicy.evaluate("example.app", node, ManualDeviceAction.ACTIVATE) is DevicePolicyDecision.Blocked)
        }
    }
    @Test fun genericApprovalRequiresDistinctStagesAndRejectsPageChanges() = runBlocking {
        start()
        val answer = async { DeviceApprovalBroker.request("卸载", "pm uninstall --user 0 example.app", "example.app", true) }
        waitApproval()
        val first = ManualControlController.state.value.approval!!
        DeviceApprovalBroker.respond(first.id, true)
        val second = ManualControlController.state.value.approval!!
        assertEquals(2, second.stage)
        assertNotEquals(first.id, second.id)
        DeviceApprovalBroker.respond(first.id, true)
        assertFalse(answer.isCompleted)
        ManualControlController.publish(snapshot("two"))
        DeviceApprovalBroker.respond(second.id, true)
        assertFalse(withTimeout(2000) { answer.await() })
        assertNull(ManualControlController.state.value.approval)
    }
    @Test fun genericApprovalCanCompleteAndOldReplyCannotApproveNextRequest() = runBlocking {
        start()
        val answer = async { DeviceApprovalBroker.request("删除", "file.txt", "example.app", true) }
        waitApproval()
        val first = ManualControlController.state.value.approval!!.id
        DeviceApprovalBroker.respond(first, true)
        val second = ManualControlController.state.value.approval!!.id
        DeviceApprovalBroker.respond(second, true)
        assertTrue(withTimeout(2000) { answer.await() })
        val next = async { DeviceApprovalBroker.request("新操作", "other.txt", "example.app") }
        waitApproval(); DeviceApprovalBroker.respond(second, true)
        assertFalse(next.isCompleted)
        DeviceApprovalBroker.respond(ManualControlController.state.value.approval!!.id, false)
        assertFalse(withTimeout(2000) { next.await() })
    }
    @Test fun shellGrammarRejectsExpansionScriptsTextAndProtectedPackages() {
        for (command in listOf("input tap 10 20; id", "input tap 1 2\nid", "sh -c id", "su -c id", "input text password",
                "input keyevent 66", "cat /data/secret", "pm uninstall --user 0 com.android.settings",
                "input tap 1080 1", "input tap -1 0", "input swipe 0 0 1 1 999999")) {
            assertNull(command, DeviceShellPolicy.parse(command, 1080, 1920))
        }
        assertTrue(DeviceShellPolicy.parse("input tap 100 100", 1080, 1920)!!.coordinateAction)
        assertTrue(DeviceShellPolicy.parse("pm uninstall --user 0 example.app", 1080, 1920)!!.destructive)
        assertNotNull(DeviceShellPolicy.parse("monkey -p example.app -c android.intent.category.LAUNCHER 1", 1080, 1920))
    }
    @Test fun petManifestRejectsUnsupportedVersionsAndInvalidControls() {
        fun json() = JSONObject().put("apiVersion", 1).put("name", "test").put("html", "<b>pet</b>")
        assertEquals("test", DevicePetStore.validate(json().toString()).getString("name"))
        assertThrows(IllegalArgumentException::class.java) { DevicePetStore.validate(json().put("apiVersion", 2).toString()) }
        val controls = org.json.JSONArray().put(JSONObject().put("key", "speed").put("label", "Speed")
            .put("type", "range").put("min", 4).put("max", 1).put("default", 2))
        assertThrows(IllegalArgumentException::class.java) { DevicePetStore.validate(json().put("controls", controls).toString()) }
    }
    private suspend fun waitApproval() = withTimeout(2000) { while (ManualControlController.state.value.approval == null) delay(10) }
    private fun start() {
        ManualControlController.start(); ManualControlController.publish(snapshot("one"))
        ManualControlController.updateChat(DeviceChatState(running = true), "example.app")
    }
    private fun snapshot(id: String) = ScreenSnapshot(id, System.currentTimeMillis(), ScreenDisplay(0, 0, 1080, 1920),
        "example.app", 1, emptyList(), listOf(node()), id, false)
    private fun node() = SemanticNode("delete", 1, null, 0, "button", "Button", "example.app", "Delete draft", null,
        "example.app:id/delete", ScreenBounds(10, 10, 100, 100), setOf(SemanticAction.ACTIVATE),
        true, true, false, true, false, false, true, false, false, false, false, false, false)
}
