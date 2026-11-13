/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.appmanager.running

import android.content.Context
import android.graphics.Color
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.appmanager.NativeAppIconLoader

class RunningAppsAdapter(
    private val scope: CoroutineScope,
    private val items: List<RunningAppModel>,
    private val onItemClick: (RunningAppModel) -> Unit,
    private val onKillClick: (RunningAppModel) -> Unit,
    private val onForceStopClick: (RunningAppModel) -> Unit
) : RecyclerView.Adapter<RunningAppsAdapter.ViewHolder>() {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = items[position].pid.toLong()

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_running_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context

        holder.tvLabel.text = item.label
        holder.tvProcessName.text = when (item.executableType) {
            ExecutableType.SHELL_SCRIPT -> "[SH] ${item.cmdline.ifBlank { item.processName }}"
            ExecutableType.ELF_64 -> "[ELF64${if (item.elfArch.isNotEmpty()) " · ${item.elfArch}" else ""}] ${item.processName}"
            ExecutableType.ELF_32 -> "[ELF32${if (item.elfArch.isNotEmpty()) " · ${item.elfArch}" else ""}] ${item.processName}"
            ExecutableType.ELF_GENERIC -> "[ELF] ${item.processName}"
            else -> item.processName
        }
        holder.tvPid.text = "PID: ${item.pid}"
        holder.tvUser.text = item.user

        val memStr = if (item.memoryBytes > 0) {
            Formatter.formatFileSize(context, item.memoryBytes)
        } else {
            "- RAM"
        }
        holder.tvMemory.text = memStr

        holder.tvCpu.text = context.getString(R.string.om_running_apps_cpu_format, item.cpuPercent)
        holder.tvCpu.setTextColor(
            when {
                item.cpuPercent >= 5f -> COLOR_CPU_HIGH
                item.cpuPercent >= 1f -> COLOR_CPU_MED
                item.cpuPercent > 0f -> COLOR_CPU_LOW
                else -> COLOR_CPU_IDLE
            }
        )

        // State Badge
        when (item.state) {
            "R" -> {
                holder.tvState.text = "RUNNING"
                holder.tvState.setTextColor(COLOR_STATE_RUNNING)
            }
            "D" -> {
                holder.tvState.text = "DISK SLEEP"
                holder.tvState.setTextColor(COLOR_STATE_DISK)
            }
            "Z" -> {
                holder.tvState.text = "ZOMBIE"
                holder.tvState.setTextColor(COLOR_STATE_ZOMBIE)
            }
            else -> {
                holder.tvState.text = if (item.isApp) "ACTIVE" else "SLEEP"
                holder.tvState.setTextColor(COLOR_STATE_SLEEP)
            }
        }

        // Icon loading
        holder.iconJob?.cancel()
        holder.iconJob = null
        holder.ivIcon.tag = item.packageName
        if (item.packageName != null) {
            val cached = NativeAppIconLoader.getCached(item.packageName)
            if (cached != null) {
                holder.ivIcon.setImageDrawable(cached)
            } else {
                holder.ivIcon.setImageResource(R.drawable.file_apk_icon)
                holder.iconJob = scope.launch(Dispatchers.IO) {
                    val icon = NativeAppIconLoader.loadIcon(context, item.packageName)
                    if (icon != null) {
                        withContext(Dispatchers.Main) {
                            if (holder.ivIcon.tag == item.packageName) {
                                holder.ivIcon.setImageDrawable(icon)
                            }
                        }
                    }
                }
            }
        } else {
            holder.ivIcon.setImageResource(iconResForProcess(item))
        }

        holder.itemView.setOnClickListener { onItemClick(item) }

        holder.btnMenu.setOnClickListener { v ->
            val popup = PopupMenu(context, v)
            popup.menu.add(0, 1, 0, context.getString(R.string.om_proc_menu_details))
            popup.menu.add(0, 2, 1, context.getString(R.string.om_proc_menu_kill))
            if (item.packageName != null) {
                popup.menu.add(0, 3, 2, context.getString(R.string.om_proc_menu_force_stop))
            }
            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    1 -> onItemClick(item)
                    2 -> onKillClick(item)
                    3 -> onForceStopClick(item)
                }
                true
            }
            popup.show()
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.iconJob?.cancel()
        holder.iconJob = null
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        var iconJob: Job? = null
        val ivIcon: ImageView = view.findViewById(R.id.ivProcessIcon)
        val tvLabel: TextView = view.findViewById(R.id.tvProcessLabel)
        val tvProcessName: TextView = view.findViewById(R.id.tvProcessName)
        val tvPid: TextView = view.findViewById(R.id.tvProcessPid)
        val tvMemory: TextView = view.findViewById(R.id.tvProcessMemory)
        val tvCpu: TextView = view.findViewById(R.id.tvProcessCpu)
        val tvUser: TextView = view.findViewById(R.id.tvProcessUser)
        val tvState: TextView = view.findViewById(R.id.tvProcessStateBadge)
        val btnMenu: ImageButton = view.findViewById(R.id.btnProcessMenu)
    }

    companion object {
        private val COLOR_CPU_HIGH = Color.parseColor("#FF7043")
        private val COLOR_CPU_MED = Color.parseColor("#FFB74D")
        private val COLOR_CPU_LOW = Color.parseColor("#80CBC4")
        private val COLOR_CPU_IDLE = Color.parseColor("#757575")
        private val COLOR_STATE_RUNNING = Color.parseColor("#00E676")
        private val COLOR_STATE_DISK = Color.parseColor("#FF9800")
        private val COLOR_STATE_ZOMBIE = Color.parseColor("#F44336")
        private val COLOR_STATE_SLEEP = Color.parseColor("#90CAF9")

        fun iconResForProcess(item: RunningAppModel): Int = when (item.executableType) {
            ExecutableType.SHELL_SCRIPT -> R.drawable.ic_exec_shell_script
            ExecutableType.ELF_64 -> R.drawable.ic_exec_elf_64
            ExecutableType.ELF_32 -> R.drawable.ic_exec_elf_32
            ExecutableType.ELF_GENERIC -> R.drawable.ic_exec_elf_generic
            else -> R.drawable.ic_running_apps_white_24dp
        }
    }
}
