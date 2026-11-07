/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist.textsearch

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import me.zhanghai.android.files.R
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.viewer.text.TextEditorActivity
import java.io.File
import java8.nio.file.Paths

class FileTextSearchDialog : DialogFragment() {

    private var targetDirectoryPath: String = "/storage/emulated/0"
    var onFilesModified: (() -> Unit)? = null

    private lateinit var layoutSetup: View
    private lateinit var layoutScanning: View
    private lateinit var layoutResults: View

    private lateinit var tvSetupPath: TextView
    private lateinit var editSearchQuery: EditText
    private lateinit var editReplaceQuery: EditText
    private lateinit var cbCaseSensitive: CheckBox
    private lateinit var cbWholeWord: CheckBox
    private lateinit var cbRegex: CheckBox
    private lateinit var cbRecursive: CheckBox
    private lateinit var editExtensionFilter: EditText
    private lateinit var btnStartSearch: MaterialButton

    private lateinit var tvScanningProgress: TextView
    private lateinit var tvResultsSummary: TextView
    private lateinit var tvResultsSub: TextView
    private lateinit var recyclerMatches: RecyclerView
    private lateinit var btnReplaceSelected: MaterialButton
    private lateinit var btnReplaceAll: MaterialButton

    private var searchResult: BatchTextSearchResult? = null
    private var adapter: TextSearchRecyclerAdapter? = null

    companion object {
        private const val ARG_PATH = "arg_path"

        fun show(activity: FragmentActivity, path: String, onModified: (() -> Unit)? = null) {
            val dialog = FileTextSearchDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_PATH, path)
                }
                this.onFilesModified = onModified
            }
            dialog.show(activity.supportFragmentManager, "FileTextSearchDialog")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetDirectoryPath = arguments?.getString(ARG_PATH) ?: "/storage/emulated/0"
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_file_text_search, null, false)

        layoutSetup = view.findViewById(R.id.layoutSetup)
        layoutScanning = view.findViewById(R.id.layoutScanning)
        layoutResults = view.findViewById(R.id.layoutResults)

        tvSetupPath = view.findViewById(R.id.tvSetupPath)
        editSearchQuery = view.findViewById(R.id.editSearchQuery)
        editReplaceQuery = view.findViewById(R.id.editReplaceQuery)
        cbCaseSensitive = view.findViewById(R.id.cbCaseSensitive)
        cbWholeWord = view.findViewById(R.id.cbWholeWord)
        cbRegex = view.findViewById(R.id.cbRegex)
        cbRecursive = view.findViewById(R.id.cbRecursive)
        editExtensionFilter = view.findViewById(R.id.editExtensionFilter)
        btnStartSearch = view.findViewById(R.id.btnStartSearch)

        tvScanningProgress = view.findViewById(R.id.tvScanningProgress)
        tvResultsSummary = view.findViewById(R.id.tvResultsSummary)
        tvResultsSub = view.findViewById(R.id.tvResultsSub)
        recyclerMatches = view.findViewById(R.id.recyclerMatches)
        btnReplaceSelected = view.findViewById(R.id.btnReplaceSelected)
        btnReplaceAll = view.findViewById(R.id.btnReplaceAll)

        tvSetupPath.text = targetDirectoryPath

        // Extension chips
        view.findViewById<TextView>(R.id.chipExtAll).setOnClickListener { editExtensionFilter.setText("*") }
        view.findViewById<TextView>(R.id.chipExtXml).setOnClickListener { editExtensionFilter.setText("xml") }
        view.findViewById<TextView>(R.id.chipExtSmali).setOnClickListener { editExtensionFilter.setText("smali") }
        view.findViewById<TextView>(R.id.chipExtCode).setOnClickListener { editExtensionFilter.setText("java, kt") }
        view.findViewById<TextView>(R.id.chipExtJson).setOnClickListener { editExtensionFilter.setText("json, txt") }

        btnStartSearch.setOnClickListener {
            val query = editSearchQuery.text.toString().trim()
            if (query.isEmpty()) {
                Toast.makeText(ctx, "Ingresa una palabra o texto a buscar", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startSearch(query)
        }

        view.findViewById<TextView>(R.id.btnSelectAll).setOnClickListener {
            searchResult?.let {
                for (fm in it.fileMatches) {
                    fm.isFileSelected = true
                }
                adapter?.notifyDataSetChanged()
                updateReplaceButtons()
            }
        }

        view.findViewById<TextView>(R.id.btnDeselectAll).setOnClickListener {
            searchResult?.let {
                for (fm in it.fileMatches) {
                    fm.isFileSelected = false
                }
                adapter?.notifyDataSetChanged()
                updateReplaceButtons()
            }
        }

        view.findViewById<TextView>(R.id.btnInvertSelect).setOnClickListener {
            searchResult?.let {
                for (fm in it.fileMatches) {
                    fm.isFileSelected = !fm.isFileSelected
                }
                adapter?.notifyDataSetChanged()
                updateReplaceButtons()
            }
        }

        btnReplaceSelected.setOnClickListener {
            confirmReplace(selectedFilesOnly = true)
        }

        btnReplaceAll.setOnClickListener {
            confirmReplace(selectedFilesOnly = false)
        }

        return MaterialAlertDialogBuilder(ctx)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    private fun startSearch(query: String) {
        val rootDir = File(targetDirectoryPath)
        val recursive = cbRecursive.isChecked
        val caseSensitive = cbCaseSensitive.isChecked
        val wholeWord = cbWholeWord.isChecked
        val isRegex = cbRegex.isChecked
        val filter = editExtensionFilter.text.toString().trim()

        layoutSetup.visibility = View.GONE
        layoutScanning.visibility = View.VISIBLE
        layoutResults.visibility = View.GONE

        lifecycleScope.launch {
            val result = FileTextSearchEngine.searchText(
                rootDir = rootDir,
                query = query,
                recursive = recursive,
                caseSensitive = caseSensitive,
                wholeWord = wholeWord,
                isRegex = isRegex,
                extensionFilter = filter.ifEmpty { null }
            ) { scanned, matches ->
                lifecycleScope.launch {
                    if (isAdded) {
                        tvScanningProgress.text = "Archivos escaneados: $scanned • Coincidencias encontradas: $matches"
                    }
                }
            }

            searchResult = result
            layoutScanning.visibility = View.GONE
            layoutResults.visibility = View.VISIBLE

            if (result.fileMatches.isEmpty()) {
                tvResultsSummary.text = "¡No se encontraron coincidencias!"
                tvResultsSub.text = "Se escanearon ${result.totalFilesSearched} archivos con la consulta '$query'."
                btnReplaceSelected.isEnabled = false
                btnReplaceSelected.alpha = 0.5f
                btnReplaceAll.isEnabled = false
                btnReplaceAll.alpha = 0.5f
            } else {
                tvResultsSummary.text = "${result.totalMatches} coincidencias en ${result.fileMatches.size} archivos"
                tvResultsSub.text = "Consulta: '$query' (${result.totalFilesSearched} archivos escaneados)"

                adapter = TextSearchRecyclerAdapter(
                    fileMatches = result.fileMatches,
                    onSelectionChanged = { updateReplaceButtons() },
                    onOccurrenceClicked = { file, occurrence ->
                        openTextEditorAtLine(file, occurrence.lineNumber)
                    }
                )
                recyclerMatches.layoutManager = LinearLayoutManager(requireContext())
                recyclerMatches.adapter = adapter
                updateReplaceButtons()
            }
        }
    }

    private fun openTextEditorAtLine(file: File, lineNumber: Int) {
        try {
            val intent = Intent(requireContext(), TextEditorActivity::class.java).apply {
                extraPath = Paths.get(file.absolutePath)
                putExtra("EXTRA_GOTO_LINE", lineNumber)
            }
            startActivity(intent)
        } catch (e: Throwable) {
            Toast.makeText(requireContext(), "No se pudo abrir el archivo: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateReplaceButtons() {
        val result = searchResult ?: return
        val selectedCount = result.fileMatches.count { it.isFileSelected }
        val totalFiles = result.fileMatches.size
        val hasReplacement = editReplaceQuery.text.toString().isNotEmpty()

        if (selectedCount > 0) {
            btnReplaceSelected.isEnabled = true
            btnReplaceSelected.alpha = 1.0f
            btnReplaceSelected.text = "Reemplazar en $selectedCount sel."
        } else {
            btnReplaceSelected.isEnabled = false
            btnReplaceSelected.alpha = 0.5f
            btnReplaceSelected.text = "Reemplazar en seleccionados"
        }

        if (totalFiles > 0) {
            btnReplaceAll.isEnabled = true
            btnReplaceAll.alpha = 1.0f
            btnReplaceAll.text = "Reemplazar en todos ($totalFiles)"
        } else {
            btnReplaceAll.isEnabled = false
            btnReplaceAll.alpha = 0.5f
            btnReplaceAll.text = "Reemplazar en todos"
        }
    }

    private fun confirmReplace(selectedFilesOnly: Boolean) {
        val result = searchResult ?: return
        val query = editSearchQuery.text.toString().trim()
        val replacement = editReplaceQuery.text.toString()

        val filesToProcess = if (selectedFilesOnly) {
            result.fileMatches.filter { it.isFileSelected }
        } else {
            result.fileMatches
        }

        if (filesToProcess.isEmpty()) {
            Toast.makeText(requireContext(), "No hay archivos seleccionados", Toast.LENGTH_SHORT).show()
            return
        }

        val totalMatches = filesToProcess.sumOf { it.matchCount }
        val targetScope = if (selectedFilesOnly) {
            "${filesToProcess.size} archivo(s) seleccionado(s)"
        } else {
            "TODOS los ${filesToProcess.size} archivo(s)"
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Confirmar Reemplazo")
            .setMessage(
                "¿Deseas reemplazar '$query' por '$replacement' en $targetScope?\n\n" +
                "Total de ocurrencias a reemplazar: $totalMatches"
            )
            .setPositiveButton("Reemplazar") { _, _ ->
                executeReplacement(filesToProcess, query, replacement, selectedFilesOnly)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun executeReplacement(
        files: List<FileTextMatches>,
        query: String,
        replacement: String,
        selectedFilesOnly: Boolean
    ) {
        lifecycleScope.launch {
            val replaceResult = FileTextSearchEngine.replaceTextInFiles(
                fileMatches = files,
                query = query,
                replacement = replacement,
                selectedFilesOnly = false, // already filtered
                caseSensitive = cbCaseSensitive.isChecked,
                wholeWord = cbWholeWord.isChecked,
                isRegex = cbRegex.isChecked
            )

            Toast.makeText(
                requireContext(),
                "¡Reemplazo exitoso!\nModificados ${replaceResult.filesModified} archivos (${replaceResult.totalReplacements} reemplazos)",
                Toast.LENGTH_LONG
            ).show()

            onFilesModified?.invoke()
            dismiss()
        }
    }
}

private sealed class SearchRecyclerItem {
    data class FileHeader(val fileMatch: FileTextMatches, var isExpanded: Boolean = true) : SearchRecyclerItem()
    data class OccurrenceRow(val file: File, val occurrence: TextOccurrence) : SearchRecyclerItem()
}

private class TextSearchRecyclerAdapter(
    private val fileMatches: List<FileTextMatches>,
    private val onSelectionChanged: () -> Unit,
    private val onOccurrenceClicked: (File, TextOccurrence) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val expandedFiles = mutableSetOf<File>()
    private val items = mutableListOf<SearchRecyclerItem>()

    init {
        // Expand all files by default
        for (fm in fileMatches) {
            expandedFiles.add(fm.file)
        }
        rebuildVisibleItems()
    }

    private fun rebuildVisibleItems() {
        items.clear()
        for (fm in fileMatches) {
            val isExp = expandedFiles.contains(fm.file)
            items.add(SearchRecyclerItem.FileHeader(fm, isExp))
            if (isExp) {
                for (occ in fm.matches) {
                    items.add(SearchRecyclerItem.OccurrenceRow(fm.file, occ))
                }
            }
        }
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is SearchRecyclerItem.FileHeader -> 0
        is SearchRecyclerItem.OccurrenceRow -> 1
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0) {
            val v = inflater.inflate(R.layout.item_text_search_file_header, parent, false)
            HeaderViewHolder(v)
        } else {
            val v = inflater.inflate(R.layout.item_text_search_occurrence, parent, false)
            OccurrenceViewHolder(v)
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is SearchRecyclerItem.FileHeader -> {
                val vh = holder as HeaderViewHolder
                vh.tvFileName.text = item.fileMatch.fileName
                vh.tvFilePath.text = item.fileMatch.relativePath
                vh.tvMatchBadge.text = "${item.fileMatch.matchCount} coinc."
                
                vh.cbSelect.setOnCheckedChangeListener(null)
                vh.cbSelect.isChecked = item.fileMatch.isFileSelected
                vh.cbSelect.setOnCheckedChangeListener { _, isChecked ->
                    item.fileMatch.isFileSelected = isChecked
                    onSelectionChanged()
                }

                vh.ivExpand.rotation = if (item.isExpanded) 180f else 0f
                vh.itemView.setOnClickListener {
                    if (expandedFiles.contains(item.fileMatch.file)) {
                        expandedFiles.remove(item.fileMatch.file)
                    } else {
                        expandedFiles.add(item.fileMatch.file)
                    }
                    rebuildVisibleItems()
                    notifyDataSetChanged()
                }
            }
            is SearchRecyclerItem.OccurrenceRow -> {
                val vh = holder as OccurrenceViewHolder
                vh.tvLineNumber.text = "L${item.occurrence.lineNumber}:"
                vh.tvLineSnippet.text = item.occurrence.lineText
                vh.itemView.setOnClickListener {
                    onOccurrenceClicked(item.file, item.occurrence)
                }
            }
        }
    }

    private class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cbSelect: CheckBox = view.findViewById(R.id.cbFileSelect)
        val tvFileName: TextView = view.findViewById(R.id.tvHeaderFileName)
        val tvFilePath: TextView = view.findViewById(R.id.tvHeaderFilePath)
        val tvMatchBadge: TextView = view.findViewById(R.id.tvHeaderMatchBadge)
        val ivExpand: ImageView = view.findViewById(R.id.ivExpandCollapse)
    }

    private class OccurrenceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvLineNumber: TextView = view.findViewById(R.id.tvLineNumber)
        val tvLineSnippet: TextView = view.findViewById(R.id.tvLineSnippet)
    }
}
