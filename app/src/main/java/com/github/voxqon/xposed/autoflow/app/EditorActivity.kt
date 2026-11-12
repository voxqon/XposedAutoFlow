package com.github.voxqon.xposed.autoflow.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.github.voxqon.xposed.autoflow.R
import com.github.voxqon.xposed.autoflow.model.Action
import com.github.voxqon.xposed.autoflow.model.ActionType
import com.github.voxqon.xposed.autoflow.model.Condition
import com.github.voxqon.xposed.autoflow.model.ConditionType
import com.github.voxqon.xposed.autoflow.model.ConnectionKind
import com.github.voxqon.xposed.autoflow.model.GeofenceSpec
import com.github.voxqon.xposed.autoflow.model.Rule
import com.github.voxqon.xposed.autoflow.model.RuleJson
import com.github.voxqon.xposed.autoflow.model.ScheduleMode
import com.github.voxqon.xposed.autoflow.model.TriggerMode
import com.github.voxqon.xposed.autoflow.model.TimeRangeSpec
import com.github.voxqon.xposed.autoflow.model.SoundKind
import com.github.voxqon.xposed.autoflow.model.SoundSpec
import com.github.voxqon.xposed.autoflow.model.MAX_RULE_COOLDOWN_SECONDS
import com.github.voxqon.xposed.autoflow.model.MAX_ACTION_DELAY_SECONDS
import com.github.voxqon.xposed.autoflow.model.ALL_DAYS_MASK
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputLayout
import java.util.UUID

/**
 * 规则编辑器：
 * - 名称与触发选项 + 四个区块：触发条件 / 执行动作 / 退出条件（或）/ 退出恢复动作；
 * - 添加弹框按所选类型显示参数，连接目标可从历史记录选择；
 * - 地理围栏使用独立地图页面，列表条目支持编辑围栏与确认删除；
 * - 保存到 SharedPreferences（AppPrefs -> auto_task_rules / rules_json）。
 */
class EditorActivity : AppCompatActivity() {

    private val conditions = mutableListOf<Condition>()
    private val actions = mutableListOf<Action>()
    private val exitConditions = mutableListOf<Condition>()
    private val restoreActions = mutableListOf<Action>()

    private lateinit var boxConditions: LinearLayout
    private lateinit var boxActions: LinearLayout
    private lateinit var boxExitConditions: LinearLayout
    private lateinit var boxRestoreActions: LinearLayout
    private lateinit var spinnerTriggerMode: Spinner
    private lateinit var spinnerAllowedDays: Spinner
    private lateinit var chipGroupAllowedDays: ChipGroup
    private val allowedDayChips = mutableListOf<Pair<Int, Chip>>()
    private var allowedDaysMask = ALL_DAYS_MASK

    private var editingId: String? = null
    private var geofenceExitCondition = false
    private var geofenceEditIndex = -1
    private var geofenceInGroup = false
    private var groupDraft: MutableList<Condition>? = null
    private var groupExit = false
    private var groupIndex = -1
    private var groupDialog: AlertDialog? = null
    private var groupItems: LinearLayout? = null
    private var itemDialog: AlertDialog? = null
    private var connectionEditor: ConnectionEditor? = null
    private val geofencePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            val fence = GeofenceSpec.parse(data.getStringExtra(GeofencePickerActivity.EXTRA_FENCE))
            val direction = data.getStringExtra(GeofencePickerActivity.EXTRA_DIRECTION)
            if (fence != null && GeofenceSpec.isValidDirection(direction)) {
                val item = Condition(
                    ConditionType.GEOFENCE,
                    fence.id,
                    direction ?: "any",
                    data.getStringExtra(GeofencePickerActivity.EXTRA_NAME),
                )
                val target = if (geofenceInGroup) groupDraft
                    else if (geofenceExitCondition) exitConditions else conditions
                if (target == null) return@registerForActivityResult
                if (geofenceEditIndex in target.indices) target[geofenceEditIndex] = item else target.add(item)
                redraw()
                redrawGroup()
            }
        }
        geofenceEditIndex = -1
        geofenceInGroup = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        boxConditions = findViewById(R.id.boxConditions)
        boxActions = findViewById(R.id.boxActions)
        boxExitConditions = findViewById(R.id.boxExitConditions)
        boxRestoreActions = findViewById(R.id.boxRestoreActions)
        spinnerTriggerMode = findViewById(R.id.spinnerTriggerMode)
        spinnerTriggerMode.adapter = ArrayAdapter(
            this,
            R.layout.item_spinner_selected,
            TriggerMode.entries.map { it.label },
        ).apply { setDropDownViewResource(R.layout.item_spinner_dropdown) }
        spinnerTriggerMode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateConditionModeHint()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        spinnerAllowedDays = findViewById(R.id.spinnerAllowedDays)
        chipGroupAllowedDays = findViewById(R.id.chipGroupAllowedDays)
        spinnerAllowedDays.adapter = ArrayAdapter(
            this,
            R.layout.item_spinner_selected,
            ScheduleMode.entries.map { it.label },
        ).apply { setDropDownViewResource(R.layout.item_spinner_dropdown) }
        val dayLabels = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        dayLabels.forEachIndexed { index, label ->
            val bit = 1 shl index
            val chip = Chip(this).apply {
                id = View.generateViewId()
                text = label
                isCheckable = true
                setEnsureMinTouchTargetSize(true)
            }
            allowedDayChips += bit to chip
            chipGroupAllowedDays.addView(chip)
        }
        spinnerAllowedDays.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                chipGroupAllowedDays.visibility = if (position == ScheduleMode.CUSTOM_DAYS.ordinal) View.VISIBLE else View.GONE
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        editingId = intent.getStringExtra(EXTRA_ID)
        geofenceExitCondition = savedInstanceState?.getBoolean(STATE_GEOFENCE_EXIT) ?: false
        geofenceEditIndex = savedInstanceState?.getInt(STATE_GEOFENCE_INDEX, -1) ?: -1
        geofenceInGroup = savedInstanceState?.getBoolean(STATE_GEOFENCE_GROUP) ?: false
        groupExit = savedInstanceState?.getBoolean(STATE_GROUP_EXIT) ?: false
        groupIndex = savedInstanceState?.getInt(STATE_GROUP_INDEX, -1) ?: -1
        groupDraft = savedInstanceState?.getString(STATE_GROUP_DRAFT)?.let {
            RuleJson.conditionFromJson(org.json.JSONObject(it))?.children?.toMutableList()
        }
        val titleView = findViewById<TextView>(R.id.textEditorTitle)
        titleView.text = if (editingId != null) "编辑规则" else "新建规则"
        val draft = savedInstanceState?.getString(STATE_DRAFT)
            ?.let { RuleJson.rulesFromJson(it).firstOrNull() }
        val source = draft ?: editingId?.let { id -> AppPrefs.loadRules(this).firstOrNull { it.id == id } }
        source?.let { r ->
            if (savedInstanceState == null) {
                findViewById<EditText>(R.id.editRuleName).setText(r.name)
                spinnerTriggerMode.setSelection(r.triggerMode.ordinal)
                findViewById<EditText>(R.id.editCooldownSeconds).setText(r.cooldownSeconds.toString())
            }
            allowedDaysMask = (r.allowedDaysMask and ALL_DAYS_MASK).takeIf { it != 0 } ?: ALL_DAYS_MASK
            spinnerAllowedDays.setSelection(r.scheduleMode.ordinal)
            allowedDayChips.forEach { (bit, chip) -> chip.isChecked = (allowedDaysMask and bit) != 0 }
            conditions.addAll(r.conditions)
            actions.addAll(r.actions)
            exitConditions.addAll(r.exitConditions)
            restoreActions.addAll(r.restoreActions)
        }
        if (source == null) allowedDayChips.forEach { (_, chip) -> chip.isChecked = true }

        findViewById<View>(R.id.btnAddCondition).setOnClickListener { pickType(true) { c, _ -> conditions.add(c as Condition); redraw() } }
        findViewById<View>(R.id.btnAddExitCondition).setOnClickListener { pickType(true, exitCondition = true) { c, _ -> exitConditions.add(c as Condition); redraw() } }
        findViewById<View>(R.id.btnAddConditionGroup).setOnClickListener { openGroup(false) }
        findViewById<View>(R.id.btnAddExitConditionGroup).setOnClickListener { openGroup(true) }
        findViewById<View>(R.id.btnAddAction).setOnClickListener { pickType(false) { _, a -> actions.add(a as Action); redraw() } }
        findViewById<View>(R.id.btnAddRestoreAction).setOnClickListener { pickType(false, restoreAction = true) { _, a -> restoreActions.add(a as Action); redraw() } }
        findViewById<View>(R.id.btnSave).setOnClickListener { save() }

        redraw()
        if (groupDraft != null) showGroupDialog()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_GEOFENCE_EXIT, geofenceExitCondition)
        outState.putInt(STATE_GEOFENCE_INDEX, geofenceEditIndex)
        outState.putBoolean(STATE_GEOFENCE_GROUP, geofenceInGroup)
        outState.putBoolean(STATE_GROUP_EXIT, groupExit)
        outState.putInt(STATE_GROUP_INDEX, groupIndex)
        groupDraft?.let {
            outState.putString(STATE_GROUP_DRAFT, RuleJson.conditionToJson(
                Condition(ConditionType.GROUP, children = it.toList())).toString())
        }
        outState.putString(STATE_DRAFT, RuleJson.rulesToJson(listOf(Rule(
            id = editingId ?: "draft",
            name = "draft",
            enabled = true,
            conditions = conditions.toList(),
            actions = actions.toList(),
            exitConditions = exitConditions.toList(),
            restoreActions = restoreActions.toList(),
            createdAt = 0L,
            updatedAt = 0L,
            scheduleMode = ScheduleMode.entries.getOrNull(spinnerAllowedDays.selectedItemPosition)
                ?: ScheduleMode.EVERYDAY,
            allowedDaysMask = selectedAllowedDaysMask() ?: allowedDaysMask,
        ))))
        super.onSaveInstanceState(outState)
    }

    // ------------------------------------------------------------- picking

    private fun pickType(
        condition: Boolean,
        exitCondition: Boolean = false,
        restoreAction: Boolean = false,
        initial: Condition? = null,
        initialAction: Action? = null,
        editIndex: Int = -1,
        groupChild: Boolean = false,
        onPicked: (Any, Any) -> Unit,
    ) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_pick_item, null, false)
        val spinner = dialogView.findViewById<Spinner>(R.id.dialogSpinner)
        val conditionPicker = if (condition) {
            ConditionPickerEditor(this, dialogView, initial?.type, groupChild)
        } else null
        val paramEdit = dialogView.findViewById<EditText>(R.id.dialogParam1)
        val param2Edit = dialogView.findViewById<EditText>(R.id.dialogParam2)
        val paramLayout = dialogView.findViewById<TextInputLayout>(R.id.layoutInputParam1)
        val param2Layout = dialogView.findViewById<TextInputLayout>(R.id.layoutInputParam2)
        paramEdit.doAfterTextChanged { paramLayout.error = null }
        val subtitle = dialogView.findViewById<TextView>(R.id.dialogSubtitle)
        val connectionEditor = ConnectionEditor(this, dialogView, initial, initialAction)
        this.connectionEditor = connectionEditor
        val initialApp = initial?.takeIf { it.type.isAppCondition }
        val initialAppAction = initialAction?.takeIf { it.type.isAppAction }
        val appEditor = AppPickerEditor(this, dialogView,
            initialApp?.param ?: initialAppAction?.param,
            initialApp?.appName ?: initialAppAction?.appName)
        val timerEditor = TimerConditionEditor(this, dialogView, initial)
        val timeRangeEditor = TimeRangeEditor(this, dialogView, initial, exitCondition)
        val switchEditor = SwitchParameterEditor(dialogView, initial, initialAction)
        val soundOptions = dialogView.findViewById<View>(R.id.dialogSoundOptions)
        val actionDelayLayout = dialogView.findViewById<TextInputLayout>(R.id.layoutActionDelay)
        val actionDelayEdit = dialogView.findViewById<EditText>(R.id.dialogActionDelay)
        val soundKind = dialogView.findViewById<Spinner>(R.id.dialogSoundKind)
        soundKind.adapter = ArrayAdapter(this, R.layout.item_spinner_selected, SoundKind.entries.map { it.label })
            .apply { setDropDownViewResource(R.layout.item_spinner_dropdown) }
        val soundDuration = dialogView.findViewById<EditText>(R.id.dialogSoundDuration)
        initialAction?.takeIf { it.type == ActionType.PLAY_SOUND }?.let {
            soundKind.setSelection(SoundKind.fromCode(it.param)?.ordinal ?: 0)
            soundDuration.setText(it.param2 ?: "5")
        }
        initialAction?.let { actionDelayEdit.setText(it.delaySeconds.toString()) }
        val geofenceSummary = dialogView.findViewById<TextView>(R.id.dialogGeofenceSummary)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (condition) {
                if (initial != null) "编辑条件" else if (exitCondition) "添加退出条件" else "添加触发条件"
            } else if (initialAction != null) "编辑动作" else if (restoreAction) "添加恢复动作" else "添加执行动作")
            .setView(dialogView)
            .setPositiveButton("确定添加", null)
            .setNegativeButton("取消", null)
            .create()
        itemDialog = dialog

        if (!condition) {
            spinner.adapter = ArrayAdapter(
                this,
                R.layout.item_spinner_selected,
                ActionType.entries.map { it.label },
            ).apply { setDropDownViewResource(R.layout.item_spinner_dropdown) }
        }

        fun updateHints() {
            val type = conditionPicker?.selectedType
            val group = type == ConditionType.GROUP
            val geofence = type == ConditionType.GEOFENCE
            val timer = type == ConditionType.TIMER
            val timeRange = type == ConditionType.TIME_RANGE
            val actionType = if (condition) null else ActionType.entries[spinner.selectedItemPosition]
            val app = type?.isAppCondition == true || actionType?.isAppAction == true
            val switch = type?.isSwitchCondition == true || actionType?.isSwitchAction == true
            val sound = actionType == ActionType.PLAY_SOUND
            val specialized = geofence || group || app || timer || timeRange || switch || sound
            soundOptions.visibility = if (sound) View.VISIBLE else View.GONE
            actionDelayLayout.visibility = if (condition) View.GONE else View.VISIBLE
            actionDelayLayout.error = null
            val hint = if (condition) type?.paramHint
                else ActionType.entries[spinner.selectedItemPosition].paramHint
            paramLayout.visibility = if (specialized || hint == null) View.GONE else View.VISIBLE
            param2Layout.visibility = if (specialized || hint == null) View.GONE else View.VISIBLE
            if (condition && type !in setOf(ConditionType.WIFI_SSID, ConditionType.BT_DEVICE)) {
                param2Layout.visibility = View.GONE
            }
            if (!condition && actionType != ActionType.BROADCAST &&
                actionType != ActionType.WIFI_CONNECTION && actionType != ActionType.BLUETOOTH_CONNECTION) {
                param2Layout.visibility = View.GONE
            }
            paramLayout.error = null
            param2Layout.error = null
            paramLayout.helperText = if (specialized) null else hint
            paramLayout.hint = "主要参数"
            subtitle.text = when {
                geofence -> getString(R.string.geofence_title)
                group -> "任一满足"
                hint == null -> "无需参数"
                else -> ""
            }
            subtitle.visibility = if (subtitle.text.isEmpty()) View.GONE else View.VISIBLE
            geofenceSummary.visibility = if (geofence) View.VISIBLE else View.GONE
            timerEditor.configure(timer)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setText(
                if (geofence) getString(R.string.geofence_picker_title)
                else if (group) "编辑条件组"
                else if (initial != null || initialAction != null) "保存" else "确定添加",
            )
            if (condition) {
                val t = conditionPicker!!.selectedType
                param2Layout.helperText = when (t) {
                    ConditionType.WIFI_SSID, ConditionType.BT_DEVICE -> "第二参数：connect / disconnect / any / not_connected"
                    else -> null
                }
            } else {
                val t = ActionType.entries[spinner.selectedItemPosition]
                param2Layout.helperText = if (t == ActionType.BROADCAST) "广播附加参数：k=v,k2=v2" else null
            }
            paramEdit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                if (!condition && ActionType.entries[spinner.selectedItemPosition] == ActionType.SHELL) {
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE
                } else 0
            if (type in setOf(ConditionType.BATTERY_ABOVE, ConditionType.BATTERY_BELOW,
                    ConditionType.SCREEN_ON_FOR, ConditionType.SCREEN_OFF_FOR) ||
                actionType == ActionType.VIBRATE || actionType == ActionType.MEDIA_VOLUME) {
                paramEdit.inputType = InputType.TYPE_CLASS_NUMBER
            }
            val connectionKind = if (condition) {
                when (type) {
                    ConditionType.WIFI_SSID -> ConnectionKind.WIFI
                    ConditionType.BT_DEVICE -> ConnectionKind.BLUETOOTH
                    else -> null
                }
            } else {
                when (ActionType.entries[spinner.selectedItemPosition]) {
                    ActionType.WIFI_CONNECTION -> ConnectionKind.WIFI
                    ActionType.BLUETOOTH_CONNECTION -> ConnectionKind.BLUETOOTH
                    else -> null
                }
            }
            connectionEditor.configure(connectionKind, condition)
            appEditor.configure(app)
            timeRangeEditor.configure(timeRange)
            switchEditor.configure(type, actionType)
        }

        if (conditionPicker != null) {
            conditionPicker.onTypeChanged = ::updateHints
        } else {
            spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = updateHints()
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
        }
        initial?.let {
            paramEdit.setText(it.param)
            param2Edit.setText(it.param2)
        }
        initialAction?.let {
            spinner.setSelection(it.type.ordinal)
            paramEdit.setText(it.param)
            param2Edit.setText(it.param2)
        }
        updateHints()

        dialog.setOnShowListener {
            updateHints()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val actionDelaySeconds = if (condition) 0L else {
                    val delayText = actionDelayEdit.text.toString().trim()
                    val delay = if (delayText.isEmpty()) 0L else delayText.toLongOrNull()
                    if (delay == null || delay !in 0L..MAX_ACTION_DELAY_SECONDS) {
                        actionDelayLayout.error = "延迟需为 0-${MAX_ACTION_DELAY_SECONDS} 秒"
                        return@setOnClickListener
                    }
                    delay
                }
                if (conditionPicker?.selectedType == ConditionType.GROUP) {
                    dialog.dismiss()
                    openGroup(exitCondition, editIndex)
                    return@setOnClickListener
                }
                if (conditionPicker?.selectedType == ConditionType.GEOFENCE) {
                    dialog.dismiss()
                    openGeofence(exitCondition, editIndex, groupChild)
                    return@setOnClickListener
                }
                if (condition) {
                    val type = conditionPicker!!.selectedType
                    if (type.isAppCondition) {
                        if (!appEditor.validate()) return@setOnClickListener
                        onPicked(Condition(type, appEditor.packageName(), appName = appEditor.appName()), Unit)
                        dialog.dismiss()
                        return@setOnClickListener
                    }
                    if (type == ConditionType.TIME_RANGE) {
                        val range = timeRangeEditor.condition() ?: return@setOnClickListener
                        onPicked(range, Unit)
                        dialog.dismiss()
                        return@setOnClickListener
                    }
                    if (type == ConditionType.TIMER) {
                        onPicked(timerEditor.condition(), Unit)
                        dialog.dismiss()
                        return@setOnClickListener
                    }
                } else {
                    val type = ActionType.entries[spinner.selectedItemPosition]
                    if (type == ActionType.PLAY_SOUND) {
                        val kind = SoundKind.entries[soundKind.selectedItemPosition].code
                        val seconds = soundDuration.text.toString().trim()
                        if (seconds.isEmpty() || SoundSpec.parse(kind, seconds) == null) {
                            dialogView.findViewById<TextInputLayout>(R.id.layoutSoundDuration).error = "请输入 1-30 秒"
                            return@setOnClickListener
                        }
                        onPicked(Unit, Action(type, kind, seconds, delaySeconds = actionDelaySeconds))
                        dialog.dismiss()
                        return@setOnClickListener
                    }
                    if (type.isAppAction) {
                        if (!appEditor.validate()) return@setOnClickListener
                        onPicked(Unit, Action(type, appEditor.packageName(), appName = appEditor.appName(),
                            delaySeconds = actionDelaySeconds))
                        dialog.dismiss()
                        return@setOnClickListener
                    }
                }
                val direction = connectionEditor.direction()
                val p = switchEditor.parameter() ?: if (paramLayout.visibility != View.VISIBLE) null
                    else if (direction != null) connectionEditor.targetValue()
                    else paramEdit.text.toString().trim().takeIf { it.isNotEmpty() }
                val p2 = direction
                    ?: if (param2Layout.visibility == View.VISIBLE) {
                        param2Edit.text.toString().trim().takeIf { it.isNotEmpty() }
                    } else null
                if (condition) {
                    val t = conditionPicker!!.selectedType
                    if (t.paramHint != null && p == null) {
                        paramLayout.error = "请填写${t.paramHint}"
                        paramEdit.requestFocus()
                        return@setOnClickListener
                    }
                    val parameterError = when (t) {
                        ConditionType.BATTERY_ABOVE, ConditionType.BATTERY_BELOW ->
                            if (p?.toIntOrNull() !in 0..100) "电量需为 0-100 的整数" else null
                        ConditionType.SCREEN_ON_FOR, ConditionType.SCREEN_OFF_FOR ->
                            if (p?.toLongOrNull()?.let { it in 0..(Long.MAX_VALUE / 60_000) } != true) "请输入有效的非负分钟数" else null
                        else -> null
                    }
                    if (parameterError != null) {
                        paramLayout.error = parameterError
                        return@setOnClickListener
                    }
                    onPicked(Condition(t, p, p2), Unit)
                } else {
                    val t = ActionType.entries[spinner.selectedItemPosition]
                    if (t.paramHint != null && p == null && t != ActionType.BROADCAST) {
                        paramLayout.error = "请填写${t.paramHint}"
                        paramEdit.requestFocus()
                        return@setOnClickListener
                    }
                    if ((t == ActionType.VIBRATE && p?.toLongOrNull()?.let { it in 1..10_000 } != true) ||
                        (t == ActionType.MEDIA_VOLUME && p?.toIntOrNull() !in 0..100)) {
                        paramLayout.error = t.paramHint
                        return@setOnClickListener
                    }
                    if (t == ActionType.BROADCAST && p == null) {
                        paramLayout.error = "请填写广播 action"
                        return@setOnClickListener
                    }
                    onPicked(Unit, Action(t, p, p2, delaySeconds = actionDelaySeconds))
                }
                dialog.dismiss()
            }
        }
        dialog.setOnDismissListener {
            if (itemDialog === dialog) itemDialog = null
            connectionEditor.close()
            if (this.connectionEditor === connectionEditor) this.connectionEditor = null
            appEditor.close()
            timerEditor.close()
            timeRangeEditor.close()
        }
        dialog.showResponsive()
    }

    override fun onDestroy() {
        itemDialog?.dismiss()
        groupDialog?.dismiss()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        connectionEditor?.onResume()
    }

    private fun openGeofence(exitCondition: Boolean, index: Int = -1, inGroup: Boolean = false) {
        geofenceExitCondition = exitCondition
        geofenceEditIndex = index
        geofenceInGroup = inGroup
        val source = (if (inGroup) groupDraft else if (exitCondition) exitConditions else conditions)?.getOrNull(index)
        geofencePicker.launch(Intent(this, GeofencePickerActivity::class.java).apply {
            putExtra(GeofencePickerActivity.EXTRA_FENCE, source?.param)
            putExtra(GeofencePickerActivity.EXTRA_DIRECTION, source?.param2)
            putExtra(GeofencePickerActivity.EXTRA_NAME, source?.placeName)
        })
    }

    private fun openGroup(exit: Boolean, index: Int = -1) {
        val source = (if (exit) exitConditions else conditions).getOrNull(index)
        groupExit = exit
        groupIndex = index
        groupDraft = when {
            source == null -> mutableListOf()
            source.type == ConditionType.GROUP -> source.children.toMutableList()
            else -> mutableListOf(source)
        }
        showGroupDialog()
    }

    private fun showGroupDialog() {
        val content = LayoutInflater.from(this).inflate(R.layout.dialog_condition_group, null, false)
        groupItems = content.findViewById(R.id.boxGroupConditions)
        content.findViewById<View>(R.id.btnAddGroupChild).setOnClickListener {
            if ((groupDraft?.size ?: 0) >= 100) {
                Toast.makeText(this, "每组最多 100 个条件", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pickType(true, exitCondition = groupExit, groupChild = true) { value, _ ->
                groupDraft?.add(value as Condition)
                redrawGroup()
            }
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("条件组（任一满足）")
            .setView(content)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消") { _, _ -> groupDraft = null }
            .create()
        groupDialog = dialog
        dialog.setOnCancelListener { groupDraft = null }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val children = groupDraft?.toList().orEmpty()
                if (children.isEmpty()) {
                    Toast.makeText(this, "条件组至少需要一个条件", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val target = if (groupExit) exitConditions else conditions
                val group = Condition(ConditionType.GROUP, children = children)
                if (groupIndex in target.indices) target[groupIndex] = group else target.add(group)
                groupDraft = null
                dialog.dismiss()
                redraw()
            }
        }
        redrawGroup()
        dialog.showResponsive()
    }

    private fun redrawGroup() {
        val box = groupItems ?: return
        val draft = groupDraft ?: return
        drawList(box, draft, onEdit = { index ->
            val source = draft.getOrNull(index) ?: return@drawList
            if (source.type == ConditionType.GEOFENCE) openGeofence(groupExit, index, true)
            else pickType(true, exitCondition = groupExit, initial = source, editIndex = index, groupChild = true) { value, _ ->
                draft[index] = value as Condition
                redrawGroup()
            }
        }, onChanged = { redrawGroup() }) { draft.removeAt(it) }
    }

    // ------------------------------------------------------------- drawing

    private fun redraw() {
        updateConditionModeHint()
        drawList(boxConditions, conditions, onEdit = { editCondition(false, it) }) { conditions.removeAt(it) }
        drawList(boxActions, actions, onEdit = { editAction(false, it) }) { actions.removeAt(it) }
        drawList(boxExitConditions, exitConditions, onEdit = { editCondition(true, it) }) { exitConditions.removeAt(it) }
        drawList(boxRestoreActions, restoreActions, onEdit = { editAction(true, it) }) { restoreActions.removeAt(it) }
    }

    private fun updateConditionModeHint() {
        val text = if (TriggerMode.entries.getOrNull(spinnerTriggerMode.selectedItemPosition) == TriggerMode.ANY) {
            "任一满足即可触发"
        } else "需要全部满足"
        findViewById<TextView>(R.id.textConditionModeHint).text =
            if (conditions.any { it.type == ConditionType.TIME_RANGE }) "$text；时间范围必须满足" else text
    }

    private fun editCondition(exit: Boolean, index: Int) {
        val target = if (exit) exitConditions else conditions
        val source = target.getOrNull(index) ?: return
        if (source.type == ConditionType.GROUP) openGroup(exit, index)
        else if (source.type == ConditionType.GEOFENCE) openGeofence(exit, index)
        else pickType(true, exitCondition = exit, initial = source, editIndex = index) { c, _ ->
            target[index] = c as Condition
            redraw()
        }
    }

    private fun labelOf(item: Any): String {
        if (item is Condition && item.type == ConditionType.GROUP) return item.description()
        if (item is Condition && (item.type.isSwitchCondition || item.type == ConditionType.GEOFENCE || item.type.isAppCondition ||
                item.type == ConditionType.TIMER ||
                item.type == ConditionType.TIME_RANGE || item.type == ConditionType.WIFI_SSID || item.type == ConditionType.BT_DEVICE)) {
            return item.description()
        }
        if (item is Action) {
            return item.description()
        }
        val name = when (item) {
            is Condition -> item.type.label
            is Action -> item.type.label
            else -> item.toString()
        }
        val params = when (item) {
            is Condition -> listOfNotNull(item.param, item.param2)
            is Action -> listOfNotNull(item.param, item.param2)
            else -> emptyList()
        }
        return if (params.isEmpty()) name else "$name  [${params.joinToString(" / ")}]"
    }

    private fun drawList(
        box: LinearLayout,
        items: List<Any>,
        onEdit: ((Int) -> Unit)? = null,
        onChanged: () -> Unit = { redraw() },
        onRemove: (Int) -> Unit,
    ) {
        box.removeAllViews()
        if (items.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "暂无配置项，点击下方按钮添加"
                textSize = 13f
                setTextColor(getColor(R.color.text_muted))
                val density = resources.displayMetrics.density
                setPadding(0, (8 * density).toInt(), 0, (16 * density).toInt())
            }
            box.addView(emptyTv)
            return
        }
        items.forEachIndexed { idx, item ->
            val v = LayoutInflater.from(this).inflate(R.layout.item_editor_entry, box, false)
            val tv = v.findViewById<TextView>(R.id.textItemContent)
            val btnDel = v.findViewById<ImageView>(R.id.btnItemDelete)

            tv.text = "${idx + 1}.  ${labelOf(item)}"
            val clickDelete = View.OnClickListener {
                MaterialAlertDialogBuilder(this)
                    .setTitle("移除确认")
                    .setMessage("确定移除「${labelOf(item)}」？")
                    .setPositiveButton("移除") { _, _ -> onRemove(idx); onChanged() }
                    .setNegativeButton("取消", null)
                    .showResponsive()
            }
            btnDel.setOnClickListener(clickDelete)
            if (onEdit != null) {
                v.setOnClickListener { onEdit(idx) }
            } else {
                v.setOnClickListener(clickDelete)
            }
            box.addView(v)
        }
    }

    private fun editAction(restore: Boolean, index: Int) {
        val target = if (restore) restoreActions else actions
        val source = target.getOrNull(index) ?: return
        pickType(false, restoreAction = restore, initialAction = source) { _, action ->
            target[index] = action as Action
            redraw()
        }
    }

    private fun selectedAllowedDaysMask(): Int? = when (
        ScheduleMode.entries.getOrNull(spinnerAllowedDays.selectedItemPosition)
    ) {
        ScheduleMode.CUSTOM_DAYS -> allowedDayChips.filter { (_, chip) -> chip.isChecked }
            .fold(0) { mask, (bit, _) -> mask or bit }
        ScheduleMode.WEEKDAYS -> WEEKDAY_MASK
        ScheduleMode.WEEKEND -> WEEKEND_MASK
        else -> ALL_DAYS_MASK
    }.takeIf { it != 0 }

    // ------------------------------------------------------------- saving

    private fun save() {
        val name = findViewById<EditText>(R.id.editRuleName).text.toString().trim()
            .ifBlank { "规则 ${System.currentTimeMillis() % 10000}" }
        if (conditions.isEmpty()) {
            Toast.makeText(this, "至少需要一个触发条件", Toast.LENGTH_SHORT).show()
            return
        }
        if (actions.isEmpty()) {
            Toast.makeText(this, "至少需要一个执行动作", Toast.LENGTH_SHORT).show()
            return
        }
        if ((conditions + exitConditions).any { it.type == ConditionType.GROUP && it.children.isEmpty() }) {
            Toast.makeText(this, "条件组至少需要一个条件", Toast.LENGTH_SHORT).show()
            return
        }
        val leafConditions = (conditions + exitConditions).flatMap { it.leaves() }
        val invalidGeofence = leafConditions.firstOrNull { condition ->
            condition.type == ConditionType.GEOFENCE &&
                (GeofenceSpec.parse(condition.param) == null || !GeofenceSpec.isValidDirection(condition.param2))
        }
        if (invalidGeofence != null) {
            Toast.makeText(this, "地理围栏参数无效，请重新添加该条件", Toast.LENGTH_LONG).show()
            return
        }
        if (leafConditions.any {
                it.type == ConditionType.TIME_RANGE &&
                    (TimeRangeSpec.parse(it.param) == null || !TimeRangeSpec.isValidMode(it.param2))
            }) {
            Toast.makeText(this, "时间范围无效，请编辑该条件", Toast.LENGTH_LONG).show()
            return
        }
        val allowedDaysMask = selectedAllowedDaysMask()
        if (allowedDaysMask == null) {
            Toast.makeText(this, "自定义星期至少选择一天", Toast.LENGTH_SHORT).show()
            return
        }
        val cooldownText = findViewById<EditText>(R.id.editCooldownSeconds).text.toString().trim()
        val cooldownSeconds = if (cooldownText.isEmpty()) 0L else cooldownText.toLongOrNull()
        if (cooldownSeconds == null || cooldownSeconds !in 0L..MAX_RULE_COOLDOWN_SECONDS) {
            Toast.makeText(this, "冷却时间需为 0-${MAX_RULE_COOLDOWN_SECONDS} 秒", Toast.LENGTH_SHORT).show()
            return
        }
        val now = System.currentTimeMillis()
        val list = AppPrefs.loadRules(this)
        val existing = list.firstOrNull { it.id == editingId }
        val rule = Rule(
            id = editingId ?: UUID.randomUUID().toString(),
            name = name,
            enabled = existing?.enabled ?: true,
            conditions = conditions.toList(),
            actions = actions.toList(),
            exitConditions = exitConditions.toList(),
            restoreActions = restoreActions.toList(),
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            triggerMode = TriggerMode.entries.getOrNull(spinnerTriggerMode.selectedItemPosition)
                ?: TriggerMode.ALL,
            cooldownSeconds = cooldownSeconds,
            scheduleMode = ScheduleMode.entries.getOrNull(spinnerAllowedDays.selectedItemPosition)
                ?: ScheduleMode.EVERYDAY,
            allowedDaysMask = allowedDaysMask,
        )
        val idx = list.indexOfFirst { it.id == rule.id }
        if (idx >= 0) {
            list[idx] = rule
        } else {
            list.add(rule)
        }
        AppPrefs.saveRules(this, list)
        Toast.makeText(this, "已保存：${rule.name}", Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        private const val WEEKDAY_MASK = 0b0011111
        private const val WEEKEND_MASK = 0b1100000
        private const val EXTRA_ID = "rule_id"
        private const val STATE_DRAFT = "rule_draft"
        private const val STATE_GEOFENCE_EXIT = "geofence_exit"
        private const val STATE_GEOFENCE_INDEX = "geofence_index"
        private const val STATE_GEOFENCE_GROUP = "geofence_group"
        private const val STATE_GROUP_DRAFT = "group_draft"
        private const val STATE_GROUP_EXIT = "group_exit"
        private const val STATE_GROUP_INDEX = "group_index"
        fun start(ctx: Context, ruleId: String?) {
            ctx.startActivity(Intent(ctx, EditorActivity::class.java).apply {
                putExtra(EXTRA_ID, ruleId)
            })
        }
    }
}
