package com.github.voxqon.xposed.autoflow.store

import android.net.Uri

object RuleConfigContract {
    val URI: Uri = Uri.parse("content://com.github.voxqon.xposed.autoflow.rule-config/snapshot")
    const val IMPORT_LEGACY = "import_legacy"
    const val GET_SETTINGS = "get_settings"
    const val MAX_SNAPSHOT_BYTES = 8 * 1024 * 1024
}
