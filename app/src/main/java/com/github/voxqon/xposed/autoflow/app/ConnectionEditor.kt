package com.github.voxqon.xposed.autoflow.app

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import com.github.voxqon.xposed.autoflow.R
import com.github.voxqon.xposed.autoflow.model.ConnectionCatalog
import com.github.voxqon.xposed.autoflow.model.ConnectionKind
import com.github.voxqon.xposed.autoflow.model.ConnectionMode
import com.github.voxqon.xposed.autoflow.model.ConnectionTarget
import com.github.voxqon.xposed.autoflow.model.Condition
import com.github.voxqon.xposed.autoflow.model.ConditionType
import com.github.voxqon.xposed.autoflow.model.Action
import com.github.voxqon.xposed.autoflow.model.ActionType

class ConnectionEditor(private val context: Context, private val view: View, private val initial: Condition? = null,
    private val initialAction: Action? = null) {
    private val options = view.findViewById<View>(R.id.dialogConnectionOptions)
    private val modes = view.findViewById<Spinner>(R.id.dialogConnectionMode)
    private val source = view.findViewById<Spinner>(R.id.dialogConnectionSource)
    private val status = view.findViewById<TextView>(R.id.dialogConnectionStatus)
    private val target = view.findViewById<EditText>(R.id.dialogParam1)
    private val refresh = view.findViewById<View>(R.id.dialogConnectionRefresh)
    private var kind: ConnectionKind? = null
    private var condition = false
    private var targets = emptyList<ConnectionTarget>()
    private var availableModes = emptyList<ConnectionMode>()
    private var cancelRequest: (() -> Unit)? = null
    private var selecting = false
    private var generation = 0
    private var returningFromSettings = false

    init {
        refresh.setOnClickListener { refresh() }
        view.findViewById<View>(R.id.dialogConnectionSettings).setOnClickListener {
            val action = if (kind == ConnectionKind.WIFI) Settings.ACTION_WIFI_SETTINGS else Settings.ACTION_BLUETOOTH_SETTINGS
            runCatching { context.startActivity(Intent(action)) }
                .onSuccess { returningFromSettings = true }
                .onFailure { status.text = "无法打开系统设置" }
        }
        source.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, child: View?, position: Int, id: Long) {
                if (selecting) return
                targets.getOrNull(position - 1)?.let {
                    selecting = true
                    target.setText(it.value)
                    target.setSelection(target.length())
                    selecting = false
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        target.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!selecting && kind != null &&
                    targets.getOrNull(source.selectedItemPosition - 1)?.value != s.toString()) {
                    source.setSelection(0)
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    fun configure(next: ConnectionKind?, isCondition: Boolean) {
        options.visibility = if (next == null) View.GONE else View.VISIBLE
        if (next != null) view.findViewById<View>(R.id.layoutInputParam2).visibility = View.GONE
        if (kind == next && condition == isCondition) return
        cancelRequest?.invoke()
        cancelRequest = null
        generation++
        val previous = kind
        kind = next
        condition = isCondition
        if (next == null) return
        if (previous != next) target.setText("")
        availableModes = if (isCondition) ConnectionMode.entries else
            listOf(ConnectionMode.CONNECT, ConnectionMode.DISCONNECT)
        modes.adapter = ArrayAdapter(context, R.layout.item_spinner_selected,
            availableModes.map { it.label }).apply { setDropDownViewResource(R.layout.item_spinner_dropdown) }
        initial?.takeIf {
            (next == ConnectionKind.WIFI && it.type == ConditionType.WIFI_SSID) ||
                (next == ConnectionKind.BLUETOOTH && it.type == ConditionType.BT_DEVICE)
        }?.let {
            target.setText(it.param)
            val mode = ConnectionMode.fromCode(it.param2) ?: ConnectionMode.ANY
            modes.setSelection(availableModes.indexOf(mode).coerceAtLeast(0))
        }
        initialAction?.takeIf {
            (next == ConnectionKind.WIFI && it.type == ActionType.WIFI_CONNECTION) ||
                (next == ConnectionKind.BLUETOOTH && it.type == ActionType.BLUETOOTH_CONNECTION)
        }?.let {
            target.setText(it.param)
            val mode = ConnectionMode.fromCode(it.param2) ?: ConnectionMode.CONNECT
            modes.setSelection(availableModes.indexOf(mode).coerceAtLeast(0))
        }
        view.findViewById<TextView>(R.id.dialogConnectionSourceLabel).text =
            if (next == ConnectionKind.WIFI) "已保存的 Wi-Fi" else "已配对的蓝牙设备"
        showTargets(ConnectionCatalogReceiver.cached(context).targets(next))
        refresh()
    }

    fun onResume() {
        if (returningFromSettings) {
            returningFromSettings = false
            refresh()
        }
    }

    private fun refresh() {
        val next = kind ?: return
        cancelRequest?.invoke()
        val requestGeneration = ++generation
        refresh.isEnabled = false
        status.text = "正在读取…"
        cancelRequest = ConnectionCatalogReceiver.request(context, next) { catalog ->
            if (kind != next || generation != requestGeneration) return@request
            refresh.isEnabled = true
            val current = catalog ?: ConnectionCatalogReceiver.cached(context)
            showTargets(current.targets(next))
            status.text = when {
                catalog == null -> if (targets.isEmpty()) "模块未响应，请打开系统设置后返回刷新；首次启用模块需重启设备" else "上次读取的记录"
                !current.readable(next) -> {
                    val reason = if (next == ConnectionKind.BLUETOOTH) "蓝牙列表不可用，请确认蓝牙已开启后刷新"
                        else "无法读取已保存网络，请打开系统设置后刷新"
                    if (targets.isEmpty()) reason else "$reason；显示上次读取的记录"
                }
                targets.isEmpty() -> if (next == ConnectionKind.WIFI) "暂无已保存的 Wi-Fi" else "暂无已配对的蓝牙设备"
                else -> "${targets.size} 个${if (next == ConnectionKind.WIFI) "网络" else "设备"}"
            }
        }
    }

    fun direction(): String? =
        if (kind == null) null else availableModes.getOrNull(modes.selectedItemPosition)?.code

    fun targetValue(): String? {
        val value = target.text.toString()
        return (if (kind == ConnectionKind.WIFI) value else value.trim()).takeIf { it.isNotBlank() }
    }

    fun close() {
        generation++
        cancelRequest?.invoke()
        cancelRequest = null
    }

    private fun showTargets(items: List<ConnectionTarget>) {
        targets = items
        selecting = true
        source.adapter = ArrayAdapter(context, R.layout.item_spinner_selected,
            listOf("手动输入") + targets.map { it.label }).apply { setDropDownViewResource(R.layout.item_spinner_dropdown) }
        val selected = targets.indexOfFirst { it.value == target.text.toString() }
        source.setSelection(if (selected >= 0) selected + 1 else 0)
        selecting = false
    }
}
