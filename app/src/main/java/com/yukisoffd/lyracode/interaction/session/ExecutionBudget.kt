package com.yukisoffd.lyracode.interaction.session

/** Monotonic task budget, independent of provider retries and UI state. */
internal class ExecutionBudget(
    private val now: () -> Long,
    private val maxRounds: Int = 128,
    private val maxActions: Int = 256,
    private val timeoutMillis: Long = 0L,
) {
    private val startedAt = now()
    private var rounds = 0
    private var actions = 0
    private val attempts = mutableMapOf<String, Int>()
    fun checkTime() { check(timeoutMillis <= 0L || now() - startedAt < timeoutMillis) { "任务已超时，请重新发起。" } }
    fun round() { checkTime(); check(++rounds <= maxRounds) { "已达到模型轮次上限。" } }
    fun action(key: String) {
        checkTime()
        check(++actions <= maxActions) { "已达到动作步数上限。" }
        val count = (attempts[key] ?: 0) + 1
        attempts[key] = count
        check(count <= 2) { "同一页面上的重复动作已停止。" }
    }
}
