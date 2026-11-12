package com.github.voxqon.xposed.autoflow.hook

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.app.KeyguardManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.PowerManager
import com.github.voxqon.xposed.autoflow.engine.EventBridge
import com.github.voxqon.xposed.autoflow.engine.ConnectionCatalogBridge
import com.github.voxqon.xposed.autoflow.engine.EventTypes
import com.github.voxqon.xposed.autoflow.engine.RuleEngine
import com.github.voxqon.xposed.autoflow.store.RuleStore
import com.github.voxqon.xposed.autoflow.store.RuleConfigContract
import com.github.voxqon.xposed.autoflow.util.Reflect
import com.github.voxqon.xposed.autoflow.util.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * android(system_server) 注册器 —— 绝大多数条件的权威监听进程。
 *
 * Hook 点清单（对应条件）：
 * 1. PowerManagerService#updatePowerStateLocked + mWakefulnessRaw / mWakefulness 字段
 *    —— 亮屏/息屏（SCREEN_ON/SCREEN_OFF、亮/熄屏持续时长计时基准）。
 *    候选：受保护的 ACTION_SCREEN_ON / ACTION_SCREEN_OFF 系统广播。
 * 2. ActivityRecord#activityResumed / ActivityTaskSupervisor(旧:ActivityStackSupervisor)
 *    #realStartActivityLocked —— 应用进入前台（APP_FOREGROUND / APP_LEFT）。
 *    优先级：activityResumed（真实 resume，最准）> realStartActivityLocked。
 * 3. ProcessList / ActivityManagerService#handleProcessStartedLocked
 *    —— 冷启动（APP_COLD_START，成功创建进程后；旧系统回退 startProcessLocked）。
 * 4. ProcessRecord#killLocked + ActivityManagerService#forceStopPackageLocked
 *    —— 应用被杀死（APP_KILLED，进程销毁节点）。
 * 5. NotificationManagerService#setInterruptionFilter —— 勿扰模式开关。
 * 6. 运行时注册（systemContext）：电池/充电、Wi-Fi 状态与 SSID、蓝牙状态与设备、
 *    定位模式、旋转锁定、zen_mode 的 ContentObserver —— 对应充电/电量/Wi-Fi/蓝牙/
 *    定位/方向锁定/勿扰条件（框架层广播与设置项变更，符合要求第 2 条）。
 * 7. 引擎内置 30s 心跳 —— 定时触发、亮/熄屏持续时长。
 */
object AndroidFrameworkHooks {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastInteractive: Boolean? = null
    private var lastFgPkg: String? = null

    fun register(lpparam: XC_LoadPackage.LoadPackageParam) {
        val engine = SharedObjects.get().first
        hookPowerState(lpparam)
        hookForeground(lpparam)
        hookProcessLifecycle(lpparam)
        hookDnd(lpparam)
        // Run once the system main looper is available instead of creating an ActivityThread.
        mainHandler.post { initializeRuntime(engine) }
    }

    private fun initializeRuntime(engine: RuleEngine, attempt: Int = 0) {
        val ctx = ContextProvider.systemContext()
        if (ctx == null) {
            if (attempt < 60) mainHandler.postDelayed({ initializeRuntime(engine, attempt + 1) }, 1_000L)
            else XLog.w("system context unavailable after retries: engine initialization degraded")
            return
        }
        engine.recoverPersistedState()
        RuleStore.initialize(ctx)
        com.github.voxqon.xposed.autoflow.model.ChinaHoliday.initialize(ctx)
        runCatching {
            val info = ctx.packageManager.getApplicationInfo(RuleStore.MODULE_PKG, 0)
            com.github.voxqon.xposed.autoflow.model.ChinaHoliday.setModuleApk(info.sourceDir)
        }.onFailure {
            XLog.w("module apk path unavailable; holiday data falls back to calendar rules")
        }
        runCatching {
            ctx.contentResolver.registerContentObserver(RuleConfigContract.URI, false,
                object : android.database.ContentObserver(mainHandler) {
                    override fun onChange(selfChange: Boolean) {
                        RuleStore.invalidate()
                        engine.post(com.github.voxqon.xposed.autoflow.engine.Event(EventTypes.TICK))
                    }
                })
        }.onFailure { XLog.w("Rule change observer unavailable; heartbeat will refresh rules") }
        registerReceivers(ctx)
        registerObservers(ctx)
        seedConnectivity(ctx)
        runCatching {
            ctx.getSystemService(PowerManager::class.java)?.let {
                SharedObjects.state().noteScreen(it.isInteractive, SystemClock.elapsedRealtime())
            }
        }.onFailure { XLog.w("initial screen state unavailable: ${it.message}") }
        runCatching {
            val geofenceMonitor = GeofenceMonitor(ctx) { event -> engine.post(event) }
            engine.setGeofenceObserver(geofenceMonitor::sync)
        }.onFailure { XLog.e("geofence monitor initialization failed; other rules remain active", it) }
        EventBridge.register(ctx, engine)
        ConnectionCatalogBridge.register(ctx)
        engine.start()
        XLog.i("system_server engine started (ctx ok)")
    }

    // ------------------------------------------------------------ 1. screen

    private fun hookPowerState(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val cl = lpparam.classLoader
            val pms = Reflect.findFirstClass(
                "com.android.server.power.PowerManagerService", cl = cl
            ) ?: run { XLog.w("PowerManagerService not found"); return }

            // 主 Hook：updatePowerStateLocked（私有方法，hookAllMethods 全重载兼容）
            XposedBridge.hookAllMethods(pms, "updatePowerStateLocked", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val awake = PowerStateResolver.isInteractive(param.thisObject) ?: return
                        val prev = lastInteractive
                        if (awake == prev) return
                        lastInteractive = awake
                        if (prev == null) {
                            // Seed the duration clock without emitting a synthetic edge event.
                            SharedObjects.state().noteScreen(awake, SystemClock.elapsedRealtime())
                            return
                        }
                        val type = if (awake) EventTypes.SCREEN_ON else EventTypes.SCREEN_OFF
                        val value = if (awake) "on" else "off"
                        SharedObjects.postEvent(type, value = value)
                    } catch (t: Throwable) {
                        XLog.w("wakefulness read failed: ${t.message}")
                    }
                }
            })
            XLog.i("power state hook ok")
        } catch (t: Throwable) {
            XLog.e("power state hook failed (screen conditions degraded)", t)
        }
    }

    // ------------------------------------------------------- 2. foreground

    private fun hookForeground(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        var registered = false

        // 优先级 1：ActivityRecord#activityResumed（13）/ activityResumedLocked（11/12）
        try {
            val arClass = Reflect.findFirstClass(
                "com.android.server.wm.ActivityRecord",
                "com.android.server.am.ActivityRecord", cl = cl
            )
            if (arClass != null) {
                val forToken = Reflect.findMethodExactIfExists(arClass, "forTokenLocked", IBinder::class.java)
                val names = arrayOf("activityResumed", "activityResumedLocked")
                for (n in names) {
                    val methods = runCatching { arClass.declaredMethods.filter { it.name == n } }.getOrDefault(emptyList())
                    for (m in methods) {
                        runCatching {
                            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (param.hasThrowable()) return
                                    runCatching {
                                        val pkg = ForegroundResolver.packageName(param.thisObject, param.args) {
                                            forToken?.invoke(null, it)
                                        }
                                        onForeground(pkg, cold = false)
                                    }.onFailure { XLog.w("resumed activity lookup failed: ${it.message}") }
                                }
                            })
                            registered = true
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            XLog.w("activityResumed hook failed: ${t.message}")
        }

        // 优先级 2：realStartActivityLocked（真实启动，覆盖冷启动首启）
        if (!registered) {
            try {
                val sup = Reflect.findFirstClass(
                    "com.android.server.wm.ActivityTaskSupervisor",
                    "com.android.server.wm.ActivityStackSupervisor",
                    "com.android.server.am.ActivityStackSupervisor", cl = cl
                )
                if (sup != null) {
                    XposedBridge.hookAllMethods(sup, "realStartActivityLocked", object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            onForeground(firstActivityRecordPkg(param.args), cold = false)
                        }
                    })
                    registered = true
                }
            } catch (t: Throwable) {
                XLog.w("realStartActivityLocked hook failed: ${t.message}")
            }
        }

        // 优先级 3（兜底）：RootWindowContainer#resumeFocusedTasksTopActivities，
        // 无包名参数，触发后延后 300ms 读取聚焦任务（尽力而为）
        if (!registered) {
            try {
                val rwc = Reflect.findFirstClass(
                    "com.android.server.wm.RootWindowContainer", cl = cl
                )
                if (rwc != null) {
                    XposedBridge.hookAllMethods(rwc, "resumeFocusedTasksTopActivities", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            mainHandler.postDelayed({
                                try {
                                    val pkg = topAppPkg()
                                    onForeground(pkg, cold = false)
                                } catch (_: Throwable) {}
                            }, 300)
                        }
                    })
                    registered = true
                }
            } catch (t: Throwable) {
                XLog.w("resumeFocusedTasksTopActivities hook failed: ${t.message}")
            }
        }

        XLog.i(if (registered) "foreground hook ok" else "foreground hook FAILED (app conditions degraded)")
    }

    private fun firstActivityRecordPkg(args: Array<Any?>): String? {
        return ForegroundResolver.packageName(null, args) { null }
    }

    /** system_server can query the focused task through the framework manager. */
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun topAppPkg(): String? = runCatching {
        val ctx = ContextProvider.systemContext() ?: return null
        ctx.getSystemService(android.app.ActivityManager::class.java)
            ?.getRunningTasks(1)?.firstOrNull()?.topActivity?.packageName
    }.getOrNull()

    private fun onForeground(pkg: String?, cold: Boolean) {
        if (pkg.isNullOrBlank() || pkg == lastFgPkg) return
        lastFgPkg = pkg
        SharedObjects.postEvent(EventTypes.APP_FG, pkg = pkg)
        if (cold) SharedObjects.postEvent(EventTypes.APP_COLD, pkg = pkg)
    }

    // -------------------------------------------- 3. cold start / killed

    private fun hookProcessLifecycle(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        // Prefer the completion callback: startProcessLocked may only enqueue an async start.
        try {
            val processRecord = Reflect.findClassIfExists("com.android.server.am.ProcessRecord", cl)
            val tracker = ProcessStartTracker()
            var registered = false
            if (processRecord != null) {
                for (className in listOf("com.android.server.am.ProcessList",
                    "com.android.server.am.ActivityManagerService")) {
                    val owner = Reflect.findClassIfExists(className, cl) ?: continue
                    val methods = owner.declaredMethods.filter {
                        it.parameterTypes.contains(processRecord)
                    }
                    val completed = methods.filter { it.name == "handleProcessStartedLocked" }
                    val candidates = completed.ifEmpty { methods.filter { it.name == "startProcessLocked" } }
                    for (method in candidates) {
                        runCatching {
                            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (param.hasThrowable() || param.result == false) return
                                    runCatching {
                                        val record = param.args.firstOrNull { processRecord.isInstance(it) }
                                            ?: return
                                        tracker.onStarted(record)?.let {
                                            SharedObjects.postEvent(EventTypes.APP_COLD, pkg = it)
                                        }
                                    }.onFailure { XLog.w("process start lookup failed: ${it.message}") }
                                }
                            })
                            registered = true
                        }.onFailure { XLog.w("process start hook unavailable: ${it.message}") }
                    }
                    if (registered) {
                        XLog.i("cold start hook ok ($className)")
                        break
                    }
                }
            }
            if (!registered) XLog.w("process start completion not found (cold start degraded)")
        } catch (t: Throwable) {
            XLog.w("process start hook failed: ${t.message}")
        }

        // Android 8/9 names this method kill; newer versions use killLocked.
        try {
            val pr = Reflect.findFirstClass("com.android.server.am.ProcessRecord", cl = cl)
            if (pr != null) {
                val method = if (pr.declaredMethods.any { it.name == "killLocked" }) "killLocked" else "kill"
                val hooks = XposedBridge.hookAllMethods(pr, method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val pkg = ProcessStartTracker.packageName(param.thisObject) ?: return
                        SharedObjects.postEvent(EventTypes.APP_KILLED, pkg = pkg)
                    }
                })
                if (hooks.isNotEmpty()) XLog.i("kill hook ok (ProcessRecord#$method)")
            }
        } catch (t: Throwable) {
            XLog.w("killLocked hook failed: ${t.message}")
        }

        // 杀死补充：AMS#forceStopPackageLocked（用户“强行停止”路径）
        try {
            val ams = Reflect.findFirstClass(
                "com.android.server.am.ActivityManagerService", cl = cl
            )
            if (ams != null) {
                XposedBridge.hookAllMethods(ams, "forceStopPackageLocked", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val pkg = param.args.filterIsInstance<String>().firstOrNull {
                            it.contains('.') && !it.contains(' ') && !it.startsWith("android.")
                        } ?: return
                        SharedObjects.postEvent(EventTypes.APP_KILLED, pkg = pkg)
                    }
                })
            }
        } catch (t: Throwable) {
            XLog.w("forceStopPackageLocked hook failed: ${t.message}")
        }
    }

    // ------------------------------------------------------------- 5. dnd

    private fun hookDnd(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val nms = Reflect.findFirstClass(
                "com.android.server.notification.NotificationManagerService", cl = lpparam.classLoader
            ) ?: return
            XposedBridge.hookAllMethods(nms, "setInterruptionFilter", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val zen = readZen()
                    val on = zen > 0
                    SharedObjects.postEvent(EventTypes.DND, value = if (on) "on" else "off")
                }
            })
            XLog.i("dnd hook ok")
        } catch (t: Throwable) {
            XLog.w("dnd hook failed (observer fallback still active): ${t.message}")
        }
    }

    // ------------------------------------ 6. receivers + content observers

    // These receivers run under system_server's identity, not the module application's uid.
    @SuppressLint("MissingPermission")
    private fun registerReceivers(ctx: Context) {
        runCatching {
            val lastModes = mutableMapOf<String, String>()
            fun publish(type: String, value: String) {
                if (lastModes.put(type, value) != value) SharedObjects.postEvent(type, value = value)
            }
            fun syncModes() {
                val power = ctx.getSystemService(PowerManager::class.java)
                power?.let { publish(EventTypes.POWER_SAVE, if (it.isPowerSaveMode) "on" else "off") }
                val mode = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                if (mode != Configuration.UI_MODE_NIGHT_UNDEFINED) {
                    publish(EventTypes.NIGHT_MODE, if (mode == Configuration.UI_MODE_NIGHT_YES) "on" else "off")
                }
                ctx.getSystemService(KeyguardManager::class.java)?.let {
                    publish(EventTypes.KEYGUARD, if (it.isKeyguardLocked) "shown" else "hidden")
                }
            }
            registerSystemReceiver(ctx, object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    if (i.action == Intent.ACTION_HEADSET_PLUG) {
                        val state = i.getIntExtra("state", -1)
                        if (state == 0 || state == 1) publish(EventTypes.HEADSET, if (state == 1) "on" else "off")
                    } else {
                        when (i.action) {
                            Intent.ACTION_SCREEN_ON -> SharedObjects.postEvent(EventTypes.SCREEN_ON, value = "on")
                            Intent.ACTION_SCREEN_OFF -> SharedObjects.postEvent(EventTypes.SCREEN_OFF, value = "off")
                        }
                        syncModes()
                        if (i.action == Intent.ACTION_SCREEN_OFF) mainHandler.postDelayed({ syncModes() }, 500L)
                    }
                }
            }, IntentFilter().apply {
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                addAction(Intent.ACTION_CONFIGURATION_CHANGED)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_HEADSET_PLUG)
            })
            syncModes()
        }.onFailure { XLog.w("device mode receiver failed: ${it.message}") }

        runCatching {
            registerSystemReceiver(ctx, object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    val value = i.getIntExtra("wifi_state", -1)
                    if (value == 11 || value == 13) {
                        SharedObjects.postEvent(EventTypes.HOTSPOT, value = if (value == 13) "on" else "off")
                    }
                }
            }, IntentFilter("android.net.wifi.WIFI_AP_STATE_CHANGED"))
            val wifi = ctx.getSystemService(WifiManager::class.java)
            val apState = wifi?.let { Reflect.findMethodByName(it.javaClass, "getWifiApState", 0)?.invoke(it) as? Int }
            if (apState == 11 || apState == 13) {
                SharedObjects.postEvent(EventTypes.HOTSPOT, value = if (apState == 13) "on" else "off")
            }
        }.onFailure { XLog.w("hotspot state receiver failed: ${it.message}") }

        // 电池 / 充电（持续型广播，注册即收到当前状态）
        runCatching {
            registerSystemReceiver(ctx, object : BroadcastReceiver() {
                private var lastCharging: Boolean? = null
                override fun onReceive(c: Context, i: Intent) {
                    val level = i.getIntExtra("level", -1)
                    val scale = i.getIntExtra("scale", 100)
                    val percent = if (level >= 0 && scale > 0)
                        (level.toLong() * 100 / scale).coerceIn(0L, 100L).toInt() else -1
                    val plugged = i.getIntExtra("plugged", 0) > 0
                    val chg = if (plugged) "on" else "off"
                    SharedObjects.postEvent(EventTypes.BATTERY, value = percent.toString(), value2 = chg)
                    if (lastCharging != plugged) {
                        lastCharging = plugged
                        SharedObjects.postEvent(EventTypes.CHARGER, value = chg)
                    }
                }
            }, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.onFailure { XLog.w("battery receiver failed: ${it.message}") }

        // Wi-Fi 开关
        runCatching {
            registerSystemReceiver(ctx, object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    val st = i.getIntExtra("wifi_state", -1)
                    val on = st == 3 // WIFI_STATE_ENABLED
                    val off = st == 1
                    if (on || off) {
                        SharedObjects.postEvent(EventTypes.WIFI, value = if (on) "on" else "off")
                    }
                }
            }, IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION))
        }.onFailure { XLog.w("wifi receiver failed: ${it.message}") }

        // SSID 连接/断开
        runCatching {
            registerSystemReceiver(ctx, object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    @Suppress("DEPRECATION")
                    val ni = i.getParcelableExtra<android.net.NetworkInfo>("networkInfo")
                    @Suppress("DEPRECATION")
                    val wifiInfo = i.getParcelableExtra<android.net.wifi.WifiInfo>("wifiInfo")
                        ?: runCatching { (ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo }.getOrNull()
                    val ssid = wifiInfo?.ssid?.removeSurrounding("\"")?.takeIf { !it.contains("<unknown") }
                    val conn = ni?.isConnected ?: return
                    // The engine may clear currentSsid as soon as the generic disconnect is posted.
                    val previousSsid = SharedObjects.state().currentSsid
                    if (ni.detailedState == android.net.NetworkInfo.DetailedState.CONNECTED ||
                        ni.detailedState == android.net.NetworkInfo.DetailedState.DISCONNECTED) {
                        SharedObjects.postEvent(EventTypes.WIFI_CONNECTION, value = if (conn) "on" else "off")
                    }
                    if (conn && ssid != null) {
                        SharedObjects.postEvent(EventTypes.SSID, value = ssid, value2 = "connect")
                    } else if (!conn) {
                        val cur = previousSsid
                        if (cur != null) {
                            SharedObjects.postEvent(EventTypes.SSID, value = cur, value2 = "disconnect")
                        }
                    }
                }
            }, IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION))
        }.onFailure { XLog.w("ssid receiver failed: ${it.message}") }

        // 蓝牙开关 + 设备连接
        runCatching {
            val f = IntentFilter().apply {
                addAction("android.bluetooth.adapter.action.STATE_CHANGED")
                addAction("android.bluetooth.device.action.ACL_CONNECTED")
                addAction("android.bluetooth.device.action.ACL_DISCONNECTED")
            }
            registerSystemReceiver(ctx, object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    when (i.action) {
                        "android.bluetooth.adapter.action.STATE_CHANGED" -> {
                            val st = i.getIntExtra("android.bluetooth.adapter.extra.STATE", -1)
                            if (st == 12 || st == 10) {
                                val on = st == 12
                                SharedObjects.postEvent(EventTypes.BT, value = if (on) "on" else "off")
                            }
                        }
                        "android.bluetooth.device.action.ACL_CONNECTED",
                        "android.bluetooth.device.action.ACL_DISCONNECTED" -> {
                            @Suppress("DEPRECATION")
                            val dev = i.getParcelableExtra<android.bluetooth.BluetoothDevice>(
                                "android.bluetooth.device.extra.DEVICE") ?: return
                            val address = runCatching { dev.address }.getOrNull() ?: return
                            val name = runCatching { dev.name }.getOrNull()
                            val device = if (name.isNullOrBlank() || name.equals(address, ignoreCase = true)) {
                                address
                            } else {
                                "$name|$address"
                            }
                            val conn = i.action == "android.bluetooth.device.action.ACL_CONNECTED"
                            SharedObjects.postEvent(EventTypes.BTDEV, value = device,
                                value2 = if (conn) "connect" else "disconnect")
                        }
                    }
                }
            }, f)
        }.onFailure { XLog.w("bluetooth receiver failed: ${it.message}") }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun seedConnectivity(ctx: Context) {
        runCatching {
            val wifi = ctx.getSystemService(WifiManager::class.java) ?: return@runCatching
            SharedObjects.postEvent(EventTypes.WIFI, value = if (wifi.isWifiEnabled) "on" else "off")
            val connectivity = ctx.getSystemService(android.net.ConnectivityManager::class.java)
            connectivity?.getNetworkInfo(android.net.ConnectivityManager.TYPE_WIFI)?.let {
                SharedObjects.postEvent(EventTypes.WIFI_CONNECTION, value = if (it.isConnected) "on" else "off")
            }
            com.github.voxqon.xposed.autoflow.model.ConnectionCatalog.wifiName(wifi.connectionInfo?.ssid)?.let {
                SharedObjects.postEvent(EventTypes.SSID, value = it, value2 = "connect")
            }
        }.onFailure { XLog.w("initial Wi-Fi state unavailable: ${it.message}") }
        runCatching {
            val adapter = ctx.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
                ?: return@runCatching
            SharedObjects.postEvent(EventTypes.BT, value = if (adapter.isEnabled) "on" else "off")
            if (adapter.isEnabled) adapter.bondedDevices.forEach { device ->
                val name = device.name
                val connected = runCatching {
                    Reflect.findMethodByName(device.javaClass, "isConnected", 0)?.invoke(device) == true
                }.getOrDefault(false)
                if (connected) {
                    val target = if (name.isNullOrBlank()) device.address else "$name|${device.address}"
                    SharedObjects.postEvent(EventTypes.BTDEV, value = target, value2 = "connect")
                }
            }
            SharedObjects.postEvent(EventTypes.BT_CONNECTION)
        }.onFailure { XLog.w("initial Bluetooth state unavailable: ${it.message}") }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerSystemReceiver(ctx: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
        // Filters contain protected system broadcasts, including those sent by the Bluetooth uid.
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            ctx.registerReceiver(receiver, filter)
        }
    }

    private fun registerObservers(ctx: Context) {
        val cr = ctx.contentResolver
        fun observe(uri: Uri, onchg: () -> Unit) {
            runCatching {
                cr.registerContentObserver(uri, true, object : android.database.ContentObserver(mainHandler) {
                    override fun onChange(selfChange: Boolean) {
                        try { onchg() } catch (t: Throwable) { XLog.w("observer failed: ${t.message}") }
                    }
                })
            }.onFailure { XLog.w("observer register failed: ${it.message}") }
        }

        fun syncAirplaneMode() {
            val value = android.provider.Settings.Global.getInt(cr, "airplane_mode_on", -1)
            if (value == 0 || value == 1) {
                SharedObjects.postEvent(EventTypes.AIRPLANE, value = if (value == 1) "on" else "off")
            }
        }
        observe(android.provider.Settings.Global.getUriFor("airplane_mode_on")) { syncAirplaneMode() }
        runCatching { syncAirplaneMode() }
            .onFailure { XLog.w("initial airplane mode unavailable: ${it.message}") }

        // 定位
        observe(android.provider.Settings.Secure.getUriFor("location_mode")) {
            val mode = android.provider.Settings.Secure.getInt(cr, "location_mode", 0)
            SharedObjects.postEvent(EventTypes.LOCATION, value = if (mode > 0) "on" else "off")
        }
        // 方向锁定
        observe(android.provider.Settings.System.getUriFor("accelerometer_rotation")) {
            val rot = android.provider.Settings.System.getInt(cr, "accelerometer_rotation", 1)
            SharedObjects.postEvent(EventTypes.ROTATION, value = if (rot == 0) "locked" else "unlocked")
        }
        // 勿扰（zen_mode observer 是 NMS hook 失败时的兜底）
        observe(android.provider.Settings.Global.getUriFor("zen_mode")) {
            SharedObjects.postEvent(EventTypes.DND, value = if (readZen() > 0) "on" else "off")
        }

        // 初始状态同步
        mainHandler.post {
            try {
                SharedObjects.postEvent(EventTypes.LOCATION,
                    value = if (android.provider.Settings.Secure.getInt(cr, "location_mode", 0) > 0) "on" else "off")
                SharedObjects.postEvent(EventTypes.ROTATION,
                    value = if (android.provider.Settings.System.getInt(cr, "accelerometer_rotation", 1) == 0) "locked" else "unlocked")
                SharedObjects.postEvent(EventTypes.DND,
                    value = if (readZen() > 0) "on" else "off")
            } catch (_: Throwable) {}
        }
    }

    private fun readZen(): Int = runCatching {
        val ctx = ContextProvider.systemContext() ?: return 0
        android.provider.Settings.Global.getInt(ctx.contentResolver, "zen_mode", 0)
    }.getOrDefault(0)
}
