package com.github.voxqon.xposed.autoflow.app

import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import androidx.appcompat.app.AlertDialog
import com.github.voxqon.xposed.autoflow.R
import com.github.voxqon.xposed.autoflow.model.Action
import com.github.voxqon.xposed.autoflow.model.ActionType
import com.github.voxqon.xposed.autoflow.model.Condition
import com.github.voxqon.xposed.autoflow.model.ConditionType
import com.github.voxqon.xposed.autoflow.model.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34], qualifiers = "w320dp-h568dp")
class SwitchParameterEditorTest {
    @Test
    fun allSwitchConditionsAndActionsSaveTheirNativeCodesWithoutTextParameters() {
        val controller = Robolectric.buildActivity(EditorActivity::class.java).setup()
        val activity = controller.get()
        val conditionTypes = ConditionType.entries.filter { it.isSwitchCondition }
        for (type in conditionTypes) {
            val dialog = open(activity, R.id.btnAddCondition, type.ordinal)
            assertSwitchFields(dialog)
            dialog.findViewById<EditText>(R.id.dialogParam2)!!.setText("stale")
            state(dialog).isChecked = false
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            val exit = open(activity, R.id.btnAddExitCondition, type.ordinal)
            special(exit).isChecked = true
            assertFalse(state(exit).isEnabled)
            exit.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
        val actionTypes = ActionType.entries.filter { it.isSwitchAction }
        for (type in actionTypes) {
            val action = open(activity, R.id.btnAddAction, type.ordinal)
            assertSwitchFields(action)
            assertTrue(state(action).isChecked)
            assertFalse(special(action).isChecked)
            action.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            val restore = open(activity, R.id.btnAddRestoreAction, type.ordinal)
            special(restore).isChecked = true
            restore.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
        activity.findViewById<View>(R.id.btnSave).performClick()
        val rule = AppPrefs.loadRules(activity).single()
        assertEquals(conditionTypes.map { Condition(it, if (it == ConditionType.ROTATION_LOCKED) "unlocked" else "off") },
            rule.conditions)
        assertEquals(conditionTypes.map { Condition(it, "any") }, rule.exitConditions)
        assertEquals(actionTypes.map { Action(it, if (it == ActionType.ROTATION_LOCK) "lock" else "on") }, rule.actions)
        assertEquals(actionTypes.map { Action(it, "toggle") }, rule.restoreActions)
        controller.pause().stop().destroy()
    }

    @Test
    fun oldAnyAndOffConditionsCanBeEditedWithoutChangingTheirMeaning() {
        val app = RuntimeEnvironment.getApplication()
        val original = listOf(Condition(ConditionType.WIFI_ON), Condition(ConditionType.BT_ON, "off"),
            Condition(ConditionType.ROTATION_LOCKED, "unlocked"))
        AppPrefs.saveRules(app, listOf(Rule("old", "Old", true, original,
            listOf(Action(ActionType.WIFI, "on")), emptyList(), emptyList(), 0, 0)))
        val intent = Intent(app, EditorActivity::class.java).putExtra("rule_id", "old")
        val controller = Robolectric.buildActivity(EditorActivity::class.java, intent).setup()
        val activity = controller.get()
        for (index in original.indices) {
            activity.findViewById<LinearLayout>(R.id.boxConditions).getChildAt(index).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertEquals(index == 0, special(dialog).isChecked)
            if (index > 0) assertFalse(state(dialog).isChecked)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
        activity.findViewById<View>(R.id.btnSave).performClick()
        assertEquals(original.mapIndexed { index, c -> if (index == 0) c.copy(param = "any") else c },
            AppPrefs.loadRules(activity).single().conditions)
        controller.pause().stop().destroy()
    }

    @Test
    fun switchingToATextActionHidesTheSwitchAndDoesNotReuseItsValue() {
        val controller = Robolectric.buildActivity(EditorActivity::class.java).setup()
        val activity = controller.get()
        val dialog = open(activity, R.id.btnAddAction, ActionType.WIFI.ordinal)
        state(dialog).isChecked = false
        dialog.findViewById<Spinner>(R.id.dialogSpinner)!!.setSelection(ActionType.BROADCAST.ordinal)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.GONE, dialog.findViewById<View>(R.id.dialogSwitchOptions)!!.visibility)
        dialog.findViewById<EditText>(R.id.dialogParam1)!!.setText("com.example.TEST")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(activity.findViewById<android.widget.TextView>(R.id.textItemContent).text.contains("com.example.TEST"))
        controller.pause().stop().destroy()
    }

    private fun assertSwitchFields(dialog: AlertDialog) {
        assertEquals(View.VISIBLE, dialog.findViewById<View>(R.id.dialogSwitchOptions)!!.visibility)
        assertEquals(View.GONE, dialog.findViewById<View>(R.id.layoutInputParam1)!!.visibility)
        assertEquals(View.GONE, dialog.findViewById<View>(R.id.layoutInputParam2)!!.visibility)
    }

    private fun state(dialog: AlertDialog) = dialog.findViewById<CompoundButton>(R.id.dialogStateSwitch)!!
    private fun special(dialog: AlertDialog) = dialog.findViewById<CompoundButton>(R.id.dialogSwitchAny)!!

    private fun open(activity: EditorActivity, button: Int, position: Int): AlertDialog {
        activity.findViewById<View>(button).performClick()
        return (ShadowDialog.getLatestDialog() as AlertDialog).also {
            if (button == R.id.btnAddCondition || button == R.id.btnAddExitCondition) {
                it.selectCondition(ConditionType.entries[position])
            } else {
                it.findViewById<Spinner>(R.id.dialogSpinner)!!.setSelection(position)
            }
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
