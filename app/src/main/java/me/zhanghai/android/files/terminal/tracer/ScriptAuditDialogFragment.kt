/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal.tracer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.filelist.ElfInspector
import me.zhanghai.android.files.filelist.ScriptRunnerHelper
import me.zhanghai.android.files.provider.root.DeadManProtectionManager
import me.zhanghai.android.files.terminal.ScriptExecutionManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Audit & protection sheet: which protections are on, what happens when the script deletes
 * something, the real decisions taken (live), and the static analysis on demand.
 */
class ScriptAuditDialogFragment : BottomSheetDialogFragment() {

    private var scriptPath: String = ""
    private lateinit var root: View
    private val refreshListener: () -> Unit = { if (isAdded) refreshActivity() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scriptPath = arguments?.getString(ARG_SCRIPT_PATH) ?: ScriptExecutionManager.activeScriptPath ?: ""
    }

    override fun onCreateDialog(savedInstanceState: Bundle?) =
        (super.onCreateDialog(savedInstanceState) as BottomSheetDialog).apply {
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        root = inflater.inflate(R.layout.script_audit_sheet, container, false)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val context = requireContext()
        // Only a real script file has a name; commands and interactive shells are "sessions".
        val scriptName = scriptPath.takeIf { it.isNotEmpty() && File(it).isFile }?.let { File(it).name }
            ?: getString(R.string.om_audit_interactive_session)
        view.findViewById<TextView>(R.id.auditScriptName).text = scriptName

        val running = ScriptExecutionManager.isRunning()
        view.findViewById<TextView>(R.id.auditStatusChip).apply {
            text = getString(if (running) R.string.om_audit2_running else R.string.om_audit2_finished)
            setChip(this, if (running) COLOR_OK else COLOR_NEUTRAL)
        }

        bindProtections(view, context)
        bindPolicy(view, context)
        setupCollapsible(view, R.id.staticHeader, R.id.staticBody, R.id.staticChevron)
        setupCollapsible(view, R.id.syscallHeader, R.id.syscallBody, R.id.syscallChevron)
        loadStaticAnalysis(view)

        view.findViewById<MaterialButton>(R.id.auditCopy).setOnClickListener { copyReport(context, scriptName) }
        view.findViewById<MaterialButton>(R.id.auditClear).setOnClickListener {
            BinaryTracerEngine.clear()
            refreshActivity()
            Toast.makeText(context, R.string.script_audit_cleared, Toast.LENGTH_SHORT).show()
        }
        refreshActivity()
    }

    override fun onStart() {
        super.onStart()
        BinaryTracerEngine.addListener(refreshListener)
        refreshActivity()
    }

    override fun onStop() {
        BinaryTracerEngine.removeListener(refreshListener)
        super.onStop()
    }

    private fun bindProtections(view: View, context: Context) {
        val deadMan = DeadManProtectionManager.isDeadManProtectionActive(context)
        val dfroot = DeadManProtectionManager.isDfrootProtectionActive(context)
        fun state(textView: TextView, on: Boolean) {
            textView.text = getString(if (on) R.string.om_audit2_on else R.string.om_audit2_off)
            textView.setTextColor(if (on) COLOR_OK else COLOR_BLOCK)
        }
        state(view.findViewById(R.id.tileDeadManState), deadMan)
        state(view.findViewById(R.id.tileDfrootState), dfroot)
        view.findViewById<TextView>(R.id.auditProtectionHint).text = getString(
            when {
                deadMan && dfroot -> R.string.om_audit2_hint_both
                deadMan -> R.string.om_audit2_hint_dead_man
                dfroot -> R.string.om_audit2_hint_dfroot
                else -> R.string.om_audit2_hint_none
            }
        )
        view.findViewById<View>(R.id.tileDeadMan).setOnClickListener {
            explain(R.string.om_audit2_dead_man, R.string.om_audit2_dead_man_explain)
        }
        view.findViewById<View>(R.id.tileDfroot).setOnClickListener {
            explain(R.string.om_audit2_dfroot, R.string.om_audit2_dfroot_explain)
        }
    }

    private fun explain(titleRes: Int, messageRes: Int) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun bindPolicy(view: View, context: Context) {
        val switch = view.findViewById<SwitchMaterial>(R.id.auditGuardSwitch)
        val toggle = view.findViewById<MaterialButtonToggleGroup>(R.id.auditPolicyToggle)
        val label = view.findViewById<TextView>(R.id.auditPolicyLabel)
        val perScript = scriptPath.isNotEmpty() && File(scriptPath).isFile
        switch.isChecked = ScriptTracerPreferences.isTracerEnabled(context, scriptPath)
        // Nothing picked: only the automatic protections apply (mass deletions, Dead Man, DFRoot).
        if (ScriptTracerPreferences.hasDeletionPolicy(context, scriptPath)) {
            toggle.check(
                when (ScriptTracerPreferences.getDeletionPolicy(context, scriptPath)) {
                    ScriptTracerPreferences.DeletionPolicy.ASK -> R.id.policyAsk
                    ScriptTracerPreferences.DeletionPolicy.BLOCK_ALL -> R.id.policyBlock
                    ScriptTracerPreferences.DeletionPolicy.ALLOW_ALL -> R.id.policyAllow
                }
            )
        } else {
            toggle.isSelectionRequired = false
            toggle.clearChecked()
        }
        fun syncEnabled() {
            val enabled = perScript && switch.isChecked
            toggle.isEnabled = enabled
            for (i in 0 until toggle.childCount) toggle.getChildAt(i).isEnabled = enabled
            label.alpha = if (enabled) 1f else 0.5f
        }
        // The per-script choice needs a script file; explain instead of showing dead controls.
        switch.isEnabled = perScript
        if (!perScript) {
            label.setText(R.string.om_audit2_policy_interactive)
            switch.visibility = View.GONE
            toggle.visibility = View.GONE
        }
        syncEnabled()
        switch.setOnCheckedChangeListener { _, checked ->
            ScriptTracerPreferences.setTracerEnabled(context, scriptPath, checked)
            syncEnabled()
        }
        toggle.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            ScriptTracerPreferences.setDeletionPolicy(
                context, scriptPath,
                when (id) {
                    R.id.policyBlock -> ScriptTracerPreferences.DeletionPolicy.BLOCK_ALL
                    R.id.policyAllow -> ScriptTracerPreferences.DeletionPolicy.ALLOW_ALL
                    else -> ScriptTracerPreferences.DeletionPolicy.ASK
                }
            )
        }
    }

    private fun refreshActivity() {
        if (!::root.isInitialized) return
        val context = context ?: return
        val stats = BinaryTracerEngine.getStats()
        root.findViewById<TextView>(R.id.auditCounters).text = getString(
            R.string.om_audit2_counters, stats.blockedDeletions, stats.allowedDeletions, BinaryTracerEngine.totalAsked.get()
        )
        val events = BinaryTracerEngine.getLedger().asReversed()
        val container = root.findViewById<LinearLayout>(R.id.auditEvents)
        root.findViewById<View>(R.id.auditEmpty).visibility = if (events.isEmpty()) View.VISIBLE else View.GONE
        container.removeAllViews()
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        for (event in events.take(MAX_ROWS)) {
            container.addView(eventRow(context, event, time))
        }

        val counters = BinaryTracerEngine.getSyscallCounters().entries.sortedByDescending { it.value }
        root.findViewById<TextView>(R.id.syscallSummary).text = if (counters.isEmpty()) {
            getString(R.string.om_audit2_syscalls_none)
        } else {
            resources.getQuantityString(
                R.plurals.om_audit2_syscalls_summary, stats.totalSyscalls.toInt().coerceAtLeast(0),
                stats.totalSyscalls, stats.ioReadSyscalls, stats.ioWriteSyscalls
            )
        }
        root.findViewById<TextView>(R.id.syscallBody).text = counters.take(10).joinToString("\n") { (name, count) ->
            val info = SyscallResolver.resolveByName(name)
            String.format(Locale.ROOT, "%-18s %7d  %s", info.name, count, info.risk.levelName)
        }
    }

    private fun eventRow(context: Context, event: TraceEvent, time: SimpleDateFormat): View {
        val density = resources.displayMetrics.density
        val blocked = event.decision == DeletionDecision.BLOCKED
        val color = if (blocked) COLOR_BLOCK else COLOR_OK
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.remote_section_bar_background)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * density).toInt() }
        }
        row.addView(View(context).apply {
            background = GradientDrawable().apply {
                setColor(color)
                cornerRadius = 2 * density
            }
            layoutParams = LinearLayout.LayoutParams((4 * density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                setMargins(0, (10 * density).toInt(), 0, (10 * density).toInt())
            }
        })
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (12 * density).toInt()
            setPadding(pad, (10 * density).toInt(), pad, (10 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        body.addView(TextView(context).apply {
            text = getString(
                if (blocked) R.string.om_audit2_event_blocked else R.string.om_audit2_event_allowed,
                time.format(Date(event.timestamp))
            )
            setTextColor(color)
            setTypeface(typeface, Typeface.BOLD)
            textSize = 12f
        })
        body.addView(TextView(context).apply {
            text = event.details
            typeface = Typeface.MONOSPACE
            textSize = 13f
            maxLines = 3
        })
        if (event.target.isNotEmpty()) {
            body.addView(TextView(context).apply {
                text = event.target.lines().take(4).joinToString("\n") { "→ $it" }
                typeface = Typeface.MONOSPACE
                textSize = 11.5f
                alpha = 0.8f
            })
        }
        if (event.reason.isNotEmpty()) {
            body.addView(TextView(context).apply {
                text = event.reason
                textSize = 11.5f
                alpha = 0.7f
            })
        }
        row.addView(body)
        return row
    }

    private fun loadStaticAnalysis(view: View) {
        val summary = view.findViewById<TextView>(R.id.staticSummary)
        val header = view.findViewById<View>(R.id.staticHeader)
        val file = scriptPath.takeIf { it.isNotEmpty() }?.let { File(it) }
        if (file == null || !file.isFile || !file.canRead()) {
            summary.setText(R.string.om_audit2_static_none)
            header.isEnabled = false
            return
        }
        summary.setText(R.string.om_audit2_static_loading)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val path = java8.nio.file.Paths.get(file.absolutePath)
                    if (ScriptRunnerHelper.isElfBinary(path)) {
                        ElfInspector.inspect(path).toScriptAuditReport() to ""
                    } else {
                        val text = file.readText()
                        ScriptRunnerHelper.auditScriptText(text) to text
                    }
                }.getOrNull()
            }
            if (!isAdded) return@launch
            if (result == null) {
                summary.setText(R.string.om_audit2_static_none)
                return@launch
            }
            val (report, content) = result
            summary.text = if (report.totalFindings == 0) {
                getString(R.string.om_audit_clean_summary)
            } else {
                getString(
                    R.string.om_audit_summary_format, report.totalFindings, report.deletions.size,
                    report.rootCalls.size, report.networkCalls.size, report.systemPaths.size
                )
            }
            val findings = view.findViewById<LinearLayout>(R.id.staticFindings)
            findings.removeAllViews()
            fun category(labelRes: Int, items: List<String>) {
                if (items.isEmpty()) return
                findings.addView(TextView(requireContext()).apply {
                    text = "${getString(labelRes)} (${items.size})"
                    setTypeface(typeface, Typeface.BOLD)
                    textSize = 12.5f
                    setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, 0)
                })
                findings.addView(TextView(requireContext()).apply {
                    text = items.take(5).joinToString("\n") { "  $it" } +
                        if (items.size > 5) "\n  " + getString(R.string.om_audit_more_in_code_format, items.size - 5) else ""
                    typeface = Typeface.MONOSPACE
                    textSize = 11.5f
                })
            }
            category(R.string.script_audit_static_category_deletions, report.deletions)
            category(R.string.script_audit_static_category_root, report.rootCalls)
            category(R.string.script_audit_static_category_network, report.networkCalls)
            category(R.string.script_audit_static_category_system, report.systemPaths)
            view.findViewById<MaterialButton>(R.id.staticViewCode).apply {
                visibility = if (content.isNotEmpty()) View.VISIBLE else View.GONE
                setOnClickListener {
                    ScriptRunnerHelper.showFullCodeDialog(requireActivity(), file.name, content, report)
                }
            }
        }
    }

    private fun setupCollapsible(view: View, headerId: Int, bodyId: Int, chevronId: Int) {
        val body = view.findViewById<View>(bodyId)
        val chevron = view.findViewById<ImageView>(chevronId)
        view.findViewById<View>(headerId).setOnClickListener {
            val expand = body.visibility != View.VISIBLE
            body.visibility = if (expand) View.VISIBLE else View.GONE
            chevron.animate().rotation(if (expand) 180f else 0f).setDuration(150).start()
        }
    }

    private fun setChip(view: TextView, color: Int) {
        view.setTextColor(color)
        view.background = GradientDrawable().apply {
            cornerRadius = 12 * resources.displayMetrics.density
            setColor(color and 0x00FFFFFF or 0x26000000)
        }
    }

    private fun copyReport(context: Context, scriptName: String) {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val stats = BinaryTracerEngine.getStats()
        val text = buildString {
            appendLine("=== OpenManager audit: $scriptName ===")
            if (scriptPath.isNotEmpty()) appendLine("Path: $scriptPath")
            appendLine("Date: ${format.format(Date())}")
            appendLine("Dead Man: ${DeadManProtectionManager.isDeadManProtectionActive(context)}")
            appendLine("DFRoot: ${DeadManProtectionManager.isDfrootProtectionActive(context)}")
            appendLine("Policy: ${ScriptTracerPreferences.getDeletionPolicy(context, scriptPath)}")
            appendLine("Blocked: ${stats.blockedDeletions}  Allowed: ${stats.allowedDeletions}")
            appendLine("--- Decisions ---")
            for (event in BinaryTracerEngine.getLedger()) {
                appendLine("[${format.format(Date(event.timestamp))}] ${event.decision} ${event.details}")
                event.target.lines().forEach { appendLine("    -> $it") }
                if (event.reason.isNotEmpty()) appendLine("    ${event.reason}")
            }
        }
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.setPrimaryClip(ClipData.newPlainText("OpenManager audit", text))
        Toast.makeText(context, R.string.script_audit_copied, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val ARG_SCRIPT_PATH = "script_path"
        private const val TAG = "ScriptAuditDialogFragment"
        private const val MAX_ROWS = 60
        private const val COLOR_OK = 0xFF30A46C.toInt()
        private const val COLOR_BLOCK = 0xFFE5484D.toInt()
        private const val COLOR_NEUTRAL = 0xFF8B949E.toInt()

        fun newInstance(scriptPath: String?): ScriptAuditDialogFragment =
            ScriptAuditDialogFragment().apply { arguments = bundleOf(ARG_SCRIPT_PATH to scriptPath) }

        fun show(fragment: Fragment, scriptPath: String?) {
            newInstance(scriptPath).show(fragment.parentFragmentManager, TAG)
        }

        fun show(activity: FragmentActivity, scriptPath: String?) {
            if (activity.supportFragmentManager.findFragmentByTag(TAG) != null) return
            newInstance(scriptPath).show(activity.supportFragmentManager, TAG)
        }
    }
}
