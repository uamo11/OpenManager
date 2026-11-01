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
        holder.tvProcessName.text = item.processName
        holder.tvPid.text = "PID: ${item.pid}"
        holder.tvUser.text = item.user

        val memStr = if (item.memoryBytes > 0) {
            Formatter.formatFileSize(context, item.memoryBytes)
        } else {
            "- RAM"
        }
        holder.tvMemory.text = memStr

        // State Badge
        when (item.state) {
            "R" -> {
                holder.tvState.text = "RUNNING"
                holder.tvState.setTextColor(Color.parseColor("#00E676"))
            }
            "D" -> {
                holder.tvState.text = "DISK SLEEP"
                holder.tvState.setTextColor(Color.parseColor("#FF9800"))
            }
            "Z" -> {
                holder.tvState.text = "ZOMBIE"
                holder.tvState.setTextColor(Color.parseColor("#F44336"))
            }
            else -> {
                holder.tvState.text = if (item.isApp) "ACTIVE" else "SLEEP"
                holder.tvState.setTextColor(Color.parseColor("#90CAF9"))
            }
        }

        // Icon loading
        if (item.packageName != null) {
            val cached = NativeAppIconLoader.getCached(item.packageName)
            if (cached != null) {
                holder.ivIcon.setImageDrawable(cached)
            } else {
                holder.ivIcon.setImageResource(R.drawable.file_apk_icon)
                scope.launch(Dispatchers.IO) {
                    val icon = NativeAppIconLoader.loadIcon(context, item.packageName)
                    if (icon != null) {
                        withContext(Dispatchers.Main) {
                            if (holder.bindingAdapterPosition == position) {
                                holder.ivIcon.setImageDrawable(icon)
                            }
                        }
                    }
                }
            }
        } else {
            holder.ivIcon.setImageResource(R.drawable.ic_running_apps)
        }

        holder.itemView.setOnClickListener { onItemClick(item) }

        holder.btnMenu.setOnClickListener { v ->
            val popup = PopupMenu(context, v)
            popup.menu.add(0, 1, 0, "Detalles del proceso")
            popup.menu.add(0, 2, 1, "Finalizar proceso (kill)")
            if (item.packageName != null) {
                popup.menu.add(0, 3, 2, "Forzar detención")
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

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivIcon: ImageView = view.findViewById(R.id.ivProcessIcon)
        val tvLabel: TextView = view.findViewById(R.id.tvProcessLabel)
        val tvProcessName: TextView = view.findViewById(R.id.tvProcessName)
        val tvPid: TextView = view.findViewById(R.id.tvProcessPid)
        val tvMemory: TextView = view.findViewById(R.id.tvProcessMemory)
        val tvUser: TextView = view.findViewById(R.id.tvProcessUser)
        val tvState: TextView = view.findViewById(R.id.tvProcessStateBadge)
        val btnMenu: ImageButton = view.findViewById(R.id.btnProcessMenu)
    }
}
