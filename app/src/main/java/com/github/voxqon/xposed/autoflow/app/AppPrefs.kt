package com.github.voxqon.xposed.autoflow.app

import android.content.Context
import android.content.SharedPreferences
import com.github.voxqon.xposed.autoflow.model.Rule
import com.github.voxqon.xposed.autoflow.model.RuleJson
import com.github.voxqon.xposed.autoflow.store.RuleStore
import com.github.voxqon.xposed.autoflow.store.RuleConfigContract
import org.json.JSONArray
import org.json.JSONObject

/**
 * 模块 App 侧的规则读写（SharedPreferences + JSON）。
 * 文件名/键名与 Hook 侧 RuleStore 严格对应：
 *   prefs "auto_task_rules" key "rules_json"
 *   prefs "auto_task_config" key "allow_shell"
 * 私有首选项通过校验调用 UID 的 ContentProvider 提供给系统规则引擎。
 */
object AppPrefs {

    private val lock = Any()
    private fun sharedPrefs(ctx: Context, name: String): SharedPreferences =
        ctx.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun notifyChanged(ctx: Context) {
        runCatching { ctx.contentResolver.notifyChange(RuleConfigContract.URI, null) }
            .onFailure { android.util.Log.w("XposedAutoFlow", "Configuration notification failed", it) }
    }

    private fun prefs(ctx: Context): SharedPreferences = sharedPrefs(ctx, RuleStore.PREFS_NAME)

    private fun config(ctx: Context): SharedPreferences = sharedPrefs(ctx, RuleStore.CONFIG_PREFS)

    fun loadRules(ctx: Context): MutableList<Rule> =
        RuleJson.rulesFromJson(prefs(ctx).getString(RuleStore.KEY_RULES, null)).toMutableList()

    fun saveRules(ctx: Context, rules: List<Rule>) = synchronized(lock) {
        prefs(ctx).edit().putString(RuleStore.KEY_RULES, RuleJson.rulesToJson(rules)).apply()
        notifyChanged(ctx)
    }

    fun replaceRules(ctx: Context, rules: List<Rule>) = synchronized(lock) {
        check(prefs(ctx).edit().putString(RuleStore.KEY_RULES, RuleJson.rulesToJson(rules)).commit()) {
            "无法写入恢复的规则"
        }
        notifyChanged(ctx)
    }

    fun shellAllowed(ctx: Context): Boolean =
        config(ctx).getBoolean(RuleStore.KEY_SHELL, false)

    fun setShellAllowed(ctx: Context, allow: Boolean) {
        config(ctx).edit().putBoolean(RuleStore.KEY_SHELL, allow).apply()
        notifyChanged(ctx)
    }

    fun floatingBottomBar(ctx: Context): Boolean =
        config(ctx).getBoolean(RuleStore.KEY_FLOATING_BOTTOM_BAR, false)

    fun setFloatingBottomBar(ctx: Context, floating: Boolean) {
        config(ctx).edit().putBoolean(RuleStore.KEY_FLOATING_BOTTOM_BAR, floating).apply()
        notifyChanged(ctx)
    }

    fun notifyExecution(ctx: Context): Boolean =
        config(ctx).getBoolean(RuleStore.KEY_NOTIFY_EXECUTION, true)

    fun setNotifyExecution(ctx: Context, notify: Boolean) {
        config(ctx).edit().putBoolean(RuleStore.KEY_NOTIFY_EXECUTION, notify).apply()
        notifyChanged(ctx)
    }

    fun configurationSnapshot(ctx: Context): String = synchronized(lock) {
        JSONObject().put("rules", prefs(ctx).getString(RuleStore.KEY_RULES, "[]"))
            .put("initialized", prefs(ctx).contains(RuleStore.KEY_RULES))
            .put("shellAllowed", shellAllowed(ctx))
            .put("floatingBottomBar", floatingBottomBar(ctx))
            .put("notifyExecution", notifyExecution(ctx)).toString()
    }

    /** Never overwrite a saved list, including an intentionally saved empty list. */
    fun importLegacy(ctx: Context, json: String?): Boolean = synchronized(lock) {
        if (json == null || json.toByteArray(Charsets.UTF_8).size > RuleConfigContract.MAX_SNAPSHOT_BYTES ||
            prefs(ctx).contains(RuleStore.KEY_RULES)) return false
        val valid = runCatching {
            val array = JSONArray(json)
            (0 until array.length()).all { RuleJson.ruleFromJson(array.getJSONObject(it)) != null }
        }.getOrDefault(false)
        if (!valid) return false
        if (!prefs(ctx).edit().putString(RuleStore.KEY_RULES, json).commit()) return false
        notifyChanged(ctx)
        true
    }
}
