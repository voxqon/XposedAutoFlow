package com.github.voxqon.xposed.autoflow.backup

import com.github.voxqon.xposed.autoflow.model.Rule
import com.github.voxqon.xposed.autoflow.model.RuleJson
import com.github.voxqon.xposed.autoflow.model.ConditionType
import com.github.voxqon.xposed.autoflow.model.ScheduleMode
import com.github.voxqon.xposed.autoflow.model.ALL_DAYS_MASK
import com.github.voxqon.xposed.autoflow.model.MAX_ACTION_DELAY_SECONDS
import com.github.voxqon.xposed.autoflow.store.ExecutionLogStore
import org.json.JSONArray
import org.json.JSONObject

data class BackupData(val rules: List<Rule>, val logLimit: Int)

object BackupCodec {
    const val MAX_BYTES = 5 * 1024 * 1024
    private const val FORMAT = "autotasklite.backup"

    fun encode(data: BackupData): String = JSONObject().put("format", FORMAT)
        .put("version", 1).put("createdAt", System.currentTimeMillis())
        .put("rules", JSONArray(RuleJson.rulesToJson(data.rules)))
        .put("logLimit", data.logLimit).toString(2)

    fun decode(json: String): BackupData {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "备份文件超过 5 MiB" }
        val o = JSONObject(json)
        require(o.getString("format") == FORMAT && integer(o, "version") == 1L) {
            "不支持的备份格式或版本"
        }
        val limit = integer(o, "logLimit")
        require(limit in 0L..ExecutionLogStore.MAX_LIMIT.toLong()) { "备份中的日志数量无效" }
        val array = o.getJSONArray("rules")
        require(array.length() <= 1000) { "备份中的规则过多" }
        val ids = mutableSetOf<String>()
        val rules = (0 until array.length()).map { i ->
            val raw = array.getJSONObject(i)
            require(raw.get("enabled") is Boolean) { "规则状态无效" }
            val rule = requireNotNull(RuleJson.ruleFromJson(raw)) { "规则内容无效" }
            require(Regex("[A-Za-z0-9_-]{1,128}").matches(rule.id) && ids.add(rule.id)) {
                "规则标识无效或重复"
            }
            listOf("conditions" to rule.conditions.size, "actions" to rule.actions.size,
                "exitConditions" to rule.exitConditions.size, "restoreActions" to rule.restoreActions.size)
                .forEach { (key, count) ->
                    require(raw.getJSONArray(key).length() == count) { "备份包含无法识别的条件或动作" }
                }
            require(raw.optString("triggerMode", "all") in setOf("all", "any")) { "条件组合方式无效" }
            require(integer(raw, "cooldownSeconds", 0L) in 0L..604800L) { "冷却时间无效" }
            require(integer(raw, "allowedDaysMask", ALL_DAYS_MASK.toLong()) in 1L..ALL_DAYS_MASK.toLong()) { "触发日期无效" }
            val scheduleMode = raw.optString("scheduleMode", "everyday")
            require(ScheduleMode.fromCode(scheduleMode) != null) { "触发日期模式无效" }
            listOf("actions", "restoreActions").forEach { key ->
                val items = raw.getJSONArray(key)
                for (index in 0 until items.length()) {
                    require(integer(items.getJSONObject(index), "delaySeconds", 0L) in 0L..MAX_ACTION_DELAY_SECONDS) {
                        "动作延迟无效"
                    }
                }
            }
            (rule.conditions + rule.exitConditions).forEach {
                require(it.type != ConditionType.GROUP ||
                    (it.children.isNotEmpty() && it.children.none { child -> child.type == ConditionType.GROUP })) {
                    "条件组为空、嵌套或包含无法识别的条件"
                }
            }
            rule
        }
        return BackupData(rules, limit.toInt())
    }

    private fun integer(json: JSONObject, key: String, default: Long? = null): Long {
        if (!json.has(key) && default != null) return default
        val value = json.opt(key)
        require(value is Number && value.toDouble().isFinite() &&
            value.toDouble() == value.toLong().toDouble()) { "备份中的 $key 必须为整数" }
        return value.toLong()
    }
}
