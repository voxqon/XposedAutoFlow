package com.github.voxqon.xposed.autoflow.hook

import android.content.Context
import com.github.voxqon.xposed.autoflow.engine.Event
import com.github.voxqon.xposed.autoflow.engine.EventBridge
import com.github.voxqon.xposed.autoflow.engine.EventTypes
import com.github.voxqon.xposed.autoflow.util.Reflect
import com.github.voxqon.xposed.autoflow.util.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * com.android.systemui 注册器。
 *
 * SystemUI 是 aux 进程：监听到的事件通过 EventBridge 转发给 system_server
 * 内唯一引擎执行。亮/熄屏的权威来源是 PowerManagerService Hook；此处仅转发锁屏事件。
 *
 * Hook 点清单（对应条件）：
 * 1. KeyguardViewMediator#handleShow / handleHide（hookAllMethods）
 *    —— 锁屏显示/解锁（KEYGUARD 事件；13+ MIUI 同名方法通常保留）。
 *    候选：KeyguardUpdateMonitor#handleKeyguardVisibilityChanged（优先级低）。
 * 2. CentralSurfacesImpl(13+)/StatusBar(≤12) 构造 —— 抓取 SystemUI Context，
 *    供事件桥发送与状态读取。
 * 3. DozeTriggers#on…… 不稳定，弃用；亮熄屏在 system_server 已覆盖。
 */
object SystemUIHooks {

    @Volatile private var uiContext: Context? = null

    fun register(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookStatusBarContext(lpparam)
        hookKeyguard(lpparam)
    }

    private fun hookStatusBarContext(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 候选 1（优先）：13+ CentralSurfacesImpl；候选 2：≤12 StatusBar
        val cl = lpparam.classLoader
        try {
            val cs = Reflect.findFirstClass(
                "com.android.systemui.statusbar.phone.CentralSurfacesImpl",
                "com.android.systemui.statusbar.phone.StatusBar", cl = cl
            ) ?: return
            XposedBridge.hookAllConstructors(cs, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val ctx = param.args.filterIsInstance<Context>().firstOrNull()
                            ?: Reflect.getField(param.thisObject, "mContext") as? Context
                        if (ctx != null && uiContext == null) {
                            uiContext = ctx.applicationContext ?: ctx
                            XLog.i("SystemUI context captured")
                        }
                    } catch (_: Throwable) {}
                }
            })
        } catch (t: Throwable) {
            XLog.w("SystemUI context hook failed (event bridge degraded): ${t.message}")
        }
    }

    private fun hookKeyguard(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        var ok = false
        try {
            // 优先级 1：KeyguardViewMediator#handleShow/handleHide（AOSP 与 MIUI 长期存在）
            val kvm = Reflect.findFirstClass(
                "com.android.systemui.keyguard.KeyguardViewMediator", cl = cl
            )
            if (kvm != null) {
                val cb = object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val shown = param.method.name == "handleShow"
                        emitKeyguard(shown)
                    }
                }
                XposedBridge.hookAllMethods(kvm, "handleShow", cb)
                XposedBridge.hookAllMethods(kvm, "handleHide", cb)
                ok = true
            }
        } catch (t: Throwable) {
            XLog.w("KeyguardViewMediator hook failed: ${t.message}")
        }
        if (!ok) {
            try {
                // 优先级 2：KeyguardUpdateMonitor#handleKeyguardVisibilityChanged
                val kum = Reflect.findFirstClass(
                    "com.android.systemui.keyguard.KeyguardUpdateMonitor", cl = cl
                )
                if (kum != null) {
                    XposedBridge.hookAllMethods(kum, "handleKeyguardVisibilityChanged", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val visible = param.args.firstOrNull() is Boolean && param.args[0] == true
                            emitKeyguard(visible)
                        }
                    })
                    ok = true
                }
            } catch (t: Throwable) {
                XLog.w("KeyguardUpdateMonitor hook failed: ${t.message}")
            }
        }
        XLog.i(if (ok) "keyguard hook ok" else "keyguard hook FAILED (degraded)")
    }

    private fun emitKeyguard(shown: Boolean) {
        uiContext?.let {
            EventBridge.send(it, Event(EventTypes.KEYGUARD, value = if (shown) "shown" else "hidden"))
        }
    }
}
