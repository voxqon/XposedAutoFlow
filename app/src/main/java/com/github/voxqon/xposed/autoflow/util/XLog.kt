package com.github.voxqon.xposed.autoflow.util

import de.robv.android.xposed.XposedBridge

/**
 * 统一日志：同时输出到 logcat(tag=XposedAutoFlow) 与 Xposed 日志。
 * 所有 Hook / 引擎代码都通过它记录，失败可降级，不抛出到目标进程。
 */
object XLog {
    private const val TAG = "XposedAutoFlow"

    fun i(msg: String) {
        android.util.Log.i(TAG, msg)
        runCatching { XposedBridge.log("[$TAG] $msg") }
    }

    fun w(msg: String) {
        android.util.Log.w(TAG, msg)
        runCatching { XposedBridge.log("[$TAG][W] $msg") }
    }

    fun e(msg: String, t: Throwable? = null) {
        android.util.Log.e(TAG, msg, t)
        runCatching {
            if (t != null) XposedBridge.log("[$TAG][E] $msg: ${t.message}")
            else XposedBridge.log("[$TAG][E] $msg")
            t?.let { XposedBridge.log(it) }
        }
    }
}
