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

class TimeRangeEditor(private val context: Context, view: View, initial: Condition? = null, exit: Boolean = false) {
    private val options = view.findViewById<View>(R.id.dialogTimeRangeOptions)
    private val start = view.findViewById<TextView>(R.id.dialogTimeRangeStart)
    private val end = view.findViewById<TextView>(R.id.dialogTimeRangeEnd)
    private val mode = view.findViewById<RadioGroup>(R.id.dialogTimeRangeMode)
    private val error = view.findViewById<View>(R.id.dialogTimeRangeError)
    private val initialRange = initial?.takeIf { it.type == ConditionType.TIME_RANGE }?.let { TimeRangeSpec.parse(it.param) }
    private var startMinute = initialRange?.startMinute ?: 9 * 60
    private var endMinute = initialRange?.endMinute ?: 18 * 60
    private var picker: TimePickerDialog? = null

    init {
        mode.check(if (initial?.param2 == "outside") R.id.dialogTimeRangeOutside else R.id.dialogTimeRangeInside)
        if (exit) {
            view.findViewById<TextView>(R.id.dialogTimeRangeInside).setText(R.string.time_range_exit_inside)
            view.findViewById<TextView>(R.id.dialogTimeRangeOutside).setText(R.string.time_range_exit_outside)
        }
        start.setOnClickListener { chooseTime(true) }
        end.setOnClickListener { chooseTime(false) }
        update()
    }

    fun configure(enabled: Boolean) {
        options.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    fun condition(): Condition? {
        val range = TimeRangeSpec.create(startMinute, endMinute)
        error.visibility = if (range == null) View.VISIBLE else View.GONE
        return range?.let {
            Condition(ConditionType.TIME_RANGE, it.value,
                if (mode.checkedRadioButtonId == R.id.dialogTimeRangeOutside) "outside" else "inside")
        }
    }

    fun close() {
        picker?.dismiss()
        picker = null
    }

    private fun chooseTime(isStart: Boolean) {
        val minute = if (isStart) startMinute else endMinute
        picker = TimePickerDialog(context, { _, hour, minutes ->
            if (isStart) startMinute = hour * 60 + minutes else endMinute = hour * 60 + minutes
            update()
        }, minute / 60, minute % 60, true).apply {
            setTitle(if (isStart) R.string.time_range_start else R.string.time_range_end)
            show()
        }
    }

    private fun update() {
        start.text = context.getString(R.string.time_range_start_value, TimeRangeSpec.formatMinute(startMinute))
        end.text = context.getString(
            if (endMinute < startMinute) R.string.time_range_end_next_day else R.string.time_range_end_value,
            TimeRangeSpec.formatMinute(endMinute),
        )
        error.visibility = View.GONE
    }
}
