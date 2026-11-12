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
import com.github.voxqon.xposed.autoflow.model.*
import org.junit.Assert.*
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
class NewActionEditorTest {
    @Test fun systemAlarmCanBeAddedWithoutParametersAndItsDelayCanBeEdited() {
        val app = RuntimeEnvironment.getApplication()
        AppPrefs.saveRules(app, listOf(Rule("alarm", "Alarm", true,
            listOf(Condition(ConditionType.SCREEN_ON)), emptyList(), emptyList(), emptyList(), 0, 0)))
        val controller = Robolectric.buildActivity(EditorActivity::class.java,
            Intent(app, EditorActivity::class.java).putExtra("rule_id", "alarm")).setup()
        val activity = controller.get()
        activity.findViewById<View>(R.id.btnAddAction).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.findViewById<Spinner>(R.id.dialogSpinner)!!.setSelection(ActionType.SYSTEM_ALARM.ordinal)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.GONE, dialog.findViewById<View>(R.id.layoutInputParam1)!!.visibility)
        assertEquals(View.GONE, dialog.findViewById<View>(R.id.layoutInputParam2)!!.visibility)
        assertEquals(View.GONE, dialog.findViewById<View>(R.id.dialogSoundOptions)!!.visibility)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertFalse(dialog.isShowing)
        activity.findViewById<LinearLayout>(R.id.boxActions).getChildAt(0).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val editing = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals(ActionType.SYSTEM_ALARM.ordinal,
            editing.findViewById<Spinner>(R.id.dialogSpinner)!!.selectedItemPosition)
        editing.findViewById<EditText>(R.id.dialogActionDelay)!!.setText("10")
        editing.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        activity.findViewById<View>(R.id.btnSave).performClick()
        assertEquals(Action(ActionType.SYSTEM_ALARM, delaySeconds = 10),
            AppPrefs.loadRules(app).single().actions.single())
        controller.pause().stop().destroy()
    }

    @Test fun executionAndRestoreDelaysSurviveEditingAndSaving() {
        val app = RuntimeEnvironment.getApplication()
        val action = Action(ActionType.WIFI, "off", delaySeconds = 8)
        val restore = Action(ActionType.BROADCAST, "test.restore", delaySeconds = 4)
        AppPrefs.saveRules(app, listOf(Rule("delay", "Delay", true,
            listOf(Condition(ConditionType.SCREEN_ON)), listOf(action),
            listOf(Condition(ConditionType.SCREEN_OFF)), listOf(restore), 0, 0)))
        val controller = Robolectric.buildActivity(EditorActivity::class.java,
            Intent(app, EditorActivity::class.java).putExtra("rule_id", "delay")).setup()
        val activity = controller.get()
        activity.findViewById<LinearLayout>(R.id.boxActions).getChildAt(0).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val delay = dialog.findViewById<EditText>(R.id.dialogActionDelay)!!
        assertEquals("8", delay.text.toString())
        delay.setText("12")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        activity.findViewById<View>(R.id.btnSave).performClick()
        val saved = AppPrefs.loadRules(app).single()
        assertEquals(action.copy(delaySeconds = 12), saved.actions.single())
        assertEquals(restore, saved.restoreActions.single())
        controller.pause().stop().destroy()
    }

    @Test fun reopeningConnectionActionKeepsItsTargetAndDisconnectDirection() {
        val app = RuntimeEnvironment.getApplication()
        val action = Action(ActionType.WIFI_CONNECTION, "Home", "disconnect")
        AppPrefs.saveRules(app, listOf(Rule("connection", "Connection", true,
            listOf(Condition(ConditionType.SCREEN_ON)), listOf(action), emptyList(), emptyList(), 0, 0)))
        val controller = Robolectric.buildActivity(EditorActivity::class.java,
            Intent(app, EditorActivity::class.java).putExtra("rule_id", "connection")).setup()
        val activity = controller.get()
        activity.findViewById<LinearLayout>(R.id.boxActions).getChildAt(0).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals("Home", dialog.findViewById<EditText>(R.id.dialogParam1)!!.text.toString())
        assertEquals(1, dialog.findViewById<Spinner>(R.id.dialogConnectionMode)!!.selectedItemPosition)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        activity.findViewById<View>(R.id.btnSave).performClick()
        assertEquals(action, AppPrefs.loadRules(app).single().actions.single())
        controller.pause().stop().destroy()
    }

    @Test fun soundDurationIsValidatedAndExistingSoundCanBeEdited() {
        val app = RuntimeEnvironment.getApplication()
        AppPrefs.saveRules(app, listOf(Rule("sound", "Sound", true,
            listOf(Condition(ConditionType.DEVICE_UNLOCKED)),
            listOf(Action(ActionType.PLAY_SOUND, "alarm", "10")), emptyList(), emptyList(), 0, 0)))
        val controller = Robolectric.buildActivity(EditorActivity::class.java,
            Intent(app, EditorActivity::class.java).putExtra("rule_id", "sound")).setup()
        val activity = controller.get()
        activity.findViewById<LinearLayout>(R.id.boxActions).getChildAt(0).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals(SoundKind.ALARM.ordinal, dialog.findViewById<Spinner>(R.id.dialogSoundKind)!!.selectedItemPosition)
        val duration = dialog.findViewById<EditText>(R.id.dialogSoundDuration)!!
        assertEquals("10", duration.text.toString())
        duration.setText("0")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(dialog.isShowing)
        duration.setText("8")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        activity.findViewById<View>(R.id.btnSave).performClick()
        assertEquals(Action(ActionType.PLAY_SOUND, "alarm", "8"), AppPrefs.loadRules(app).single().actions.single())
        controller.pause().stop().destroy()
    }

    @Test fun reopeningOffSwitchActionPreservesTheSavedDirection() {
        val app = RuntimeEnvironment.getApplication()
        AppPrefs.saveRules(app, listOf(Rule("off", "Off", true,
            listOf(Condition(ConditionType.SCREEN_ON)), listOf(Action(ActionType.WIFI, "off")),
            emptyList(), emptyList(), 0, 0)))
        val controller = Robolectric.buildActivity(EditorActivity::class.java,
            Intent(app, EditorActivity::class.java).putExtra("rule_id", "off")).setup()
        val activity = controller.get()
        activity.findViewById<LinearLayout>(R.id.boxActions).getChildAt(0).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertFalse(dialog.findViewById<CompoundButton>(R.id.dialogStateSwitch)!!.isChecked)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        activity.findViewById<View>(R.id.btnSave).performClick()
        assertEquals("off", AppPrefs.loadRules(app).single().actions.single().param)
        controller.pause().stop().destroy()
    }
}
