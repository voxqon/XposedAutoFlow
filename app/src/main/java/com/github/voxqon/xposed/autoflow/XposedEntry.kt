package com.github.voxqon.xposed.autoflow

import com.github.voxqon.xposed.autoflow.hook.HookDispatcher
import com.github.voxqon.xposed.autoflow.util.XLog
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 传统模块入口（assets/xposed_init 指向本类，Manifest 声明模块元数据）。
 *
 * 职责：实现 IXposedHookLoadPackage，在 handleLoadPackage 中根据
 * lpparam.packageName 分发到对应 Hook 注册器。
 *
 * 未在推荐作用域中声明的包名一律不注册 Hook。
 * LSPosed 管理器决定注入范围；HookDispatcher 白名单与 RuleEngine 进一步限制执行。
 *
 * 所有 Hook 全部 try-catch（HookDispatcher 顶层兜底 + 各注册器逐点捕获），
 * 任何失败只记录日志并降级，绝不导致目标系统进程崩溃。
 */
class XposedEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            if (lpparam.packageName.isNullOrBlank()) return
            HookDispatcher.dispatch(lpparam)
        } catch (t: Throwable) {
            // 最终兜底：任何情况下不向宿主进程抛异常
            XLog.e("handleLoadPackage fatal guard hit for ${lpparam.packageName}", t)
        }
    }
}
