package com.github.voxqon.xposed.autoflow.engine

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ResultReceiver
import android.os.VibrationEffect
import android.os.Vibrator
import com.github.voxqon.xposed.autoflow.model.Action
import com.github.voxqon.xposed.autoflow.model.ActionType
import com.github.voxqon.xposed.autoflow.model.ExecutionLog
import com.github.voxqon.xposed.autoflow.model.ExecutionStatus
import com.github.voxqon.xposed.autoflow.model.ExecutionStep
import com.github.voxqon.xposed.autoflow.model.Rule
import com.github.voxqon.xposed.autoflow.model.MAX_ACTION_DELAY_SECONDS
import com.github.voxqon.xposed.autoflow.model.SoundSpec
import com.github.voxqon.xposed.autoflow.store.RuleStore
import com.github.voxqon.xposed.autoflow.util.Reflect
import com.github.voxqon.xposed.autoflow.util.XLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 动作执行器。
 *
 * 职责：把 Action 翻译为系统调用。实例仅由 system_server 的规则引擎持有，
 * 具备写 Secure/Global 设置与反射调用隐藏 API 的权限；失败只记录并降级。
 *
 * 恢复（restore）语义：执行动作前若能捕获“原值”，则记录到 RestoreStore；
 * 退出恢复时优先写回原值，否则按 on/off 反转；恢复失败的快照保留并重试。
 */
class ActionExecutor(private val contextProvider: () -> Context?) {

    private companion object {
        const val SHELL_TIMEOUT_MS = 10_000L
        const val MAX_SHELL_OUTPUT_BYTES = 64 * 1024
        const val TETHERING_WIFI = 0
        const val WIFI_AP_STATE_DISABLED = 11
        const val WIFI_AP_STATE_ENABLED = 13
        val delayedActions by lazy {
            Handler(HandlerThread("atlt-actions").apply { start() }.looper)
        }
    }

    /** ruleId -> 已捕获原值列表，用于退出恢复。 */
    private val captured = HashMap<String, MutableList<Captured>>()
    private val pendingRuns = ConcurrentHashMap<String, () -> Unit>()

    data class Captured(val action: Action, val original: String?)

    private fun Action.logName(): String {
        val parameters = listOfNotNull(appName ?: param, param2).joinToString(" / ")
        val name = if (parameters.isBlank()) type.label else "${type.label}：$parameters"
        return if (delaySeconds > 0) "$name（延迟 $delaySeconds 秒）" else name
    }

    // ---------------------------------------------------------------- entry

    fun executeLogged(rule: Rule, phase: String, reason: String, actions: List<Action>,
        capture: Boolean, restoreCaptured: Boolean = false,
        actionHandler: Handler? = null, onDispatched: () -> Unit = {}) {
        pendingRuns.remove(rule.id)?.invoke()
        val started = android.os.SystemClock.elapsedRealtime()
        val base = ExecutionLog(ruleId = rule.id, ruleName = rule.name, phase = phase,
            reason = reason, steps = emptyList())
        if (actions.isNotEmpty()) {
            NotificationHelper.notifyRunning(contextProvider(), rule.id, rule.name, phase, base.id)
        } else if (restoreCaptured) {
            NotificationHelper.cancel(contextProvider(), rule.id)
        }
        val lock = Any()
        val steps = actions.map { ExecutionStep(it.logName(), ExecutionStatus.SUBMITTED, "等待执行") }
            .toMutableList()
        var ready = false
        var dispatched = false
        var notified = false
        var cancelled = false
        var callback: Runnable? = null
        var scheduler: Handler? = null
        lateinit var cancelRun: () -> Unit
        fun publish() {
            if (!ready) return
            val log = base.copy(steps = steps.toList(),
                durationMs = android.os.SystemClock.elapsedRealtime() - started)
            ExecutionLogBridge.record(contextProvider(), log)
            if (!notified && (dispatched || cancelled) && actions.isNotEmpty() &&
                steps.none { it.status == ExecutionStatus.SUBMITTED }) {
                notified = true
                pendingRuns.remove(rule.id, cancelRun)
                NotificationHelper.notifyResult(contextProvider(), rule.id, rule.name, log.status, phase, base.id)
            }
        }
        fun update(index: Int, step: ExecutionStep) = synchronized(lock) {
            if (cancelled) return@synchronized
            // A connection callback can complete before its submission returns.
            if (step.status != ExecutionStatus.SUBMITTED || steps[index].status == ExecutionStatus.SUBMITTED) {
                steps[index] = step
            }
            publish()
        }
        cancelRun = {
            synchronized(lock) {
                cancelled = true
                callback?.let { scheduler?.removeCallbacks(it) }
                callback = null
                steps.indices.filter { steps[it].status == ExecutionStatus.SUBMITTED }.forEach {
                    steps[it] = steps[it].copy(status = ExecutionStatus.SKIPPED, detail = "执行已取消")
                }
                publish()
            }
        }
        if (actions.isNotEmpty()) pendingRuns[rule.id] = cancelRun
        if (!capture) ConnectionActions.cancelRule(rule.id)
        fun execute(index: Int, action: Action) {
            val step = runCatching {
                executeOne(rule.id, action, capture, rule.name) { status, message ->
                    update(index, ExecutionStep(action.logName(), status, message))
                }
            }.getOrElse {
                XLog.e("action ${action.type.code} failed (degraded)", it)
                ExecutionStep(action.logName(), ExecutionStatus.FAILED,
                    it.cause?.message ?: it.message ?: it.javaClass.simpleName)
            }
            update(index, step)
        }
        var index = 0
        fun continueRun(delayElapsed: Boolean = false) {
            synchronized(lock) {
                if (cancelled) return
                callback = null
                var skipDelay = delayElapsed
                while (index < actions.size) {
                    val action = actions[index]
                    if (!skipDelay && action.delaySeconds > 0) {
                        scheduler = actionHandler ?: delayedActions
                        steps[index] = steps[index].copy(detail = "执行前等待 ${action.delaySeconds} 秒")
                        callback = Runnable { continueRun(delayElapsed = true) }
                        scheduler!!.postDelayed(callback!!,
                            action.delaySeconds.coerceIn(0L, MAX_ACTION_DELAY_SECONDS) * 1_000L)
                        publish()
                        return
                    }
                    execute(index, action)
                    index++
                    skipDelay = false
                }
                if (restoreCaptured) steps.addAll(restoreAll(rule.id))
                dispatched = true
                onDispatched()
                publish()
            }
        }
        continueRun()
        synchronized(lock) {
            ready = true
            publish()
        }
    }

    fun cancelPending(ruleId: String) {
        pendingRuns.remove(ruleId)?.invoke()
        ConnectionActions.cancelRule(ruleId)
        ConnectionActions.cancelRule("exit:$ruleId")
        SoundActions.cancelRule(ruleId)
        SoundActions.cancelRule("exit:$ruleId")
        NotificationHelper.cancel(contextProvider(), ruleId)
    }

    fun executeAll(ruleId: String, actions: List<Action>, restore: Boolean): List<ExecutionStep> {
        if (!restore) ConnectionActions.cancelRule(ruleId)
        return actions.map { a ->
            runCatching { executeOne(ruleId, a, restore) { _, _ -> } }.getOrElse { t ->
                XLog.e("action ${a.type.code} failed (degraded)", t)
                ExecutionStep(a.logName(), ExecutionStatus.FAILED, t.message.orEmpty())
            }
        }
    }

    fun restoreAll(ruleId: String): List<ExecutionStep> {
        ConnectionActions.cancelRule(ruleId)
        SoundActions.cancelRule(ruleId)
        val list = synchronized(captured) { captured[ruleId]?.toList() }
        if (list.isNullOrEmpty()) {
            // 进程重启后内存恢复态丢失：读取持久化快照兜底
            return restoreFromPersisted(ruleId)
        }
        val results = mutableListOf<ExecutionStep>()
        val failed = list.asReversed().filterNot { c ->
            val ok = runCatching { applyRestore(c) }
                .onFailure { XLog.e("restore ${c.action.type.code} failed (degraded)", it) }
                .getOrDefault(false)
            results.add(ExecutionStep("恢复原值：${c.action.type.label}",
                if (ok) ExecutionStatus.SUCCESS else ExecutionStatus.FAILED,
                if (ok) "原值已写回" else "恢复失败，保留快照以便重试"))
            ok
        }
        synchronized(captured) {
            if (failed.isEmpty()) captured.remove(ruleId)
            else captured[ruleId] = failed.asReversed().toMutableList()
        }
        if (failed.isEmpty()) RestoreStore.clear(ruleId)
        else RestoreStore.save(ruleId, failed.asReversed())
        return results
    }

    /** Restore snapshots left by an interrupted system_server process. */
    fun restorePersistedAll() {
        RestoreStore.ruleIds().forEach { id ->
            val start = android.os.SystemClock.elapsedRealtime()
            val results = restoreFromPersisted(id)
            ExecutionLogBridge.record(contextProvider(), ExecutionLog(ruleId = id, ruleName = id,
                phase = "重启恢复", reason = "恢复系统进程重启前的设置",
                durationMs = android.os.SystemClock.elapsedRealtime() - start, steps = results))
        }
    }

    /** 从 /data/system/autotasklite/restore_<id>.json 恢复（仅 system_server 可写处）。 */
    private fun restoreFromPersisted(ruleId: String): List<ExecutionStep> {
        val arr = RestoreStore.load(ruleId) ?: return emptyList()
        XLog.i("restore rule=$ruleId from persisted state (${arr.length()} items)")
        val failed = mutableListOf<Captured>()
        val results = mutableListOf<ExecutionStep>()
        for (i in arr.length() - 1 downTo 0) {
            val item = runCatching {
                val o = arr.getJSONObject(i)
                val t = ActionType.fromCode(o.optString("type")) ?: return@runCatching null
                val param = o.optString("param").takeIf { it.isNotEmpty() }
                val param2 = o.optString("param2").takeIf { it.isNotEmpty() }
                val original = o.optString("original").takeIf { it.isNotEmpty() }
                Captured(Action(t, param, param2), original)
            }.onFailure { XLog.e("persisted restore item parse failed (degraded)", it) }.getOrNull()
                ?: continue
            val restored = runCatching { applyRestore(item) }
                .onFailure { XLog.e("persisted restore item failed (degraded)", it) }
                .getOrDefault(false)
            if (!restored) failed.add(item)
            results.add(ExecutionStep("恢复原值：${item.action.type.label}",
                if (restored) ExecutionStatus.SUCCESS else ExecutionStatus.FAILED,
                if (restored) "原值已写回" else "恢复失败，保留快照以便重试"))
        }
        if (failed.isEmpty()) RestoreStore.clear(ruleId)
        else RestoreStore.save(ruleId, failed.asReversed())
        return results
    }

    private fun executeOne(ruleId: String, a: Action, restore: Boolean,
        ruleName: String = ruleId,
        onComplete: (ExecutionStatus, String) -> Unit): ExecutionStep {
        val ctx = contextProvider()
        if (a.type.highRisk && !RuleStore.shellAllowed()) {
            XLog.w("SHELL action blocked: allow_shell=false (高风险动作默认关闭)")
            return ExecutionStep(a.logName(), ExecutionStatus.SKIPPED, "Shell 高权限动作开关未开启")
        }
        if (restore) captureOriginal(ruleId, a, ctx)
        if (a.type == ActionType.SYSTEM_ALARM) {
            return SystemAlarmAction.execute(ctx, ruleName).copy(name = a.logName())
        }
        if (a.type == ActionType.PLAY_SOUND) {
            val accepted = SoundActions.play(ctx, if (restore) ruleId else "exit:$ruleId",
                SoundSpec.parse(a.param, a.param2), onComplete)
            return ExecutionStep(a.logName(), if (accepted) ExecutionStatus.SUBMITTED else ExecutionStatus.FAILED,
                if (accepted) "正在加载声音" else "声音或播放时长无效")
        }
        if (a.type == ActionType.WIFI_CONNECTION || a.type == ActionType.BLUETOOTH_CONNECTION) {
            val owner = if (restore) ruleId else "exit:$ruleId"
            if (a.type == ActionType.WIFI_CONNECTION) {
                ConnectionActions.wifi(ctx, a.param, a.param2, owner, onComplete)
            } else {
                ConnectionActions.bluetooth(ctx, a.param, a.param2, owner, onComplete)
            }
            return ExecutionStep(a.logName(), ExecutionStatus.SUBMITTED, "等待系统处理连接请求")
        }
        val ok = when (a.type) {
            ActionType.OPEN_APP -> openApp(ctx, a.param)
            ActionType.KILL_BACKGROUND_APP -> killBackgroundApp(ctx, a.param)
            ActionType.CLOSE_APP -> forceStopApp(ctx, a.param)
            ActionType.LOCATION -> setLocation(ctx, ToggleResolver.resolve(a.param) {
                ctx?.let { enabledState(readSecureInt(it, "location_mode")) }
            })
            ActionType.WIFI -> setWifi(ctx, ToggleResolver.resolve(a.param) { stateWifiOn(ctx) })
            ActionType.HOTSPOT -> setHotspot(ctx, ToggleResolver.resolve(a.param) { stateHotspotOn(ctx) })
            ActionType.BLUETOOTH -> setBt(ctx, ToggleResolver.resolve(a.param) { stateBtOn() })
            ActionType.WIFI_CONNECTION, ActionType.BLUETOOTH_CONNECTION -> false
            ActionType.ROTATION_LOCK -> setRotationLock(ctx, a.param)
            ActionType.DND -> setDnd(ctx, ToggleResolver.resolve(a.param) {
                ctx?.let { enabledState(readGlobalInt(it, "zen_mode")) }
            })
            ActionType.INCOGNITO -> setIncognito(ctx, ToggleResolver.resolve(a.param) {
                ctx?.let { enabledState(readSecureInt(it, "incognito_mode_enabled")) }
            })
            ActionType.SHELL -> runShell(a.param)
            ActionType.BROADCAST -> sendBroadcast(ctx, a.param, a.param2)
            ActionType.START_SERVICE -> startService(ctx, a.param)
            ActionType.PLAY_SOUND, ActionType.SYSTEM_ALARM -> false
            ActionType.VIBRATE -> vibrate(ctx, a.param)
            ActionType.MEDIA_VOLUME -> setMediaVolume(ctx, a.param)
        }
        return ExecutionStep(a.logName(), if (ok) ExecutionStatus.SUCCESS else ExecutionStatus.FAILED,
            if (!ok) "执行失败或系统不支持，请检查参数、权限及系统日志"
            else when (a.type) {
                ActionType.KILL_BACKGROUND_APP -> "已提交后台进程清理请求；系统可能保留仍在使用的进程"
                ActionType.CLOSE_APP -> "系统已接受强制停止请求；应用再次启动后可恢复运行"
                else -> "系统调用已完成"
            })
    }

    // ------------------------------------------------------- capture/restore

    private fun captureOriginal(ruleId: String, a: Action, ctx: Context?) {
        if (ctx == null) return
        val original: String? = when (a.type) {
            ActionType.LOCATION -> readSecureInt(ctx, "location_mode").takeIf { it >= 0 }?.toString()
            ActionType.WIFI -> stateWifiOn(ctx)?.let { if (it) "1" else "0" }
            ActionType.HOTSPOT -> stateHotspotOn(ctx)?.toString()
            ActionType.BLUETOOTH -> stateBtOn()?.toString()
            ActionType.ROTATION_LOCK -> {
                val rotationMode = readSystemInt(ctx, "accelerometer_rotation")
                val userRotation = readSystemInt(ctx, "user_rotation")
                when {
                    rotationMode < 0 -> null
                    userRotation >= 0 -> "$rotationMode:$userRotation"
                    else -> rotationMode.toString()
                }
            }
            ActionType.DND -> readGlobalInt(ctx, "zen_mode").takeIf { it >= 0 }?.toString()
            ActionType.INCOGNITO -> readSecureInt(ctx, "incognito_mode_enabled").takeIf { it >= 0 }?.toString()
            ActionType.MEDIA_VOLUME -> (ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
                ?.getStreamVolume(AudioManager.STREAM_MUSIC)?.toString()
            else -> null
        }
        if (original != null) {
            synchronized(captured) {
                captured.getOrPut(ruleId) { mutableListOf() }.add(Captured(a, original))
            }
            RestoreStore.save(ruleId, synchronized(captured) { captured[ruleId] })
        }
    }

    private fun applyRestore(c: Captured): Boolean {
        val ctx = contextProvider() ?: return false
        val a = c.action
        val orig = c.original
        return when (a.type) {
            ActionType.LOCATION -> {
                val v = orig?.toIntOrNull()
                    ?: invertCurrent(a, readSecureInt(ctx, "location_mode"), enabledVal = 3, disabledVal = 0)
                v != null && writeSecureInt(ctx, "location_mode", v)
            }
            ActionType.WIFI -> {
                val v = orig?.toIntOrNull()
                    ?: invertCurrent(a, readGlobalInt(ctx, "wifi_on"), enabledVal = 1, disabledVal = 0)
                v != null && setWifi(ctx, v == 1)
            }
            ActionType.HOTSPOT -> {
                val on = orig?.toBooleanStrictOrNull() ?: when ((a.param ?: "toggle").lowercase()) {
                    "on" -> false
                    "off" -> true
                    else -> stateHotspotOn(ctx)?.not()
                }
                on != null && setHotspot(ctx, on)
            }
            ActionType.BLUETOOTH -> {
                // 原值未知时：显式 on/off 取反执行；toggle 只有读到可靠状态才取反，否则跳过
                val on = orig?.toBooleanStrictOrNull() ?: when ((a.param ?: "toggle").lowercase()) {
                    "on" -> false
                    "off" -> true
                    else -> stateBtOn()?.not()
                }
                on != null && setBt(ctx, on)
            }
            ActionType.ROTATION_LOCK -> {
                val parts = orig?.split(':')
                val originalUserRotation = parts?.getOrNull(1)?.toIntOrNull()
                val rotationWriteOk = originalUserRotation == null ||
                    writeSystemInt(ctx, "user_rotation", originalUserRotation)
                val originalMode = parts?.firstOrNull()?.toIntOrNull() ?: orig?.toIntOrNull()
                val v = originalMode
                    ?: invertCurrent(a, readSystemInt(ctx, "accelerometer_rotation"), enabledVal = 0, disabledVal = 1)
                v != null && rotationWriteOk && writeSystemInt(ctx, "accelerometer_rotation", v)
            }
            ActionType.DND -> {
                val v = orig?.toIntOrNull()
                    ?: invertCurrent(a, readGlobalInt(ctx, "zen_mode"), enabledVal = 1, disabledVal = 0)
                v != null && writeGlobalInt(ctx, "zen_mode", v)
            }
            ActionType.INCOGNITO -> {
                val v = orig?.toIntOrNull()
                    ?: invertCurrent(a, readSecureInt(ctx, "incognito_mode_enabled"), enabledVal = 1, disabledVal = 0)
                v != null && writeSecureInt(ctx, "incognito_mode_enabled", v)
            }
            ActionType.MEDIA_VOLUME -> {
                val manager = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
                val volume = orig?.toIntOrNull() ?: return false
                manager.setStreamVolume(AudioManager.STREAM_MUSIC,
                    volume.coerceIn(0, manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), 0)
                true
            }
            else -> true
        }
    }

    /**
     * 原值缺失时的恢复兜底（对应类注释“否则按 on/off 反转”）：
     * 显式 on/lock 动作 -> 写 disabledVal；off/unlock -> 写 enabledVal；
     * toggle -> 读当前值取反；当前值未知（非法/越界）则返回 null（不盲目写默认关闭）。
     */
    private fun invertCurrent(a: Action, cur: Int, enabledVal: Int, disabledVal: Int): Int? =
        when ((a.param ?: "toggle").lowercase()) {
            "on", "lock" -> disabledVal
            "off", "unlock" -> enabledVal
            else -> when (cur) {
                enabledVal -> disabledVal
                disabledVal -> enabledVal
                else -> null
            }
        }

    // ------------------------------------------------------------- actions

    @SuppressLint("MissingPermission")
    private fun vibrate(ctx: Context?, value: String?): Boolean {
        val duration = value?.toLongOrNull()?.takeIf { it in 1..10_000 } ?: return false
        val vibrator = ctx?.getSystemService(Vibrator::class.java)?.takeIf { it.hasVibrator() } ?: return false
        vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
        return true
    }

    private fun setMediaVolume(ctx: Context?, value: String?): Boolean {
        val percent = value?.toIntOrNull()?.takeIf { it in 0..100 } ?: return false
        val manager = ctx?.getSystemService(AudioManager::class.java) ?: return false
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val volume = kotlin.math.round(max * percent / 100.0).toInt()
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
        return manager.getStreamVolume(AudioManager.STREAM_MUSIC) == volume
    }

    private fun openApp(ctx: Context?, pkg: String?): Boolean {
        if (ctx == null || pkg.isNullOrBlank()) return false
        val pm = ctx.packageManager
        val intent = pm.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            XLog.w("openApp: no launch intent for $pkg")
            return false
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        XLog.i("action: openApp $pkg")
        return true
    }

    // Executed in system_server; this only requests cleanup of eligible background processes.
    @SuppressLint("MissingPermission")
    private fun killBackgroundApp(ctx: Context?, pkg: String?): Boolean {
        if (ctx == null || pkg.isNullOrBlank()) return false
        return runCatching {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.killBackgroundProcesses(pkg)
            XLog.i("action: killBackgroundProcesses requested for $pkg")
            true
        }.onFailure { XLog.w("killBackgroundProcesses failed: ${it.message}") }.getOrDefault(false)
    }

    // Force-stop is separate because it also disables the app until it is launched again.
    private fun forceStopApp(ctx: Context?, pkg: String?): Boolean {
        if (ctx == null || pkg.isNullOrBlank()) return false
        return runCatching {
            val amClass = Class.forName("android.app.ActivityManager")
            val m = Reflect.findMethodByName(amClass, "forceStopPackage", 1)
                ?: throw NoSuchMethodException("ActivityManager.forceStopPackage")
            m.invoke(ctx.getSystemService(Context.ACTIVITY_SERVICE), pkg)
            XLog.i("action: forceStopPackage $pkg")
            true
        }.onFailure { XLog.w("forceStopPackage failed: ${it.message}") }.getOrDefault(false)
    }

    private fun setLocation(ctx: Context?, on: Boolean?): Boolean {
        if (ctx == null || on == null) return false
        // 候选 1：LocationManager.setLocationEnabledForUser（隐藏，API 28+，优先）
        val ok = runCatching {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val m = Reflect.findMethodByName(lm.javaClass, "setLocationEnabledForUser", 2)
                ?: Reflect.findMethodByName(lm.javaClass, "setLocationEnabledForUser", 3)
            val user = Class.forName("android.os.Process").getMethod("myUserHandle").invoke(null)
            if (m != null && m.parameterTypes.size == 2) {
                m.invoke(lm, on, user)
            } else if (m != null) {
                m.invoke(lm, on, user, true)
            } else throw NoSuchMethodException("setLocationEnabledForUser")
            true
        }.getOrDefault(false)
        val applied = if (!ok) {
            // 候选 2：写 Settings.Secure location_mode（0=off，3=high accuracy）
            writeSecureInt(ctx, "location_mode", if (on) 3 else 0)
        } else true
        XLog.i("action: location -> $on")
        return applied
    }

    private fun setWifi(ctx: Context?, on: Boolean?): Boolean {
        if (ctx == null || on == null) return false
        ConnectionActions.cancelWifi()
        // 候选 1：WifiManager.setWifiEnabled（隐藏但长期存在）
        val ok = runCatching {
            val wm = ctx.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val m = Reflect.findMethodByName(wm.javaClass, "setWifiEnabled", 1)
            m?.invoke(wm, on) == true
        }.getOrDefault(false)
        val applied = if (!ok) {
            // 候选 2：写 Settings.Global wifi_on（部分版本仅影响 UI 状态，尽力而为）
            writeGlobalInt(ctx, "wifi_on", if (on) 1 else 0)
        } else true
        XLog.i("action: wifi -> $on")
        return applied
    }

    private fun stateHotspotOn(ctx: Context?): Boolean? = runCatching {
        val manager = ctx?.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            ?: return@runCatching null
        val state = Reflect.findMethodByName(manager.javaClass, "getWifiApState", 0)
            ?.invoke(manager) as? Int ?: return@runCatching null
        when (state) {
            WIFI_AP_STATE_ENABLED -> true
            WIFI_AP_STATE_DISABLED -> false
            else -> null
        }
    }.getOrNull()

    /** Prefer the modular tethering service, retaining ConnectivityService for older Android. */
    private fun setHotspot(ctx: Context?, on: Boolean?): Boolean {
        check(ctx != null) { "系统上下文不可用，无法控制热点" }
        check(on != null) { "热点状态或开关参数不可用" }
        if (stateHotspotOn(ctx) == on) return true
        ConnectionActions.cancelWifi()
        HotspotActions.set(ctx, on)?.let { return it }
        val manager = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: error("系统没有 TetheringManager 或 ConnectivityManager")
        val service = Reflect.getField(manager, "mService") ?: error("系统网络服务不可用")
        return try {
            if (!on) {
                val method = service.javaClass.methods.firstOrNull { candidate ->
                    candidate.name == "stopTethering" && candidate.parameterTypes.firstOrNull() == Int::class.javaPrimitiveType
                } ?: error("系统未提供兼容的热点关闭接口")
                val args = candidateArguments(method.parameterTypes, TETHERING_WIFI,
                    opPackageName(ctx), attributionTag(ctx))
                method.isAccessible = true
                method.invoke(service, *args)
                XLog.i("action: hotspot -> off")
                return true
            }

            val completed = CountDownLatch(1)
            val result = AtomicInteger(Int.MIN_VALUE)
            val callback = object : ResultReceiver(null) {
                override fun onReceiveResult(resultCode: Int, resultData: android.os.Bundle?) {
                    result.set(resultCode)
                    completed.countDown()
                }
            }
            val method = service.javaClass.methods.firstOrNull { candidate ->
                val parameters = candidate.parameterTypes
                candidate.name == "startTethering" && parameters.size >= 3 &&
                    parameters[0] == Int::class.javaPrimitiveType &&
                    parameters[1].isAssignableFrom(ResultReceiver::class.java) &&
                    parameters[2] == Boolean::class.javaPrimitiveType
            } ?: error("系统未提供兼容的热点开启接口")
            val args = arrayOfNulls<Any>(method.parameterTypes.size)
            args[0] = TETHERING_WIFI
            args[1] = callback
            args[2] = false
            var stringIndex = 0
            for (index in 3 until args.size) {
                if (method.parameterTypes[index] == String::class.java) {
                    args[index] = if (stringIndex++ == 0) opPackageName(ctx) else attributionTag(ctx)
                }
            }
            method.isAccessible = true
            method.invoke(service, *args)
            check(completed.await(15, TimeUnit.SECONDS)) { "热点开启超时，系统未返回结果" }
            check(result.get() == 0) { "系统拒绝开启热点，Tethering 错误码 ${result.get()}" }
            XLog.i("action: hotspot -> on (legacy accepted)")
            true
        } catch (t: Throwable) {
            XLog.e("hotspot toggle failed", t)
            throw t
        }
    }

    private fun candidateArguments(
        types: Array<Class<*>>,
        type: Int,
        packageName: String,
        attributionTag: String?,
    ): Array<Any?> {
        var stringIndex = 0
        return Array(types.size) { index ->
            when {
                index == 0 && types[index] == Int::class.javaPrimitiveType -> type
                types[index] == String::class.java ->
                    if (stringIndex++ == 0) packageName else attributionTag
                else -> null
            }
        }
    }

    private fun attributionTag(ctx: Context): String? = runCatching {
        ctx.javaClass.getMethod("getAttributionTag").invoke(ctx) as? String
    }.getOrNull()

    /** getOpPackageName is public only since API 29; both names match in system_server. */
    private fun opPackageName(ctx: Context): String =
        if (Build.VERSION.SDK_INT >= 29) ctx.opPackageName else ctx.packageName

    private fun setBt(ctx: Context?, on: Boolean?): Boolean {
        if (ctx == null || on == null) return false
        ConnectionActions.cancelBluetooth()
        // 候选 1：BluetoothAdapter.enable()/disable()（隐藏，uid=1000 可用）
        val ok = runCatching {
            val adapter = Class.forName("android.bluetooth.BluetoothAdapter")
                .getMethod("getDefaultAdapter").invoke(null) ?: return@runCatching false
            val m = Reflect.findMethodByName(adapter.javaClass, if (on) "enable" else "disable", 0)
            m?.invoke(adapter) == true
        }.getOrDefault(false)
        if (!ok) XLog.w("bluetooth toggle unavailable on this build (degraded)")
        XLog.i("action: bluetooth -> $on")
        return ok
    }

    /** 蓝牙是否开启；null 表示读取失败（状态未知），不能当 original 捕获。 */
    private fun stateBtOn(): Boolean? = runCatching {
        val adapter = Class.forName("android.bluetooth.BluetoothAdapter")
            .getMethod("getDefaultAdapter").invoke(null) ?: return@runCatching null
        (adapter as android.bluetooth.BluetoothAdapter).isEnabled
    }.getOrNull()

    private fun stateWifiOn(ctx: Context?): Boolean? = runCatching {
        val wm = ctx?.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            ?: return@runCatching null
        wm.isWifiEnabled
    }.getOrNull()

    /**
     * 方向锁定：写 Settings.System.ACCELEROMETER_ROTATION（0=锁定，1=自动旋转），
     * 并把当前方向写进 USER_ROTATION 以锁定画面（尽力而为）。
     * 这是“启动相机时自动锁定方向”最小示例的执行终点。
     */
    private fun setRotationLock(ctx: Context?, p: String?): Boolean {
        if (ctx == null) return false
        val command = when (p?.lowercase()) {
            "lock" -> "on"
            "unlock" -> "off"
            else -> p
        }
        val lock = ToggleResolver.resolve(command) {
            when (readSystemInt(ctx, "accelerometer_rotation")) {
                0 -> true
                1 -> false
                else -> null
            }
        } ?: return false
        if (lock) {
            val rotation = runCatching {
                val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
                @Suppress("DEPRECATION")
                wm?.defaultDisplay?.rotation
            }.getOrNull()?.takeIf { it in 0..3 } ?: return false
            if (!writeSystemInt(ctx, "user_rotation", rotation)) return false
        }
        val applied = writeSystemInt(ctx, "accelerometer_rotation", if (lock) 0 else 1)
        XLog.i("action: rotationLock locked=$lock")
        return applied
    }

    private fun setDnd(ctx: Context?, on: Boolean?): Boolean {
        if (ctx == null || on == null) return false
        // 候选 1：NotificationManager.setInterruptionFilter（反射，优先）
        val ok = runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.setInterruptionFilter(
                if (on) android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
                else android.app.NotificationManager.INTERRUPTION_FILTER_ALL
            )
            true
        }.getOrDefault(false)
        val applied = if (!ok) {
            // 候选 2：写 Settings.Global zen_mode（0=off）
            writeGlobalInt(ctx, "zen_mode", if (on) 1 else 0)
        } else true
        XLog.i("action: dnd -> $on")
        return applied
    }

    /**
     * 隐身模式（HyperOS 私有）：无公开稳定 API。
     * 候选优先级：
     *  1. Settings.Secure "incognito_mode_enabled"（HyperOS 12+/13 常用键）
     *  2. 广播 miui.intent.action.SECURITY_CENTER_INCOGNITO_MODE_CHANGED
     * 两个都失败则记录降级日志（不 crash、不重试轰炸）。
     */
    private fun setIncognito(ctx: Context?, on: Boolean?): Boolean {
        if (ctx == null || on == null) return false
        val v = if (on) 1 else 0
        val ok = runCatching { writeSecureInt(ctx, "incognito_mode_enabled", v) }
            .getOrDefault(false)
        if (!ok || readSecureInt(ctx, "incognito_mode_enabled") != v) {
            runCatching {
                ctx.sendBroadcast(Intent("miui.intent.action.SECURITY_CENTER_INCOGNITO_MODE_CHANGED")
                    .putExtra("enabled", on))
                XLog.i("action: incognito via broadcast -> $on")
                return true
            }
            XLog.w("incognito toggle not supported on this build (degraded)")
            return false
        }
        XLog.i("action: incognito -> $on")
        return true
    }

    /** 高风险：仅当 allow_shell=true 才会到达这里（见 executeOne 前置拦截）。 */
    private fun runShell(cmd: String?): Boolean {
        if (cmd.isNullOrBlank()) return false
        return runCatching {
            val proc = ProcessBuilder("sh", "-c", cmd)
                .redirectErrorStream(true)
                .start()

            // Drain output concurrently so a verbose command cannot block on a full pipe.
            val output = ByteArrayOutputStream()
            val reader = Thread({
                runCatching {
                    proc.inputStream.use { input ->
                        val buffer = ByteArray(4096)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            val remaining = MAX_SHELL_OUTPUT_BYTES - output.size()
                            if (remaining > 0) output.write(buffer, 0, minOf(count, remaining))
                        }
                    }
                }
            }, "atlt-shell-output").apply {
                isDaemon = true
                start()
            }

            if (!proc.waitFor(SHELL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly()
                proc.waitFor(1, TimeUnit.SECONDS)
                reader.join(1_000)
                XLog.w("shell timeout (${SHELL_TIMEOUT_MS}ms), killed: $cmd")
                return@runCatching false
            }
            reader.join(1_000)
            val out = String(output.toByteArray(), Charsets.UTF_8).trim()
            XLog.i("shell executed (exit=${proc.exitValue()}): $cmd -> ${out.take(200)}")
            proc.exitValue() == 0
        }.onFailure { XLog.e("shell failed (degraded)", it) }.getOrDefault(false)
    }

    private fun sendBroadcast(ctx: Context?, action: String?, extras: String?): Boolean {
        if (ctx == null || action.isNullOrBlank()) return false
        val i = Intent(action)
        extras?.split(",")?.forEach { kv ->
            val p = kv.split("=", limit = 2)
            if (p.size == 2) i.putExtra(p[0].trim(), p[1].trim())
        }
        ctx.sendBroadcast(i)
        XLog.i("action: broadcast $action")
        return true
    }

    private fun startService(ctx: Context?, comp: String?): Boolean {
        if (ctx == null || comp.isNullOrBlank()) return false
        val component = ComponentName.unflattenFromString(comp)
        if (component == null) {
            XLog.w("startService param must be pkg/cls")
            return false
        }
        return runCatching {
            val started = ctx.startService(Intent().setComponent(component))
            XLog.i("action: startService $comp")
            started != null
        }.onFailure { XLog.w("startService failed (degraded): ${it.message}") }.getOrDefault(false)
    }

    // ------------------------------------------------------- settings helpers

    private fun cr(ctx: Context) = ctx.contentResolver

    private fun enabledState(value: Int): Boolean? = value.takeIf { it >= 0 }?.let { it > 0 }

    private fun readSecureInt(ctx: Context, key: String): Int =
        android.provider.Settings.Secure.getInt(cr(ctx), key, -1)

    private fun readGlobalInt(ctx: Context, key: String): Int =
        android.provider.Settings.Global.getInt(cr(ctx), key, -1)

    private fun readSystemInt(ctx: Context, key: String): Int =
        android.provider.Settings.System.getInt(cr(ctx), key, -1)

    private fun writeSecureInt(ctx: Context, key: String, v: Int): Boolean =
        runCatching { android.provider.Settings.Secure.putInt(cr(ctx), key, v) }
            .onFailure { XLog.w("write secure $key failed: ${it.message}") }
            .getOrDefault(false)

    private fun writeGlobalInt(ctx: Context, key: String, v: Int): Boolean =
        runCatching { android.provider.Settings.Global.putInt(cr(ctx), key, v) }
            .onFailure { XLog.w("write global $key failed: ${it.message}") }
            .getOrDefault(false)

    private fun writeSystemInt(ctx: Context, key: String, v: Int): Boolean =
        runCatching { android.provider.Settings.System.putInt(cr(ctx), key, v) }
            .onFailure { XLog.w("write system $key failed: ${it.message}") }
            .getOrDefault(false)
}

/**
 * 恢复状态持久化：system_server 可写 /data/system/（system uid 属主），
 * 用于 system_server 重启后恢复动作原值；写入失败时仅保留内存态（明确降级）。
 */
object RestoreStore {
    private const val DIR = "/data/system/autotasklite"
    private val validRuleId = Regex("[A-Za-z0-9_-]{1,128}")

    private fun file(ruleId: String): File? =
        if (validRuleId.matches(ruleId)) File(DIR, "restore_$ruleId.json") else null

    fun save(ruleId: String, captured: List<ActionExecutor.Captured>?) {
        runCatching {
            val target = file(ruleId) ?: return
            val dir = File(DIR)
            if (!dir.exists()) dir.mkdirs()
            val arr = JSONArray()
            captured?.forEach { c ->
                arr.put(JSONObject()
                    .put("type", c.action.type.code)
                    .put("param", c.action.param ?: "")
                .put("param2", c.action.param2 ?: "")
                    .put("original", c.original ?: ""))
            }
            val temp = File(dir, "${target.name}.tmp")
            temp.writeText(arr.toString())
            runCatching {
                Files.move(temp.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.getOrElse {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }.onFailure { XLog.w("restore persist failed (memory-only): ${it.message}") }
    }

    fun load(ruleId: String): JSONArray? = runCatching {
        val f = file(ruleId) ?: return null
        if (f.exists()) JSONArray(f.readText()) else null
    }.getOrNull()

    fun ruleIds(): List<String> = runCatching {
        File(DIR).listFiles().orEmpty().mapNotNull { entry ->
            if (!entry.isFile || !entry.name.startsWith("restore_") || !entry.name.endsWith(".json")) {
                return@mapNotNull null
            }
            entry.name.removePrefix("restore_").removeSuffix(".json")
                .takeIf(validRuleId::matches)
        }
    }.getOrDefault(emptyList())

    fun clear(ruleId: String) {
        runCatching { file(ruleId)?.delete() }
    }
}
