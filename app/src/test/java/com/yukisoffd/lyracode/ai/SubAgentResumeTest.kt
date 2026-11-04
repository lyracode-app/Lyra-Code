package com.yukisoffd.lyracode.ai

import com.yukisoffd.lyracode.data.OpenSubAgentBatch
import com.yukisoffd.lyracode.data.SubAgentTaskRecord
import com.yukisoffd.lyracode.data.SubAgentTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentResumeTest {
    @Test
    fun `a completed task is reused only for the same explicit agent`() {
        val stored = listOf(row(0, "research", child = 300, status = SubAgentTaskStatus.COMPLETED).copy(preferredAgent = "agent-a"))
        fun plan(agent: String) = SubAgentResume.plan(
            batchId = "batch-agent",
            storedUserMessageId = 1,
            currentUserMessageId = 1,
            stored = stored,
            requested = listOf(SubAgentResume.RequestedTask(0, "research", true, preferredAgent = agent)),
            reusableChildIds = setOf(300),
        )

        assertEquals(SubAgentResume.Action.Reuse(300), plan("agent-a")?.actions?.get(0))
        // The selector also trims and lowercases the requested agent using Locale.US.
        assertEquals(SubAgentResume.Action.Reuse(300), plan(" AGENT-A ")?.actions?.get(0))
        assertNull(plan("agent-b"))
        assertNull(plan(""))
    }

    @Test
    fun `a batch interrupted after task k reuses the finished tasks and runs the rest`() {
        val stored = listOf(
            row(0, "a", child = 101, status = SubAgentTaskStatus.COMPLETED),
            row(1, "b", child = 102, status = SubAgentTaskStatus.COMPLETED),
            row(2, "c", child = 103, status = SubAgentTaskStatus.COMPLETED),
            row(3, "d", child = 104, status = SubAgentTaskStatus.RUNNING),
            row(4, "e"),
            row(5, "f"),
        )

        val plan = SubAgentResume.plan(
            batchId = "batch-1",
            storedUserMessageId = 1,
            currentUserMessageId = 1,
            stored = stored,
            requested = requested("a" to true, "b" to true, "c" to true, "d" to true, "e" to true, "f" to true),
            reusableChildIds = setOf(101, 102, 103),
        )!!

        assertEquals(listOf(0, 1, 2), plan.reusedIndices)
        assertEquals(listOf(3, 4, 5), plan.runIndices)
        assertEquals(SubAgentResume.Action.Reuse(101), plan.actions[0])
        assertEquals(SubAgentResume.Action.Run, plan.actions[3])
    }

    @Test
    fun `a finished child whose result never reached the parent is reused`() {
        val stored = listOf(row(0, "research", child = 300, status = SubAgentTaskStatus.RUNNING))

        val plan = SubAgentResume.plan(
            batchId = "batch-2",
            storedUserMessageId = 1,
            currentUserMessageId = 1,
            stored = stored,
            requested = requested("research" to true),
            reusableChildIds = setOf(300),
        )!!

        assertEquals(SubAgentResume.Action.Reuse(300), plan.actions[0])
        assertTrue(plan.runIndices.isEmpty())
    }

    @Test
    fun `an interrupted write task is reported with its paths and backups, not run again`() {
        val stored = listOf(
            row(0, "edit", writePaths = listOf("src/A.kt"), child = 204, status = SubAgentTaskStatus.RUNNING),
            row(1, "read", child = 205, status = SubAgentTaskStatus.RUNNING),
        )

        val plan = SubAgentResume.plan(
            batchId = "batch-3",
            storedUserMessageId = 1,
            currentUserMessageId = 1,
            stored = stored,
            requested = listOf(
                SubAgentResume.RequestedTask(0, "edit", false, listOf("src/A.kt")),
                SubAgentResume.RequestedTask(1, "read", true),
            ),
            reusableChildIds = setOf(205),
            backupPaths = setOf("src/A.kt.bak"),
        )!!

        val interrupted = plan.actions[0] as SubAgentResume.Action.InterruptedWrite
        assertEquals(listOf("src/A.kt"), interrupted.writePaths)
        assertEquals(listOf("src/A.kt.bak"), interrupted.backupPaths)
        assertEquals(SubAgentResume.Action.Reuse(205), plan.actions[1])
        assertEquals(listOf(0), plan.interruptedIndices)
        assertTrue(plan.runIndices.isEmpty())
    }

    @Test
    fun `an interrupted write task with no backup still names its paths`() {
        val stored = listOf(
            row(0, "edit", writePaths = listOf("src/A.kt", "src/B.kt"), child = 204, status = SubAgentTaskStatus.RUNNING),
        )

        val plan = SubAgentResume.plan(
            batchId = "batch-4",
            storedUserMessageId = 1,
            currentUserMessageId = 1,
            stored = stored,
            requested = requested("edit" to false, writePaths = listOf("src/A.kt", "src/B.kt")),
            backupPaths = setOf("src/B.kt.bak"),
        )!!

        val interrupted = plan.actions[0] as SubAgentResume.Action.InterruptedWrite
        assertEquals(listOf("src/A.kt", "src/B.kt"), interrupted.writePaths)
        assertEquals(listOf("src/B.kt.bak"), interrupted.backupPaths)
    }

    @Test
    fun `an interrupted write reports the never-replaced copy before the ordinary backup`() {
        val stored = listOf(
            row(0, "edit", writePaths = listOf("src/A.kt"), child = 204, status = SubAgentTaskStatus.RUNNING),
        )

        val plan = SubAgentResume.plan(
            batchId = "batch-6",
            storedUserMessageId = 1,
            currentUserMessageId = 1,
            stored = stored,
            requested = requested("edit" to false, writePaths = listOf("src/A.kt")),
            // Both exist: a later write replaced the ordinary backup, while the preserved copy
            // of this batch still holds the state from before the interrupted write.
            backupPaths = setOf("src/A.kt.bak", "src/A.kt.batch6.interrupted.bak"),
        )!!

        val interrupted = plan.actions[0] as SubAgentResume.Action.InterruptedWrite
        assertEquals(listOf("src/A.kt.batch6.interrupted.bak", "src/A.kt.bak"), interrupted.backupPaths)
    }

    @Test
    fun `a write task that never started is safe to run`() {
        val stored = listOf(row(0, "edit", writePaths = listOf("src/A.kt"), status = SubAgentTaskStatus.PENDING))

        val plan = SubAgentResume.plan(
            batchId = "batch-5",
            storedUserMessageId = 1,
            currentUserMessageId = 1,
            stored = stored,
            requested = requested("edit" to false, writePaths = listOf("src/A.kt")),
        )!!

        assertEquals(SubAgentResume.Action.Run, plan.actions[0])
    }

    @Test
    fun `a completed write task is reused without touching its paths`() {
        val stored = listOf(
            row(0, "edit", writePaths = listOf("src/A.kt"), child = 401, status = SubAgentTaskStatus.COMPLETED),
        )

        val plan = SubAgentResume.plan(
            batchId = "batch-6",
            storedUserMessageId = 1,
            currentUserMessageId = 1,
            stored = stored,
            requested = requested("edit" to false, writePaths = listOf("src/A.kt")),
            reusableChildIds = setOf(401),
        )!!

        assertEquals(SubAgentResume.Action.Reuse(401), plan.actions[0])
    }

    @Test
    fun `a completed write task whose child is gone is interrupted, not reused with an empty result`() {
        val stored = listOf(
            row(0, "edit", writePaths = listOf("src/A.kt"), child = 401, status = SubAgentTaskStatus.COMPLETED),
        )

        val plan = SubAgentResume.plan(
            batchId = "batch-6b",
            storedUserMessageId = 1,
            currentUserMessageId = 1,
            stored = stored,
            requested = requested("edit" to false, writePaths = listOf("src/A.kt")),
        )!!

        assertEquals(SubAgentResume.Action.InterruptedWrite(listOf("src/A.kt"), emptyList()), plan.actions[0])
    }

    @Test
    fun `a child killed before it finished is not reusable, so its write is reported instead`() {
        val stored = listOf(
            row(0, "edit", writePaths = listOf("src/A.kt"), child = 501, status = SubAgentTaskStatus.RUNNING),
        )

        val plan = SubAgentResume.plan(
            batchId = "batch-6c",
            storedUserMessageId = 1,
            currentUserMessageId = 1,
            stored = stored,
            requested = requested("edit" to false, writePaths = listOf("src/A.kt")),
            // The old childProducedResult would have put this id here for idle + assistant.
            reusableChildIds = emptySet(),
            backupPaths = setOf("src/A.kt.bak"),
        )!!

        assertEquals(
            SubAgentResume.Action.InterruptedWrite(listOf("src/A.kt"), listOf("src/A.kt.bak")),
            plan.actions[0],
        )
    }

    @Test
    fun `a child that is idle with a non-blank assistant answer has finished`() {
        assertTrue(SubAgentResume.childFinished("idle", "assistant", "the answer"))
    }

    @Test
    fun `a child that is running with partial text has not finished`() {
        assertEquals(false, SubAgentResume.childFinished("running", "assistant", "half an ans"))
    }

    @Test
    fun `a child killed before its first token has not finished`() {
        assertEquals(false, SubAgentResume.childFinished("running", "assistant", ""))
    }

    @Test
    fun `a child whose last message is a tool result has not finished`() {
        assertEquals(false, SubAgentResume.childFinished("idle", "tool", "tool output"))
    }

    @Test
    fun `a child that is idle but blank has not finished`() {
        assertEquals(false, SubAgentResume.childFinished("idle", "assistant", " "))
        assertEquals(false, SubAgentResume.childFinished("idle", "assistant", null))
    }

    @Test
    fun `a child whose conversation is gone has not finished`() {
        assertEquals(false, SubAgentResume.childFinished(null, null, null))
    }

    @Test
    fun `a child interrupted when its run was cancelled has not finished`() {
        assertEquals(false, SubAgentResume.childFinished("interrupted", "assistant", "part"))
    }

    @Test
    fun `different task text does not resume the stored batch`() {
        val stored = listOf(row(0, "old plan", child = 501, status = SubAgentTaskStatus.COMPLETED))

        assertNull(
            SubAgentResume.plan(
                batchId = "batch-7",
                storedUserMessageId = 1,
                currentUserMessageId = 1,
                stored = stored,
                requested = requested("new plan" to true),
            ),
        )
    }

    @Test
    fun `a different task count does not resume the stored batch`() {
        val stored = listOf(row(0, "a", child = 501, status = SubAgentTaskStatus.COMPLETED))

        assertNull(
            SubAgentResume.plan(
                batchId = "batch-8",
                storedUserMessageId = 1,
                currentUserMessageId = 1,
                stored = stored,
                requested = requested("a" to true, "b" to true),
            ),
        )
    }

    @Test
    fun `a different user message does not resume the stored batch`() {
        val stored = listOf(row(0, "a", child = 501, status = SubAgentTaskStatus.COMPLETED))

        assertNull(
            SubAgentResume.plan(
                batchId = "batch-9",
                storedUserMessageId = 11,
                currentUserMessageId = 12,
                stored = stored,
                requested = requested("a" to true),
                reusableChildIds = setOf(501),
            ),
        )
    }

    @Test
    fun `a different read-only flag does not resume the stored batch`() {
        assertNull(
            SubAgentResume.plan(
                batchId = "batch-10",
                storedUserMessageId = 1,
                currentUserMessageId = 1,
                stored = listOf(row(0, "t", readOnly = true)),
                requested = requested("t" to false, writePaths = listOf("src/A.kt")),
            ),
        )
    }

    @Test
    fun `different write paths do not resume the stored batch`() {
        assertNull(
            SubAgentResume.plan(
                batchId = "batch-11",
                storedUserMessageId = 1,
                currentUserMessageId = 1,
                stored = listOf(row(0, "t", readOnly = false, writePaths = listOf("src/A.kt"))),
                requested = requested("t" to false, writePaths = listOf("src/B.kt")),
            ),
        )
    }

    @Test
    fun `a different expected output does not resume the stored batch`() {
        assertNull(
            SubAgentResume.plan(
                batchId = "batch-12",
                storedUserMessageId = 1,
                currentUserMessageId = 1,
                stored = listOf(row(0, "t", expectedOutput = "a diff")),
                requested = requested("t" to true, expectedOutput = "a patch"),
            ),
        )
    }

    @Test
    fun `a different capability hint does not resume the stored batch`() {
        assertNull(
            SubAgentResume.plan(
                batchId = "batch-13",
                storedUserMessageId = 1,
                currentUserMessageId = 1,
                stored = listOf(row(0, "t", capabilityHint = "frontend")),
                requested = requested("t" to true, capabilityHint = "backend"),
            ),
        )
    }

    @Test
    fun `the same task with the same fields resumes`() {
        val stored = listOf(
            row(
                0,
                "t",
                readOnly = false,
                writePaths = listOf("src/A.kt"),
                expectedOutput = "a patch",
                capabilityHint = "backend",
                child = 7,
                status = SubAgentTaskStatus.COMPLETED,
            ),
        )

        val plan = SubAgentResume.plan(
            batchId = "batch-14",
            storedUserMessageId = 1,
            currentUserMessageId = 1,
            stored = stored,
            requested = requested(
                "t" to false,
                writePaths = listOf("src/A.kt"),
                expectedOutput = "a patch",
                capabilityHint = "backend",
            ),
            reusableChildIds = setOf(7),
        )!!

        assertEquals(SubAgentResume.Action.Reuse(7), plan.actions[0])
    }

    @Test
    fun `a same-turn retry of the same tasks under a new call id resumes the batch`() {
        val batch = openBatch(toolCallId = "call-1")

        val selected = SubAgentResume.selectResume(batch, 1, "call-2", null, batchPlan(batch))

        assertEquals(SubAgentResume.Action.Reuse(300), selected?.actions?.get(0))
        assertEquals("call-2", SubAgentResume.repointToolCallId(batch, "call-2", null))
        // The same call needs no re-point, and a different turn or task list never resumes.
        assertNull(SubAgentResume.repointToolCallId(batch, "call-1", null))
        assertNull(SubAgentResume.selectResume(batch, 2, "call-2", null, batchPlan(batch, currentUserMessageId = 2)))
        assertNull(SubAgentResume.selectResume(batch, 1, "call-2", null, batchPlan(batch, task = "other")))
    }

    @Test
    fun `a batch saved without a call id resumes on a same-turn retry but never on a replay`() {
        val batch = openBatch(toolCallId = "")

        assertEquals(SubAgentResume.Action.Reuse(300), SubAgentResume.selectResume(batch, 1, "call-2", null, batchPlan(batch))?.actions?.get(0))
        assertEquals("call-2", SubAgentResume.repointToolCallId(batch, "call-2", null))
        assertNull(SubAgentResume.selectResume(batch, 1, "call-2", "batch-r", batchPlan(batch)))
        assertNull(SubAgentResume.selectResume(batch, 1, "", "batch-r", batchPlan(batch)))
    }

    @Test
    fun `a Continue replay resumes only through the batch's own saved call`() {
        val batch = openBatch(toolCallId = "call-1")

        assertEquals("batch-r", SubAgentResume.selectResume(batch, 1, "call-1", "batch-r", batchPlan(batch))?.batchId)
        assertNull(SubAgentResume.selectResume(batch, 1, "call-2", "batch-r", batchPlan(batch)))
        assertNull(SubAgentResume.selectResume(batch, 1, "call-1", "batch-other", batchPlan(batch)))
        assertNull(SubAgentResume.selectResume(batch, 2, "call-1", "batch-r", batchPlan(batch, currentUserMessageId = 2)))
        // A replay never re-points the batch.
        assertNull(SubAgentResume.repointToolCallId(batch, "call-2", "batch-r"))
    }

    @Test
    fun `after a re-point a Continue replay matches the new call and not the old one`() {
        val before = openBatch(toolCallId = "call-1")
        val newId = SubAgentResume.repointToolCallId(before, "call-2", null)!!
        val after = before.copy(toolCallId = newId)

        assertEquals("call-2", after.toolCallId)
        assertEquals("batch-r", SubAgentResume.selectResume(after, 1, "call-2", "batch-r", batchPlan(after))?.batchId)
        assertNull(SubAgentResume.selectResume(after, 1, "call-1", "batch-r", batchPlan(after)))
    }

    private fun openBatch(toolCallId: String) = OpenSubAgentBatch(
        batchId = "batch-r",
        userMessageId = 1,
        tasks = listOf(row(0, "research", child = 300, status = SubAgentTaskStatus.RUNNING)),
        toolCallId = toolCallId,
    )

    private fun batchPlan(batch: OpenSubAgentBatch, currentUserMessageId: Long = 1, task: String = "research") =
        SubAgentResume.plan(
            batchId = batch.batchId,
            storedUserMessageId = batch.userMessageId,
            currentUserMessageId = currentUserMessageId,
            stored = batch.tasks,
            requested = requested(task to true),
            reusableChildIds = setOf(300),
        )

    @Test
    fun `the resume duration text names seconds minutes hours and days`() {
        assertEquals("0 seconds", SubAgentResume.durationText(0))
        assertEquals("1 second", SubAgentResume.durationText(1))
        assertEquals("45 seconds", SubAgentResume.durationText(45))
        assertEquals("1 minute", SubAgentResume.durationText(60))
        assertEquals("12 minutes", SubAgentResume.durationText(12 * 60 + 59))
        assertEquals("1 hour", SubAgentResume.durationText(3_600))
        assertEquals("5 hours", SubAgentResume.durationText(5 * 3_600 + 1_799))
        assertEquals("1 day", SubAgentResume.durationText(86_400))
        assertEquals("3 days", SubAgentResume.durationText(3 * 86_400 + 7_200))
    }

    @Test
    fun `the resume timing comes from the batch rows read before the re-point`() {
        // 2026-11-03T10:00:00Z and two minutes later; the re-point runs at the clock reading.
        val firstWrite = 1_793_700_000_000L
        val lastWrite = firstWrite + 120_000L
        val batch = OpenSubAgentBatch(
            batchId = "batch-time",
            userMessageId = 1,
            tasks = listOf(
                row(0, "read", child = 300, status = SubAgentTaskStatus.COMPLETED).copy(updatedAt = firstWrite),
                row(1, "write", listOf("a.txt"), child = 301, status = SubAgentTaskStatus.RUNNING)
                    .copy(updatedAt = lastWrite),
            ),
        )
        val repointAt = lastWrite + 600_500L
        val timing = requireNotNull(SubAgentResume.resumeTiming(SubAgentResume.interruptedAtMillis(batch), repointAt))
        assertEquals("2026-11-03T10:02:00Z", timing.interruptedAtIso)
        assertEquals(600L, timing.secondsAgo)
        assertEquals("10 minutes", timing.agoText)
    }

    @Test
    fun `every resume carries the timing note with no threshold`() {
        val at = 1_793_700_000_000L
        val timing = requireNotNull(SubAgentResume.resumeTiming(at, at))
        assertEquals("2026-11-03T10:00:00Z", timing.interruptedAtIso)
        assertEquals(0L, timing.secondsAgo)
        assertEquals(
            "The batch was interrupted at 2026-11-03T10:00:00Z, about 0 seconds ago. Reused task results date " +
                "from before the interruption and may be stale; re-check the write paths and any outside state " +
                "before relying on them.",
            SubAgentResume.resumeTimingNote(timing),
        )
        assertEquals(
            mapOf<String, Any>("interrupted_at" to "2026-11-03T10:00:00Z", "interrupted_seconds_ago" to 0L),
            SubAgentResume.resumeTimingFields(timing),
        )
    }

    @Test
    fun `an unknown interruption time omits the timing fields and says the time is unknown`() {
        val now = 1_793_700_000_000L
        val noRows = OpenSubAgentBatch(batchId = "batch-empty", userMessageId = 1, tasks = emptyList())
        val zeroRow = OpenSubAgentBatch(
            batchId = "batch-zero",
            userMessageId = 1,
            tasks = listOf(row(0, "read", child = 300, status = SubAgentTaskStatus.COMPLETED)),
        )
        assertNull(SubAgentResume.interruptedAtMillis(noRows))
        assertNull(SubAgentResume.interruptedAtMillis(zeroRow))
        assertNull(SubAgentResume.resumeTiming(SubAgentResume.interruptedAtMillis(zeroRow), now))
        assertNull(SubAgentResume.resumeTiming(-5L, now))
        assertEquals(emptyMap<String, Any>(), SubAgentResume.resumeTimingFields(null))
        assertEquals(
            "The interruption time is unknown; reused task results may be stale; re-check the write paths " +
                "and any outside state before relying on them.",
            SubAgentResume.resumeTimingNote(null),
        )
    }

    private fun row(
        index: Int,
        text: String,
        writePaths: List<String> = emptyList(),
        readOnly: Boolean = writePaths.isEmpty(),
        expectedOutput: String = "",
        capabilityHint: String = "",
        child: Long = 0L,
        status: String = SubAgentTaskStatus.PENDING,
    ) = SubAgentTaskRecord(
        index = index,
        taskText = text,
        writePaths = writePaths,
        readOnly = readOnly,
        expectedOutput = expectedOutput,
        capabilityHint = capabilityHint,
        childConversationId = child,
        status = status,
    )

    private fun requested(
        vararg tasks: Pair<String, Boolean>,
        writePaths: List<String> = emptyList(),
        expectedOutput: String = "",
        capabilityHint: String = "",
    ): List<SubAgentResume.RequestedTask> =
        tasks.mapIndexed { index, (text, readOnly) ->
            SubAgentResume.RequestedTask(index, text, readOnly, writePaths, expectedOutput, capabilityHint)
        }
}
