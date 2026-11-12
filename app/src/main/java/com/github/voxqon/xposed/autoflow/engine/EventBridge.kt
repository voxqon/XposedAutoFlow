package com.github.voxqon.xposed.autoflow.engine

import android.content.BroadcastReceiver
import android.app.BroadcastOptions
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.github.voxqon.xposed.autoflow.util.XLog

/**
 * 事件桥：跨进程事件分发。
 *
 * 设计：条件监听按“条件归属进程”单点产生事件，全部汇聚到 android(system_server)
 * 进程内唯一的 RuleEngine 执行，天然避免多进程重复执行动作。
 *
 * - system_server：注册接收器（收）。
 * - SystemUI / Settings / MiuiHome 等 aux 进程：只发（send）。
 * - 广播显式投递到 android 包；API 34+ 接收端校验 uid=system(1000) 和辅助包名。
 *   更旧版本无法可靠读取发送方身份，因此拒绝桥接事件。
 * - 发送失败（如接收器未注册）只记日志降级：核心条件（前台/亮熄屏/电量/
 *   Wi-Fi/蓝牙/定位/勿扰/旋转）都在 system_server 内原生监听，不受影响。
 */
object EventBridge {
    const val ACTION = "com.github.voxqon.xposed.autoflow.EVENT"
    const val EXTRA_JSON = "event_json"
    private const val SYSTEM_PACKAGE = "android"
    private val allowedSenderPackages = setOf(
        "com.android.systemui",
        "com.android.settings",
        "com.miui.home",
    )

    fun send(context: Context, event: Event) {
        if (Build.VERSION.SDK_INT < 34) return
        runCatching {
            context.sendBroadcast(Intent(ACTION).setPackage(SYSTEM_PACKAGE)
                .putExtra(EXTRA_JSON, event.toJson()), null,
                BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
        }.onFailure { XLog.w("event bridge send failed (degraded): ${it.message}") }
    }

    /** 仅 system_server 调用：注册接收器并把事件投递给引擎。 */
    fun register(context: Context, engine: RuleEngine) {
        if (Build.VERSION.SDK_INT < 34) {
            XLog.i("event bridge disabled: sender uid unavailable before API 34")
            return
        }
        runCatching {
            var lastRejectedLogAt: Long? = null
            fun logRejected(reason: String) {
                val now = android.os.SystemClock.elapsedRealtime()
                val last = lastRejectedLogAt
                if (last == null || now - last >= 60_000L) {
                    lastRejectedLogAt = now
                    XLog.w(reason)
                }
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    runCatching receive@{
                        @Suppress("NewApi")
                        val senderUid = getSentFromUid()
                        @Suppress("NewApi")
                        val senderPackage = getSentFromPackage()
                        if (senderUid != 1000 || senderPackage !in allowedSenderPackages) {
                            logRejected("event bridge: rejected sender=$senderPackage uid=$senderUid")
                            return@receive
                        }
                        val json = intent.getStringExtra(EXTRA_JSON)
                        val event = json?.let { Event.fromJson(it) }
                        if (event == null) {
                            logRejected("event bridge: rejected invalid event payload")
                            return@receive
                        }
                        if (event.type == EventTypes.GEOFENCE) {
                            logRejected("event bridge: rejected local-only geofence event")
                            return@receive
                        }
                        engine.post(event)
                    }.onFailure { logRejected("event bridge: receive failed: ${it.message}") }
                }
            }
            val filter = IntentFilter(ACTION)
            @Suppress("NewApi")
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            XLog.i("event bridge receiver registered")
        }.onFailure { XLog.e("event bridge register failed", it) }
    }

}
