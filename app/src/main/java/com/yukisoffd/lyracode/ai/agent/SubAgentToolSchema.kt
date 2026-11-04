package com.yukisoffd.lyracode.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the `run_sub_agents` tool definition.
 *
 * [maxTasks] is the same configured value the runtime checks before it starts a batch, so the
 * schema advertises exactly the limit `runSubAgents` enforces.
 */
internal fun buildSubAgentFunction(maxTasks: Int): JSONObject {
    val taskProperties = JSONObject()
        .put("task", JSONObject().put("type", "string").put("description", "One independent, bounded subtask."))
        .put("capability_hint", JSONObject().put("type", "string").put("description", "Optional specialization hint used for automatic agent selection."))
        .put("expected_output", JSONObject().put("type", "string").put("description", "Evidence and result shape the parent needs for verification."))
        .put("sub_agent_id", JSONObject().put("type", "string").put("description", "Optional exact enabled sub-agent id. Omit for automatic selection."))
        .put("read_only", JSONObject().put("type", "boolean").put("description", "True when the task must not mutate workspace state."))
        .put(
            "write_paths",
            JSONObject()
                .put("type", "array")
                .put(
                    "description",
                    "Every exact workspace-relative file or directory this task may mutate. " +
                        "Empty for read-only tasks. Paths cannot overlap another task. " +
                        "Only writes are reserved: a read-only task may observe a file that another task is writing.",
                )
                .put("items", JSONObject().put("type", "string")),
        )
    val taskSchema = JSONObject()
        .put("type", "object")
        .put("properties", taskProperties)
        .put("required", JSONArray().put("task").put("read_only").put("write_paths"))
        .put("additionalProperties", false)
    return JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", "run_sub_agents")
                .put(
                    "description",
                    "Delegate independent subtasks only for complex work that benefits from separate research, review, validation, alternatives, or specialization. Mutating tasks must declare non-overlapping exact workspace paths. Sub-agents receive restricted tools, cannot delegate again, and return results for parent verification. " +
                        "A batch larger than the configured task limit is refused as a whole; split the work instead of relying on truncation.",
                )
                .put(
                    "parameters",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "tasks",
                                JSONObject()
                                    .put("type", "array")
                                    .put("minItems", 1)
                                    .put("maxItems", SubAgentLimits.normalizeTaskLimit(maxTasks))
                                    .put("items", taskSchema),
                            ),
                        )
                        .put("required", JSONArray().put("tasks"))
                        .put("additionalProperties", false),
                ),
        )
}
