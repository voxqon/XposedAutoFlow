package com.github.voxqon.xposed.autoflow.hook

import android.content.Context
import com.github.voxqon.xposed.autoflow.util.XLog

/**
 * system_server 内的 Context 获取（全反射，ActivityThread 不在公开 SDK 中）。
 *
 * 只读取已存在的 ActivityThread；systemMain() 会创建并替换框架实例，不能用作 getter。
 * 启动阶段尚未就绪时返回 null，由注册器稍后重试。
 */
object ContextProvider {
    @Volatile private var cached: Context? = null

    fun systemContext(): Context? {
        cached?.let { return it }
        return runCatching {
            val atc = Class.forName("android.app.ActivityThread", false, Context::class.java.classLoader)
            val at = atc.getMethod("currentActivityThread").invoke(null) ?: return null
            (atc.getMethod("getSystemContext").invoke(at) as? Context)?.also { cached = it }
        }.onFailure { XLog.w("systemContext read failed: ${it.message}") }.getOrNull()
    }
}
