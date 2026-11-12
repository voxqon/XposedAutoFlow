package com.github.voxqon.xposed.autoflow.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.Spinner
import androidx.appcompat.app.AlertDialog
import com.github.voxqon.xposed.autoflow.R
import com.github.voxqon.xposed.autoflow.model.Condition
import com.github.voxqon.xposed.autoflow.model.ConditionType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34], qualifiers = "w320dp-h568dp")
class ConditionGroupEditorTest {
    private fun dialog(): AlertDialog {
        shadowOf(Looper.getMainLooper()).idle()
        return ShadowDialog.getLatestDialog() as AlertDialog
    }
    private fun addChild(group: AlertDialog, type: ConditionType) {
        group.findViewById<View>(R.id.btnAddGroupChild)!!.performClick()
        val picker = dialog()
        picker.selectCondition(type)
        picker.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("The $type picker must accept the condition", picker.isShowing)
        if (type != ConditionType.GEOFENCE) {
            val rows = group.findViewById<LinearLayout>(R.id.boxGroupConditions)!!
            assertTrue("The group must display $type", (0 until rows.childCount).any {
                rows.getChildAt(it).findViewById<android.widget.TextView>(R.id.textItemContent)
                    ?.text?.contains(type.label) == true
            })
        }
    }

    @Test
    fun groupsSaveAsOneConditionAndCanBeEditedOrCancelledInBothSections() {
        val controller = Robolectric.buildActivity(EditorActivity::class.java).setup()
        val activity = controller.get()
        listOf(R.id.btnAddConditionGroup to R.id.boxConditions,
            R.id.btnAddExitConditionGroup to R.id.boxExitConditions).forEach { (button, box) ->
            activity.findViewById<View>(button).performClick()
            val group = dialog()
            group.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(group.isShowing)
            addChild(group, ConditionType.SCREEN_ON)
            addChild(group, ConditionType.SCREEN_OFF)
            group.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse("Saving the group must dismiss its dialog", group.isShowing)
            val rows = activity.findViewById<LinearLayout>(box)
            assertEquals(1, rows.childCount)
            assertTrue(rows.getChildAt(0).findViewById<android.widget.TextView>(R.id.textItemContent)
                ?.text?.contains("条件组") == true)
            rows.getChildAt(0).performClick()
            val editing = dialog()
            addChild(editing, ConditionType.CHARGER_CONNECTED)
            editing.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
        }
        activity.findViewById<View>(R.id.btnAddAction).performClick()
        val action = dialog()
        action.findViewById<Spinner>(R.id.dialogSpinner)!!.setSelection(
            com.github.voxqon.xposed.autoflow.model.ActionType.WIFI.ordinal)
        shadowOf(Looper.getMainLooper()).idle()
        action.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        activity.findViewById<View>(R.id.btnSave).performClick()
        val rule = AppPrefs.loadRules(activity).single()
        val expected = Condition(ConditionType.GROUP, children = listOf(
            Condition(ConditionType.SCREEN_ON), Condition(ConditionType.SCREEN_OFF)))
        assertEquals(listOf(expected), rule.conditions)
        assertEquals(listOf(expected), rule.exitConditions)
        controller.pause().stop().destroy()
    }

    @Test
    fun geofenceSelectionReturnsToItsGroupAfterActivityRecreation() {
        val controller = Robolectric.buildActivity(EditorActivity::class.java).setup()
        val activity = controller.get()
        activity.findViewById<View>(R.id.btnAddExitConditionGroup).performClick()
        addChild(dialog(), ConditionType.GEOFENCE)
        val launch = shadowOf(activity).nextStartedActivityForResult
        val saved = Bundle()
        controller.saveInstanceState(saved).pause().stop().destroy()
        val restoredController = Robolectric.buildActivity(EditorActivity::class.java)
            .create(saved).start().restoreInstanceState(saved).resume()
        val restored = restoredController.get()
        assertTrue(restored.activityResultRegistry.dispatchResult(launch.requestCode, Activity.RESULT_OK,
            Intent().putExtra(GeofencePickerActivity.EXTRA_FENCE, "31,121,500")
                .putExtra(GeofencePickerActivity.EXTRA_DIRECTION, "inside")
                .putExtra(GeofencePickerActivity.EXTRA_NAME, "Home")))
        val group = dialog()
        val rows = group.findViewById<LinearLayout>(R.id.boxGroupConditions)!!
        assertEquals(1, rows.childCount)
        rows.getChildAt(0).performClick()
        val editing = shadowOf(restored).nextStartedActivityForResult
        assertEquals("31.0,121.0,500.0", editing.intent.getStringExtra(GeofencePickerActivity.EXTRA_FENCE))
        restored.activityResultRegistry.dispatchResult(editing.requestCode, Activity.RESULT_CANCELED, null)
        group.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        val target = restored.findViewById<LinearLayout>(R.id.boxExitConditions)
        assertTrue(target.getChildAt(0).findViewById<android.widget.TextView>(R.id.textItemContent)
            .text.contains("Home"))
        restoredController.pause().stop().destroy()
    }

    @Test
    fun unsavedGroupSurvivesActivityRecreation() {
        val controller = Robolectric.buildActivity(EditorActivity::class.java).setup()
        controller.get().findViewById<View>(R.id.btnAddConditionGroup).performClick()
        addChild(dialog(), ConditionType.SCREEN_ON)
        controller.recreate()
        val group = dialog()
        assertEquals(1, group.findViewById<LinearLayout>(R.id.boxGroupConditions)!!.childCount)
        group.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(1, controller.get().findViewById<LinearLayout>(R.id.boxConditions).childCount)
        controller.pause().stop().destroy()
    }
}
