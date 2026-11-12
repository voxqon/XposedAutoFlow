package com.github.voxqon.xposed.autoflow.app

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import com.github.voxqon.xposed.autoflow.R
import com.google.android.material.bottomnavigation.BottomNavigationView

/** Shared bottom navigation setup for the three primary activities. */
object BottomNavHelper {

    const val EXTRA_TAB = "nav_tab"

    fun selectedTab(activity: android.app.Activity): Int =
        when (activity) {
            is MainActivity -> R.id.nav_home
            is ExecutionLogsActivity -> R.id.nav_logs
            is SettingsActivity -> R.id.nav_settings
            else -> activity.intent.getIntExtra(EXTRA_TAB, R.id.nav_home)
        }

    fun bottomPaddingForFloating(activity: android.app.Activity): Int =
        if (AppPrefs.floatingBottomBar(activity) &&
            activity.findViewById<View>(R.id.bottomNav)?.visibility != View.GONE) {
            (if (activity.resources.configuration.screenHeightDp <= 600) 120 else 88)
                .times(activity.resources.displayMetrics.density).toInt()
        } else 0

    fun apply(activity: android.app.Activity, nav: BottomNavigationView, onInsetsChanged: () -> Unit = {}) {
        val floating = AppPrefs.floatingBottomBar(activity)
        nav.menu.findItem(selectedTab(activity))?.isChecked = true
        val content = activity.findViewById<View>(android.R.id.content)
        fun updateVisibility(insets: WindowInsetsCompat?) {
            nav.visibility = if (insets?.isVisible(WindowInsetsCompat.Type.ime()) == true) View.GONE else View.VISIBLE
        }
        // Listen on the content root: a hidden navigation view stops receiving insets.
        ViewCompat.setOnApplyWindowInsetsListener(content) { _, insets ->
            updateVisibility(insets)
            onInsetsChanged()
            insets
        }
        ViewCompat.requestApplyInsets(content)
        if (floating) {
            nav.elevation = 12f
            nav.setBackgroundResource(R.drawable.bg_bottom_nav_floating)
            val density = activity.resources.displayMetrics.density
            val hMargin = (16 * density).toInt()
            val vMargin = (14 * density).toInt()
            nav.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                marginStart = hMargin
                marginEnd = hMargin
                bottomMargin = vMargin
                topMargin = 0
            }
        } else {
            nav.elevation = 8f
            nav.setBackgroundResource(R.color.card_bg)
            nav.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                marginStart = 0
                marginEnd = 0
                bottomMargin = 0
                topMargin = 0
            }
        }
        nav.setOnItemSelectedListener { item ->
            if (item.itemId == selectedTab(activity)) return@setOnItemSelectedListener true
            val target = when (item.itemId) {
                R.id.nav_home -> MainActivity::class.java
                R.id.nav_logs -> ExecutionLogsActivity::class.java
                R.id.nav_settings -> SettingsActivity::class.java
                else -> return@setOnItemSelectedListener false
            }
            val intent = Intent(activity, target).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                putExtra(EXTRA_TAB, item.itemId)
            }
            activity.startActivity(intent)
            activity.overridePendingTransition(0, 0)
            true
        }
    }
}
