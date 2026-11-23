/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java8.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.theme.custom.OpenManagerPalette
import me.zhanghai.android.files.ui.bindToLifecycle
import me.zhanghai.android.files.ui.isUiAlive

/** Batch rename with a live preview: every name is shown old → new before anything changes. */
object BatchRenameDialog {

    private const val PREVIEW_LIMIT = 200

    fun show(
        activity: FragmentActivity,
        folder: Path,
        selected: List<FileItem>,
        folderNames: Set<String>,
        onDone: () -> Unit
    ) {
        if (!activity.isUiAlive || selected.isEmpty()) return
        val palette = OpenManagerPalette.from(activity)
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val files = selected.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        val oldNames = files.map { it.name }
        val directories = files.filter { it.attributesNoFollowLinks.isDirectory }.map { it.name }.toSet()
        val others = folderNames - oldNames.toSet()

        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        fun label(text: Int) = TextView(activity).apply {
            setText(text)
            setTextColor(palette.textSecondary)
            textSize = 12f
            setPadding(0, dp(8), 0, 0)
        }
        fun field(initial: String, hint: Int, number: Boolean = false) = EditText(activity).apply {
            setText(initial)
            setHint(hint)
            isSingleLine = true
            setTextColor(palette.textPrimary)
            setHintTextColor(palette.textSecondary)
            inputType = if (number) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }

        val etTemplate = field("{name}", R.string.om_batch_rename_template_hint)
        val etFind = field("", R.string.om_batch_rename_find_hint)
        val etReplace = field("", R.string.om_batch_rename_replace_hint)
        val cbRegex = CheckBox(activity).apply { setText(R.string.om_batch_rename_regex); setTextColor(palette.textPrimary) }
        val cbIgnoreCase = CheckBox(activity).apply { setText(R.string.om_batch_rename_ignore_case); setTextColor(palette.textPrimary) }
        val spCase = Spinner(activity).apply {
            adapter = ArrayAdapter(
                activity, android.R.layout.simple_spinner_dropdown_item,
                activity.resources.getStringArray(R.array.om_batch_rename_cases)
            )
        }
        val etStart = field("1", R.string.om_batch_rename_start_hint, number = true)
        val etPadding = field("0", R.string.om_batch_rename_padding_hint, number = true)
        val tvSummary = TextView(activity).apply {
            setTextColor(palette.textPrimary)
            setPadding(0, dp(10), 0, dp(4))
            setTypeface(typeface, Typeface.BOLD)
        }
        val tvPreview = TextView(activity).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(palette.textPrimary)
            setTextIsSelectable(true)
        }

        box.addView(label(R.string.om_batch_rename_template_label))
        box.addView(etTemplate)
        box.addView(TextView(activity).apply {
            setText(R.string.om_batch_rename_tokens)
            setTextColor(palette.textSecondary)
            textSize = 11f
        })
        box.addView(label(R.string.om_batch_rename_find_label))
        box.addView(etFind)
        box.addView(etReplace)
        box.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(cbRegex)
            addView(cbIgnoreCase)
        })
        box.addView(label(R.string.om_batch_rename_case_label))
        box.addView(spCase)
        box.addView(label(R.string.om_batch_rename_counter_label))
        box.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(etStart, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(etPadding, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
        box.addView(tvSummary)
        box.addView(tvPreview)

        val scroll = ScrollView(activity).apply { addView(box) }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.resources.getQuantityString(R.plurals.om_batch_rename_title, files.size, files.size))
            .setView(scroll)
            .setPositiveButton(R.string.om_batch_rename_apply, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .bindToLifecycle(activity)

        var plan: List<BatchRenameEngine.Item> = emptyList()

        fun currentRule() = BatchRenameEngine.Rule(
            template = etTemplate.text.toString(),
            find = etFind.text.toString(),
            replace = etReplace.text.toString(),
            regex = cbRegex.isChecked,
            ignoreCase = cbIgnoreCase.isChecked,
            letterCase = BatchRenameEngine.LetterCase.values()[spCase.selectedItemPosition.coerceAtLeast(0)],
            start = etStart.text.toString().toIntOrNull() ?: 1,
            padding = (etPadding.text.toString().toIntOrNull() ?: 0).coerceIn(0, 9)
        )

        fun refresh() {
            plan = BatchRenameEngine.plan(oldNames, currentRule(), directories, others)
            val changed = plan.count { it.changed }
            val problems = plan.count { it.problem != null }
            tvSummary.text = if (problems > 0) {
                activity.getString(R.string.om_batch_rename_summary_problems, changed, problems)
            } else {
                activity.getString(R.string.om_batch_rename_summary, changed, plan.size)
            }
            val warn = 0xFFE53935.toInt()
            val text = SpannableStringBuilder()
            for (item in plan.take(PREVIEW_LIMIT)) {
                val start = text.length
                text.append(item.oldName).append("  →  ").append(item.newName)
                item.problem?.let { text.append("  ⚠ ").append(problemText(activity, it)) }
                text.append('\n')
                if (item.problem != null) {
                    text.setSpan(ForegroundColorSpan(warn), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else if (!item.changed) {
                    text.setSpan(ForegroundColorSpan(palette.textSecondary), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            if (plan.size > PREVIEW_LIMIT) text.append("… +${plan.size - PREVIEW_LIMIT}")
            tvPreview.text = text
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = problems == 0 && changed > 0
        }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refresh()
        }
        listOf(etTemplate, etFind, etReplace, etStart, etPadding).forEach { it.addTextChangedListener(watcher) }
        cbRegex.setOnCheckedChangeListener { _, _ -> refresh() }
        cbIgnoreCase.setOnCheckedChangeListener { _, _ -> refresh() }
        spCase.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = refresh()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        dialog.setOnShowListener {
            refresh()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val finalPlan = plan
                dialog.dismiss()
                activity.lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { BatchRenameEngine.apply(folder, finalPlan) }
                    }
                    if (!activity.isUiAlive) return@launch
                    result.onSuccess { count ->
                        Toast.makeText(
                            activity,
                            activity.resources.getQuantityString(R.plurals.om_batch_rename_done, count, count),
                            Toast.LENGTH_SHORT
                        ).show()
                    }.onFailure { e ->
                        MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.om_batch_rename_failed_title)
                            .setMessage(activity.getString(R.string.om_batch_rename_failed_message, e.message ?: e.toString()))
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                    onDone()
                }
            }
        }
        dialog.show()
    }

    private fun problemText(activity: FragmentActivity, problem: BatchRenameEngine.Problem): String =
        activity.getString(
            when (problem) {
                BatchRenameEngine.Problem.EMPTY -> R.string.om_batch_rename_problem_empty
                BatchRenameEngine.Problem.INVALID_CHARACTER -> R.string.om_batch_rename_problem_char
                BatchRenameEngine.Problem.DUPLICATE -> R.string.om_batch_rename_problem_duplicate
                BatchRenameEngine.Problem.EXISTS -> R.string.om_batch_rename_problem_exists
                BatchRenameEngine.Problem.BAD_REGEX -> R.string.om_batch_rename_problem_regex
            }
        )
}
