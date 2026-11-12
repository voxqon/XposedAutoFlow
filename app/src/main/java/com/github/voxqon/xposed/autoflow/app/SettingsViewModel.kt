package com.github.voxqon.xposed.autoflow.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import com.github.voxqon.xposed.autoflow.backup.BackupData
import com.github.voxqon.xposed.autoflow.backup.WebDavClient
import com.github.voxqon.xposed.autoflow.backup.WebDavConfig
import com.github.voxqon.xposed.autoflow.backup.WebDavPreferences
import com.github.voxqon.xposed.autoflow.store.ExecutionLogStore
import java.util.concurrent.Executors

data class BackupUiState(
    val busy: Boolean = false,
    val message: String = "",
    val failed: Boolean = false,
    val pendingRestore: BackupData? = null,
)

class SettingsViewModel(private val app: Application) : AndroidViewModel(app) {
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "atlt-webdav").apply { isDaemon = true }
    }
    val state = MutableLiveData(BackupUiState())
    var draft: WebDavConfig = runCatching { WebDavPreferences(app).load() }.getOrElse {
        state.value = BackupUiState(message = "无法读取已保存的凭据，请重新填写并保存", failed = true)
        WebDavConfig()
    }

    fun run(label: String, config: WebDavConfig) {
        if (state.value?.busy == true || state.value?.pendingRestore != null) return
        draft = config
        state.value = BackupUiState(busy = true, message = "正在${label}…")
        // Snapshot local data on the main thread before starting network work.
        val backup = if (label == "备份") BackupData(AppPrefs.loadRules(app),
            ExecutionLogStore.get(app).limit()) else null
        worker.execute {
            val result = runCatching {
                val client = WebDavClient(config)
                client.validate()
                when (label) {
                    "保存配置" -> {
                        WebDavPreferences(app).save(config)
                        BackupUiState(message = "WebDAV 配置已保存")
                    }
                    "测试连接" -> {
                        val exists = client.testConnection()
                        BackupUiState(message = if (exists) "连接成功，WebDAV 目录可读取"
                            else "连接成功，父目录可读取；备份目录将在首次备份时创建，写权限届时确认")
                    }
                    "备份" -> {
                        client.backup(requireNotNull(backup))
                        BackupUiState(message = "备份成功，共 ${backup.rules.size} 条规则")
                    }
                    else -> BackupUiState(message = "备份已下载并通过校验",
                        pendingRestore = client.restore())
                }
            }.getOrElse { BackupUiState(message = "$label 失败：${it.message ?: it.javaClass.simpleName}", failed = true) }
            state.postValue(result)
        }
    }

    fun cancelRestore() { state.value = BackupUiState(message = "已取消恢复") }

    fun applyRestore(data: BackupData) {
        runCatching {
            // Only rules and retention are restored; credentials and privileged switches stay local.
            AppPrefs.replaceRules(app, data.rules)
            ExecutionLogStore.get(app).setLimit(data.logLimit)
        }.onSuccess {
            state.value = BackupUiState(message = "恢复成功，共 ${data.rules.size} 条规则")
        }.onFailure {
            state.value = BackupUiState(message = "恢复失败：${it.message}", failed = true)
        }
    }

    override fun onCleared() {
        worker.shutdownNow()
        super.onCleared()
    }
}
