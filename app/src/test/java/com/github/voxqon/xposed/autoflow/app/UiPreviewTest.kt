package com.github.voxqon.xposed.autoflow.app

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.Spinner
import androidx.appcompat.app.AlertDialog
import com.github.voxqon.xposed.autoflow.R
import com.github.voxqon.xposed.autoflow.model.ConditionType
import com.github.voxqon.xposed.autoflow.model.ActionType
import com.github.voxqon.xposed.autoflow.model.ExecutionLog
import com.github.voxqon.xposed.autoflow.model.ExecutionStep
import com.github.voxqon.xposed.autoflow.model.ExecutionStatus
import com.github.voxqon.xposed.autoflow.store.ExecutionLogStore
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.views.MapView
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiPreviewTest {
    @Test
    @Config(qualifiers = "w320dp-h568dp-night")
    fun renderGroupedWifiPickerOnASmallNightScreen() {
        val controller = Robolectric.buildActivity(EditorActivity::class.java).setup()
        controller.get().findViewById<View>(R.id.btnAddCondition).performClick()
        val picker = ShadowDialog.getLatestDialog() as AlertDialog
        picker.selectCondition(ConditionType.WIFI_SSID)
        capture("grouped-wifi-small-night", picker.window!!.decorView,
            picker.window!!.attributes.width, dialog = true)
        picker.selectCondition(ConditionType.WIFI_DISCONNECTED)
        capture("grouped-wifi-state-small-night", picker.window!!.decorView,
            picker.window!!.attributes.width, dialog = true)
        picker.dismiss()
        controller.pause().stop().destroy()
    }

    @Test
    @Config(qualifiers = "w320dp-h568dp-night")
    fun renderConditionGroupOnASmallNightScreen() {
        val controller = Robolectric.buildActivity(EditorActivity::class.java).setup()
        controller.get().findViewById<View>(R.id.btnAddConditionGroup).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val group = ShadowDialog.getLatestDialog() as AlertDialog
        for (type in listOf(ConditionType.WIFI_CONNECTED, ConditionType.BT_CONNECTED,
                ConditionType.SCREEN_ON, ConditionType.CHARGER_CONNECTED)) {
            group.findViewById<View>(R.id.btnAddGroupChild)!!.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val picker = ShadowDialog.getLatestDialog() as AlertDialog
            picker.selectCondition(type)
            picker.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
        capture("condition-group-small-night", group.window!!.decorView, group.window!!.attributes.width, dialog = true)
        group.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        capture("condition-group-editor-small-night", controller.get().window.decorView)
        controller.pause().stop().destroy()
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-night")
    fun renderNightPagesAndSoundDialogWithReadableColors() {
        val main = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = main.get()
        val pairs = listOf(R.color.text_title to R.color.bg_page,
            R.color.text_body to R.color.card_bg, R.color.text_muted to R.color.card_bg,
            R.color.tag_condition_text to R.color.tag_condition_bg,
            R.color.tag_action_text to R.color.tag_action_bg,
            R.color.tag_exit_condition_text to R.color.tag_exit_condition_bg,
            R.color.tag_restore_action_text to R.color.tag_restore_action_bg)
        pairs.forEach { (foreground, background) ->
            assertTrue("Night text contrast must be at least 4.5",
                androidx.core.graphics.ColorUtils.calculateContrast(
                    activity.getColor(foreground), activity.getColor(background)) >= 4.5)
        }
        assertTrue(!activity.resources.getBoolean(R.bool.light_system_bars))
        val fab = activity.findViewById<com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton>(R.id.btnNewRule)
        assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(
            fab.currentTextColor, requireNotNull(fab.backgroundTintList).defaultColor) >= 4.5)
        capture("main-night", activity.window.decorView)
        main.pause().stop().destroy()
        val editor = Robolectric.buildActivity(EditorActivity::class.java).setup()
        capture("editor-night", editor.get().window.decorView)
        editor.get().findViewById<View>(R.id.btnAddAction).performClick()
        val sound = ShadowDialog.getLatestDialog() as AlertDialog
        sound.findViewById<Spinner>(R.id.dialogSpinner)!!.setSelection(ActionType.PLAY_SOUND.ordinal)
        shadowOf(Looper.getMainLooper()).idle()
        capture("sound-dialog-night", sound.window!!.decorView, sound.window!!.attributes.width, dialog = true)
        sound.dismiss()
        editor.pause().stop().destroy()
        captureSettingsAndLogs("night")
        val geofence = Robolectric.buildActivity(GeofencePickerActivity::class.java,
            Intent(RuntimeEnvironment.getApplication(), GeofencePickerActivity::class.java)
                .putExtra(GeofencePickerActivity.EXTRA_FENCE, "31,121,200")
                .putExtra(GeofencePickerActivity.EXTRA_DIRECTION, "inside")).setup()
        geofence.get().findViewById<MapView>(R.id.geofenceMap).setUseDataConnection(false)
        capture("geofence-night", geofence.get().window.decorView)
        geofence.pause().stop().destroy()
    }

    @Test
    fun renderLogsAndSettingsOnSmallScreens() = captureSettingsAndLogs("small")

    @Test
    @Config(qualifiers = "w800dp-h1000dp")
    fun renderLogsAndSettingsOnTablets() = captureSettingsAndLogs("tablet")

    @Test
    @Config(qualifiers = "w320dp-h568dp")
    fun renderJianguoyunSettingsOnSmallScreens() = captureJianguoyunSettings("small")

    @Test
    @Config(qualifiers = "w800dp-h1000dp")
    fun renderJianguoyunSettingsOnTablets() = captureJianguoyunSettings("tablet")

    private fun captureJianguoyunSettings(size: String) {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        activity.findViewById<View>(R.id.btnUseJianguoyun).performClick()
        val section = activity.findViewById<View>(R.id.inputWebDavUrl).parent as View
        val width = activity.resources.displayMetrics.widthPixels
        section.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        section.layout(0, 0, width, section.measuredHeight)
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        capture("jianguoyun-$size", section, height = section.measuredHeight)
        val address = activity.findViewById<android.widget.EditText>(R.id.editWebDavUrl)
        val availableWidth = address.width - address.compoundPaddingLeft - address.compoundPaddingRight
        for (line in 0 until address.layout.lineCount) {
            assertTrue("The backup address must fit without horizontal clipping",
                address.layout.getLineWidth(line) <= availableWidth + 1)
        }
        controller.pause().stop().destroy()
    }

    private fun captureSettingsAndLogs(size: String) {
        val settings = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        capture("settings-$size", settings.get().window.decorView)
        settings.pause().stop().destroy()
        val store = ExecutionLogStore.get(RuntimeEnvironment.getApplication())
        store.append(ExecutionLog(ruleId = "camera", ruleName = "相机启动后锁定方向",
            phase = "触发执行", reason = "app_fg · com.android.camera",
            steps = listOf(ExecutionStep("方向锁定", ExecutionStatus.SUCCESS, "系统调用已完成"))))
        val logs = Robolectric.buildActivity(ExecutionLogsActivity::class.java).setup()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        val list = logs.get().findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerLogs)
        while ((list.adapter?.itemCount ?: 0) == 0 && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        capture("logs-$size", logs.get().window.decorView)
        logs.pause().stop().destroy()
    }

    @Test
    fun renderAppAndTimeRangeDialogsOnSmallScreens() = captureNewConditionDialogs("small")

    @Test
    @Config(qualifiers = "w800dp-h1000dp")
    fun renderAppAndTimeRangeDialogsOnTablets() = captureNewConditionDialogs("tablet")

    private fun captureNewConditionDialogs(size: String) {
        val pm = RuntimeEnvironment.getApplication().packageManager
        listOf("com.example.camera" to "Camera", "com.example.maps" to "Maps",
            "com.example.music" to "Music").forEach { (pkg, label) ->
            shadowOf(pm).installPackage(PackageInfo().apply {
                packageName = pkg
                applicationInfo = ApplicationInfo().apply {
                    packageName = pkg
                    nonLocalizedLabel = label
                    flags = ApplicationInfo.FLAG_INSTALLED
                }
            })
        }
        val editor = Robolectric.buildActivity(EditorActivity::class.java).setup()
        editor.get().findViewById<View>(R.id.btnAddCondition).performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        val status = dialog.findViewById<android.widget.TextView>(R.id.dialogAppStatus)!!
        while (status.text.toString().startsWith("正在") && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        capture("apps-dialog-$size", dialog.window!!.decorView, dialog.window!!.attributes.width, dialog = true)
        dialog.selectCondition(ConditionType.TIME_RANGE)
        capture("time-range-dialog-$size", dialog.window!!.decorView, dialog.window!!.attributes.width, dialog = true)
        dialog.selectCondition(ConditionType.WIFI_ON)
        capture("switch-dialog-$size", dialog.window!!.decorView, dialog.window!!.attributes.width, dialog = true)
        dialog.dismiss()
        editor.get().findViewById<View>(R.id.btnAddAction).performClick()
        val actionDialog = ShadowDialog.getLatestDialog() as AlertDialog
        val actionStatus = actionDialog.findViewById<android.widget.TextView>(R.id.dialogAppStatus)!!
        val actionDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (actionStatus.text.toString().startsWith("正在") && System.nanoTime() < actionDeadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        capture("apps-action-dialog-$size", actionDialog.window!!.decorView,
            actionDialog.window!!.attributes.width, dialog = true)
        actionDialog.dismiss()
        editor.pause().stop().destroy()
    }

    @Test
    fun renderSmallScreenPagesAndScrollableDialogs() {
        val main = Robolectric.buildActivity(MainActivity::class.java).setup()
        capture("main", main.get().window.decorView)
        main.pause().stop().destroy()

        val editor = Robolectric.buildActivity(EditorActivity::class.java).setup()
        capture("editor", editor.get().window.decorView)
        editor.get().findViewById<View>(R.id.btnAddCondition).performClick()
        val connection = ShadowDialog.getLatestDialog() as AlertDialog
        connection.selectCondition(ConditionType.WIFI_SSID)
        capture("connection-dialog", connection.window!!.decorView, connection.window!!.attributes.width, dialog = true)
        assertTrue(connection.getButton(AlertDialog.BUTTON_POSITIVE).bottom > 0)
        connection.dismiss()
        editor.pause().stop().destroy()

        val intent = Intent(RuntimeEnvironment.getApplication(), GeofencePickerActivity::class.java)
            .putExtra(GeofencePickerActivity.EXTRA_FENCE, "31.2304,121.4737,200")
            .putExtra(GeofencePickerActivity.EXTRA_NAME, "Home")
        val geofence = Robolectric.buildActivity(GeofencePickerActivity::class.java, intent).setup()
        geofence.get().findViewById<MapView>(R.id.geofenceMap).setUseDataConnection(false)
        capture("geofence", geofence.get().window.decorView)
        geofence.get().findViewById<View>(R.id.btnEnterCoordinates).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val coordinates = ShadowDialog.getLatestDialog() as AlertDialog
        capture("coordinates-dialog", coordinates.window!!.decorView, coordinates.window!!.attributes.width, dialog = true)
        coordinates.dismiss()
        geofence.pause().stop().destroy()
    }

    private fun capture(
        name: String,
        view: View,
        width: Int = view.resources.displayMetrics.widthPixels,
        dialog: Boolean = false,
        height: Int = view.resources.displayMetrics.heightPixels,
    ) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, if (dialog) View.MeasureSpec.AT_MOST else View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val output = File("build/ui-preview/$name.png")
        output.parentFile?.mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val colors = mutableSetOf<Int>()
        for (y in 0 until bitmap.height step 8) {
            for (x in 0 until bitmap.width step 8) colors.add(bitmap.getPixel(x, y))
        }
        assertTrue("Preview must contain rendered content: $name", colors.size > 5)
        bitmap.recycle()
    }
}
