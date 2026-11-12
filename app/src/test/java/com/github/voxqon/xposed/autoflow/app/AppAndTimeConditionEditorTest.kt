package com.github.voxqon.xposed.autoflow.app

import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.github.voxqon.xposed.autoflow.R
import com.github.voxqon.xposed.autoflow.model.ActionType
import com.github.voxqon.xposed.autoflow.model.Action
import com.github.voxqon.xposed.autoflow.model.Condition
import com.github.voxqon.xposed.autoflow.model.ConditionType
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
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34], qualifiers = "w320dp-h568dp")
class AppAndTimeConditionEditorTest {
    @Test
    fun openAndCloseAppActionsUseThePickerForBothExecutionAndRestoration() {
        install("com.example.camera", "Camera")
        install("com.example.camera.other", "Camera")
        val controller = Robolectric.buildActivity(EditorActivity::class.java).setup()
        val activity = controller.get()
        for (button in listOf(R.id.btnAddAction, R.id.btnAddRestoreAction)) {
            for (type in ActionType.entries.filter { it.isAppAction }) {
                val dialog = openAction(activity, button, type)
                assertEquals(View.VISIBLE, dialog.findViewById<View>(R.id.dialogAppOptions)!!.visibility)
                assertEquals(View.GONE, dialog.findViewById<View>(R.id.layoutInputParam1)!!.visibility)
                assertEquals(View.GONE, dialog.findViewById<View>(R.id.layoutInputParam2)!!.visibility)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertTrue(dialog.isShowing)
                selectApp(dialog, "com.example.camera.other")
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertFalse(dialog.isShowing)
            }
        }
        val condition = open(activity, R.id.btnAddCondition, ConditionType.SCREEN_ON)
        condition.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        activity.findViewById<View>(R.id.btnSave).performClick()
        val rule = AppPrefs.loadRules(activity).single()
        val expected = ActionType.entries.filter { it.isAppAction }.map {
            Action(it, "com.example.camera.other", appName = "Camera")
        }
        assertEquals(expected, rule.actions)
        assertEquals(expected, rule.restoreActions)
        controller.pause().stop().destroy()
    }

    @Test
    fun existingAppActionsCanBeReselectedAndCancellingKeepsThePreviousApp() {
        install("com.example.camera", "Camera")
        install("com.example.maps", "Maps")
        val controller = Robolectric.buildActivity(EditorActivity::class.java).setup()
        val activity = controller.get()
        for (button in listOf(R.id.btnAddAction, R.id.btnAddRestoreAction)) {
            val dialog = openAction(activity, button, ActionType.OPEN_APP)
            selectApp(dialog, "Camera")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
        activity.findViewById<LinearLayout>(R.id.boxActions).getChildAt(0).performClick()
        val editing = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(editing.findViewById<TextView>(R.id.dialogAppSelection)!!.text.contains("com.example.camera"))
        selectApp(editing, "Maps")
        editing.findViewById<Spinner>(R.id.dialogSpinner)!!.setSelection(ActionType.CLOSE_APP.ordinal)
        shadowOf(Looper.getMainLooper()).idle()
        editing.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        activity.findViewById<LinearLayout>(R.id.boxRestoreActions).getChildAt(0).performClick()
        val cancel = ShadowDialog.getLatestDialog() as AlertDialog
        selectApp(cancel, "Maps")
        cancel.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        val condition = open(activity, R.id.btnAddCondition, ConditionType.SCREEN_ON)
        condition.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        activity.findViewById<View>(R.id.btnSave).performClick()
        val rule = AppPrefs.loadRules(activity).single()
        assertEquals(listOf(Action(ActionType.CLOSE_APP, "com.example.maps", appName = "Maps")), rule.actions)
        assertEquals(listOf(Action(ActionType.OPEN_APP, "com.example.camera", appName = "Camera")), rule.restoreActions)
        controller.pause().stop().destroy()
    }

    @Test
    fun changingAnAppActionToASwitchDoesNotSaveTheAppSelection() {
        install("com.example.camera", "Camera")
        val controller = Robolectric.buildActivity(EditorActivity::class.java).setup()
        val activity = controller.get()
        val dialog = openAction(activity, R.id.btnAddAction, ActionType.OPEN_APP)
        selectApp(dialog, "Camera")
        dialog.findViewById<Spinner>(R.id.dialogSpinner)!!.setSelection(ActionType.WIFI.ordinal)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.GONE, dialog.findViewById<View>(R.id.dialogAppOptions)!!.visibility)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        val condition = open(activity, R.id.btnAddCondition, ConditionType.SCREEN_ON)
        condition.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        activity.findViewById<View>(R.id.btnSave).performClick()
        assertEquals(listOf(Action(ActionType.WIFI, "on")), AppPrefs.loadRules(activity).single().actions)
        controller.pause().stop().destroy()
    }

    @Test
    fun allAppConditionsRequireASelectionAndSaveThePackageRatherThanTheLabel() {
        install("com.example.camera", "Camera")
        install("com.example.camera.other", "Camera")
        val controller = Robolectric.buildActivity(EditorActivity::class.java).setup()
        val activity = controller.get()
        for (type in ConditionType.entries.filter { it.isAppCondition }) {
            val dialog = open(activity, R.id.btnAddCondition, type)
            assertEquals(View.GONE, dialog.findViewById<View>(R.id.layoutInputParam1)!!.visibility)
            assertEquals(View.GONE, dialog.findViewById<View>(R.id.layoutInputParam2)!!.visibility)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(dialog.isShowing)
            selectApp(dialog, "com.example.camera.other")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertFalse(dialog.isShowing)
        }
        val exit = open(activity, R.id.btnAddExitCondition, ConditionType.APP_LEFT)
        selectApp(exit, "com.example.camera.other")
        exit.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        addActionAndSave(activity)

        val rule = AppPrefs.loadRules(activity).single()
        assertEquals(ConditionType.entries.filter { it.isAppCondition }, rule.conditions.map { it.type })
        (rule.conditions + rule.exitConditions).forEach {
            assertEquals("com.example.camera.other", it.param)
            assertEquals("Camera", it.appName)
            assertEquals(null, it.param2)
        }
        controller.pause().stop().destroy()
    }

    @Test
    fun appSelectionCanBeEditedAndSearchFindsLabelsAndPackages() {
        install("com.example.camera", "Camera")
        install("com.example.maps", "Maps")
        val controller = Robolectric.buildActivity(EditorActivity::class.java).setup()
        val activity = controller.get()
        val dialog = open(activity, R.id.btnAddCondition, ConditionType.APP_FOREGROUND)
        selectApp(dialog, "Camera")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        activity.findViewById<LinearLayout>(R.id.boxConditions).getChildAt(0).performClick()
        val editing = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(editing.findViewById<TextView>(R.id.dialogAppSelection)!!.text.contains("com.example.camera"))
        selectApp(editing, "com.example.maps")
        editing.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        addActionAndSave(activity)
        assertEquals("com.example.maps", AppPrefs.loadRules(activity).single().conditions.single().param)
        controller.pause().stop().destroy()
    }

    @Test
    fun overnightOutsideWindowCanBeAddedEditedAndUsedAsAnExitCondition() {
        val controller = Robolectric.buildActivity(EditorActivity::class.java).setup()
        val activity = controller.get()
        val dialog = open(activity, R.id.btnAddCondition, ConditionType.TIME_RANGE)
        chooseTime(dialog, R.id.dialogTimeRangeStart, 22, 30)
        chooseTime(dialog, R.id.dialogTimeRangeEnd, 7, 15)
        assertTrue(dialog.findViewById<TextView>(R.id.dialogTimeRangeEnd)!!.text.contains("次日"))
        dialog.findViewById<RadioGroup>(R.id.dialogTimeRangeMode)!!.check(R.id.dialogTimeRangeOutside)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        activity.findViewById<LinearLayout>(R.id.boxConditions).getChildAt(0).performClick()
        val editing = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals(R.id.dialogTimeRangeOutside,
            editing.findViewById<RadioGroup>(R.id.dialogTimeRangeMode)!!.checkedRadioButtonId)
        chooseTime(editing, R.id.dialogTimeRangeStart, 23, 0)
        editing.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        val exit = open(activity, R.id.btnAddExitCondition, ConditionType.TIME_RANGE)
        exit.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        addActionAndSave(activity)

        val rule = AppPrefs.loadRules(activity).single()
        assertEquals(listOf(Condition(ConditionType.TIME_RANGE, "23:00-07:15", "outside")), rule.conditions)
        assertEquals(listOf(Condition(ConditionType.TIME_RANGE, "09:00-18:00", "inside")), rule.exitConditions)
        controller.pause().stop().destroy()
    }

    @Test
    fun equalTimesStayOpenAndSwitchingTypesDoesNotSaveStaleParameters() {
        val controller = Robolectric.buildActivity(EditorActivity::class.java).setup()
        val activity = controller.get()
        val dialog = open(activity, R.id.btnAddCondition, ConditionType.TIME_RANGE)
        chooseTime(dialog, R.id.dialogTimeRangeEnd, 9, 0)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(dialog.isShowing)
        assertEquals(View.VISIBLE, dialog.findViewById<View>(R.id.dialogTimeRangeError)!!.visibility)
        dialog.selectCondition(ConditionType.SCREEN_ON)
        assertEquals(View.GONE, dialog.findViewById<View>(R.id.dialogTimeRangeOptions)!!.visibility)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        addActionAndSave(activity)
        assertEquals(listOf(Condition(ConditionType.SCREEN_ON)), AppPrefs.loadRules(activity).single().conditions)
        controller.pause().stop().destroy()
    }

    @Test
    fun timerConditionsUseTimePickerAndGraphicalDaySelectionWhileReadingLegacyValues() {
        val app = RuntimeEnvironment.getApplication()
        AppPrefs.saveRules(app, listOf(com.github.voxqon.xposed.autoflow.model.Rule(
            "timer-old", "Timer", true,
            listOf(Condition(ConditionType.TIMER, "18:30", "wd")),
            listOf(Action(ActionType.WIFI, "on")), emptyList(), emptyList(), 0, 0)))
        val controller = Robolectric.buildActivity(EditorActivity::class.java,
            Intent(app, EditorActivity::class.java).putExtra("rule_id", "timer-old")).setup()
        val activity = controller.get()
        activity.findViewById<LinearLayout>(R.id.boxConditions).getChildAt(0).performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals(View.VISIBLE, dialog.findViewById<View>(R.id.dialogTimerOptions)!!.visibility)
        assertTrue(dialog.findViewById<TextView>(R.id.dialogTimerTime)!!.text.contains("18:30"))
        assertEquals(R.id.dialogTimerWeekdays,
            dialog.findViewById<RadioGroup>(R.id.dialogTimerDays)!!.checkedRadioButtonId)
        chooseTime(dialog, R.id.dialogTimerTime, 7, 45)
        dialog.findViewById<RadioGroup>(R.id.dialogTimerDays)!!.check(R.id.dialogTimerWeekends)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        activity.findViewById<View>(R.id.btnSave).performClick()
        assertEquals(listOf(Condition(ConditionType.TIMER, "07:45", "we")),
            AppPrefs.loadRules(activity).single().conditions)
        controller.pause().stop().destroy()
    }

    @Test
    fun timerConditionsSupportStatutoryWorkdayAndHolidayModes() {
        val app = RuntimeEnvironment.getApplication()
        AppPrefs.saveRules(app, listOf(com.github.voxqon.xposed.autoflow.model.Rule(
            "timer-legal", "TimerLegal", true,
            listOf(Condition(ConditionType.TIMER, "09:00", "workday")),
            listOf(Action(ActionType.WIFI, "on")), emptyList(), emptyList(), 0, 0)))
        val controller = Robolectric.buildActivity(EditorActivity::class.java,
            Intent(app, EditorActivity::class.java).putExtra("rule_id", "timer-legal")).setup()
        val activity = controller.get()
        activity.findViewById<LinearLayout>(R.id.boxConditions).getChildAt(0).performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals(R.id.dialogTimerWorkdays,
            dialog.findViewById<RadioGroup>(R.id.dialogTimerDays)!!.checkedRadioButtonId)
        dialog.findViewById<RadioGroup>(R.id.dialogTimerDays)!!.check(R.id.dialogTimerHolidays)
        shadowOf(Looper.getMainLooper()).idle()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        activity.findViewById<View>(R.id.btnSave).performClick()
        assertEquals(listOf(Condition(ConditionType.TIMER, "09:00", "holiday")),
            AppPrefs.loadRules(activity).single().conditions)
        controller.pause().stop().destroy()
    }

    private fun install(pkg: String, label: String) {
        shadowOf(RuntimeEnvironment.getApplication().packageManager).installPackage(PackageInfo().apply {
            packageName = pkg
            applicationInfo = ApplicationInfo().apply {
                packageName = pkg
                nonLocalizedLabel = label
                flags = ApplicationInfo.FLAG_INSTALLED
            }
        })
    }

    private fun open(activity: EditorActivity, button: Int, type: ConditionType): AlertDialog {
        activity.findViewById<View>(button).performClick()
        return (ShadowDialog.getLatestDialog() as AlertDialog).also {
            it.selectCondition(type)
        }
    }

    private fun openAction(activity: EditorActivity, button: Int, type: ActionType): AlertDialog {
        activity.findViewById<View>(button).performClick()
        return (ShadowDialog.getLatestDialog() as AlertDialog).also {
            it.findViewById<Spinner>(R.id.dialogSpinner)!!.setSelection(type.ordinal)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun selectApp(dialog: AlertDialog, query: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        val status = dialog.findViewById<TextView>(R.id.dialogAppStatus)!!
        while (status.text.toString().startsWith("正在") && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        dialog.findViewById<EditText>(R.id.dialogAppSearch)!!.setText(query)
        shadowOf(Looper.getMainLooper()).idle()
        val list = dialog.findViewById<RecyclerView>(R.id.dialogAppList)!!
        assertEquals(1, list.adapter!!.itemCount)
        list.measure(View.MeasureSpec.makeMeasureSpec(280, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(220, View.MeasureSpec.EXACTLY))
        list.layout(0, 0, 280, 220)
        list.findViewHolderForAdapterPosition(0)!!.itemView.performClick()
    }

    private fun chooseTime(dialog: AlertDialog, button: Int, hour: Int, minute: Int) {
        dialog.findViewById<View>(button)!!.performClick()
        val picker = ShadowDialog.getLatestDialog() as TimePickerDialog
        picker.updateTime(hour, minute)
        picker.getButton(TimePickerDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun addActionAndSave(activity: EditorActivity) {
        activity.findViewById<View>(R.id.btnAddAction).performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.findViewById<Spinner>(R.id.dialogSpinner)!!.setSelection(ActionType.WIFI.ordinal)
        shadowOf(Looper.getMainLooper()).idle()
        dialog.findViewById<android.widget.CompoundButton>(R.id.dialogStateSwitch)!!.isChecked = true
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        activity.findViewById<View>(R.id.btnSave).performClick()
    }
}
