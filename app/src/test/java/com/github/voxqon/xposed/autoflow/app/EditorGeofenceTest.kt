package com.github.voxqon.xposed.autoflow.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.github.voxqon.xposed.autoflow.R
import com.github.voxqon.xposed.autoflow.model.ConditionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34])
class EditorGeofenceTest {
    @Test
    fun selectingGeofenceOpensPickerAndAddsATriggerCondition() {
        val controller = Robolectric.buildActivity(EditorActivity::class.java).setup()
        val activity = controller.get()
        activity.findViewById<View>(R.id.btnAddCondition).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.selectCondition(ConditionType.GEOFENCE)
        assertEquals(View.GONE, dialog.findViewById<View>(R.id.layoutInputParam1)!!.visibility)
        assertEquals(View.GONE, dialog.findViewById<View>(R.id.layoutInputParam2)!!.visibility)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        val launch = shadowOf(activity).nextStartedActivityForResult
        assertEquals(GeofencePickerActivity::class.java.name, launch.intent.component!!.className)

        activity.activityResultRegistry.dispatchResult(launch.requestCode, Activity.RESULT_OK, selection())

        val triggers = activity.findViewById<LinearLayout>(R.id.boxConditions)
        assertEquals(1, triggers.childCount)
        assertEquals("1.  进入 Home（半径 200 米）", triggers.getChildAt(0).findViewById<TextView>(R.id.textItemContent).text.toString())
        assertFalse(activity.findViewById<LinearLayout>(R.id.boxExitConditions).getChildAt(0).isClickable)
        controller.pause().stop().destroy()
    }

    @Test
    fun pendingExitSelectionSurvivesEditorRecreationAndEditingReplacesTheItem() {
        val controller = Robolectric.buildActivity(EditorActivity::class.java).setup()
        val activity = controller.get()
        activity.findViewById<View>(R.id.btnAddExitCondition).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.selectCondition(ConditionType.GEOFENCE)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        val launch = shadowOf(activity).nextStartedActivityForResult
        val state = Bundle()
        controller.saveInstanceState(state).pause().stop().destroy()
        val restoredController = Robolectric.buildActivity(EditorActivity::class.java).create(state).start().restoreInstanceState(state).resume()
        val restored = restoredController.get()

        assertTrue(restored.activityResultRegistry.dispatchResult(launch.requestCode, Activity.RESULT_OK, selection()))
        val exits = restored.findViewById<LinearLayout>(R.id.boxExitConditions)
        assertEquals(1, exits.childCount)
        assertEquals("1.  进入 Home（半径 200 米）", exits.getChildAt(0).findViewById<TextView>(R.id.textItemContent).text.toString())
        exits.getChildAt(0).performClick()
        val edit = shadowOf(restored).nextStartedActivityForResult
        assertEquals("31.0,121.0,200.0", edit.intent.getStringExtra(GeofencePickerActivity.EXTRA_FENCE))
        restored.activityResultRegistry.dispatchResult(edit.requestCode, Activity.RESULT_CANCELED, null)
        assertEquals(1, exits.childCount)
        assertEquals("1.  进入 Home（半径 200 米）", exits.getChildAt(0).findViewById<TextView>(R.id.textItemContent).text.toString())

        exits.getChildAt(0).performClick()
        val secondEdit = shadowOf(restored).nextStartedActivityForResult
        restored.activityResultRegistry.dispatchResult(secondEdit.requestCode, Activity.RESULT_OK, selection("500", "exit"))

        assertEquals(1, exits.childCount)
        assertEquals("1.  离开 Home（半径 500 米）", exits.getChildAt(0).findViewById<TextView>(R.id.textItemContent).text.toString())
        restoredController.pause().stop().destroy()
    }

    private fun selection(radius: String = "200", direction: String = "enter"): Intent = Intent().apply {
        putExtra(GeofencePickerActivity.EXTRA_FENCE, "31,121,$radius")
        putExtra(GeofencePickerActivity.EXTRA_DIRECTION, direction)
        putExtra(GeofencePickerActivity.EXTRA_NAME, "Home")
    }
}
