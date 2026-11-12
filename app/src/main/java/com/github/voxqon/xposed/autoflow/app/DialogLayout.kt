package com.github.voxqon.xposed.autoflow.app

import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.roundToInt

@Suppress("DEPRECATION")
fun AlertDialog.showResponsive(): AlertDialog {
    window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    show()
    val metrics = context.resources.displayMetrics
    val availableWidth = context.resources.configuration.screenWidthDp * metrics.density
    val width = minOf(availableWidth - 24 * metrics.density, 560 * metrics.density).roundToInt()
    window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    return this
}

fun MaterialAlertDialogBuilder.showResponsive(): AlertDialog = create().showResponsive()
