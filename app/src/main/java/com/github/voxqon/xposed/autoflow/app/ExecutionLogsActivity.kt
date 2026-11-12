package com.github.voxqon.xposed.autoflow.app

import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.voxqon.xposed.autoflow.R
import com.github.voxqon.xposed.autoflow.model.ExecutionLog
import com.github.voxqon.xposed.autoflow.model.ExecutionStatus
import com.github.voxqon.xposed.autoflow.store.ExecutionLogStore
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class ExecutionLogsActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "atlt-log-reader").apply { isDaemon = true }
    }
    private val adapter = LogAdapter { showDetails(it) }
    private val handler = Handler(Looper.getMainLooper())
    private val reload = Runnable { refresh() }
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            handler.removeCallbacks(reload)
            handler.postDelayed(reload, 150)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_execution_logs)
        findViewById<MaterialToolbar>(R.id.logsToolbar).apply {
            navigationContentDescription = "返回"
            setNavigationOnClickListener { finish() }
            menu.add(0, 1, 0, R.string.logs_refresh).setIcon(android.R.drawable.ic_popup_sync)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)

            menu.add(0, 3, 2, R.string.logs_clear).setIcon(android.R.drawable.ic_menu_delete)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            (0 until menu.size()).forEach { index ->
                menu.getItem(index).icon?.setTint(ContextCompat.getColor(context, R.color.text_body))
            }
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> refresh()
                    3 -> MaterialAlertDialogBuilder(this@ExecutionLogsActivity)
                        .setTitle(R.string.logs_clear).setMessage("确定清空所有执行记录？")
                        .setNegativeButton("取消", null).setPositiveButton("清空") { _, _ ->
                            worker.execute {
                                runCatching { ExecutionLogStore.get(applicationContext).clear() }
                                    .onFailure { error -> showError(error) }
                            }
                        }.showResponsive()
                }
                true
            }
        }
        findViewById<RecyclerView>(R.id.recyclerLogs).apply {
            layoutManager = LinearLayoutManager(this@ExecutionLogsActivity)
            adapter = this@ExecutionLogsActivity.adapter
        }
        BottomNavHelper.apply(this, findViewById(R.id.bottomNav), ::applyContentBottomPadding)
    }

    override fun onStart() {
        super.onStart()
        BottomNavHelper.apply(this, findViewById(R.id.bottomNav), ::applyContentBottomPadding)
        contentResolver.registerContentObserver(ExecutionLogStore.CONTENT_URI, true, observer)
        applyContentBottomPadding()
        refresh()
    }

    private fun applyContentBottomPadding() {
        val recycler = findViewById<RecyclerView>(R.id.recyclerLogs)
        val base = resources.getDimensionPixelSize(R.dimen.logs_content_bottom_padding)
        val extra = BottomNavHelper.bottomPaddingForFloating(this)
        recycler.setPadding(0, recycler.paddingTop, 0, base + extra)
        findViewById<View>(R.id.textLogsEmpty).apply {
            setPadding(paddingLeft, paddingTop, paddingRight, base + extra)
        }
    }

    override fun onStop() {
        contentResolver.unregisterContentObserver(observer)
        handler.removeCallbacks(reload)
        super.onStop()
    }

    private fun refresh() {
        if (worker.isShutdown) return
        worker.execute {
            runCatching {
                val store = ExecutionLogStore.get(applicationContext)
                val logs = store.load()
                val limit = store.limit()
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    adapter.submitList(logs)
                    findViewById<TextView>(R.id.textLogsSummary).text =
                        getString(R.string.logs_summary, logs.size, limit)
                    findViewById<TextView>(R.id.textLogsEmpty).apply {
                        visibility = if (logs.isEmpty()) View.VISIBLE else View.GONE
                        setText(if (limit == 0) R.string.logs_disabled else R.string.logs_empty)
                    }
                }
            }.onFailure { showError(it) }
        }
    }

    private fun showError(error: Throwable) = runOnUiThread {
        if (!isDestroyed) Toast.makeText(this, "读取日志失败：${error.message}", Toast.LENGTH_LONG).show()
    }

    private fun showDetails(log: ExecutionLog) {
        val detail = buildString {
            append("${formatTime(log.startedAt)}\n${log.phase} · ${log.status.label} · ${log.durationMs} ms")
            append("\n${log.reason}\n")
            if (log.steps.isEmpty()) append("\n没有需要执行的动作")
            log.steps.forEachIndexed { index, step ->
                append("\n${index + 1}. ${step.name} · ${step.status.label}\n${step.detail}\n")
            }
        }
        val padding = (20 * resources.displayMetrics.density).toInt()
        val text = TextView(this).apply {
            setPadding(padding, padding / 2, padding, padding / 2)
            setTextColor(ContextCompat.getColor(context, R.color.text_body))
            textSize = 14f
            setTextIsSelectable(true)
            text = detail
        }
        MaterialAlertDialogBuilder(this).setTitle(log.ruleName)
            .setView(ScrollView(this).apply { addView(text) })
            .setPositiveButton("关闭", null).showResponsive()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        super.onDestroy()
    }
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))

private class LogAdapter(private val clicked: (ExecutionLog) -> Unit) :
    ListAdapter<ExecutionLog, LogAdapter.Holder>(object : DiffUtil.ItemCallback<ExecutionLog>() {
        override fun areItemsTheSame(old: ExecutionLog, new: ExecutionLog) = old.id == new.id
        override fun areContentsTheSame(old: ExecutionLog, new: ExecutionLog) = old == new
    }) {
    class Holder(view: View) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_execution_log, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val log = getItem(position)
        holder.itemView.apply {
            findViewById<TextView>(R.id.textLogName).text = log.ruleName
            findViewById<TextView>(R.id.textLogMeta).text = "${formatTime(log.startedAt)} · ${log.phase}"
            findViewById<TextView>(R.id.textLogReason).text = log.reason
            findViewById<TextView>(R.id.textLogStatus).apply {
                text = log.status.label
                setTextColor(ContextCompat.getColor(context, when (log.status) {
                    ExecutionStatus.SUCCESS -> R.color.accent_success
                    ExecutionStatus.FAILED -> R.color.accent_danger
                    else -> R.color.accent_warning
                }))
            }
            setOnClickListener { clicked(log) }
        }
    }
}
