package com.github.voxqon.xposed.autoflow.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 规则 JSON 序列化/反序列化工具。
 * 使用平台内置 org.json（boot classpath 自带，system_server / SystemUI / 普通 App 均可用），
 * 不引入第三方依赖。
 */
object RuleJson {

    /** 旧规则没有 scheduleMode 时，按 allowedDaysMask 反推日期模式以保留原语义。 */
    fun resolveScheduleMode(mask: Int): ScheduleMode = when (mask and ALL_DAYS_MASK) {
        ALL_DAYS_MASK -> ScheduleMode.EVERYDAY
        WEEKDAYS_MASK -> ScheduleMode.WEEKDAYS
        WEEKEND_MASK -> ScheduleMode.WEEKEND
        else -> ScheduleMode.CUSTOM_DAYS
    }

    private const val WEEKDAYS_MASK = 0b0011111
    private const val WEEKEND_MASK = 0b1100000

    fun conditionToJson(c: Condition): JSONObject = JSONObject()
        .put("type", c.type.code)
        .put("param", c.param ?: "")
        .put("param2", c.param2 ?: "")
        .apply { c.placeName?.let { put("placeName", it) } }
        .apply { c.appName?.let { put("appName", it) } }
        .apply {
            if (c.type == ConditionType.GROUP) {
                put("children", JSONArray().apply { c.children.forEach { put(conditionToJson(it)) } })
            }
        }

    fun conditionFromJson(o: JSONObject): Condition? {
        val t = ConditionType.fromCode(o.optString("type")) ?: return null
        if (t == ConditionType.GROUP) {
            val array = o.optJSONArray("children")
            val children = mutableListOf<Condition>()
            if (array == null || array.length() !in 1..100) return Condition(t)
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: return Condition(t)
                // Reject malformed or nested groups as a whole instead of broadening their meaning.
                if (item.optString("type") == ConditionType.GROUP.code) return Condition(t)
                children += conditionFromJson(item) ?: return Condition(t)
            }
            return Condition(t, children = children)
        }
        val p = o.optString("param").takeIf { it.isNotEmpty() }
        val p2 = o.optString("param2").takeIf { it.isNotEmpty() }
        val placeName = o.optString("placeName").takeIf { it.isNotBlank() }
        val appName = o.optString("appName").takeIf { it.isNotBlank() }
        return Condition(t, p, p2, placeName, appName)
    }

    fun actionToJson(a: Action): JSONObject = JSONObject()
        .put("type", a.type.code)
        .put("param", a.param ?: "")
        .put("param2", a.param2 ?: "")
        .put("delaySeconds", a.delaySeconds.coerceIn(0L, MAX_ACTION_DELAY_SECONDS))
        .apply { a.appName?.let { put("appName", it) } }

    fun actionFromJson(o: JSONObject): Action? {
        val t = ActionType.fromCode(o.optString("type")) ?: return null
        val p = o.optString("param").takeIf { it.isNotEmpty() }
        val p2 = o.optString("param2").takeIf { it.isNotEmpty() }
        val appName = o.optString("appName").takeIf { it.isNotBlank() }
        val delaySeconds = o.optLong("delaySeconds", 0L).coerceIn(0L, MAX_ACTION_DELAY_SECONDS)
        return Action(t, p, p2, appName, delaySeconds)
    }

    fun ruleToJson(r: Rule): JSONObject = JSONObject()
        .put("id", r.id)
        .put("name", r.name)
        .put("enabled", r.enabled)
        .put("conditions", JSONArray().apply { r.conditions.forEach { put(conditionToJson(it)) } })
        .put("actions", JSONArray().apply { r.actions.forEach { put(actionToJson(it)) } })
        .put("exitConditions", JSONArray().apply { r.exitConditions.forEach { put(conditionToJson(it)) } })
        .put("restoreActions", JSONArray().apply { r.restoreActions.forEach { put(actionToJson(it)) } })
        .put("createdAt", r.createdAt)
        .put("updatedAt", r.updatedAt)
        .put("triggerMode", r.triggerMode.code)
        .put("cooldownSeconds", r.cooldownSeconds.coerceIn(0L, MAX_RULE_COOLDOWN_SECONDS))
        .put("allowedDaysMask", (r.allowedDaysMask and ALL_DAYS_MASK).takeIf { it != 0 } ?: ALL_DAYS_MASK)
        .put("scheduleMode", r.scheduleMode.code)

    fun ruleFromJson(o: JSONObject): Rule? {
        val id = o.optString("id").takeIf { it.isNotBlank() && it.length <= 128 } ?: return null
        val conditions = mutableListOf<Condition>()
        val actions = mutableListOf<Action>()
        val exitConditions = mutableListOf<Condition>()
        val restoreActions = mutableListOf<Action>()
        fun appendItems(key: String, destination: MutableList<Condition>, decode: (JSONObject) -> Condition?) {
            val array = o.optJSONArray(key) ?: return
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                runCatching { decode(item) }.getOrNull()?.let(destination::add)
            }
        }
        fun appendActions(key: String, destination: MutableList<Action>) {
            val array = o.optJSONArray(key) ?: return
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                runCatching { actionFromJson(item) }.getOrNull()?.let(destination::add)
            }
        }
        appendItems("conditions", conditions) { conditionFromJson(it) }
        appendActions("actions", actions)
        appendItems("exitConditions", exitConditions) { conditionFromJson(it) }
        appendActions("restoreActions", restoreActions)

        return Rule(
            id = id,
            name = o.optString("name").takeIf { it.isNotBlank() } ?: id,
            enabled = o.optBoolean("enabled", true),
            conditions = conditions,
            actions = actions,
            exitConditions = exitConditions,
            restoreActions = restoreActions,
            createdAt = o.optLong("createdAt", 0L),
            updatedAt = o.optLong("updatedAt", 0L),
            triggerMode = TriggerMode.fromCode(o.optString("triggerMode")) ?: TriggerMode.ALL,
            cooldownSeconds = o.optLong("cooldownSeconds", 0L)
                .coerceIn(0L, MAX_RULE_COOLDOWN_SECONDS),
            allowedDaysMask = (o.optInt("allowedDaysMask", ALL_DAYS_MASK) and ALL_DAYS_MASK)
                .takeIf { it != 0 } ?: ALL_DAYS_MASK,
            scheduleMode = ScheduleMode.fromCode(o.optString("scheduleMode", ""))
                ?: resolveScheduleMode(o.optInt("allowedDaysMask", ALL_DAYS_MASK)),
        )
    }

    /** 规则列表 -> JSON 字符串（写入 SharedPreferences 的单个 key）。 */
    fun rulesToJson(rules: List<Rule>): String {
        val arr = JSONArray()
        rules.forEach { arr.put(ruleToJson(it)) }
        return arr.toString()
    }

    /** JSON 字符串 -> 规则列表，坏数据逐条跳过并尽量多恢复。 */
    fun rulesFromJson(json: String?): List<Rule> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            val out = mutableListOf<Rule>()
            for (i in 0 until arr.length()) {
                val rule = runCatching { ruleFromJson(arr.getJSONObject(i)) }.getOrNull()
                if (rule != null) out.add(rule)
            }
            out
        }.getOrDefault(emptyList())
    }
}
