package com.github.voxqon.xposed.autoflow.store

import com.github.voxqon.xposed.autoflow.util.XLog
import de.robv.android.xposed.XSharedPreferences
import java.io.File

/** Optional one-time recovery for LSPosed versions that still expose the old shared directory. */
internal object LegacyPreferenceMigration {
    fun readRules(): String? = runCatching {
        val clientClass = Class.forName("org.lsposed.lspd.core.ApplicationServiceClient",
            false, XSharedPreferences::class.java.classLoader)
        val client = clientClass.getField("serviceClient").get(null) ?: return null
        val directory = clientClass.getMethod("getPrefsPath", String::class.java)
            .invoke(client, RuleStore.MODULE_PKG) as? String ?: return null
        val file = File(directory, "${RuleStore.PREFS_NAME}.xml")
        XSharedPreferences(file).getString(RuleStore.KEY_RULES, null)
    }.onFailure { XLog.i("Legacy shared preferences unavailable; private configuration remains active") }
        .getOrNull()
}
