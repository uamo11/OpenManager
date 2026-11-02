/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.text

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.TextEditorPreferencesDialogBinding

class TextEditorPreferencesDialogFragment : DialogFragment() {

    interface Listener {
        fun onPreferencesChanged()
    }

    private var _binding: TextEditorPreferencesDialogBinding? = null
    private val binding get() = _binding!!

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        _binding = TextEditorPreferencesDialogBinding.inflate(LayoutInflater.from(context))

        // 1. Theme
        val currentTheme = TextEditorSettings.getTheme(context)
        binding.textThemeValue.text = currentTheme.displayName
        binding.layoutTheme.setOnClickListener {
            val themes = EditorTheme.entries.toTypedArray()
            val themeNames = themes.map { it.displayName }.toTypedArray()
            val selectedIndex = themes.indexOf(TextEditorSettings.getTheme(context))

            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.text_editor_theme_title)
                .setSingleChoiceItems(themeNames, selectedIndex) { d, which ->
                    val chosen = themes[which]
                    TextEditorSettings.setTheme(context, chosen)
                    binding.textThemeValue.text = chosen.displayName
                    notifyChanged()
                    d.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        // 2. Font Size
        val currentFontSize = TextEditorSettings.getFontSize(context)
        binding.textFontSizeValue.text = String.format(java.util.Locale.US, "%.1f sp", currentFontSize)
        binding.layoutFontSize.setOnClickListener {
            val sizes = floatArrayOf(10f, 12f, 14f, 16f, 18f, 20f, 24f)
            val sizeLabels = sizes.map { "${it.toInt()} sp" }.toTypedArray()
            val currentSize = TextEditorSettings.getFontSize(context)
            val nearestIndex = sizes.indices.minByOrNull { kotlin.math.abs(sizes[it] - currentSize) } ?: 2

            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.text_editor_font_size)
                .setSingleChoiceItems(sizeLabels, nearestIndex) { d, which ->
                    val chosenSize = sizes[which]
                    TextEditorSettings.setFontSize(context, chosenSize)
                    binding.textFontSizeValue.text = String.format(java.util.Locale.US, "%.1f sp", chosenSize)
                    notifyChanged()
                    d.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        // 3. Line Numbers
        binding.switchLineNumbers.isChecked = TextEditorSettings.isShowLineNumbers(context)
        binding.switchLineNumbers.setOnCheckedChangeListener { _, isChecked ->
            TextEditorSettings.setShowLineNumbers(context, isChecked)
            notifyChanged()
        }

        // 4. Wrap Arrows
        binding.switchWrapArrows.isChecked = TextEditorSettings.isShowWrapArrows(context)
        binding.switchWrapArrows.setOnCheckedChangeListener { _, isChecked ->
            TextEditorSettings.setShowWrapArrows(context, isChecked)
            notifyChanged()
        }

        // 5. Highlight Current Line
        binding.switchHighlightLine.isChecked = TextEditorSettings.isHighlightCurrentLine(context)
        binding.switchHighlightLine.setOnCheckedChangeListener { _, isChecked ->
            TextEditorSettings.setHighlightCurrentLine(context, isChecked)
            notifyChanged()
        }

        // 6. Syntax Highlighting
        binding.switchSyntax.isChecked = TextEditorSettings.isSyntaxHighlighting(context)
        binding.switchSyntax.setOnCheckedChangeListener { _, isChecked ->
            TextEditorSettings.setSyntaxHighlighting(context, isChecked)
            notifyChanged()
        }

        // 7. Symbol Bar
        binding.switchSymbolBar.isChecked = TextEditorSettings.isShowSymbolBar(context)
        binding.switchSymbolBar.setOnCheckedChangeListener { _, isChecked ->
            TextEditorSettings.setShowSymbolBar(context, isChecked)
            notifyChanged()
        }

        // 8. Auto Indent
        binding.switchAutoIndent.isChecked = TextEditorSettings.isAutoIndent(context)
        binding.switchAutoIndent.setOnCheckedChangeListener { _, isChecked ->
            TextEditorSettings.setAutoIndent(context, isChecked)
            notifyChanged()
        }

        // 9. Auto Close Pairs
        binding.switchAutoPairs.isChecked = TextEditorSettings.isAutoClosePairs(context)
        binding.switchAutoPairs.setOnCheckedChangeListener { _, isChecked ->
            TextEditorSettings.setAutoClosePairs(context, isChecked)
            notifyChanged()
        }

        // 10. Pinch to Zoom
        binding.switchPinchZoom.isChecked = TextEditorSettings.isPinchToZoom(context)
        binding.switchPinchZoom.setOnCheckedChangeListener { _, isChecked ->
            TextEditorSettings.setPinchToZoom(context, isChecked)
            notifyChanged()
        }

        // 11. Word Wrap
        binding.switchWordWrap.isChecked = TextEditorSettings.isWordWrap(context)
        binding.switchWordWrap.setOnCheckedChangeListener { _, isChecked ->
            TextEditorSettings.setWordWrap(context, isChecked)
            notifyChanged()
        }

        // 12. Read Only Default
        binding.switchReadOnly.isChecked = TextEditorSettings.isReadOnlyDefault(context)
        binding.switchReadOnly.setOnCheckedChangeListener { _, isChecked ->
            TextEditorSettings.setReadOnlyDefault(context, isChecked)
            notifyChanged()
        }

        // 13. Confirm Exit
        binding.switchConfirmExit.isChecked = TextEditorSettings.isConfirmOnExit(context)
        binding.switchConfirmExit.setOnCheckedChangeListener { _, isChecked ->
            TextEditorSettings.setConfirmOnExit(context, isChecked)
            notifyChanged()
        }

        return MaterialAlertDialogBuilder(context)
            .setTitle(R.string.text_editor_preferences)
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok, null)
            .create()
    }

    private fun notifyChanged() {
        (targetFragment as? Listener)?.onPreferencesChanged()
            ?: (parentFragment as? Listener)?.onPreferencesChanged()
            ?: (activity as? Listener)?.onPreferencesChanged()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun show(fragment: androidx.fragment.app.Fragment) {
            val dialog = TextEditorPreferencesDialogFragment()
            dialog.setTargetFragment(fragment, 0)
            dialog.show(fragment.parentFragmentManager, TextEditorPreferencesDialogFragment::class.java.name)
        }
    }
}
