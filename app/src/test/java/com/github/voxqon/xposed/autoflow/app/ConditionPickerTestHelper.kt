package com.github.voxqon.xposed.autoflow.app

import android.os.Looper
import android.widget.Spinner
import androidx.appcompat.app.AlertDialog
import com.github.voxqon.xposed.autoflow.R
import com.github.voxqon.xposed.autoflow.model.ConditionType
import org.robolectric.Shadows.shadowOf

internal fun AlertDialog.selectCondition(type: ConditionType) {
    val category = ConditionCategory.entries.first { type in it.types }
    val categories = findViewById<Spinner>(R.id.dialogConditionCategory)!!
    categories.setSelection((0 until categories.count).first {
        categories.getItemAtPosition(it) == category.label
    })
    shadowOf(Looper.getMainLooper()).idle()
    val functions = findViewById<Spinner>(R.id.dialogSpinner)!!
    functions.setSelection((0 until functions.count).first {
        functions.getItemAtPosition(it) == type.label
    })
    shadowOf(Looper.getMainLooper()).idle()
}
