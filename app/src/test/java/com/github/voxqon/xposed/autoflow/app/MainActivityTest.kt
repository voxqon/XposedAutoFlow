package com.github.voxqon.xposed.autoflow.app

import android.os.Looper
import androidx.appcompat.app.AlertDialog
import com.github.voxqon.xposed.autoflow.R
import com.google.android.material.switchmaterial.SwitchMaterial
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
@Config(sdk = [28, 34], qualifiers = "w320dp-h568dp")
class MainActivityTest {
    @Test
    fun shellSwitchOnlyChangesAfterConfirmationAndCancellationKeepsTheSavedState() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val toggle = activity.findViewById<SwitchMaterial>(R.id.btnShellToggle)
        assertFalse(toggle.isChecked)

        toggle.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val cancelled = ShadowDialog.getLatestDialog() as AlertDialog
        assertFalse(AppPrefs.shellAllowed(activity))
        assertFalse(toggle.isChecked)
        cancelled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(toggle.isChecked)

        toggle.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(AppPrefs.shellAllowed(activity))
        assertTrue(toggle.isChecked)

        toggle.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(AppPrefs.shellAllowed(activity))
        assertTrue(toggle.isChecked)
        controller.pause().stop().destroy()
    }

    @Test
    fun returningToHomeRestoresItsSelectedTabAndAppliesChangedFloatingPreference() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val nav = activity.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNav)
        nav.selectedItemId = R.id.nav_settings
        controller.pause().stop()
        AppPrefs.setFloatingBottomBar(activity, true)
        controller.start().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(nav.menu.findItem(R.id.nav_home).isChecked)
        assertFalse(nav.menu.findItem(R.id.nav_settings).isChecked)
        val margins = nav.layoutParams as android.view.ViewGroup.MarginLayoutParams
        assertTrue(margins.marginStart > 0)
        controller.pause().stop().destroy()
    }
}
