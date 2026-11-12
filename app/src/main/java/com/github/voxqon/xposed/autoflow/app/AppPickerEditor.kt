package com.github.voxqon.xposed.autoflow.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.github.voxqon.xposed.autoflow.R
import java.text.Collator
import java.util.concurrent.Executors

class AppPickerEditor(
    private val context: Context,
    view: View,
    initialPackage: String? = null,
    initialName: String? = null,
) {
    private data class App(val info: ApplicationInfo, val label: String)
    private val options = view.findViewById<View>(R.id.dialogAppOptions)
    private val search = view.findViewById<EditText>(R.id.dialogAppSearch)
    private val selection = view.findViewById<TextView>(R.id.dialogAppSelection)
    private val status = view.findViewById<TextView>(R.id.dialogAppStatus)
    private val retry = view.findViewById<View>(R.id.dialogAppRetry)
    private val list = view.findViewById<RecyclerView>(R.id.dialogAppList)
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var apps = emptyList<App>()
    private var filtered = emptyList<App>()
    private var requested = false
    private var closed = false
    private var selectedPackage = initialPackage
    private var selectedName = initialName
    private var loading = false
    private var failed = false

    private val adapter = object : RecyclerView.Adapter<AppHolder>() {
        override fun getItemCount(): Int = filtered.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppHolder =
            AppHolder(LayoutInflater.from(context).inflate(R.layout.item_app_choice, parent, false))

        override fun onBindViewHolder(holder: AppHolder, position: Int) {
            val app = filtered[position]
            holder.name.text = app.label
            holder.pkg.text = app.info.packageName
            holder.icon.setImageDrawable(runCatching { app.info.loadIcon(context.packageManager) }.getOrNull())
            holder.selected.isChecked = selectedPackage == app.info.packageName
            holder.itemView.contentDescription = "${app.label}, ${app.info.packageName}"
            holder.itemView.isSelected = holder.selected.isChecked
            holder.itemView.setOnClickListener {
                val previous = filtered.indexOfFirst { it.info.packageName == selectedPackage }
                selectedPackage = app.info.packageName
                selectedName = app.label
                showSelection()
                if (previous >= 0) notifyItemChanged(previous)
                val current = filtered.indexOfFirst { it.info.packageName == selectedPackage }
                if (current >= 0 && current != previous) notifyItemChanged(current)
            }
        }
    }

    init {
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter
        search.doAfterTextChanged { filter() }
        retry.setOnClickListener { load() }
        showSelection()
    }

    fun configure(enabled: Boolean) {
        options.visibility = if (enabled) View.VISIBLE else View.GONE
        if (!enabled) return
        if (!requested) load() else filter()
    }

    fun packageName(): String? = selectedPackage
    fun appName(): String? = selectedName

    fun validate(): Boolean {
        if (selectedPackage != null) return true
        selection.text = context.getString(R.string.app_choose_required)
        selection.setTextColor(context.getColor(R.color.accent_warning))
        return false
    }

    fun close() {
        closed = true
        executor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
    }

    private fun load() {
        requested = true
        loading = true
        failed = false
        filter()
        executor.submit {
            val result = runCatching {
                val pm = context.packageManager
                val collator = Collator.getInstance()
                pm.getInstalledApplications(0).distinctBy { it.packageName }.map { info ->
                    App(info, runCatching { info.loadLabel(pm).toString() }.getOrDefault(info.packageName))
                }.sortedWith { a, b ->
                    collator.compare(a.label, b.label).takeIf { it != 0 }
                        ?: a.info.packageName.compareTo(b.info.packageName)
                }
            }
            handler.post {
                if (closed) return@post
                loading = false
                failed = result.isFailure
                apps = result.getOrDefault(emptyList())
                apps.firstOrNull { it.info.packageName == selectedPackage }?.let { selectedName = it.label }
                showSelection()
                filter()
            }
        }
    }

    private fun showSelection() {
        selection.text = selectedPackage?.let { "${selectedName ?: it}\n$it" }
            ?: context.getString(R.string.app_choose_required)
        selection.setTextColor(context.getColor(R.color.text_body))
    }

    private fun filter() {
        val query = search.text.toString().trim()
        val previous = filtered
        val next = apps.filter {
            query.isEmpty() || it.label.contains(query, true) || it.info.packageName.contains(query, true)
        }
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = previous.size
            override fun getNewListSize(): Int = next.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                previous[oldItemPosition].info.packageName == next[newItemPosition].info.packageName
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                previous[oldItemPosition].label == next[newItemPosition].label
        })
        filtered = next
        diff.dispatchUpdatesTo(adapter)
        retry.visibility = if (failed) View.VISIBLE else View.GONE
        status.text = context.getString(when {
            loading -> R.string.app_loading
            failed -> R.string.app_load_failed
            apps.isEmpty() -> R.string.app_list_empty
            filtered.isEmpty() -> R.string.app_no_results
            else -> R.string.app_list_count
        }, filtered.size)
    }

    private class AppHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.appChoiceName)
        val pkg: TextView = view.findViewById(R.id.appChoicePackage)
        val icon: ImageView = view.findViewById(R.id.appChoiceIcon)
        val selected: RadioButton = view.findViewById(R.id.appChoiceSelected)
    }
}
