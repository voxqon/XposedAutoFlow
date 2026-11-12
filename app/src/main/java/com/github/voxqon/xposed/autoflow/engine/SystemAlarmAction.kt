package com.github.voxqon.xposed.autoflow.engine

import android.content.Context
import android.content.Intent
import android.os.Binder
import android.provider.AlarmClock
import com.github.voxqon.xposed.autoflow.model.ActionType
import com.github.voxqon.xposed.autoflow.model.ExecutionStatus
import com.github.voxqon.xposed.autoflow.model.ExecutionStep
import com.github.voxqon.xposed.autoflow.util.XLog
import java.util.Calendar
import java.util.Locale

object SystemAlarmAction {
    private const val MINIMUM_LEAD_MS = 5_000L

    fun execute(context: Context?, ruleName: String, nowMillis: Long = System.currentTimeMillis()): ExecutionStep {
        val name = ActionType.SYSTEM_ALARM.label
        if (context == null) return ExecutionStep(name, ExecutionStatus.FAILED, "系统上下文不可用")
        // Clock intents have minute precision; leave time for the clock app to handle the request.
        val target = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.MINUTE, 1)
            if (timeInMillis - nowMillis < MINIMUM_LEAD_MS) add(Calendar.MINUTE, 1)
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(AlarmClock.EXTRA_HOUR, target.get(Calendar.HOUR_OF_DAY))
            .putExtra(AlarmClock.EXTRA_MINUTES, target.get(Calendar.MINUTE))
            .putExtra(AlarmClock.EXTRA_MESSAGE, ruleName.take(256))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val identity = Binder.clearCallingIdentity()
        return try {
            context.startActivity(intent)
            val time = String.format(Locale.ROOT, "%02d:%02d",
                target.get(Calendar.HOUR_OF_DAY), target.get(Calendar.MINUTE))
            ExecutionStep(name, ExecutionStatus.SUCCESS, "已向系统时钟提交 $time 的一次性闹钟")
        } catch (t: Exception) {
            XLog.w("system alarm request failed: ${t.message}")
            ExecutionStep(name, ExecutionStatus.FAILED, "无法调用系统闹钟，请确认已安装兼容的时钟应用并允许调用")
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }
}
