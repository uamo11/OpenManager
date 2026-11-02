/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal.tracer

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.zhanghai.android.files.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BinaryTracerDialog : DialogFragment() {

    private var scriptPath: String? = null

    companion object {
        private const val ARG_SCRIPT_PATH = "arg_script_path"
        private const val TAG = "BinaryTracerDialog"

        fun newInstance(scriptPath: String?): BinaryTracerDialog {
            return BinaryTracerDialog().apply {
                arguments = bundleOf(ARG_SCRIPT_PATH to scriptPath)
            }
        }

        fun show(activity: FragmentActivity, scriptPath: String?) {
            newInstance(scriptPath).show(activity.supportFragmentManager, TAG)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scriptPath = arguments?.getString(ARG_SCRIPT_PATH)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_binary_tracer, null, false)

        val tvScriptPath = view.findViewById<TextView>(R.id.tvTracerScriptPath)
        val switchTracer = view.findViewById<SwitchCompat>(R.id.switchTracerEnabled)
        val rgPolicy = view.findViewById<RadioGroup>(R.id.rgDeletionPolicy)
        val rbAsk = view.findViewById<RadioButton>(R.id.rbPolicyAsk)
        val rbBlock = view.findViewById<RadioButton>(R.id.rbPolicyBlock)
        val rbAllow = view.findViewById<RadioButton>(R.id.rbPolicyAllow)

        val tvMetricDeletions = view.findViewById<TextView>(R.id.tvMetricDeletions)
        val tvMetricSyscalls = view.findViewById<TextView>(R.id.tvMetricSyscalls)
        val tvMetricKernel = view.findViewById<TextView>(R.id.tvMetricKernel)
        val tvEmptyLog = view.findViewById<TextView>(R.id.tvEmptyLog)
        val layoutEvents = view.findViewById<LinearLayout>(R.id.layoutTracerEvents)

        // Script Path Label
        tvScriptPath.text = if (!scriptPath.isNullOrEmpty()) scriptPath else "Terminal / Sesión interactiva"

        // Tracer Enabled Toggle
        val isEnabled = ScriptTracerPreferences.isTracerEnabled(ctx, scriptPath)
        switchTracer.isChecked = isEnabled
        switchTracer.setOnCheckedChangeListener { _, checked ->
            ScriptTracerPreferences.setTracerEnabled(ctx, scriptPath, checked)
            val msgRes = if (checked) R.string.tracer_enabled_permanent_toast else R.string.tracer_disabled_permanent_toast
            Toast.makeText(ctx, msgRes, Toast.LENGTH_SHORT).show()
        }

        // Deletion Policy Radio Group
        val currentPolicy = ScriptTracerPreferences.getDeletionPolicy(ctx, scriptPath)
        when (currentPolicy) {
            ScriptTracerPreferences.DeletionPolicy.ASK -> rbAsk.isChecked = true
            ScriptTracerPreferences.DeletionPolicy.BLOCK_ALL -> rbBlock.isChecked = true
            ScriptTracerPreferences.DeletionPolicy.ALLOW_ALL -> rbAllow.isChecked = true
        }

        rgPolicy.setOnCheckedChangeListener { _, checkedId ->
            val newPolicy = when (checkedId) {
                R.id.rbPolicyBlock -> ScriptTracerPreferences.DeletionPolicy.BLOCK_ALL
                R.id.rbPolicyAllow -> ScriptTracerPreferences.DeletionPolicy.ALLOW_ALL
                else -> ScriptTracerPreferences.DeletionPolicy.ASK
            }
            ScriptTracerPreferences.setDeletionPolicy(ctx, scriptPath, newPolicy)
        }

        fun refreshMetricsAndEvents() {
            val stats = BinaryTracerEngine.getStats()
            tvMetricDeletions.text = "${stats.totalDeletions} (${stats.blockedDeletions} bloq, ${stats.allowedDeletions} perm)"
            tvMetricSyscalls.text = "${stats.totalSyscalls}"
            tvMetricKernel.text = "${stats.totalKernelReads}"

            val events = BinaryTracerEngine.getEvents().reversed()
            layoutEvents.removeAllViews()

            if (events.isEmpty()) {
                tvEmptyLog.visibility = View.VISIBLE
                layoutEvents.visibility = View.GONE
            } else {
                tvEmptyLog.visibility = View.GONE
                layoutEvents.visibility = View.VISIBLE

                val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                val density = ctx.resources.displayMetrics.density

                for (event in events) {
                    val itemLayout = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        val pad = (8 * density).toInt()
                        setPadding(pad, pad, pad, pad)
                        setBackgroundColor(Color.parseColor("#12FFFFFF"))
                        val params = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = (6 * density).toInt()
                        }
                        layoutParams = params
                    }

                    // Top row: Tag badge + timestamp + decision
                    val topRow = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }

                    val badgeColor = when (event.type) {
                        TraceEventType.DELETION_ATTEMPT -> Color.parseColor("#FF5252") // Red
                        TraceEventType.SYSCALL_BURST -> Color.parseColor("#40C4FF")    // Cyan
                        TraceEventType.KERNEL_PROBE -> Color.parseColor("#69F0AE")     // Green
                        TraceEventType.SECURITY_AUDIT -> Color.parseColor("#FFD740")   // Amber
                        TraceEventType.PROCESS_SPAWN -> Color.parseColor("#B388FF")    // Purple
                    }

                    val tvBadge = TextView(ctx).apply {
                        text = event.type.name.replace("_", " ")
                        setTextColor(badgeColor)
                        textSize = 10f
                        typeface = Typeface.DEFAULT_BOLD
                        setPadding(0, 0, (8 * density).toInt(), 0)
                    }
                    topRow.addView(tvBadge)

                    val tvTime = TextView(ctx).apply {
                        text = timeFormat.format(Date(event.timestamp))
                        setTextColor(Color.parseColor("#80FFFFFF"))
                        textSize = 10f
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
                    }
                    topRow.addView(tvTime)

                    if (event.decision != null) {
                        val decisionText = when (event.decision) {
                            DeletionDecision.BLOCKED -> "[BLOQUEADO]"
                            DeletionDecision.ALLOWED -> "[PERMITIDO]"
                            DeletionDecision.PENDING -> "[PENDIENTE]"
                            null -> ""
                        }
                        val decisionColor = when (event.decision) {
                            DeletionDecision.BLOCKED -> Color.parseColor("#FF5252")
                            DeletionDecision.ALLOWED -> Color.parseColor("#69F0AE")
                            DeletionDecision.PENDING -> Color.parseColor("#FFD740")
                            null -> Color.WHITE
                        }
                        val tvDecision = TextView(ctx).apply {
                            text = decisionText
                            setTextColor(decisionColor)
                            textSize = 11f
                            typeface = Typeface.DEFAULT_BOLD
                        }
                        topRow.addView(tvDecision)
                    }

                    itemLayout.addView(topRow)

                    // Target / Summary line
                    val tvTarget = TextView(ctx).apply {
                        text = if (event.count > 1) "${event.target} (x${event.count})" else event.target
                        setTextColor(Color.WHITE)
                        textSize = 12f
                        typeface = Typeface.DEFAULT_BOLD
                        setPadding(0, (2 * density).toInt(), 0, 0)
                    }
                    itemLayout.addView(tvTarget)

                    // Details line
                    if (event.details.isNotEmpty()) {
                        val tvDetails = TextView(ctx).apply {
                            text = event.details
                            setTextColor(Color.parseColor("#B0BEC5"))
                            textSize = 11f
                            setPadding(0, (2 * density).toInt(), 0, 0)
                        }
                        itemLayout.addView(tvDetails)
                    }

                    layoutEvents.addView(itemLayout)
                }
            }
        }

        refreshMetricsAndEvents()

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.tracer_title)
            .setView(view)
            .setNeutralButton(R.string.tracer_clear_log, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()

        dialog.setOnShowListener {
            val btnClear = dialog.getButton(Dialog.BUTTON_NEUTRAL)
            btnClear?.setOnClickListener {
                BinaryTracerEngine.clear()
                refreshMetricsAndEvents()
                Toast.makeText(ctx, "Registro limpiado", Toast.LENGTH_SHORT).show()
            }
        }

        return dialog
    }
}
