package com.github.voxqon.xposed.autoflow.hook

import com.github.voxqon.xposed.autoflow.util.XLog
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Hook 分发器。
 *
 * 重要的架构约定（README 同步说明）：
 * - Manifest 的 xposedscope 声明推荐作用域，由管理器决定实际注入范围；
 * - 本分发器按 lpparam.packageName 白名单注册 Hook，其他包一律跳过。
 */
object HookDispatcher {

    fun dispatch(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName
        XLog.i("loaded into process: $pkg (uid=${android.os.Process.myUid()})")
        when (pkg) {
            "android" -> register("AndroidFramework", pkg) { AndroidFrameworkHooks.register(lpparam) }
            "com.android.systemui" -> register("SystemUI", pkg) { SystemUIHooks.register(lpparam) }
            "com.android.settings" -> register("Settings", pkg) { SettingsHooks.register(lpparam) }
            "com.miui.home" -> register("MiuiHome", pkg) { MiuiHomeHooks.register(lpparam) }
            else -> {
                XLog.i("package $pkg not in supported scope, skip hooking")
            }
        }
    }

    private inline fun register(name: String, pkg: String, block: () -> Unit) {
        try {
            block()
            XLog.i("$name hooks registered for $pkg")
        } catch (t: Throwable) {
            // 顶层兜底：注册器内部已逐点 try-catch，这里保证整进程不因模块崩溃
            XLog.e("$name registrar crashed (all hooks degraded)", t)
        }
    }
}
