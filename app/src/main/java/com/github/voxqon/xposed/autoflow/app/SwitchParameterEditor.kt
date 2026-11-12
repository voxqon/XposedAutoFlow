package com.github.voxqon.xposed.autoflow.app

import android.view.View
import android.widget.CompoundButton
import com.github.voxqon.xposed.autoflow.R
import com.github.voxqon.xposed.autoflow.model.ActionType
import com.github.voxqon.xposed.autoflow.model.Action
import com.github.voxqon.xposed.autoflow.model.Condition
import com.github.voxqon.xposed.autoflow.model.ConditionType

class SwitchParameterEditor(view: View, private val initial: Condition?, private val initialAction: Action? = null) {
    private val options = view.findViewById<View>(R.id.dialogSwitchOptions)
    private val state = view.findViewById<CompoundButton>(R.id.dialogStateSwitch)
    private val special = view.findViewById<CompoundButton>(R.id.dialogSwitchAny)
    private var condition: ConditionType? = null
    private var action: ActionType? = null

    init {
        state.setOnCheckedChangeListener { _, _ -> updateState() }
        special.setOnCheckedChangeListener { _, _ -> updateState() }
    }

    fun configure(nextCondition: ConditionType?, nextAction: ActionType?) {
        val next = nextCondition?.takeIf { it.isSwitchCondition }
        val nextActionType = nextAction?.takeIf { it.isSwitchAction }
        options.visibility = if (next != null || nextActionType != null) View.VISIBLE else View.GONE
        if (condition == next && action == nextActionType) return
        condition = next
        action = nextActionType
        special.text = if (next != null) "开启或关闭均可" else "切换当前状态"
        val value = (initial?.takeIf { it.type == next }?.param
            ?: initialAction?.takeIf { it.type == nextActionType }?.param)?.lowercase()
        state.isChecked = value != "off" && value != "unlocked" && value != "unlock"
        special.isChecked = if (next != null) {
            initial != null && initial.type == next && (value.isNullOrBlank() || value == "any")
        } else initialAction != null && initialAction.type == nextActionType && (value.isNullOrBlank() || value == "toggle")
        updateState()
    }

    fun parameter(): String? {
        if (condition == null && action == null) return null
        if (special.isChecked) return if (condition != null) "any" else "toggle"
        return when {
            condition == ConditionType.ROTATION_LOCKED -> if (state.isChecked) "locked" else "unlocked"
            action == ActionType.ROTATION_LOCK -> if (state.isChecked) "lock" else "unlock"
            else -> if (state.isChecked) "on" else "off"
        }
    }

    private fun updateState() {
        state.isEnabled = !special.isChecked
        val rotation = condition == ConditionType.ROTATION_LOCKED || action == ActionType.ROTATION_LOCK
        state.text = when {
            special.isChecked -> if (condition != null) "开启或关闭均可" else "切换当前状态"
            rotation -> if (state.isChecked) "锁定" else "解锁"
            else -> if (state.isChecked) "开启" else "关闭"
        }
    }
}
