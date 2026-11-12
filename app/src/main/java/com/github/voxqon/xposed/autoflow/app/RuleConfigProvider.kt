package com.github.voxqon.xposed.autoflow.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import com.github.voxqon.xposed.autoflow.store.RuleConfigContract
import java.io.FileNotFoundException

/** Read-only private configuration bridge; legacy data is accepted only before local writes. */
class RuleConfigProvider : ContentProvider() {
    override fun onCreate() = true

    private fun enforceCaller() {
        val uid = Binder.getCallingUid()
        if (uid != Process.SYSTEM_UID && uid != Process.myUid()) {
            throw SecurityException("Rule configuration is private")
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        enforceCaller()
        if (uri != RuleConfigContract.URI || mode != "r") throw FileNotFoundException("Read-only configuration")
        val json = AppPrefs.configurationSnapshot(requireNotNull(context))
        // A pipe also handles rule sets that exceed Binder's transaction size.
        return openPipeHelper(uri, "application/json", null, json) { output, _, _, _, data ->
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(output).use {
                    it.write(requireNotNull(data).toByteArray(Charsets.UTF_8))
                }
            }.onFailure { android.util.Log.w("AutoTaskLite", "Configuration reader disconnected", it) }
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        enforceCaller()
        if (method == RuleConfigContract.GET_SETTINGS) {
            val ctx = requireNotNull(context)
            return Bundle().apply {
                putBoolean("floatingBottomBar", AppPrefs.floatingBottomBar(ctx))
                putBoolean("notifyExecution", AppPrefs.notifyExecution(ctx))
            }
        }
        require(method == RuleConfigContract.IMPORT_LEGACY)
        val imported = AppPrefs.importLegacy(requireNotNull(context), extras?.getString("rules"))
        return Bundle().apply { putBoolean("imported", imported) }
    }

    override fun getType(uri: Uri): String {
        enforceCaller()
        require(uri == RuleConfigContract.URI)
        return "application/json"
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        enforceCaller()
        return null
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        enforceCaller()
        throw UnsupportedOperationException("Read-only configuration")
    }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        enforceCaller()
        throw UnsupportedOperationException("Read-only configuration")
    }
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        enforceCaller()
        throw UnsupportedOperationException("Read-only configuration")
    }
}
