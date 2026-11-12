package com.github.voxqon.xposed.autoflow.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.voxqon.xposed.autoflow.R
import com.github.voxqon.xposed.autoflow.model.ChinaHoliday
import com.github.voxqon.xposed.autoflow.model.Rule
import com.github.voxqon.xposed.autoflow.store.RuleConfigContract
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: RuleAdapter
    private lateinit var emptyLayout: View
    private lateinit var recyclerRules: RecyclerView
    private lateinit var shellBtn: SwitchMaterial
    private lateinit var textShellDesc: TextView
    private var updatingShell = false
    private val notificationPermission = ExecutionNotificationPermission(this)
    private val configurationObserver = object : android.database.ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            refresh(AppPrefs.loadRules(this@MainActivity))
            updateShellLabel()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ChinaHoliday.initialize(applicationContext)

        emptyLayout = findViewById(R.id.layoutEmpty)
        shellBtn = findViewById(R.id.btnShellToggle)
        textShellDesc = findViewById(R.id.textShellDesc)

        adapter = RuleAdapter(
            onToggle = { rule, checked ->
                val list = AppPrefs.loadRules(this)
                list.indexOfFirst { it.id == rule.id }.takeIf { it >= 0 }?.let { idx ->
                    val old = list[idx]
                    list[idx] = old.copy(enabled = checked, updatedAt = System.currentTimeMillis())
                    AppPrefs.saveRules(this, list)
                    refresh(list)
                }
            },
            onClick = { rule ->
                EditorActivity.start(this, rule.id)
            },
            onLongClick = { rule ->
                MaterialAlertDialogBuilder(this)
                    .setTitle("删除规则")
                    .setMessage("确定删除「${rule.name}」？此操作无法撤销。")
                    .setPositiveButton("删除") { _, _ ->
                        val list = AppPrefs.loadRules(this)
                        list.removeAll { it.id == rule.id }
                        AppPrefs.saveRules(this, list)
                        refresh(list)
                    }
                    .setNegativeButton("取消", null)
                    .showResponsive()
            },
        )

        recyclerRules = findViewById<RecyclerView>(R.id.recyclerRules).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        findViewById<View>(R.id.btnNewRule).setOnClickListener {
            EditorActivity.start(this, null)
        }
        BottomNavHelper.apply(this, findViewById(R.id.bottomNav), ::applyContentBottomPadding)
        emptyLayout.setOnClickListener {
            EditorActivity.start(this, null)
        }

        shellBtn.setOnCheckedChangeListener { _, _ ->
            if (!updatingShell) {
                updateShellLabel()
                toggleShell()
            }
        }
        findViewById<View>(R.id.cardShellBanner).setOnClickListener { toggleShell() }
        contentResolver.registerContentObserver(RuleConfigContract.URI, false, configurationObserver)
        notificationPermission.requestIfNeeded()
    }

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(configurationObserver)
        super.onDestroy()
    }
    private fun toggleShell() {
        val cur = AppPrefs.shellAllowed(this)
        val next = !cur
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.shell_toggle))
            .setMessage(getString(R.string.shell_warning) + "\n\n当前状态：${if (cur) "已开启" else "已关闭"} → ${if (next) "开启" else "关闭"}")
            .setPositiveButton(if (next) "确认开启" else "确认关闭") { _, _ ->
                AppPrefs.setShellAllowed(this, next)
                updateShellLabel()
            }
            .setNegativeButton("取消", null)
            .showResponsive()
    }

    private fun updateShellLabel() {
        val on = AppPrefs.shellAllowed(this)
        updatingShell = true
        shellBtn.isChecked = on
        updatingShell = false
        textShellDesc.setText(if (on) R.string.shell_enabled else R.string.shell_disabled)
        textShellDesc.setTextColor(ContextCompat.getColor(this, if (on) R.color.accent_warning else R.color.text_muted))
    }

    override fun onResume() {
        super.onResume()
        BottomNavHelper.apply(this, findViewById(R.id.bottomNav), ::applyContentBottomPadding)
        applyContentBottomPadding()
        val list = AppPrefs.loadRules(this)
        refresh(list)
        updateShellLabel()
    }

    private fun applyContentBottomPadding() {
        val base = resources.getDimensionPixelSize(R.dimen.main_content_bottom_padding)
        val extra = BottomNavHelper.bottomPaddingForFloating(this)
        recyclerRules.setPadding(0, recyclerRules.paddingTop, 0, base + extra)
        emptyLayout.setPadding(emptyLayout.paddingLeft, emptyLayout.paddingTop,
            emptyLayout.paddingRight, base + extra)
        findViewById<View>(R.id.btnNewRule).updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = resources.getDimensionPixelSize(R.dimen.fab_bottom_margin) + extra
        }
    }

    private fun refresh(list: List<Rule>) {
        adapter.submitList(list.toList())
        findViewById<TextView>(R.id.textRuleCount).text =
            getString(R.string.rules_count, list.size, list.count { it.enabled })
        emptyLayout.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }
}
