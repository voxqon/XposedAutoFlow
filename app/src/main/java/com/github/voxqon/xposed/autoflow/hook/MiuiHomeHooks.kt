package com.github.voxqon.xposed.autoflow.hook

import android.content.Context
import android.content.Intent
import com.github.voxqon.xposed.autoflow.engine.Event
import com.github.voxqon.xposed.autoflow.engine.EventBridge
import com.github.voxqon.xposed.autoflow.engine.EventTypes
import com.github.voxqon.xposed.autoflow.util.Reflect
import com.github.voxqon.xposed.autoflow.util.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * com.miui.home（桌面启动器）注册器。
 *
 * 应用前台条件的权威来源是 system_server 的 ActivityRecord/前台 Hook；
 * 本进程 Hook 提供一个冗余信号（经 EventBridge 转发，引擎有 800ms 防抖）：
 * 1. Launcher#startActivitySafely —— 用户从桌面点开应用（离冷启动最近的节点）。
 * Launcher#onResume 仅用于捕获桌面 Context；回桌面事件由 system_server 权威 Hook 产生。
 *
 * 候选类名（优先级从高到低）：
 *   com.miui.home.launcher.Launcher（MIUI/HyperOS 主 Launcher Activity）
 *   com.android.launcher3.Launcher（HyperOS 底层 Launcher3 基类）
 * 候选方法：startActivitySafely(View, Intent, Object) / startActivity(Intent)
 */
object MiuiHomeHooks {

    @Volatile private var homeContext: Context? = null

    fun register(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        val launcher = Reflect.findFirstClass(
            "com.miui.home.launcher.Launcher",
            "com.android.launcher3.Launcher", cl = cl
        )
        if (launcher == null) {
            XLog.w("Launcher class not found (home hooks degraded)")
            return
        }

        // 从桌面启动应用
        try {
            val names = arrayOf("startActivitySafely", "startActivity")
            var ok = false
            for (n in names) {
                val methods = runCatching { launcher.declaredMethods.filter { it.name == n } }
                    .getOrDefault(emptyList())
                for (m in methods) {
                    if (m.parameterTypes.isEmpty()) continue
                    runCatching {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                try {
                                    val intent = param.args.filterIsInstance<Intent>()
                                        .firstOrNull { it.component != null } ?: return
                                    val pkg = intent.component?.packageName ?: return
                                    if (pkg.startsWith("com.android.systemui")) return
                                    homeContext?.let {
                                        EventBridge.send(it, Event(EventTypes.APP_FG, pkg = pkg))
                                    }
                                    XLog.i("home launch -> $pkg")
                                } catch (t: Throwable) {
                                    XLog.w("home launch hook body failed: ${t.message}")
                                }
                            }
                        })
                        ok = true
                    }
                }
                if (ok) break
            }
            XLog.i(if (ok) "home launch hook ok" else "home launch hook not found (degraded)")
        } catch (t: Throwable) {
            XLog.w("home launch hook failed: ${t.message}")
        }

        // 回到桌面
        try {
            XposedBridge.hookAllMethods(launcher, "onResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (homeContext == null) {
                        homeContext = (param.thisObject as? android.app.Activity)?.applicationContext
                    }
                    // 回桌面本身不需要转发事件：system_server 的 APP_LEFT 已覆盖；
                    // 仅初始化 Context 供后续转发使用
                }
            })
            XLog.i("home resume hook ok")
        } catch (t: Throwable) {
            XLog.w("home resume hook failed: ${t.message}")
        }
    }
}
