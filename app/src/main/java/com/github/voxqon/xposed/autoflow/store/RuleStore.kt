package com.github.voxqon.xposed.autoflow.store

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.SystemClock
import com.github.voxqon.xposed.autoflow.model.Rule
import com.github.voxqon.xposed.autoflow.model.RuleJson
import com.github.voxqon.xposed.autoflow.util.XLog
import org.json.JSONArray
import org.json.JSONObject

/** Private app storage is read through a UID-checked provider; failures retain the last good rules. */
object RuleStore {
    const val MODULE_PKG = "com.github.voxqon.xposed.autoflow"
    const val PREFS_NAME = "auto_task_rules"
    const val KEY_RULES = "rules_json"
    const val CONFIG_PREFS = "auto_task_config"
    const val KEY_SHELL = "allow_shell"
    const val KEY_FLOATING_BOTTOM_BAR = "floating_bottom_bar"
    const val KEY_NOTIFY_EXECUTION = "notify_execution"
    @Volatile private var context: Context? = null
    @Volatile private var cache: List<Rule> = emptyList()
    private var lastJson: String? = null
    private var lastProbe = -1L
    private var lastWarning = -1L
    private var legacyAttempted = false

    fun initialize(context: Context) {
        this.context = context
    }

    @Synchronized fun invalidate() {
        lastProbe = -1L
    }

    private fun snapshot(): JSONObject? {
        val ctx = context ?: return null
        val token = Binder.clearCallingIdentity()
        return try {
            ctx.contentResolver.openInputStream(RuleConfigContract.URI)?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= RuleConfigContract.MAX_SNAPSHOT_BYTES) { "Configuration too large" }
                    output.write(buffer, 0, count)
                }
                JSONObject(output.toString(Charsets.UTF_8.name()))
            }
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    @Synchronized fun load(): List<Rule> {
        val now = SystemClock.elapsedRealtime()
        if (lastProbe >= 0 && now - lastProbe in 0 until 1_000L) return cache
        lastProbe = now
        runCatching {
            var data = snapshot() ?: error("Configuration provider unavailable")
            if (!data.getBoolean("initialized") && !legacyAttempted) {
                legacyAttempted = true
                LegacyPreferenceMigration.readRules()?.let { json ->
                    val token = Binder.clearCallingIdentity()
                    try {
                        context?.contentResolver?.call(RuleConfigContract.URI,
                            RuleConfigContract.IMPORT_LEGACY, null, Bundle().apply { putString("rules", json) })
                        data = snapshot() ?: data
                    } finally {
                        Binder.restoreCallingIdentity(token)
                    }
                }
            }
            val json = data.getString("rules")
            if (json != lastJson) {
                val array = JSONArray(json)
                val parsed = (0 until array.length()).mapNotNull {
                    RuleJson.ruleFromJson(array.getJSONObject(it))
                }.distinctBy { it.id }
                cache = parsed
                lastJson = json
            }
        }.onFailure {
            if (lastWarning < 0 || now - lastWarning >= 60_000L) {
                lastWarning = now
                XLog.w("Rules unavailable; retained previous configuration: ${it.message}")
            }
        }
        return cache
    }

    /** A failed fresh read must never reuse permission for a high-risk action. */
    fun shellAllowed(): Boolean =
        runCatching { snapshot()?.getBoolean("shellAllowed") == true }.getOrDefault(false)

    /** Whether the bottom navigation bar floats above content. Default false (fixed). */
    fun floatingBottomBar(): Boolean =
        runCatching { settings()?.getBoolean("floatingBottomBar", false) ?: false }.getOrDefault(false)

    /** Whether to send notifications when rules trigger and finish. Default true. */
    fun notifyExecution(): Boolean =
        runCatching { settings()?.getBoolean("notifyExecution", true) ?: true }.getOrDefault(true)

    private fun settings(): Bundle? {
        val ctx = context ?: return null
        val token = Binder.clearCallingIdentity()
        return try {
            ctx.contentResolver.call(RuleConfigContract.URI, RuleConfigContract.GET_SETTINGS, null, null)
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }
}
