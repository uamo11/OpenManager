/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal.tracer

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.DialogScriptAuditBinding
import me.zhanghai.android.files.provider.root.DeadManProtectionManager
import me.zhanghai.android.files.filelist.ScriptRunnerHelper
import me.zhanghai.android.files.terminal.ScriptExecutionManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScriptAuditDialogFragment : AppCompatDialogFragment() {

    private var scriptPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scriptPath = arguments?.getString(ARG_SCRIPT_PATH)
            ?: ScriptExecutionManager.activeScriptPath
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogScriptAuditBinding.inflate(LayoutInflater.from(requireContext()))
        val context = requireContext()
        val path = scriptPath ?: ScriptExecutionManager.activeScriptPath ?: ""
        val scriptName = if (path.isNotEmpty()) File(path).name else ScriptExecutionManager.activeScriptName.ifEmpty { "Script" }

        // 1. Script Identity & Status
        binding.tvAuditTitle.text = getString(R.string.script_audit_title) + " - " + scriptName
        binding.tvAuditScriptPath.text = path.ifEmpty { getString(R.string.om_audit_interactive_session) }

        val isRunning = ScriptExecutionManager.isRunning()
        if (isRunning) {
            binding.tvAuditStatusBadge.text = getString(R.string.script_audit_status_running).uppercase(Locale.ROOT)
            binding.tvAuditStatusBadge.setTextColor(Color.parseColor("#4CAF50"))
            binding.tvAuditStatusBadge.setBackgroundColor(Color.parseColor("#1B5E20"))
        } else {
            binding.tvAuditStatusBadge.text = getString(R.string.script_audit_status_finished).uppercase(Locale.ROOT)
            binding.tvAuditStatusBadge.setTextColor(Color.parseColor("#B0BEC5"))
            binding.tvAuditStatusBadge.setBackgroundColor(Color.parseColor("#37474F"))
        }

        // Static Code Audit (when path points to a readable script file)
        var staticAuditFindings = 0
        var staticAuditSummary = ""
        val staticAuditReport: ScriptRunnerHelper.ScriptAuditReport? = if (path.isNotEmpty()) {
            val scriptFile = File(path)
            if (scriptFile.isFile && scriptFile.canRead()) {
                val isElf = ScriptRunnerHelper.isElfBinary(java8.nio.file.Paths.get(path))
                val report: ScriptRunnerHelper.ScriptAuditReport
                val scriptContent: String
                if (isElf) {
                    val elfReport = me.zhanghai.android.files.filelist.ElfInspector.inspect(java8.nio.file.Paths.get(path))
                    report = elfReport.toScriptAuditReport()
                    scriptContent = buildString {
                        appendLine(getString(R.string.om_audit_elf_header))
                        appendLine(getString(R.string.om_audit_elf_arch_format, elfReport.machineArch))
                        appendLine(getString(R.string.om_audit_elf_bitness_format, if (elfReport.is64Bit) "64-bit" else "32-bit"))
                        appendLine("PIE (Position Independent Executable): ${getString(if (elfReport.isPie) R.string.om_audit_elf_pie_yes else R.string.om_audit_elf_pie_no)}")
                        appendLine(getString(R.string.om_audit_elf_linker_format, elfReport.interpreter ?: getString(if (elfReport.isDynamic) R.string.om_audit_elf_dynamic else R.string.om_audit_elf_static)))
                        appendLine(getString(R.string.om_audit_elf_abi_format, getString(if (elfReport.isAbiCompatible) R.string.om_audit_elf_compatible else R.string.om_audit_elf_incompatible)))
                        appendLine()
                        appendLine(getString(R.string.om_audit_elf_syscalls_header_format, elfReport.totalFindings))
                        for (f in elfReport.findings) {
                            appendLine("- [${f.category.displayName}] ${f.symbol}: ${f.description} ${getString(R.string.om_audit_elf_risk_suffix_format, f.risk.levelName)}")
                        }
                    }
                } else {
                    scriptContent = runCatching { scriptFile.readText() }.getOrDefault("")
                    report = ScriptRunnerHelper.auditScriptText(scriptContent)
                }

                if (scriptContent.isNotEmpty() || isElf) {
                    staticAuditFindings = report.totalFindings
                    binding.cardStaticAudit.visibility = View.VISIBLE
                    if (report.totalFindings > 0) {
                        binding.tvStaticAuditBadge.text = getString(R.string.om_audit_risks_badge_format, report.totalFindings)
                        binding.tvStaticAuditBadge.setTextColor(Color.parseColor("#EF5350"))
                        binding.tvStaticAuditBadge.setBackgroundColor(Color.parseColor("#371B1B"))
                        staticAuditSummary = getString(
                            R.string.om_audit_summary_format,
                            report.totalFindings,
                            report.deletions.size,
                            report.rootCalls.size,
                            report.networkCalls.size,
                            report.systemPaths.size
                        )
                        binding.tvStaticAuditDesc.text = getString(
                            R.string.script_audit_static_findings_desc,
                            report.totalFindings,
                            report.deletions.size,
                            report.rootCalls.size,
                            report.networkCalls.size,
                            report.systemPaths.size
                        )

                        binding.layoutStaticAuditCategories.removeAllViews()
                        val addCat = { labelRes: Int, items: List<String>, colorHex: String, icon: String ->
                            if (items.isNotEmpty()) {
                                val tvCat = TextView(context).apply {
                                    text = "$icon ${getString(labelRes)} (${items.size}):"
                                    textSize = 11f
                                    setTypeface(null, Typeface.BOLD)
                                    setTextColor(Color.parseColor(colorHex))
                                    setPadding(0, 4, 0, 1)
                                }
                                binding.layoutStaticAuditCategories.addView(tvCat)
                                for (item in items.take(4)) {
                                    val tvItem = TextView(context).apply {
                                        text = "   $item"
                                        textSize = 10f
                                        setTypeface(Typeface.MONOSPACE)
                                        setTextColor(Color.parseColor("#CFD8DC"))
                                    }
                                    binding.layoutStaticAuditCategories.addView(tvItem)
                                }
                                if (items.size > 4) {
                                    val tvMore = TextView(context).apply {
                                        text = getString(R.string.om_audit_more_in_code_format, items.size - 4)
                                        textSize = 10f
                                        setTextColor(Color.parseColor("#90A4AE"))
                                    }
                                    binding.layoutStaticAuditCategories.addView(tvMore)
                                }
                            }
                        }

                        addCat(R.string.script_audit_static_category_deletions, report.deletions, "#EF5350", "🗑️")
                        addCat(R.string.script_audit_static_category_root, report.rootCalls, "#FFD54F", "⚡")
                        addCat(R.string.script_audit_static_category_network, report.networkCalls, "#80D8FF", "🌐")
                        addCat(R.string.script_audit_static_category_system, report.systemPaths, "#B388FF", "📁")
                    } else {
                        binding.tvStaticAuditBadge.text = getString(R.string.om_audit_clean_badge)
                        binding.tvStaticAuditBadge.setTextColor(Color.parseColor("#81C784"))
                        binding.tvStaticAuditBadge.setBackgroundColor(Color.parseColor("#1B5E20"))
                        binding.tvStaticAuditDesc.text = if (isElf) getString(R.string.om_audit_elf_clean_desc) else getString(R.string.script_audit_static_clean_desc)
                        binding.layoutStaticAuditCategories.removeAllViews()
                        staticAuditSummary = getString(R.string.om_audit_clean_summary)
                    }

                    binding.btnViewAuditCode.setOnClickListener {
                        ScriptRunnerHelper.showFullCodeDialog(requireActivity(), scriptFile.name, scriptContent, report)
                    }
                    report
                } else {
                    binding.cardStaticAudit.visibility = View.GONE
                    null
                }
            } else {
                binding.cardStaticAudit.visibility = View.GONE
                null
            }
        } else {
            binding.cardStaticAudit.visibility = View.GONE
            null
        }

        // 2. Telemetry Switch (On/Off)
        val tracerEnabled = ScriptTracerPreferences.isTracerEnabled(context, path)
        binding.switchEnableTracer.isChecked = tracerEnabled
        binding.switchEnableTracer.setOnCheckedChangeListener { _, isChecked ->
            ScriptTracerPreferences.setTracerEnabled(context, path, isChecked)
            val msg = if (isChecked) "Rastreador activado para este script" else "Rastreador desactivado para este script (optimizado)"
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }

        // 3. Deletion Policy Radio Group
        val currentPolicy = ScriptTracerPreferences.getDeletionPolicy(context, path)
        when (currentPolicy) {
            ScriptTracerPreferences.DeletionPolicy.ASK -> binding.rbPolicyAsk.isChecked = true
            ScriptTracerPreferences.DeletionPolicy.BLOCK_ALL -> binding.rbPolicyBlock.isChecked = true
            ScriptTracerPreferences.DeletionPolicy.ALLOW_ALL -> binding.rbPolicyAllow.isChecked = true
        }

        binding.rgDeletionPolicy.setOnCheckedChangeListener { _, checkedId ->
            val newPolicy = when (checkedId) {
                R.id.rbPolicyBlock -> ScriptTracerPreferences.DeletionPolicy.BLOCK_ALL
                R.id.rbPolicyAllow -> ScriptTracerPreferences.DeletionPolicy.ALLOW_ALL
                else -> ScriptTracerPreferences.DeletionPolicy.ASK
            }
            ScriptTracerPreferences.setDeletionPolicy(context, path, newPolicy)
        }

        // 4. Update Stats & Event Ledger
        fun refreshAuditData() {
            val stats = BinaryTracerEngine.getStats()
            binding.tvStatDeletionsVal.text = stats.totalDeletions.toString()
            binding.tvStatDeletionsSub.text = getString(R.string.om_audit_del_sub_format, stats.blockedDeletions, stats.allowedDeletions)

            binding.tvStatSyscallsVal.text = stats.totalSyscalls.toString()
            binding.tvStatSyscallsSub.text = getString(if (stats.totalSyscalls > 0) R.string.om_audit_syscalls_monitored else R.string.om_audit_syscalls_none)

            binding.tvStatKernelVal.text = stats.totalKernelReads.toString()
            binding.tvStatKernelSub.text = getString(R.string.om_audit_proc_queries)

            val deadManActive = DeadManProtectionManager.isDeadManProtectionActive(context)
            binding.tvStatDeadManVal.text = getString(if (deadManActive) R.string.om_audit_status_active else R.string.om_audit_status_disabled)
            binding.tvStatDeadManVal.setTextColor(if (deadManActive) Color.parseColor("#81C784") else Color.parseColor("#E57373"))

            // Update Live Syscall Transparency Card
            val isProcRunning = ScriptExecutionManager.isRunning()
            if (isProcRunning) {
                binding.tvLiveSyscallStatusBadge.text = getString(R.string.om_audit_badge_monitoring)
                binding.tvLiveSyscallStatusBadge.setTextColor(Color.parseColor("#4CAF50"))
                binding.tvLiveSyscallStatusBadge.setBackgroundColor(Color.parseColor("#1B5E20"))
            } else {
                binding.tvLiveSyscallStatusBadge.text = getString(if (stats.totalSyscalls > 0) R.string.om_audit_badge_finished else R.string.om_audit_badge_waiting)
                binding.tvLiveSyscallStatusBadge.setTextColor(Color.parseColor("#B0BEC5"))
                binding.tvLiveSyscallStatusBadge.setBackgroundColor(Color.parseColor("#37474F"))
            }

            val active = stats.activeSyscall
            if (active != null) {
                binding.tvActiveSyscallText.text = getString(R.string.om_audit_active_syscall_format, active.name, active.number, active.pid, active.category.displayName)
                binding.tvActiveSyscallText.setTextColor(Color.parseColor(active.risk.colorHex))
            } else {
                binding.tvActiveSyscallText.text = getString(if (isProcRunning) R.string.om_audit_active_syscall_idle else R.string.om_audit_active_syscall_none)
                binding.tvActiveSyscallText.setTextColor(Color.parseColor("#CFD8DC"))
            }

            binding.tvIoReadCalls.text = getString(R.string.om_audit_io_read_format, stats.ioReadSyscalls)
            binding.tvIoWriteCalls.text = getString(R.string.om_audit_io_write_format, stats.ioWriteSyscalls)

            val counters = BinaryTracerEngine.getSyscallCounters()
            binding.layoutSyscallBreakdown.removeAllViews()
            if (counters.isNotEmpty()) {
                val sortedSyscalls = counters.toList().sortedByDescending { it.second }.take(6)
                for ((sysName, count) in sortedSyscalls) {
                    val info = SyscallResolver.resolveByName(sysName)
                    val tvRow = TextView(context).apply {
                        text = getString(R.string.om_audit_syscall_row_format, info.category.icon, info.name, count, info.risk.levelName)
                        textSize = 10f
                        setTypeface(Typeface.MONOSPACE)
                        setTextColor(Color.parseColor(info.risk.colorHex))
                        setPadding(0, 2, 0, 2)
                    }
                    binding.layoutSyscallBreakdown.addView(tvRow)
                }
            } else {
                val tvNone = TextView(context).apply {
                    text = getString(R.string.om_audit_no_syscalls_yet)
                    textSize = 10f
                    setTextColor(Color.parseColor("#78909C"))
                }
                binding.layoutSyscallBreakdown.addView(tvNone)
            }

            // Populate Event Ledger
            val events = BinaryTracerEngine.getEvents()
            binding.layoutEventsContainer.removeAllViews()

            if (events.isEmpty()) {
                binding.tvEmptyEvents.visibility = View.VISIBLE
                binding.layoutEventsContainer.visibility = View.GONE
            } else {
                binding.tvEmptyEvents.visibility = View.GONE
                binding.layoutEventsContainer.visibility = View.VISIBLE

                val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                for (event in events.reversed()) {
                    val card = MaterialCardView(context).apply {
                        radius = 8f
                        setCardBackgroundColor(Color.parseColor("#262626"))
                        strokeWidth = 1
                        setStrokeColor(Color.parseColor("#383838"))
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(0, 0, 0, 8)
                        }
                    }

                    val rowContent = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(12, 10, 12, 10)
                    }

                    // Top Row: Type & Timestamp & Badge
                    val topRow = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }

                    val (typeIcon, typeTitleRes, typeColor) = when (event.type) {
                        TraceEventType.DELETION_ATTEMPT -> Triple("🗑️", R.string.om_audit_ev_deletion, "#EF5350")
                        TraceEventType.SYSCALL_BURST -> Triple("⚡", R.string.om_audit_ev_syscall, "#64B5F6")
                        TraceEventType.KERNEL_PROBE -> Triple("🔍", R.string.om_audit_ev_kernel, "#FFB74D")
                        TraceEventType.SECURITY_AUDIT -> Triple("🛡️", R.string.om_audit_ev_security, "#81C784")
                        TraceEventType.PROCESS_SPAWN -> Triple("⚙️", R.string.om_audit_ev_process, "#BA68C8")
                    }

                    val tvType = TextView(context).apply {
                        text = "$typeIcon ${getString(typeTitleRes)}"
                        textSize = 12f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(Color.parseColor(typeColor))
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }

                    val tvTime = TextView(context).apply {
                        text = dateFormat.format(Date(event.timestamp))
                        textSize = 10f
                        setTextColor(Color.parseColor("#90A4AE"))
                    }

                    topRow.addView(tvType)
                    topRow.addView(tvTime)
                    rowContent.addView(topRow)

                    // Target / Detail text
                    val tvTarget = TextView(context).apply {
                        text = event.target
                        textSize = 13f
                        setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                        setTextColor(Color.WHITE)
                        setPadding(0, 4, 0, 2)
                    }
                    rowContent.addView(tvTarget)

                    if (event.details.isNotEmpty() && event.details != event.target) {
                        val tvDetails = TextView(context).apply {
                            text = event.details
                            textSize = 11f
                            setTextColor(Color.parseColor("#B0BEC5"))
                            setTypeface(Typeface.MONOSPACE)
                        }
                        rowContent.addView(tvDetails)
                    }

                    // Decision Badge for deletions
                    val dec = event.decision
                    if (event.type == TraceEventType.DELETION_ATTEMPT && dec != null) {
                        val (badgeRes, badgeBg, badgeFg) = when (dec) {
                            DeletionDecision.BLOCKED -> Triple(R.string.om_audit_dec_blocked, "#B71C1C", "#FFCDD2")
                            DeletionDecision.ALLOWED -> Triple(R.string.om_audit_dec_allowed, "#1B5E20", "#C8E6C9")
                            DeletionDecision.PENDING -> Triple(R.string.om_audit_dec_pending, "#E65100", "#FFE0B2")
                        }

                        val tvBadge = TextView(context).apply {
                            text = getString(badgeRes)
                            textSize = 10f
                            setTypeface(null, Typeface.BOLD)
                            setTextColor(Color.parseColor(badgeFg))
                            setBackgroundColor(Color.parseColor(badgeBg))
                            setPadding(8, 2, 8, 2)
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                topMargin = 6
                            }
                        }
                        rowContent.addView(tvBadge)
                    }

                    card.addView(rowContent)
                    binding.layoutEventsContainer.addView(card)
                }
            }
        }

        refreshAuditData()

        // 5. Action Buttons (Copy & Clear)
        binding.btnCopyAuditLog.setOnClickListener {
            val stats = BinaryTracerEngine.getStats()
            val events = BinaryTracerEngine.getEvents()
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

            val sb = StringBuilder()
            sb.appendLine("=== OpenManager Script Audit Report ===")
            sb.appendLine("Script: $scriptName")
            sb.appendLine("Path: $path")
            sb.appendLine("Date: ${dateFormat.format(Date())}")
            sb.appendLine("Status: ${if (isRunning) "Running" else "Finished"}")
            if (staticAuditReport != null) {
                sb.appendLine("--- Static Code Analysis ---")
                sb.appendLine("Summary: $staticAuditSummary")
                if (staticAuditReport.deletions.isNotEmpty()) {
                    sb.appendLine("Deletions:")
                    staticAuditReport.deletions.forEach { sb.appendLine("  $it") }
                }
                if (staticAuditReport.rootCalls.isNotEmpty()) {
                    sb.appendLine("Root / Superuser:")
                    staticAuditReport.rootCalls.forEach { sb.appendLine("  $it") }
                }
                if (staticAuditReport.networkCalls.isNotEmpty()) {
                    sb.appendLine("Network:")
                    staticAuditReport.networkCalls.forEach { sb.appendLine("  $it") }
                }
                if (staticAuditReport.systemPaths.isNotEmpty()) {
                    sb.appendLine("System Paths:")
                    staticAuditReport.systemPaths.forEach { sb.appendLine("  $it") }
                }
            }
            sb.appendLine("--- Statistics ---")
            sb.appendLine("Deletions: ${stats.totalDeletions} (Blocked: ${stats.blockedDeletions}, Allowed: ${stats.allowedDeletions})")
            sb.appendLine("Syscalls: ${stats.totalSyscalls}")
            sb.appendLine("Kernel Reads: ${stats.totalKernelReads}")
            sb.appendLine("IO Read Syscalls (syscr): ${stats.ioReadSyscalls}")
            sb.appendLine("IO Write Syscalls (syscw): ${stats.ioWriteSyscalls}")
            sb.appendLine("Dead Man Protection: ${if (DeadManProtectionManager.isDeadManProtectionActive(context)) "ACTIVE" else "DISABLED"}")
            val activeSys = stats.activeSyscall
            if (activeSys != null) {
                sb.appendLine("Active Syscall: ${activeSys.name} (#${activeSys.number}) - PID ${activeSys.pid} [${activeSys.category.displayName}]")
            }
            val liveCounters = BinaryTracerEngine.getSyscallCounters()
            if (liveCounters.isNotEmpty()) {
                sb.appendLine("--- Syscall Transparency Table ---")
                for ((k, v) in liveCounters) {
                    val res = SyscallResolver.resolveByName(k)
                    sb.appendLine("  ${res.name}: $v calls [${res.category.displayName} - ${res.risk.levelName}]")
                }
            }
            sb.appendLine("--- Event Ledger (${events.size} events) ---")
            for (e in events) {
                sb.appendLine("[${dateFormat.format(Date(e.timestamp))}] [${e.type.name}] ${e.target} - ${e.details} -> Decision: ${e.decision ?: "N/A"}")
            }

            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("Script Audit Log", sb.toString()))
            Toast.makeText(context, R.string.script_audit_copied, Toast.LENGTH_SHORT).show()
        }

        binding.btnClearAuditLog.setOnClickListener {
            BinaryTracerEngine.clear()
            refreshAuditData()
            Toast.makeText(context, R.string.script_audit_cleared, Toast.LENGTH_SHORT).show()
        }

        return MaterialAlertDialogBuilder(context)
            .setView(binding.root)
            .setPositiveButton(R.string.close, null)
            .create()
    }

    companion object {
        private const val ARG_SCRIPT_PATH = "script_path"
        private const val TAG = "ScriptAuditDialogFragment"

        fun newInstance(scriptPath: String?): ScriptAuditDialogFragment {
            return ScriptAuditDialogFragment().apply {
                arguments = bundleOf(ARG_SCRIPT_PATH to scriptPath)
            }
        }

        fun show(fragment: Fragment, scriptPath: String?) {
            newInstance(scriptPath).show(fragment.parentFragmentManager, TAG)
        }

        fun show(activity: FragmentActivity, scriptPath: String?) {
            newInstance(scriptPath).show(activity.supportFragmentManager, TAG)
        }
    }
}
