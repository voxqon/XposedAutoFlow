package com.github.voxqon.xposed.autoflow.app

import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.EditText
import com.github.voxqon.xposed.autoflow.R
import com.github.voxqon.xposed.autoflow.backup.WebDavConfig
import com.github.voxqon.xposed.autoflow.model.Action
import com.github.voxqon.xposed.autoflow.model.ActionType
import com.github.voxqon.xposed.autoflow.engine.ActionExecutor
import com.github.voxqon.xposed.autoflow.model.ExecutionStatus
import com.github.voxqon.xposed.autoflow.store.ExecutionLogStore
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34], qualifiers = "w320dp-h568dp")
class SettingsActivityTest {
    @Test
    fun retentionValidatesInputAndLauncherCanBeHiddenAndRestoredWithoutDisablingTheActivity() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        activity.findViewById<EditText>(R.id.editLogLimit).setText("10001")
        activity.findViewById<View>(R.id.btnSaveLogLimit).performClick()
        assertNotNull(activity.findViewById<TextInputLayout>(R.id.inputLogLimit).error)
        activity.findViewById<EditText>(R.id.editLogLimit).setText("50")
        activity.findViewById<View>(R.id.btnSaveLogLimit).performClick()
        assertEquals(50, ExecutionLogStore.get(activity).limit())

        val toggle = activity.findViewById<SwitchMaterial>(R.id.switchHideIcon)
        toggle.performClick()
        assertTrue(LauncherIcon.hidden(activity))
        val info = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_INFO).setPackage(activity.packageName)
        assertNotNull(activity.packageManager.resolveActivity(info, 0))
        toggle.performClick()
        assertFalse(LauncherIcon.hidden(activity))
        assertNotNull(activity.packageManager.getLaunchIntentForPackage(activity.packageName))
        controller.recreate()
        assertEquals("50", controller.get().findViewById<EditText>(R.id.editLogLimit).text.toString())
        controller.pause().stop().destroy()
    }

    @Test
    fun mainPageOpensLogsAndSettingsViaBottomNav() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val nav = activity.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNav)
        nav.menu.findItem(R.id.nav_logs).isChecked = false
        nav.selectedItemId = R.id.nav_logs
        assertEquals(ExecutionLogsActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
        nav.selectedItemId = R.id.nav_settings
        assertEquals(SettingsActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
        controller.pause().stop().destroy()
    }

    @Test
    fun executorReportsUnavailableActionsAndBlockedShellAccurately() {
        val executor = ActionExecutor { null }
        val failed = executor.executeAll("test", listOf(Action(ActionType.OPEN_APP, "missing")), false)
        assertEquals(ExecutionStatus.FAILED, failed.single().status)
        val blocked = executor.executeAll("test", listOf(Action(ActionType.SHELL, "echo test")), false)
        assertEquals(ExecutionStatus.SKIPPED, blocked.single().status)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun webdavRejectsInvalidAddressBeforeNetworkWork() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        activity.findViewById<EditText>(R.id.editWebDavUrl).setText("http://example.com/dav")
        activity.findViewById<View>(R.id.btnTestWebDav).performClick()
        assertNotNull(activity.findViewById<TextInputLayout>(R.id.inputWebDavUrl).error)
        controller.pause().stop().destroy()
    }

    @Test
    fun jianguoyunPresetKeepsCredentialsAndUpdatesHintsWhenTheProviderChanges() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        val url = activity.findViewById<EditText>(R.id.editWebDavUrl)
        val username = activity.findViewById<EditText>(R.id.editWebDavUsername)
        val password = activity.findViewById<EditText>(R.id.editWebDavPassword)
        username.setText("user@example.com")
        password.setText("application-password")
        url.setText("http://invalid.example.com")
        activity.findViewById<View>(R.id.btnTestWebDav).performClick()
        activity.findViewById<View>(R.id.btnUseJianguoyun).performClick()
        assertEquals(WebDavConfig.JIANGUOYUN_BACKUP_URL, url.text.toString())
        assertEquals("user@example.com", username.text.toString())
        assertEquals("application-password", password.text.toString())
        assertEquals(null, activity.findViewById<TextInputLayout>(R.id.inputWebDavUrl).error)
        val passwordInput = activity.findViewById<TextInputLayout>(R.id.inputWebDavPassword)
        assertEquals(activity.getString(R.string.webdav_jianguoyun_password), passwordInput.hint)
        assertTrue(passwordInput.helperText.toString().contains("第三方应用管理"))
        controller.recreate()
        val recreated = controller.get()
        assertEquals(WebDavConfig.JIANGUOYUN_BACKUP_URL,
            recreated.findViewById<EditText>(R.id.editWebDavUrl).text.toString())
        assertEquals(recreated.getString(R.string.webdav_jianguoyun_password),
            recreated.findViewById<TextInputLayout>(R.id.inputWebDavPassword).hint)
        recreated.findViewById<EditText>(R.id.editWebDavUrl).setText("https://example.com/dav/")
        assertEquals(recreated.getString(R.string.webdav_password),
            recreated.findViewById<TextInputLayout>(R.id.inputWebDavPassword).hint)
        assertEquals(null, recreated.findViewById<TextInputLayout>(R.id.inputWebDavPassword).helperText)
        controller.pause().stop().destroy()
    }

    @Test
    fun jianguoyunMissingCredentialsAreReportedOnTheirOwnFieldsBeforeNetworking() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        activity.findViewById<View>(R.id.btnUseJianguoyun).performClick()
        activity.findViewById<EditText>(R.id.editWebDavUsername).setText("")
        activity.findViewById<EditText>(R.id.editWebDavPassword).setText("")
        activity.findViewById<View>(R.id.btnTestWebDav).performClick()
        assertNotNull(activity.findViewById<TextInputLayout>(R.id.inputWebDavUsername).error)
        assertNotNull(activity.findViewById<TextInputLayout>(R.id.inputWebDavPassword).error)
        assertEquals(null, activity.findViewById<TextInputLayout>(R.id.inputWebDavUrl).error)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.progressWebDav).visibility)
        controller.pause().stop().destroy()
    }

    @Test
    fun floatingSwitchKeepsDraftAndDoesNotRecreateTheActivity() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        val url = activity.findViewById<EditText>(R.id.editWebDavUrl)
        url.setText("https://dav.example.com/backup")
        val toggle = activity.findViewById<SwitchMaterial>(R.id.switchFloatingBottomBar)
        toggle.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(AppPrefs.floatingBottomBar(activity))
        assertSame(activity, controller.get())
        assertEquals("https://dav.example.com/backup",
            activity.findViewById<EditText>(R.id.editWebDavUrl).text.toString())
        controller.pause().stop().destroy()
    }

    @Test
    fun floatingNavigationHidesForImeAndRestoresItsPaddingWhenTheKeyboardCloses() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        AppPrefs.setFloatingBottomBar(app, true)
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        val root = activity.findViewById<View>(android.R.id.content)
        val nav = activity.findViewById<View>(R.id.bottomNav)
        val content = activity.findViewById<View>(R.id.settingsScrollContent)
        val initialPadding = content.paddingBottom
        val ime = androidx.core.view.WindowInsetsCompat.Type.ime()
        val visible = if (android.os.Build.VERSION.SDK_INT < 30) {
            // Older Android versions report the keyboard through system window insets.
            androidx.core.view.WindowInsetsCompat.Builder()
                .setSystemWindowInsets(androidx.core.graphics.Insets.of(0, 0, 0, 300)).build()
        } else {
            androidx.core.view.WindowInsetsCompat.Builder()
                .setInsets(ime, androidx.core.graphics.Insets.of(0, 0, 0, 300))
                .setVisible(ime, true).build()
        }
        androidx.core.view.ViewCompat.dispatchApplyWindowInsets(root, visible)
        assertEquals(View.GONE, nav.visibility)
        assertEquals(activity.resources.getDimensionPixelSize(R.dimen.settings_content_bottom_padding), content.paddingBottom)
        activity.findViewById<SwitchMaterial>(R.id.switchFloatingBottomBar).performClick()
        activity.findViewById<SwitchMaterial>(R.id.switchFloatingBottomBar).performClick()
        assertEquals(View.GONE, nav.visibility)
        androidx.core.view.ViewCompat.dispatchApplyWindowInsets(root,
            androidx.core.view.WindowInsetsCompat.Builder().setVisible(ime, false).build())
        assertEquals(View.VISIBLE, nav.visibility)
        assertEquals(initialPadding, content.paddingBottom)
        controller.pause().stop().destroy()
    }
}
