package com.github.voxqon.xposed.autoflow.store

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import com.github.voxqon.xposed.autoflow.model.ExecutionLog

class ExecutionLogStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "execution_logs.db", null, 1) {
    private val app = context.applicationContext
    private val prefs get() = app.getSharedPreferences("execution_log_settings", Context.MODE_PRIVATE)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE logs (id TEXT PRIMARY KEY, started_at INTEGER NOT NULL, json TEXT NOT NULL)")
        db.execSQL("CREATE INDEX logs_time ON logs(started_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun limit(): Int = prefs.getInt("limit", DEFAULT_LIMIT).coerceIn(0, MAX_LIMIT)

    @Synchronized
    fun setLimit(limit: Int) {
        require(limit in 0..MAX_LIMIT)
        val edit = prefs.edit().putInt("limit", limit)
        if (limit == 0) edit.putLong("cleared_before", System.currentTimeMillis())
        check(edit.commit()) { "无法保存日志数量" }
        trim(writableDatabase, limit)
        notifyChanged()
    }

    @Synchronized
    fun append(log: ExecutionLog) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val limit = limit()
            if (limit > 0) {
                if (log.startedAt <= prefs.getLong("cleared_before", Long.MIN_VALUE)) {
                    db.setTransactionSuccessful()
                    return
                }
                db.replaceOrThrow("logs", null, ContentValues().apply {
                    put("id", log.id)
                    put("started_at", log.startedAt)
                    put("json", log.toJson())
                })
            }
            trim(db, limit)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notifyChanged()
    }

    private fun trim(db: SQLiteDatabase, limit: Int) {
        db.execSQL("DELETE FROM logs WHERE id NOT IN " +
            "(SELECT id FROM logs ORDER BY started_at DESC, rowid DESC LIMIT ?)", arrayOf(limit))
    }

    @Synchronized
    fun load(): List<ExecutionLog> {
        val logs = mutableListOf<ExecutionLog>()
        readableDatabase.query("logs", arrayOf("json"), null, null, null, null,
            "started_at DESC, rowid DESC", limit().toString()).use { cursor ->
            while (cursor.moveToNext()) {
                runCatching { ExecutionLog.fromJson(cursor.getString(0)) }.getOrNull()?.let(logs::add)
            }
        }
        return logs
    }

    @Synchronized
    fun clear() {
        check(prefs.edit().putLong("cleared_before", System.currentTimeMillis()).commit()) {
            "无法清空日志"
        }
        writableDatabase.delete("logs", null, null)
        notifyChanged()
    }

    private fun notifyChanged() = app.contentResolver.notifyChange(CONTENT_URI, null)

    companion object {
        const val DEFAULT_LIMIT = 200
        const val MAX_LIMIT = 10_000
        val CONTENT_URI: Uri = Uri.parse("content://com.github.voxqon.xposed.autoflow.execution-logs/logs")
        @Volatile private var instance: ExecutionLogStore? = null

        fun get(context: Context): ExecutionLogStore {
            val app = context.applicationContext
            return instance?.takeIf { it.app === app } ?: synchronized(this) {
                instance?.takeIf { it.app === app } ?: ExecutionLogStore(app).also { instance = it }
            }
        }
    }
}
