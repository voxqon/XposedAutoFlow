package com.github.voxqon.xposed.autoflow.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class ExecutionNotificationPermission(
    private val activity: AppCompatActivity,
    private val onChanged: () -> Unit = {},
) {
    private val preferences by lazy { activity.getSharedPreferences("notification_permission", 0) }
    private val request = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        onChanged()
    }

    fun requestIfNeeded(userInitiated: Boolean = false) {
        if (!AppPrefs.notifyExecution(activity)) return
        ExecutionNotifications.ensureChannels(activity)
        if (Build.VERSION.SDK_INT >= 33 &&
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            if (userInitiated || !preferences.getBoolean("requested", false)) {
                preferences.edit().putBoolean("requested", true).apply()
                request.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    fun openSettings() {
        activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
    }
}
