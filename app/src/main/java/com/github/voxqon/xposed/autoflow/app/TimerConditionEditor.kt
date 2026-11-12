package com.github.voxqon.xposed.autoflow.app

import android.app.TimePickerDialog
import android.content.Context
import android.view.View
import android.widget.RadioGroup
import android.widget.TextView
import com.github.voxqon.xposed.autoflow.R
import com.github.voxqon.xposed.autoflow.model.Condition
import com.github.voxqon.xposed.autoflow.model.ConditionType
import com.github.voxqon.xposed.autoflow.model.TimeRangeSpec

class TimerConditionEditor(context: Context, view: View, initial: Condition? = null) {
    private val options = view.findViewById<View>(R.id.dialogTimerOptions)
    private val time = view.findViewById<TextView>(R.id.dialogTimerTime)
    private val days = view.findViewById<RadioGroup>(R.id.dialogTimerDays)
    private var minute = initial?.takeIf { it.type == ConditionType.TIMER }?.param
        ?.let(TimeRangeSpec::parseMinute)
        ?: 9 * 60
    private var picker: TimePickerDialog? = null

    init {
        days.check(when (initial?.takeIf { it.type == ConditionType.TIMER }?.param2) {
            "wd" -> R.id.dialogTimerWeekdays
            "we" -> R.id.dialogTimerWeekends
            "workday" -> R.id.dialogTimerWorkdays
            "holiday" -> R.id.dialogTimerHolidays
            else -> R.id.dialogTimerEveryDay
        })
        time.setOnClickListener {
            picker = TimePickerDialog(context, { _, hour, minutes ->
                minute = hour * 60 + minutes
                update()
            }, minute / 60, minute % 60, true).apply {
                setTitle(R.string.timer_pick_time)
                show()
            }
        }
        update()
    }

    fun configure(enabled: Boolean) {
        options.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    fun condition(): Condition = Condition(
        ConditionType.TIMER,
        TimeRangeSpec.formatMinute(minute),
        when (days.checkedRadioButtonId) {
            R.id.dialogTimerWeekdays -> "wd"
            R.id.dialogTimerWeekends -> "we"
            R.id.dialogTimerWorkdays -> "workday"
            R.id.dialogTimerHolidays -> "holiday"
            else -> null
        },
    )

    fun close() {
        picker?.dismiss()
        picker = null
    }

    private fun update() {
        time.text = time.context.getString(R.string.timer_time_value, TimeRangeSpec.formatMinute(minute))
    }
}
