package com.github.voxqon.xposed.autoflow.app

import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.github.voxqon.xposed.autoflow.R
import com.github.voxqon.xposed.autoflow.model.ActionType
import com.github.voxqon.xposed.autoflow.model.ConditionType
import com.google.android.material.textfield.TextInputLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34], qualifiers = "w320dp-h568dp")
class EditorDialogLayoutTest {
    @Test
    fun missingRequiredParameterShowsAnInlineErrorAndKeepsTheDialogOpen() {
        val controller = Robolectric.buildActivity(EditorActivity::class.java).setup()
        val activity = controller.get()
        activity.findViewById<View>(R.id.btnAddAction).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.findViewById<Spinner>(R.id.dialogSpinner)!!.setSelection(ActionType.START_SERVICE.ordinal)
        shadowOf(Looper.getMainLooper()).idle()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()

        assertTrue(dialog.isShowing)
        assertNotNull(dialog.findViewById<TextInputLayout>(R.id.layoutInputParam1)!!.error)
        dialog.findViewById<EditText>(R.id.dialogParam1)!!.setText("com.android.camera/.ExampleService")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertFalse(dialog.isShowing)
        assertTrue(activity.findViewById<TextView>(R.id.textItemContent).text.contains("com.android.camera"))
        controller.pause().stop().destroy()
    }

    @Test
    fun switchingToAParameterlessConditionDoesNotSaveHiddenParameters() {
        val controller = Robolectric.buildActivity(EditorActivity::class.java).setup()
        val activity = controller.get()
        activity.findViewById<View>(R.id.btnAddCondition).performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.findViewById<EditText>(R.id.dialogParam1)!!.setText("com.android.camera")
        dialog.findViewById<EditText>(R.id.dialogParam2)!!.setText("stale")
        dialog.selectCondition(ConditionType.SCREEN_ON)

        assertEquals(View.GONE, dialog.findViewById<View>(R.id.layoutInputParam1)!!.visibility)
        assertEquals(View.GONE, dialog.findViewById<View>(R.id.layoutInputParam2)!!.visibility)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals("1.  亮屏", activity.findViewById<TextView>(R.id.textItemContent).text.toString())
        controller.pause().stop().destroy()
    }

    @Test
    @Config(qualifiers = "w800dp-h1000dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun tabletDialogHasABoundedWidthAndLongTypeNamesCanWrap() {
        RuntimeEnvironment.setFontScale(1.5f)
        val controller = Robolectric.buildActivity(EditorActivity::class.java).setup()
        val activity = controller.get()
        activity.findViewById<View>(R.id.btnAddRestoreAction).performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val spinner = dialog.findViewById<Spinner>(R.id.dialogSpinner)!!
        val label = spinner.adapter.getView(ActionType.BLUETOOTH_CONNECTION.ordinal, null, spinner) as TextView
        label.text = "Office Wi-Fi network with a very long name"
        val density = activity.resources.displayMetrics.density

        label.measure(View.MeasureSpec.makeMeasureSpec((200 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        label.layout(0, 0, label.measuredWidth, label.measuredHeight)

        assertTrue(dialog.window!!.attributes.width <= 560 * density)
        assertTrue(label.maxLines > 1)
        assertTrue(label.layout.lineCount > 1)
        dialog.dismiss()
        controller.pause().stop().destroy()
    }
}
