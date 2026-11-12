package com.github.voxqon.xposed.autoflow.app

import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.github.voxqon.xposed.autoflow.R
import com.github.voxqon.xposed.autoflow.model.Action
import com.github.voxqon.xposed.autoflow.model.ActionType
import com.github.voxqon.xposed.autoflow.model.Condition
import com.github.voxqon.xposed.autoflow.model.ConditionType
import com.github.voxqon.xposed.autoflow.model.ConnectionCatalog
import com.github.voxqon.xposed.autoflow.model.ConnectionMode
import com.github.voxqon.xposed.autoflow.model.ConnectionTarget
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
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34])
class ConnectionEditorTest {
    @Test
    fun manualWifiConditionAndActionKeepTheSelectedDirections() {
        val controller = Robolectric.buildActivity(EditorActivity::class.java).setup()
        val activity = controller.get()
        val condition = open(activity, R.id.btnAddCondition, ConditionType.WIFI_SSID.ordinal)
        assertEquals(View.GONE, condition.findViewById<View>(R.id.layoutInputParam2)!!.visibility)
        assertEquals(4, condition.findViewById<Spinner>(R.id.dialogConnectionMode)!!.count)
        condition.findViewById<Spinner>(R.id.dialogConnectionMode)!!.setSelection(1)
        condition.findViewById<EditText>(R.id.dialogParam1)!!.setText(" Home ")
        condition.getButton(AlertDialog.BUTTON_POSITIVE).performClick()

        val action = open(activity, R.id.btnAddAction, ActionType.WIFI_CONNECTION.ordinal)
        assertEquals(2, action.findViewById<Spinner>(R.id.dialogConnectionMode)!!.count)
        action.findViewById<EditText>(R.id.dialogParam1)!!.setText("Office")
        action.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        activity.findViewById<View>(R.id.btnSave).performClick()

        val saved = AppPrefs.loadRules(activity).single()
        assertEquals(listOf(Condition(ConditionType.WIFI_SSID, " Home ", "disconnect")), saved.conditions)
        assertEquals(listOf(Action(ActionType.WIFI_CONNECTION, "Office", "connect")), saved.actions)
        controller.pause().stop().destroy()
    }

    @Test
    fun bluetoothHistoryStoresMacAndManualInputSurvivesARefreshTimeout() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("connection_catalog", 0).edit().putString("catalog",
            ConnectionCatalog(bluetooth = listOf(
                ConnectionTarget("AA:BB:CC:DD:EE:FF", "Headphones"),
                ConnectionTarget("11:22:33:44:55:66", "Headphones"),
            )).toJson()).commit()
        val controller = Robolectric.buildActivity(EditorActivity::class.java).setup()
        val activity = controller.get()
        val dialog = open(activity, R.id.btnAddCondition, ConditionType.BT_DEVICE.ordinal)
        val source = dialog.findViewById<Spinner>(R.id.dialogConnectionSource)!!
        val target = dialog.findViewById<EditText>(R.id.dialogParam1)!!
        assertEquals(3, source.count)
        source.setSelection(2)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("11:22:33:44:55:66", target.text.toString())
        target.setText("22:33:44:55:66:77")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, source.selectedItemPosition)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertEquals("22:33:44:55:66:77", target.text.toString())
        assertEquals("上次读取的记录", dialog.findViewById<TextView>(R.id.dialogConnectionStatus)!!.text)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        val box = activity.findViewById<android.widget.LinearLayout>(R.id.boxConditions)
        assertTrue(box.findViewById<TextView>(R.id.textItemContent).text.contains("22:33:44:55:66:77"))
        controller.pause().stop().destroy()
    }

    @Test
    fun missingTargetKeepsTheDialogOpenAndSwitchActionsUseTheSwitchEditor() {
        val controller = Robolectric.buildActivity(EditorActivity::class.java).setup()
        val activity = controller.get()
        val dialog = open(activity, R.id.btnAddAction, ActionType.BLUETOOTH_CONNECTION.ordinal)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(dialog.isShowing)
        dialog.findViewById<Spinner>(R.id.dialogSpinner)!!.setSelection(ActionType.BLUETOOTH.ordinal)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.GONE, dialog.findViewById<View>(R.id.dialogConnectionOptions)!!.visibility)
        assertEquals(View.GONE, dialog.findViewById<View>(R.id.layoutInputParam1)!!.visibility)
        assertEquals(View.GONE, dialog.findViewById<View>(R.id.layoutInputParam2)!!.visibility)
        assertEquals(View.VISIBLE, dialog.findViewById<View>(R.id.dialogSwitchOptions)!!.visibility)
        dialog.findViewById<CompoundButton>(R.id.dialogSwitchAny)!!.isChecked = true
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertFalse(dialog.isShowing)
        controller.pause().stop().destroy()
    }

    @Test
    fun editingAConnectionKeepsTheTargetAndDirection() {
        val controller = Robolectric.buildActivity(EditorActivity::class.java).setup()
        val activity = controller.get()
        val dialog = open(activity, R.id.btnAddCondition, ConditionType.WIFI_SSID.ordinal)
        dialog.findViewById<EditText>(R.id.dialogParam1)!!.setText(" Home ")
        dialog.findViewById<Spinner>(R.id.dialogConnectionMode)!!.setSelection(1)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        activity.findViewById<android.widget.LinearLayout>(R.id.boxConditions).getChildAt(0).performClick()
        val editing = ShadowDialog.getLatestDialog() as AlertDialog
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(" Home ", editing.findViewById<EditText>(R.id.dialogParam1)!!.text.toString())
        assertEquals(1, editing.findViewById<Spinner>(R.id.dialogConnectionMode)!!.selectedItemPosition)
        editing.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        val action = open(activity, R.id.btnAddAction, ActionType.WIFI.ordinal)
        action.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        activity.findViewById<View>(R.id.btnSave).performClick()
        assertEquals(listOf(Condition(ConditionType.WIFI_SSID, " Home ", "disconnect")),
            AppPrefs.loadRules(activity).single().conditions)
        controller.pause().stop().destroy()
    }

    @Test
    fun notConnectedWifiAndBluetoothSaveAndReopenInTriggerAndExitConditions() {
        val controller = Robolectric.buildActivity(EditorActivity::class.java).setup()
        val activity = controller.get()
        val expected = listOf(
            Condition(ConditionType.WIFI_SSID, " Home ", "not_connected"),
            Condition(ConditionType.BT_DEVICE, "AA:BB:CC:DD:EE:FF", "not_connected"),
        )
        for (button in listOf(R.id.btnAddCondition, R.id.btnAddExitCondition)) {
            for (condition in expected) {
                val dialog = open(activity, button, condition.type.ordinal)
                val mode = dialog.findViewById<Spinner>(R.id.dialogConnectionMode)!!
                mode.setSelection(ConnectionMode.NOT_CONNECTED.ordinal)
                assertEquals("未连接", mode.selectedItem)
                dialog.findViewById<EditText>(R.id.dialogParam1)!!.setText(condition.param)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertFalse(dialog.isShowing)
            }
        }
        open(activity, R.id.btnAddAction, ActionType.WIFI.ordinal)
            .getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        activity.findViewById<View>(R.id.btnSave).performClick()
        val saved = AppPrefs.loadRules(activity).single()
        assertEquals(expected, saved.conditions)
        assertEquals(expected, saved.exitConditions)
        controller.pause().stop().destroy()

        val editingController = Robolectric.buildActivity(EditorActivity::class.java,
            Intent(activity, EditorActivity::class.java).putExtra("rule_id", saved.id)).setup()
        val editingActivity = editingController.get()
        for (box in listOf(R.id.boxConditions, R.id.boxExitConditions)) {
            for ((index, condition) in expected.withIndex()) {
                val row = editingActivity.findViewById<LinearLayout>(box).getChildAt(index)
                assertTrue(row.findViewById<TextView>(R.id.textItemContent).text.contains("未连接"))
                row.performClick()
                shadowOf(Looper.getMainLooper()).idle()
                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                assertEquals(condition.param, dialog.findViewById<EditText>(R.id.dialogParam1)!!.text.toString())
                assertEquals("未连接", dialog.findViewById<Spinner>(R.id.dialogConnectionMode)!!.selectedItem)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
        }
        editingActivity.findViewById<View>(R.id.btnSave).performClick()
        assertEquals(saved, AppPrefs.loadRules(editingActivity).single().copy(updatedAt = saved.updatedAt))
        editingController.pause().stop().destroy()
    }

    private fun open(activity: EditorActivity, button: Int, position: Int): AlertDialog {
        activity.findViewById<View>(button).performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        if (button == R.id.btnAddCondition || button == R.id.btnAddExitCondition) {
            dialog.selectCondition(ConditionType.entries[position])
        } else {
            dialog.findViewById<Spinner>(R.id.dialogSpinner)!!.setSelection(position)
        }
        shadowOf(Looper.getMainLooper()).idle()
        return dialog
    }
}
