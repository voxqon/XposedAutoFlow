package com.github.voxqon.xposed.autoflow.app

import android.content.Context
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import com.github.voxqon.xposed.autoflow.R
import com.github.voxqon.xposed.autoflow.model.ConditionType

internal enum class ConditionCategory(val label: String, vararg val types: ConditionType) {
    APP("应用", ConditionType.APP_FOREGROUND, ConditionType.APP_LEFT, ConditionType.APP_COLD_START,
        ConditionType.APP_KILLED, ConditionType.APP_NOT_FOREGROUND),
    SCREEN("屏幕", ConditionType.SCREEN_ON, ConditionType.SCREEN_OFF,
        ConditionType.SCREEN_ON_FOR, ConditionType.SCREEN_OFF_FOR),
    LOCATION("定位", ConditionType.LOCATION_ON),
    WIFI("Wi-Fi", ConditionType.WIFI_ON, ConditionType.WIFI_SSID,
        ConditionType.WIFI_CONNECTED, ConditionType.WIFI_DISCONNECTED),
    BLUETOOTH("蓝牙", ConditionType.BT_ON, ConditionType.BT_DEVICE,
        ConditionType.BT_CONNECTED, ConditionType.BT_DISCONNECTED),
    ROTATION("方向锁定", ConditionType.ROTATION_LOCKED),
    DND("勿扰模式", ConditionType.DND_ON),
    CHARGER("充电器", ConditionType.CHARGER_CONNECTED, ConditionType.CHARGER_DISCONNECTED),
    BATTERY("电量", ConditionType.BATTERY_BELOW, ConditionType.BATTERY_ABOVE),
    TIME("时间", ConditionType.TIMER, ConditionType.TIME_RANGE),
    GEOFENCE("地理围栏", ConditionType.GEOFENCE),
    LOCK("设备锁屏", ConditionType.DEVICE_LOCKED, ConditionType.DEVICE_UNLOCKED),
    POWER_SAVE("省电模式", ConditionType.POWER_SAVE_ON),
    NIGHT("夜间模式", ConditionType.NIGHT_MODE),
    HEADSET("有线耳机", ConditionType.HEADSET_CONNECTED, ConditionType.HEADSET_DISCONNECTED),
    AIRPLANE("飞行模式", ConditionType.AIRPLANE_MODE),
    HOTSPOT("个人热点", ConditionType.HOTSPOT_ON),
    GROUP("条件组（任一满足）", ConditionType.GROUP),
}

internal class ConditionPickerEditor(
    private val context: Context,
    view: View,
    initial: ConditionType?,
    groupChild: Boolean,
) {
    private val categories = ConditionCategory.entries.filter {
        !groupChild || it != ConditionCategory.GROUP
    }
    private val categorySpinner = view.findViewById<Spinner>(R.id.dialogConditionCategory)
    private val functionSpinner = view.findViewById<Spinner>(R.id.dialogSpinner)
    private val functionLabel = view.findViewById<TextView>(R.id.dialogTypeLabel)
    private var category = categories.firstOrNull { initial in it.types } ?: categories.first()

    var onTypeChanged: (() -> Unit)? = null

    val selectedType: ConditionType
        get() = category.types.getOrNull(functionSpinner.selectedItemPosition) ?: category.types.first()

    init {
        view.findViewById<View>(R.id.dialogConditionCategoryOptions).visibility = View.VISIBLE
        functionLabel.text = "功能"
        functionSpinner.contentDescription = "条件功能"
        categorySpinner.adapter = adapter(categories.map { it.label })
        categorySpinner.setSelection(categories.indexOf(category))
        showFunctions(initial)
        functionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, child: View?, position: Int, id: Long) {
                onTypeChanged?.invoke()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        categorySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, child: View?, position: Int, id: Long) {
                val next = categories[position]
                if (next == category) return
                category = next
                showFunctions()
                onTypeChanged?.invoke()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun showFunctions(initial: ConditionType? = null) {
        functionSpinner.adapter = adapter(category.types.map { it.label })
        functionSpinner.setSelection(category.types.indexOf(initial).coerceAtLeast(0))
        val visibility = if (category.types.size > 1) View.VISIBLE else View.GONE
        functionSpinner.visibility = visibility
        functionLabel.visibility = visibility
    }

    private fun adapter(labels: List<String>) =
        ArrayAdapter(context, R.layout.item_spinner_selected, labels).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }
}
