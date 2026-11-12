package com.github.voxqon.xposed.autoflow.hook

import android.app.Activity
import android.app.Application
import android.content.Context
import com.github.voxqon.xposed.autoflow.engine.ConnectionCatalogBridge
import com.github.voxqon.xposed.autoflow.engine.Event
import com.github.voxqon.xposed.autoflow.engine.EventBridge
import com.github.voxqon.xposed.autoflow.engine.EventTypes
import com.github.voxqon.xposed.autoflow.util.Reflect
import com.github.voxqon.xposed.autoflow.util.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * com.android.settings 注册器。
 *
 * 架构定位（重要）：定位/Wi-Fi/蓝牙/方向锁定/勿扰等开关状态的“权威监听”
 * 位于 android(system_server) 的 ContentObserver（见 AndroidFrameworkHooks）——
 * 设置值一旦变化无论来自哪个界面都会捕获。
 * 本进程的 Hook 是补充层：捕获设置界面内的开关点击，作为状态同步信号与
 * 诊断日志，帮助定位设置变更来源。
 *
 * Hook 点清单（候选优先级从高到低，全部 hookAllMethods 容错）：
 * 1. androidx.preference.TwoStatePreference#setChecked(boolean)
 *    —— Settings 内所有开关型偏好项的公共落点（MIUI Settings 使用 androidx）。
 * 2. com.android.settings.wifi.WifiSettings#onWifiStateChanged（部分版本存在）
 * 3. SubSettings/SettingsActivity#onCreate —— 抓取 Context。
 * 找不到任何一个 Hook 点时仅记录日志，功能由 system_server 观察者兜底。
 */
object SettingsHooks {

    @Volatile private var settingsContext: Context? = null

    fun register(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.processName == lpparam.packageName) hookCatalogContext()
        hookContext(lpparam)
        hookTwoStatePreference(lpparam)
        hookWifiStateChanged(lpparam)
    }

    private fun hookCatalogContext() {
        runCatching {
            // Application.attach works even when a ROM replaces the Settings activity classes.
            XposedBridge.hookAllMethods(Application::class.java, "attach", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val app = param.thisObject as? Application ?: return
                    settingsContext = app
                    ConnectionCatalogBridge.register(app)
                }
            })
        }.onFailure { XLog.w("Settings catalog context hook failed: ${it.message}") }
    }

    private fun hookContext(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val cl = lpparam.classLoader
            val act = Reflect.findFirstClass(
                "com.android.settings.SubSettings",
                "com.android.settings.SettingsActivity", cl = cl
            ) ?: return
            XposedBridge.hookAllMethods(act, "onCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (settingsContext == null) {
                        val a = param.thisObject as? Activity ?: return
                        settingsContext = a.applicationContext
                        XLog.i("Settings context captured")
                    }
                }
            })
        } catch (t: Throwable) {
            XLog.w("Settings context hook failed: ${t.message}")
        }
    }

    private fun hookTwoStatePreference(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val tsp = Reflect.findFirstClass(
                "androidx.preference.TwoStatePreference", cl = lpparam.classLoader
            ) ?: return
            XposedBridge.hookAllMethods(tsp, "setChecked", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val checked = param.args.firstOrNull() as? Boolean ?: return
                        val prefKey = Reflect.getField(param.thisObject, "mKey") as? String
                        // 仅诊断日志：具体状态以 system_server ContentObserver 为准
                        XLog.i("settings pref toggled key=$prefKey -> $checked")
                    } catch (_: Throwable) {}
                }
            })
            XLog.i("TwoStatePreference hook ok")
        } catch (t: Throwable) {
            XLog.w("TwoStatePreference hook failed (degraded): ${t.message}")
        }
    }

    private fun hookWifiStateChanged(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val ws = Reflect.findFirstClass(
                "com.android.settings.wifi.WifiSettings", cl = lpparam.classLoader
            ) ?: return
            XposedBridge.hookAllMethods(ws, "onWifiStateChanged", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val st = param.args.firstOrNull() as? Int ?: return
                    if (st == 3 || st == 1) {
                        val v = if (st == 3) "on" else "off"
                        settingsContext?.let { EventBridge.send(it, Event(EventTypes.WIFI, value = v)) }
                    }
                }
            })
        } catch (t: Throwable) {
            XLog.w("WifiSettings hook failed (degraded): ${t.message}")
        }
    }
}
