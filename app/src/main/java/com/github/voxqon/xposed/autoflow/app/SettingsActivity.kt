package com.github.voxqon.xposed.autoflow.app

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModelProvider
import com.github.voxqon.xposed.autoflow.R
import com.github.voxqon.xposed.autoflow.backup.WebDavClient
import com.github.voxqon.xposed.autoflow.backup.WebDavConfig
import com.github.voxqon.xposed.autoflow.store.ExecutionLogStore
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputLayout

class SettingsActivity : AppCompatActivity() {
    private lateinit var model: SettingsViewModel
    private var restoreDialog: AlertDialog? = null
    private var updatingIcon = false
    private val notificationPermission = ExecutionNotificationPermission(this) { updateNotificationStatus() }
    private val operationButtons = intArrayOf(R.id.btnSaveWebDav, R.id.btnTestWebDav,
        R.id.btnBackupWebDav, R.id.btnRestoreWebDav, R.id.btnUseJianguoyun)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        model = ViewModelProvider(this)[SettingsViewModel::class.java]
        findViewById<MaterialToolbar>(R.id.settingsToolbar).apply {
            navigationContentDescription = "返回"
            setNavigationOnClickListener { finish() }
        }
        BottomNavHelper.apply(this, findViewById(R.id.bottomNav), ::applyContentBottomPadding)
        applyContentBottomPadding()
        val floating = findViewById<SwitchMaterial>(R.id.switchFloatingBottomBar)
        floating.isChecked = AppPrefs.floatingBottomBar(this)
        floating.setOnCheckedChangeListener { _, checked ->
            AppPrefs.setFloatingBottomBar(this, checked)
            BottomNavHelper.apply(this, findViewById(R.id.bottomNav), ::applyContentBottomPadding)
            applyContentBottomPadding()
        }
        val notifyExec = findViewById<SwitchMaterial>(R.id.switchNotifyExecution)
        notifyExec.isChecked = AppPrefs.notifyExecution(this)
        notifyExec.setOnCheckedChangeListener { _, checked ->
            AppPrefs.setNotifyExecution(this, checked)
            if (checked) notificationPermission.requestIfNeeded(userInitiated = true)
            updateNotificationStatus()
        }
        findViewById<View>(R.id.btnNotificationSettings).setOnClickListener { notificationPermission.openSettings() }
        findViewById<EditText>(R.id.editLogLimit).setText(ExecutionLogStore.get(this).limit().toString())
        findViewById<View>(R.id.btnSaveLogLimit).setOnClickListener { saveLogLimit() }
        val hide = findViewById<SwitchMaterial>(R.id.switchHideIcon)
        hide.isChecked = LauncherIcon.hidden(this)
        hide.setOnCheckedChangeListener { _, checked ->
            if (updatingIcon) return@setOnCheckedChangeListener
            runCatching { LauncherIcon.setHidden(this, checked) }.onFailure {
                updatingIcon = true
                hide.isChecked = LauncherIcon.hidden(this)
                updatingIcon = false
                Toast.makeText(this, "无法更改桌面图标：${it.message}", Toast.LENGTH_LONG).show()
            }
        }
        findViewById<EditText>(R.id.editWebDavUrl).setText(model.draft.url)
        findViewById<EditText>(R.id.editWebDavUsername).setText(model.draft.username)
        findViewById<EditText>(R.id.editWebDavPassword).setText(model.draft.password)
        findViewById<EditText>(R.id.editWebDavUrl).doAfterTextChanged { updateWebDavHints() }
        updateWebDavHints()
        findViewById<View>(R.id.btnUseJianguoyun).setOnClickListener {
            findViewById<EditText>(R.id.editWebDavUrl).setText(WebDavConfig.JIANGUOYUN_BACKUP_URL)
            findViewById<TextInputLayout>(R.id.inputWebDavUrl).error = null
        }
        findViewById<View>(R.id.btnSaveWebDav).setOnClickListener { runOperation("保存配置") }
        findViewById<View>(R.id.btnTestWebDav).setOnClickListener { runOperation("测试连接") }
        findViewById<View>(R.id.btnBackupWebDav).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.webdav_backup)
                .setMessage("将当前规则和日志保存数量备份到配置的 WebDAV 目录，覆盖同名备份文件。")
                .setNegativeButton("取消", null)
                .setPositiveButton("备份") { _, _ -> runOperation("备份") }.showResponsive()
        }
        findViewById<View>(R.id.btnRestoreWebDav).setOnClickListener { runOperation("下载备份") }
        model.state.observe(this) { state ->
            findViewById<View>(R.id.progressWebDav).visibility = if (state.busy) View.VISIBLE else View.GONE
            val canOperate = !state.busy && state.pendingRestore == null
            operationButtons.forEach { findViewById<View>(it).isEnabled = canOperate }
            findViewById<TextView>(R.id.textWebDavStatus).apply {
                text = state.message
                setTextColor(ContextCompat.getColor(this@SettingsActivity,
                    if (state.failed) R.color.accent_danger else R.color.text_body))
            }
            val backup = state.pendingRestore
            if (backup != null && restoreDialog == null) {
                restoreDialog = MaterialAlertDialogBuilder(this)
                    .setTitle("恢复备份")
                    .setMessage("备份包含 ${backup.rules.size} 条规则，日志保存数量为 ${backup.logLimit}。" +
                        "\n\n恢复将替换本机全部规则，并立即按新数量裁剪日志。确定继续？")
                    .setNegativeButton("取消") { _, _ -> model.cancelRestore() }
                    .setOnCancelListener { model.cancelRestore() }
                    .setPositiveButton("恢复") { _, _ ->
                        model.applyRestore(backup)
                        findViewById<EditText>(R.id.editLogLimit).setText(backup.logLimit.toString())
                    }.create().also { dialog ->
                        dialog.setOnDismissListener { restoreDialog = null }
                        dialog.showResponsive()
                    }
            }
        }
    }

    private fun applyContentBottomPadding() {
        val content = findViewById<View>(R.id.settingsScrollContent)
        val base = resources.getDimensionPixelSize(R.dimen.settings_content_bottom_padding)
        val extra = BottomNavHelper.bottomPaddingForFloating(this)
        content.setPadding(content.paddingLeft, content.paddingTop,
            content.paddingRight, base + extra)
    }

    private fun updateNotificationStatus() {
        val blocked = AppPrefs.notifyExecution(this) && !ExecutionNotifications.permitted(this)
        findViewById<TextView>(R.id.textNotificationStatus).setText(
            if (blocked) R.string.execution_notify_blocked else R.string.execution_notify_note)
        findViewById<View>(R.id.btnNotificationSettings).visibility = if (blocked) View.VISIBLE else View.GONE
    }

    private fun saveLogLimit() {
        val value = findViewById<EditText>(R.id.editLogLimit).text.toString().toIntOrNull()
        val input = findViewById<TextInputLayout>(R.id.inputLogLimit)
        if (value == null || value !in 0..ExecutionLogStore.MAX_LIMIT) {
            input.error = "请输入 0 至 10000 之间的整数"
            return
        }
        input.error = null
        runCatching { ExecutionLogStore.get(this).setLimit(value) }.onSuccess {
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        }.onFailure { input.error = it.message }
    }

    private fun config() = WebDavConfig(
        findViewById<EditText>(R.id.editWebDavUrl).text.toString().trim(),
        findViewById<EditText>(R.id.editWebDavUsername).text.toString().trim(),
        findViewById<EditText>(R.id.editWebDavPassword).text.toString(),
    )

    private fun updateWebDavHints() {
        val jianguoyun = config().isJianguoyun
        findViewById<TextInputLayout>(R.id.inputWebDavUsername).setHint(
            if (jianguoyun) R.string.webdav_jianguoyun_username else R.string.webdav_username)
        findViewById<TextInputLayout>(R.id.inputWebDavPassword).apply {
            setHint(if (jianguoyun) R.string.webdav_jianguoyun_password else R.string.webdav_password)
            helperText = if (jianguoyun) getString(R.string.webdav_jianguoyun_password_hint) else null
        }
    }

    private fun runOperation(label: String) {
        val config = config()
        val input = findViewById<TextInputLayout>(R.id.inputWebDavUrl)
        val username = findViewById<TextInputLayout>(R.id.inputWebDavUsername)
        val password = findViewById<TextInputLayout>(R.id.inputWebDavPassword)
        input.error = null
        username.error = null
        password.error = null
        if (config.isJianguoyun) {
            if (config.username.isBlank()) username.error = "请输入坚果云账号邮箱"
            if (config.password.isEmpty()) password.error = "请输入坚果云第三方应用密码"
            if (username.error != null || password.error != null) return
        }
        runCatching { WebDavClient(config).validate() }.onFailure {
            input.error = it.message ?: "请输入有效的 HTTPS WebDAV 目录地址"
            return
        }
        model.run(label, config)
    }

    override fun onResume() {
        super.onResume()
        BottomNavHelper.apply(this, findViewById(R.id.bottomNav), ::applyContentBottomPadding)
        applyContentBottomPadding()
        updateNotificationStatus()
    }

    override fun onPause() {
        model.draft = config()
        super.onPause()
    }

    override fun onDestroy() {
        restoreDialog?.dismiss()
        restoreDialog = null
        super.onDestroy()
    }
}
