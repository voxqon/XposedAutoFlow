package com.github.voxqon.xposed.autoflow.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

object LauncherIcon {
    private fun component(context: Context) =
        ComponentName(context.packageName, "${context.packageName}.Launcher")

    fun hidden(context: Context): Boolean = context.packageManager
        .getComponentEnabledSetting(component(context)) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED

    fun setHidden(context: Context, hidden: Boolean) {
        context.packageManager.setComponentEnabledSetting(component(context),
            if (hidden) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            else PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
    }
}
