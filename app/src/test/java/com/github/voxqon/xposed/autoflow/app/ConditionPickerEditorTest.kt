package com.github.voxqon.xposed.autoflow.app

import android.content.Intent
import android.os.Looper
import android.view.View
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
class ConditionPickerEditorTest {
    @Test
    fun similarConditionsShareOneEntryAndEveryConditionRemainsAvailable() {
        val allTypes = ConditionCategory.entries.flatMap { it.types.toList() }
        assertEquals(ConditionType.entries.toSet(), allTypes.toSet())
        assertEquals(allTypes.size, allTypes.distinct().size)
        val controller = Robolectric.buildActivity(EditorActivity::class.java).setup()
        val activity = controller.get()
        activity.findViewById<View>(R.id.btnAddCondition).performClick()
        val picker = dialog()
        val categories = picker.findViewById<Spinner>(R.id.dialogConditionCategory)!!
        val labels = (0 until categories.count).map { categories.getItemAtPosition(it) }
        assertEquals(1, labels.count { it == "Wi-Fi" })
        assertEquals(1, labels.count { it == "蓝牙" })
        assertTrue(categories.count < ConditionType.entries.size)
        picker.selectCondition(ConditionType.WIFI_ON)
        assertEquals(listOf("Wi-Fi 开关", "指定 Wi-Fi 连接状态", "已连接任意 Wi-Fi", "未连接 Wi-Fi"),
            functions(picker))
        picker.selectCondition(ConditionType.BT_ON)
        assertEquals(listOf("蓝牙开关", "指定蓝牙设备连接状态", "已连接任意蓝牙设备", "未连接蓝牙设备"),
            functions(picker))
        picker.selectCondition(ConditionType.LOCATION_ON)
        assertEquals(View.GONE, picker.findViewById<View>(R.id.dialogSpinner)!!.visibility)
        assertEquals(View.GONE, picker.findViewById<View>(R.id.dialogTypeLabel)!!.visibility)
        assertEquals(View.VISIBLE, picker.findViewById<View>(R.id.dialogSwitchOptions)!!.visibility)
        picker.dismiss()

        activity.findViewById<View>(R.id.btnAddConditionGroup).performClick()
        val group = dialog()
        group.findViewById<View>(R.id.btnAddGroupChild)!!.performClick()
        val child = dialog()
        val childCategories = child.findViewById<Spinner>(R.id.dialogConditionCategory)!!
        assertFalse((0 until childCategories.count).any {
            childCategories.getItemAtPosition(it).toString().contains("条件组")
        })
        child.selectCondition(ConditionType.WIFI_DISCONNECTED)
        child.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(1, group.findViewById<LinearLayout>(R.id.boxGroupConditions)!!.childCount)
        group.dismiss()

        activity.findViewById<View>(R.id.btnAddAction).performClick()
        val action = dialog()
        assertEquals(View.GONE, action.findViewById<View>(R.id.dialogConditionCategoryOptions)!!.visibility)
        assertEquals(ActionType.entries.size, action.findViewById<Spinner>(R.id.dialogSpinner)!!.count)
        action.dismiss()
        controller.pause().stop().destroy()
    }

    @Test
    fun existingWifiAndBluetoothFunctionsReopenWithTheirOriginalParameters() {
        val app = RuntimeEnvironment.getApplication()
        val expected = listOf(
            Condition(ConditionType.WIFI_ON, "off"),
            Condition(ConditionType.WIFI_SSID, "Home", "not_connected"),
            Condition(ConditionType.WIFI_CONNECTED),
            Condition(ConditionType.WIFI_DISCONNECTED),
            Condition(ConditionType.BT_ON, "any"),
            Condition(ConditionType.BT_DEVICE, "AA:BB:CC:DD:EE:FF", "disconnect"),
            Condition(ConditionType.BT_CONNECTED),
            Condition(ConditionType.BT_DISCONNECTED),
        )
        AppPrefs.saveRules(app, listOf(Rule("connections", "Connections", true, expected,
            listOf(Action(ActionType.WIFI, "on")), expected, emptyList(), 0, 0)))
        val controller = Robolectric.buildActivity(EditorActivity::class.java,
            Intent(app, EditorActivity::class.java).putExtra("rule_id", "connections")).setup()
        val activity = controller.get()
        for (box in listOf(R.id.boxConditions, R.id.boxExitConditions)) {
            for ((index, condition) in expected.withIndex()) {
                activity.findViewById<LinearLayout>(box).getChildAt(index).performClick()
                val picker = dialog()
                val category = if (index < 4) "Wi-Fi" else "蓝牙"
                assertEquals(category, picker.findViewById<Spinner>(R.id.dialogConditionCategory)!!.selectedItem)
                assertEquals(condition.type.label, picker.findViewById<Spinner>(R.id.dialogSpinner)!!.selectedItem)
                picker.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertFalse(picker.isShowing)
            }
        }
        activity.findViewById<View>(R.id.btnSave).performClick()
        val saved = AppPrefs.loadRules(activity).single()
        assertEquals(expected, saved.conditions)
        assertEquals(expected, saved.exitConditions)
        controller.pause().stop().destroy()
    }

    @Test
    fun switchingFunctionsAndCategoriesUsesTheNewTypeWithoutStaleTargetParameters() {
        val controller = Robolectric.buildActivity(EditorActivity::class.java).setup()
        val activity = controller.get()
        for (button in listOf(R.id.btnAddCondition, R.id.btnAddExitCondition)) {
            activity.findViewById<View>(button).performClick()
            val picker = dialog()
            picker.selectCondition(ConditionType.WIFI_SSID)
            picker.findViewById<EditText>(R.id.dialogParam1)!!.setText("Home")
            picker.selectCondition(ConditionType.WIFI_DISCONNECTED)
            assertEquals(View.GONE, picker.findViewById<View>(R.id.dialogConnectionOptions)!!.visibility)
            assertEquals(View.GONE, picker.findViewById<View>(R.id.layoutInputParam1)!!.visibility)
            picker.getButton(AlertDialog.BUTTON_POSITIVE).performClick()

            activity.findViewById<View>(button).performClick()
            val other = dialog()
            other.selectCondition(ConditionType.WIFI_SSID)
            other.findViewById<EditText>(R.id.dialogParam1)!!.setText("Office")
            other.selectCondition(ConditionType.HEADSET_DISCONNECTED)
            other.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
        activity.findViewById<View>(R.id.btnAddAction).performClick()
        val action = dialog()
        action.findViewById<Spinner>(R.id.dialogSpinner)!!.setSelection(ActionType.WIFI.ordinal)
        shadowOf(Looper.getMainLooper()).idle()
        action.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        activity.findViewById<View>(R.id.btnSave).performClick()
        val saved = AppPrefs.loadRules(activity).single()
        val expected = listOf(Condition(ConditionType.WIFI_DISCONNECTED),
            Condition(ConditionType.HEADSET_DISCONNECTED))
        assertEquals(expected, saved.conditions)
        assertEquals(expected, saved.exitConditions)
        controller.pause().stop().destroy()
    }

    private fun dialog(): AlertDialog {
        shadowOf(Looper.getMainLooper()).idle()
        return ShadowDialog.getLatestDialog() as AlertDialog
    }

    private fun functions(dialog: AlertDialog): List<Any> {
        val spinner = dialog.findViewById<Spinner>(R.id.dialogSpinner)!!
        return (0 until spinner.count).map { spinner.getItemAtPosition(it) }
    }
}
