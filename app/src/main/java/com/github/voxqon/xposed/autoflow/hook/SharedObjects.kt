package com.github.voxqon.xposed.autoflow.hook

import com.github.voxqon.xposed.autoflow.engine.ActionExecutor
import com.github.voxqon.xposed.autoflow.engine.RuleEngine
import com.github.voxqon.xposed.autoflow.engine.StateStore
import com.github.voxqon.xposed.autoflow.util.XLog

/**
 * system_server 内唯一的 StateStore + RuleEngine + ActionExecutor。
 * SystemUI/Settings/Home 不创建引擎，只通过 EventBridge 转发辅助事件。
 */
object SharedObjects {
    @Volatile private var engine: RuleEngine? = null
    @Volatile private var state: StateStore? = null

    fun get(): Pair<RuleEngine, StateStore> {
        synchronized(this) {
            if (engine == null) {
                val s = StateStore()
                state = s
                engine = RuleEngine(s, ActionExecutor { ContextProvider.systemContext() })
            }
            return Pair(engine!!, state!!)
        }
    }

    fun state(): StateStore = get().second

    fun postEvent(type: String, pkg: String? = null, value: String? = null, value2: String? = null) {
        try {
            get().first.post(com.github.voxqon.xposed.autoflow.engine.Event(type, pkg, value, value2))
        } catch (t: Throwable) {
            XLog.e("postEvent $type failed", t)
        }
    }
}
