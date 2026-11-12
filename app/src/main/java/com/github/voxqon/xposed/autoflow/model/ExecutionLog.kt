package com.github.voxqon.xposed.autoflow.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class ExecutionStatus(val label: String) {
    SUCCESS("成功"), FAILED("失败"), SKIPPED("已跳过"), SUBMITTED("已提交");
}

data class ExecutionStep(
    val name: String,
    val status: ExecutionStatus,
    val detail: String = "",
)

data class ExecutionLog(
    val id: String = UUID.randomUUID().toString(),
    val ruleId: String,
    val ruleName: String,
    val phase: String,
    val reason: String,
    val startedAt: Long = System.currentTimeMillis(),
    val durationMs: Long = 0,
    val steps: List<ExecutionStep>,
) {
    val status: ExecutionStatus
        get() = when {
            steps.any { it.status == ExecutionStatus.FAILED } -> ExecutionStatus.FAILED
            steps.any { it.status == ExecutionStatus.SUBMITTED } -> ExecutionStatus.SUBMITTED
            steps.any { it.status == ExecutionStatus.SKIPPED } -> ExecutionStatus.SKIPPED
            else -> ExecutionStatus.SUCCESS
        }

    fun toJson(): String = JSONObject()
        .put("id", id).put("ruleId", ruleId.take(128)).put("ruleName", ruleName.take(256))
        .put("phase", phase.take(100)).put("reason", reason.take(500))
        .put("startedAt", startedAt).put("durationMs", durationMs)
        .put("steps", JSONArray().apply {
            steps.take(100).forEach {
                put(JSONObject().put("name", it.name.take(256)).put("status", it.status.name)
                    .put("detail", it.detail.take(1000)))
            }
        }).toString()

    companion object {
        fun fromJson(json: String): ExecutionLog {
            require(json.length <= 200_000) { "Execution log is too large" }
            val o = JSONObject(json)
            val id = o.getString("id")
            require(id.length in 1..128)
            val array = o.getJSONArray("steps")
            require(array.length() <= 100)
            return ExecutionLog(
                id = id, ruleId = o.getString("ruleId"), ruleName = o.getString("ruleName"),
                phase = o.getString("phase"), reason = o.getString("reason"),
                startedAt = o.getLong("startedAt"), durationMs = o.getLong("durationMs"),
                steps = (0 until array.length()).map {
                    val step = array.getJSONObject(it)
                    ExecutionStep(step.getString("name"),
                        ExecutionStatus.valueOf(step.getString("status")), step.getString("detail"))
                },
            )
        }
    }
}
