package com.github.voxqon.xposed.autoflow.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.github.voxqon.xposed.autoflow.model.ExecutionLog
import com.github.voxqon.xposed.autoflow.store.ExecutionLogStore
import com.github.voxqon.xposed.autoflow.store.ExecutionNotificationContract

/** Only the module itself and the system rule engine may access the log bridge. */
class ExecutionLogProvider : ContentProvider() {
    override fun onCreate() = true

    private fun enforceCaller() {
        val uid = Binder.getCallingUid()
        if (uid != Process.SYSTEM_UID && uid != Process.myUid()) {
            throw SecurityException("Execution logs are private")
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        enforceCaller()
        require(uri == ExecutionLogStore.CONTENT_URI)
        val log = ExecutionLog.fromJson(requireNotNull(values?.getAsString("json")))
        ExecutionLogStore.get(requireNotNull(context)).append(log)
        return Uri.withAppendedPath(uri, log.id)
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        enforceCaller()
        require(method == ExecutionNotificationContract.METHOD)
        val token = Binder.clearCallingIdentity()
        try {
            ExecutionNotifications.receive(requireNotNull(context), arg, requireNotNull(extras))
            return Bundle.EMPTY
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    override fun getType(uri: Uri): String {
        enforceCaller()
        return "vnd.android.cursor.dir/vnd.autotasklite.execution-log"
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        enforceCaller()
        return null
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?): Int {
        enforceCaller()
        return 0
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        enforceCaller()
        return 0
    }
}
