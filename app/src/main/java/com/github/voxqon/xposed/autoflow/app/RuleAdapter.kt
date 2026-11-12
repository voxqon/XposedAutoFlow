package com.github.voxqon.xposed.autoflow.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.voxqon.xposed.autoflow.R
import com.github.voxqon.xposed.autoflow.model.Rule
import com.github.voxqon.xposed.autoflow.model.ConditionType
import com.github.voxqon.xposed.autoflow.model.TimeRangeSpec
import com.github.voxqon.xposed.autoflow.model.TriggerSchedule
import com.google.android.material.switchmaterial.SwitchMaterial

class RuleAdapter(
    private val onToggle: (Rule, Boolean) -> Unit,
    private val onClick: (Rule) -> Unit,
    private val onLongClick: (Rule) -> Unit,
) : ListAdapter<Rule, RuleAdapter.VH>(object : DiffUtil.ItemCallback<Rule>() {
    override fun areItemsTheSame(oldItem: Rule, newItem: Rule): Boolean = oldItem.id == newItem.id
    override fun areContentsTheSame(oldItem: Rule, newItem: Rule): Boolean = oldItem == newItem
}) {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.textName)
        val textConditionContent: TextView? = v.findViewById(R.id.textConditionContent)
        val textActionContent: TextView? = v.findViewById(R.id.textActionContent)
        val textNextExecution: TextView? = v.findViewById(R.id.textNextExecution)
        val textExitContent: TextView? = v.findViewById(R.id.textExitContent)
        val layoutExitRow: View? = v.findViewById(R.id.layoutExitRow)
        val sw: SwitchMaterial = v.findViewById(R.id.switchEnabled)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_rule, parent, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val r = getItem(pos)
        h.name.text = r.name
        h.name.alpha = if (r.enabled) 1.0f else 0.45f

        val conditionText = r.triggerDescription()
        val actionText = r.actions.joinToString("、") { it.description() }
        val exitText = r.exitConditions.joinToString(" 或 ") { it.description() }

        h.textConditionContent?.text = conditionText.ifBlank { "（无条件）" }
        h.textActionContent?.text = actionText.ifBlank { "（无动作）" }
        h.textNextExecution?.text = if (r.enabled) {
            val timerMinutes = r.conditions.filter { it.type == ConditionType.TIMER }
                .mapNotNull { it.param?.let(TimeRangeSpec::parseMinute) }
            TriggerSchedule.nextAllowedText(
                r.scheduleMode,
                r.allowedDaysMask,
                System.currentTimeMillis(),
                timerMinutes = timerMinutes,
            )
        } else {
            "下次可执行：规则已停用"
        }

        if (exitText.isNotBlank()) {
            h.layoutExitRow?.visibility = View.VISIBLE
            h.textExitContent?.text = exitText
        } else {
            h.layoutExitRow?.visibility = View.GONE
        }

        h.sw.setOnCheckedChangeListener(null)
        h.sw.isChecked = r.enabled
        h.sw.setOnCheckedChangeListener { _, checked ->
            h.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
                ?.let { onToggle(getItem(it), checked) }
        }
        h.itemView.setOnClickListener {
            h.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { onClick(getItem(it)) }
        }
        h.itemView.setOnLongClickListener {
            h.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { onLongClick(getItem(it)) }
            true
        }
    }

}
