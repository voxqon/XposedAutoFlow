package com.github.voxqon.xposed.autoflow.engine

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.github.voxqon.xposed.autoflow.model.ConditionType
import com.github.voxqon.xposed.autoflow.model.Condition
import com.github.voxqon.xposed.autoflow.model.Action
import com.github.voxqon.xposed.autoflow.model.GeofenceSpec
import com.github.voxqon.xposed.autoflow.model.Rule
import com.github.voxqon.xposed.autoflow.model.MAX_RULE_COOLDOWN_SECONDS
import com.github.voxqon.xposed.autoflow.model.TriggerSchedule
import com.github.voxqon.xposed.autoflow.store.RuleStore
import com.github.voxqon.xposed.autoflow.util.XLog

/**
 * 规则引擎 —— 核心流程：
 *
 *   条件监听（各 Hook 注册器 / 接收器 / 观察者产生 Event）
 *        -> onEvent(e)
 *        -> 规则匹配：
 *             触发：日期允许、按规则的全部/任一组合模式满足，且由本事件新满足；冷却期内跳过
 *             -> 标记“已触发”，按顺序执行 actions（先捕获原值；单个动作可延迟）
 *        -> 已触发规则检查退出：
 *             任一退出条件经历“未满足 -> 满足”（或关系；避免触发时已成立就立刻恢复）
 *             -> 执行 restoreActions / 写回原值，回到未触发状态
 *
 * 引擎只评估“已启用”规则；所有逻辑运行在独立 HandlerThread，不阻塞 Hook 调用线程。
 */
class RuleEngine(
    private val state: StateStore,
    private val executor: ActionExecutor,
) {
    private val engineThread = HandlerThread("atlt-engine").apply { start() }
    private val handler = Handler(engineThread.looper)

    private data class TriggeredState(
        val rule: Rule,
        val exitSatisfiedAtTrigger: BooleanArray,
        val exitObservedFalse: BooleanArray,
    )

    private class PendingActionRun(val rule: Rule)

    /** ruleId -> exit hysteresis state captured when the rule triggered. */
    private val triggered = HashMap<String, TriggeredState>()
    private val lastTriggeredAt = HashMap<String, Long>()
    private val pendingActionRuns = HashMap<String, PendingActionRun>()

    private var started = false
    private var tickScheduled = false
    private var nextTickAt = 0L
    private val tick = Runnable {
        tickScheduled = false
        post(Event(EventTypes.TICK))
    }
    private var loadedRules: List<Rule>? = null
    private var enabledRules: List<Rule> = emptyList()
    private var geofenceConditions: List<Condition> = emptyList()
    @Volatile private var geofenceObserver: ((List<Condition>, Boolean) -> Unit)? = null
    private var lastGeofenceConditions: List<Condition>? = null
    private var lastGeofenceLocationEnabled: Boolean? = null

    /** 仅由 30s 心跳驱动的持续型条件类型。 */
    private val tickDriven = setOf(
        ConditionType.TIMER,
        ConditionType.TIME_RANGE,
        ConditionType.SCREEN_ON_FOR,
        ConditionType.SCREEN_OFF_FOR,
    )

    private companion object {
        const val TICK_INTERVAL_MS = 30_000L
        const val RULES_REFRESH_INTERVAL_MS = 60_000L
    }

    init {
        // Persisted action values are restored before the engine begins evaluating events.
        XLog.i("RuleEngine initialized")
    }

    fun post(event: Event) {
        handler.post { runCatching { onEvent(event) }.onFailure { XLog.e("event ${event.type} eval failed", it) } }
    }

    fun recoverPersistedState() {
        handler.post {
            runCatching { executor.restorePersistedAll() }
                .onFailure { XLog.e("persisted state recovery failed", it) }
        }
    }

    /** Observe enabled geofence conditions and location availability without coupling the engine to Android APIs. */
    fun setGeofenceObserver(observer: ((List<Condition>, Boolean) -> Unit)?) {
        handler.post {
            geofenceObserver = observer
            lastGeofenceConditions = null
            lastGeofenceLocationEnabled = null
        }
    }

    /** 引擎入口：先更新状态快照，再评估规则。 */
    private fun onEvent(e: Event) {
        if (e.type == EventTypes.SCREEN_ON && state.screenOn && state.screenOnSince >= 0) return
        if (e.type == EventTypes.SCREEN_OFF && !state.screenOn && state.screenOffSince >= 0) return
        updateState(e)
        when (e.type) {
            EventTypes.APP_FG -> {
                val key = e.pkg ?: return
                // Ignore redundant resumes without dropping a real A -> B -> A transition.
                if (!state.noteForeground(key)) return
                // 派生“离开应用”事件
                val prev = state.prevForegroundPkg
                if (prev != null && prev != key) {
                    evaluateRules(Event(EventTypes.APP_LEFT, pkg = prev))
                }
            }
        }
        evaluateRules(e)
    }

    private fun updateState(e: Event) {
        when (e.type) {
            EventTypes.SCREEN_ON -> state.noteScreen(true, SystemClock.elapsedRealtime())
            EventTypes.SCREEN_OFF -> state.noteScreen(false, SystemClock.elapsedRealtime())
            EventTypes.WIFI -> {
                if (e.value == "on") state.wifiOn = true
                if (e.value == "off") {
                    state.wifiOn = false
                    state.currentSsid = null
                    state.wifiConnected = false
                }
            }
            EventTypes.SSID -> {
                // 连接：更新 SSID（Wi-Fi 必然开启）；断开：仅清除 SSID，
                // Wi-Fi 本身未必关闭（断开 != 关闭），不能写 wifiOn=false
                if (e.value2 == "connect") {
                    state.currentSsid = e.value
                    state.wifiOn = true
                    state.wifiConnected = true
                } else {
                    state.currentSsid = null
                    state.wifiConnected = false
                }
            }
            EventTypes.BT -> state.noteBluetooth(e.value == "on")
            EventTypes.BTDEV -> state.noteBluetoothDevice(e.value, e.value2 == "connect")
            EventTypes.LOCATION -> {
                state.locationOn = e.value == "on"
                if (!state.locationOn) state.clearGeofences()
            }
            EventTypes.ROTATION -> state.rotationLocked = e.value == "locked"
            EventTypes.DND -> state.dndOn = e.value == "on"
            EventTypes.CHARGER -> state.charging = e.value == "on"
            EventTypes.BATTERY -> {
                state.batteryLevel = e.value?.toIntOrNull() ?: state.batteryLevel
                if (e.value2 != null) state.charging = e.value2 == "on"
            }
            EventTypes.KEYGUARD -> {
                state.keyguardShown = e.value == "shown"
                state.keyguardKnown = true
            }
            EventTypes.POWER_SAVE -> state.powerSaveOn = e.value == "on"
            EventTypes.NIGHT_MODE -> state.nightModeOn = e.value == "on"
            EventTypes.HEADSET -> state.headsetConnected = e.value == "on"
            EventTypes.AIRPLANE -> state.airplaneModeOn = e.value == "on"
            EventTypes.HOTSPOT -> state.hotspotOn = e.value == "on"
            EventTypes.WIFI_CONNECTION -> {
                state.wifiConnected = e.value == "on"
                if (e.value == "off") state.currentSsid = null
            }
            EventTypes.BT_CONNECTION -> state.noteBluetoothConnectionKnown()
            EventTypes.GEOFENCE -> if (state.locationOn) {
                state.noteGeofence(e.value2, e.value,
                    e.extras["locationElapsedMs"]?.toLongOrNull() ?: SystemClock.elapsedRealtime())
            }
            EventTypes.NOTIF, EventTypes.APP_COLD, EventTypes.APP_KILLED, EventTypes.TICK -> Unit
        }
    }

    /** 规则匹配核心：只评估启用规则；触发 -> 已触发；退出 -> 恢复 -> 未触发。 */
    private fun evaluateRules(e: Event) {
        if (!started) return
        val rules: List<Rule> = try {
            val loaded = RuleStore.load()
            if (loaded !== loadedRules) {
                val previousRules = enabledRules
                loadedRules = loaded
                enabledRules = loaded.filter { it.enabled }
                geofenceConditions = enabledRules.asSequence()
                    .flatMap { it.conditions.asSequence() + it.exitConditions.asSequence() }
                    .flatMap { it.leaves().asSequence() }
                    .filter { it.type == ConditionType.GEOFENCE }
                    .distinct()
                    .toList()
                reconcileRules(enabledRules, previousRules)
            }
            enabledRules
        } catch (t: Throwable) {
            XLog.e("load rules failed", t)
            // 载入失败也要维持心跳链，否则定时/持续时长条件永久停摆
            if (e.type == EventTypes.TICK) rescheduleTick(RULES_REFRESH_INTERVAL_MS)
            return
        }
        maybeScheduleTick(rules)
        syncGeofenceObserver()

        val now = SystemClock.elapsedRealtime()
        val wallTimeMillis = System.currentTimeMillis()

        // ---- 触发：未触发规则，按组合模式满足且至少一个条件由本事件新满足 ----
        for (r in rules) {
            if (synchronized(triggered) { triggered.containsKey(r.id) } ||
                pendingActionRuns.containsKey(r.id) || r.conditions.isEmpty()) continue
            if (!TriggerSchedule.allowsAtMillis(r.scheduleMode, r.allowedDaysMask, wallTimeMillis)) continue

            val cooldownMs = r.cooldownSeconds.coerceIn(0L, MAX_RULE_COOLDOWN_SECONDS) * 1_000L
            val lastTrigger = lastTriggeredAt[r.id]
            if (lastTrigger != null && now >= lastTrigger && now - lastTrigger < cooldownMs) continue
            if (!ConditionEvaluator.triggerSatisfied(r.conditions, r.triggerMode, e, state, now, wallTimeMillis)) continue

            val snapshot = BooleanArray(r.exitConditions.size) {
                ConditionEvaluator.satisfiedForCurrentEvent(r.exitConditions[it], e, state, now, wallTimeMillis)
            }
            synchronized(triggered) {
                triggered[r.id] = TriggeredState(r, snapshot, BooleanArray(snapshot.size))
            }
            lastTriggeredAt[r.id] = now
            XLog.i("rule TRIGGER \"${r.name}\" by event ${e.type}(${e.pkg ?: e.value})")
            executeActions(r, "触发执行", eventReason(e), r.actions, capture = true)
        }

        // ---- 退出：已触发规则，任一退出条件满足（或关系）即恢复 ----
        for (r in rules) {
            val triggerState = synchronized(triggered) { triggered[r.id] } ?: continue
            if (r.exitConditions.isEmpty()) continue
            val fired = r.exitConditions.indices.any { i ->
                val c = r.exitConditions[i]
                val sNow = ConditionEvaluator.satisfiedForCurrentEvent(c, e, state, now, wallTimeMillis)
                if (!sNow) triggerState.exitObservedFalse[i] = true
                ConditionEvaluator.shouldExit(
                    triggerState.exitSatisfiedAtTrigger[i],
                    triggerState.exitObservedFalse[i],
                    sNow,
                )
            }
            if (fired) {
                synchronized(triggered) { triggered.remove(r.id) }
                XLog.i("rule RESTORE \"${r.name}\" by event ${e.type}(${e.pkg ?: e.value})")
                executeActions(r, "退出恢复", eventReason(e), r.restoreActions,
                    capture = false, restoreCaptured = true)
            }
        }

    }

    private fun reconcileRules(rules: List<Rule>, previousRules: List<Rule>) {
        val byId = rules.associateBy { it.id }
        // Exit runs also own pending delays after their triggered state has been removed.
        val active = LinkedHashMap<String, Rule>()
        previousRules.filter { it.id in lastTriggeredAt }.forEach { active[it.id] = it }
        synchronized(triggered) { triggered.values.forEach { active[it.rule.id] = it.rule } }
        pendingActionRuns.values.forEach { active[it.rule.id] = it.rule }
        active.values.forEach { rule ->
            val current = byId[rule.id]
            if (current != null && sameTriggerConfig(rule, current)) return@forEach
            synchronized(triggered) { triggered.remove(rule.id) }
            lastTriggeredAt.remove(rule.id)
            XLog.i("rule ${rule.id} changed/removed -> cancel pending actions and restore")
            executeActions(rule, if (current == null) "停用恢复" else "规则修改恢复",
                if (current == null) "规则已被禁用或删除" else "规则配置发生变化", emptyList(),
                capture = false, restoreCaptured = true)
        }
        lastTriggeredAt.keys.retainAll(byId.keys)
    }

    private fun eventReason(event: Event): String {
        val name = when (event.type) {
            EventTypes.APP_FG -> "应用进入前台"
            EventTypes.APP_LEFT -> "应用离开前台"
            EventTypes.APP_COLD -> "应用冷启动"
            EventTypes.APP_KILLED -> "应用进程退出"
            EventTypes.SCREEN_ON -> "亮屏"
            EventTypes.SCREEN_OFF -> "息屏"
            EventTypes.WIFI -> "Wi-Fi 开关变化"
            EventTypes.SSID -> "Wi-Fi 连接变化"
            EventTypes.BT -> "蓝牙开关变化"
            EventTypes.BTDEV -> "蓝牙连接变化"
            EventTypes.LOCATION -> "定位开关变化"
            EventTypes.ROTATION -> "方向锁定变化"
            EventTypes.DND -> "勿扰模式变化"
            EventTypes.CHARGER -> "充电状态变化"
            EventTypes.BATTERY -> "电量变化"
            EventTypes.TICK -> "时间或持续时长条件满足"
            EventTypes.GEOFENCE -> "地理围栏变化"
            EventTypes.KEYGUARD -> "锁屏状态变化"
            EventTypes.POWER_SAVE -> "省电模式变化"
            EventTypes.NIGHT_MODE -> "夜间模式变化"
            EventTypes.HEADSET -> "有线耳机连接变化"
            EventTypes.AIRPLANE -> "飞行模式变化"
            EventTypes.HOTSPOT -> "个人热点变化"
            EventTypes.WIFI_CONNECTION -> "Wi-Fi 连接状态变化"
            EventTypes.BT_CONNECTION -> "蓝牙连接状态变化"
            else -> event.type
        }
        return listOfNotNull(name, event.pkg, event.value, event.value2).joinToString(" · ")
    }

    private fun executeActions(
        rule: Rule,
        phase: String,
        reason: String,
        actions: List<Action>,
        capture: Boolean,
        restoreCaptured: Boolean = false,
    ) {
        pendingActionRuns.remove(rule.id)
        if (!capture) executor.cancelPending(rule.id)
        val run = PendingActionRun(rule)
        pendingActionRuns[rule.id] = run
        executor.executeLogged(rule, phase, reason, actions, capture, restoreCaptured, handler) {
            if (pendingActionRuns[rule.id] === run) pendingActionRuns.remove(rule.id)
        }
    }

    private fun sameTriggerConfig(a: Rule, b: Rule): Boolean =
        a.conditions == b.conditions && a.actions == b.actions &&
            a.exitConditions == b.exitConditions && a.restoreActions == b.restoreActions &&
            a.triggerMode == b.triggerMode && a.allowedDaysMask == b.allowedDaysMask &&
            a.scheduleMode == b.scheduleMode && a.cooldownSeconds == b.cooldownSeconds

    private fun syncGeofenceObserver() {
        val conditions = geofenceConditions
        val locationEnabled = state.locationOn
        if (conditions == lastGeofenceConditions && locationEnabled == lastGeofenceLocationEnabled) return
        lastGeofenceConditions = conditions
        lastGeofenceLocationEnabled = locationEnabled
        state.retainGeofences(conditions.mapNotNull { GeofenceSpec.parse(it.param)?.id }.toSet())
        runCatching { geofenceObserver?.invoke(conditions, locationEnabled) }
            .onFailure { XLog.e("geofence monitor sync failed", it) }
    }

    /** Active time-based rules tick every 30s; idle heartbeats notice newly saved rules. */
    private fun maybeScheduleTick(rules: List<Rule>) {
        val needTick = rules.any { r ->
            (r.conditions + r.exitConditions).any { condition ->
                condition.leaves().any { it.type in tickDriven }
            }
        }
        rescheduleTick(if (needTick) TICK_INTERVAL_MS else RULES_REFRESH_INTERVAL_MS)
    }

    private fun rescheduleTick(delayMs: Long) {
        val dueAt = SystemClock.uptimeMillis() + delayMs
        if (tickScheduled && nextTickAt <= dueAt) return
        handler.removeCallbacks(tick)
        nextTickAt = dueAt
        tickScheduled = true
        handler.postDelayed(tick, delayMs)
    }

    /** 首个 TICK：让持续时长/定时条件有机会启动。 */
    fun start() {
        handler.post {
            if (started) return@post
            started = true
            runCatching { onEvent(Event(EventTypes.TICK)) }
                .onFailure { XLog.e("initial rule evaluation failed", it) }
        }
    }
}
