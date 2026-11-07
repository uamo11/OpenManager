/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist.duplicate

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import me.zhanghai.android.files.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DuplicateFinderDialog : DialogFragment() {

    private var targetDirectoryPath: String = "/storage/emulated/0"
    var onDuplicatesDeleted: (() -> Unit)? = null

    private lateinit var layoutSetup: View
    private lateinit var layoutScanning: View
    private lateinit var layoutResults: View

    private lateinit var tvSetupPath: TextView
    private lateinit var switchRecursive: SwitchCompat
    private lateinit var rgMinSize: RadioGroup
    private lateinit var editExtensionFilter: EditText
    private lateinit var btnStartScan: MaterialButton

    private lateinit var tvScanningProgress: TextView
    private lateinit var tvResultsSummary: TextView
    private lateinit var tvResultsSub: TextView
    private lateinit var recyclerDuplicates: RecyclerView
    private lateinit var btnDeleteDuplicates: MaterialButton

    private var scanResult: DuplicateScanResult? = null
    private var adapter: DuplicateRecyclerAdapter? = null

    companion object {
        private const val ARG_PATH = "arg_path"

        fun show(activity: FragmentActivity, path: String, onDeleted: (() -> Unit)? = null) {
            val dialog = DuplicateFinderDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_PATH, path)
                }
                this.onDuplicatesDeleted = onDeleted
            }
            dialog.show(activity.supportFragmentManager, "DuplicateFinderDialog")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetDirectoryPath = arguments?.getString(ARG_PATH) ?: "/storage/emulated/0"
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_duplicate_finder, null, false)

        layoutSetup = view.findViewById(R.id.layoutSetup)
        layoutScanning = view.findViewById(R.id.layoutScanning)
        layoutResults = view.findViewById(R.id.layoutResults)

        tvSetupPath = view.findViewById(R.id.tvSetupPath)
        switchRecursive = view.findViewById(R.id.switchRecursive)
        rgMinSize = view.findViewById(R.id.rgMinSize)
        editExtensionFilter = view.findViewById(R.id.editExtensionFilter)
        btnStartScan = view.findViewById(R.id.btnStartScan)

        tvScanningProgress = view.findViewById(R.id.tvScanningProgress)
        tvResultsSummary = view.findViewById(R.id.tvResultsSummary)
        tvResultsSub = view.findViewById(R.id.tvResultsSub)
        recyclerDuplicates = view.findViewById(R.id.recyclerDuplicates)
        btnDeleteDuplicates = view.findViewById(R.id.btnDeleteDuplicates)

        tvSetupPath.text = targetDirectoryPath

        btnStartScan.setOnClickListener {
            startScan()
        }

        view.findViewById<TextView>(R.id.btnSelectOldest).setOnClickListener {
            scanResult?.let {
                DuplicateFinderEngine.selectOldestDuplicates(it.groups)
                adapter?.notifyDataSetChanged()
                updateDeleteButtonLabel()
            }
        }

        view.findViewById<TextView>(R.id.btnSelectNewest).setOnClickListener {
            scanResult?.let {
                DuplicateFinderEngine.selectNewestDuplicates(it.groups)
                adapter?.notifyDataSetChanged()
                updateDeleteButtonLabel()
            }
        }

        view.findViewById<TextView>(R.id.btnInvertSelection).setOnClickListener {
            scanResult?.let { res ->
                for (group in res.groups) {
                    for (file in group.files) {
                        file.isMarkedForDeletion = !file.isMarkedForDeletion
                    }
                }
                adapter?.notifyDataSetChanged()
                updateDeleteButtonLabel()
            }
        }

        view.findViewById<TextView>(R.id.btnClearSelection).setOnClickListener {
            scanResult?.let {
                DuplicateFinderEngine.clearAllSelections(it.groups)
                adapter?.notifyDataSetChanged()
                updateDeleteButtonLabel()
            }
        }

        btnDeleteDuplicates.setOnClickListener {
            confirmDeleteSelected()
        }

        return MaterialAlertDialogBuilder(ctx)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    private fun startScan() {
        val minSizeBytes = when (rgMinSize.checkedRadioButtonId) {
            R.id.rbMinSize0 -> 0L
            R.id.rbMinSize100K -> 100 * 1024L
            R.id.rbMinSize1M -> 1024 * 1024L
            else -> 1024L // > 1 KB default
        }
        val recursive = switchRecursive.isChecked
        val filter = editExtensionFilter.text.toString().trim()
        val rootDir = File(targetDirectoryPath)

        layoutSetup.visibility = View.GONE
        layoutScanning.visibility = View.VISIBLE
        layoutResults.visibility = View.GONE

        lifecycleScope.launch {
            val result = DuplicateFinderEngine.scanDuplicates(
                rootDir = rootDir,
                recursive = recursive,
                minSizeBytes = minSizeBytes,
                extensionFilter = filter.ifEmpty { null }
            ) { scanned, duplicates ->
                lifecycleScope.launch {
                    if (isAdded) {
                        tvScanningProgress.text = "Escaneados: $scanned archivos • $duplicates duplicados encontrados..."
                    }
                }
            }

            scanResult = result
            layoutScanning.visibility = View.GONE
            layoutResults.visibility = View.VISIBLE

            if (result.groups.isEmpty()) {
                tvResultsSummary.text = "¡No se encontraron archivos duplicados!"
                tvResultsSub.text = "Se escanearon ${result.totalFilesScanned} archivos en total."
                btnDeleteDuplicates.isEnabled = false
                btnDeleteDuplicates.alpha = 0.5f
            } else {
                tvResultsSummary.text = "${result.totalDuplicateFilesCount} duplicados encontrados (${result.formattedTotalWastedBytes} recuperables)"
                tvResultsSub.text = "Se mantendrá 1 copia intacta de cada grupo."
                btnDeleteDuplicates.isEnabled = true
                btnDeleteDuplicates.alpha = 1.0f

                adapter = DuplicateRecyclerAdapter(result.groups) {
                    updateDeleteButtonLabel()
                }
                recyclerDuplicates.layoutManager = LinearLayoutManager(requireContext())
                recyclerDuplicates.adapter = adapter
                updateDeleteButtonLabel()
            }
        }
    }

    private fun updateDeleteButtonLabel() {
        val result = scanResult ?: return
        var markedCount = 0
        var markedBytes = 0L
        for (g in result.groups) {
            for (f in g.files) {
                if (f.isMarkedForDeletion) {
                    markedCount++
                    markedBytes += f.size
                }
            }
        }
        if (markedCount > 0) {
            btnDeleteDuplicates.text = "Eliminar $markedCount Duplicados (${formatBytes(markedBytes)})"
            btnDeleteDuplicates.isEnabled = true
            btnDeleteDuplicates.alpha = 1.0f
        } else {
            btnDeleteDuplicates.text = "Selecciona duplicados a eliminar"
            btnDeleteDuplicates.isEnabled = false
            btnDeleteDuplicates.alpha = 0.5f
        }
    }

    private fun confirmDeleteSelected() {
        val result = scanResult ?: return
        val toDelete = mutableListOf<DuplicateFileItem>()
        var totalBytes = 0L
        for (g in result.groups) {
            for (f in g.files) {
                if (f.isMarkedForDeletion) {
                    toDelete.add(f)
                    totalBytes += f.size
                }
            }
        }
        if (toDelete.isEmpty()) return

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Confirmar eliminación")
            .setMessage("¿Estás seguro de que deseas eliminar permanentemente ${toDelete.size} archivo(s) duplicado(s)?\n\nSe liberarán ${formatBytes(totalBytes)} de almacenamiento.")
            .setPositiveButton("Eliminar") { _, _ ->
                lifecycleScope.launch {
                    val (deleted, freed) = DuplicateFinderEngine.deleteMarkedDuplicates(toDelete, requireContext())
                    Toast.makeText(
                        requireContext(),
                        "Eliminados $deleted archivo(s). Liberados: ${formatBytes(freed)}",
                        Toast.LENGTH_LONG
                    ).show()
                    onDuplicatesDeleted?.invoke()
                    dismiss()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

private sealed class DuplicateRecyclerItem {
    data class Header(val group: DuplicateGroup, val index: Int) : DuplicateRecyclerItem()
    data class FileRow(val item: DuplicateFileItem, val group: DuplicateGroup) : DuplicateRecyclerItem()
}

private class DuplicateRecyclerAdapter(
    private val groups: List<DuplicateGroup>,
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<DuplicateRecyclerItem>()
    private val dateFormat = SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault())

    init {
        rebuildItems()
    }

    private fun rebuildItems() {
        items.clear()
        for ((groupIndex, group) in groups.withIndex()) {
            items.add(DuplicateRecyclerItem.Header(group, groupIndex + 1))
            for (fileItem in group.files) {
                items.add(DuplicateRecyclerItem.FileRow(fileItem, group))
            }
        }
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is DuplicateRecyclerItem.Header -> 0
        is DuplicateRecyclerItem.FileRow -> 1
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0) {
            val v = inflater.inflate(R.layout.item_duplicate_group_header, parent, false)
            HeaderViewHolder(v)
        } else {
            val v = inflater.inflate(R.layout.item_duplicate_file_row, parent, false)
            FileViewHolder(v)
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is DuplicateRecyclerItem.Header -> {
                val vh = holder as HeaderViewHolder
                vh.tvTitle.text = "Grupo #${item.index} • ${item.group.totalCopies} copias (${item.group.formattedWastedBytes} recuperables)"
                vh.tvWasted.text = "${formatBytes(item.group.sizePerFile)} c/u"
            }
            is DuplicateRecyclerItem.FileRow -> {
                val vh = holder as FileViewHolder
                vh.tvName.text = item.item.name
                vh.tvPath.text = item.item.path
                vh.tvDate.text = dateFormat.format(Date(item.item.lastModified))
                vh.cbMark.setOnCheckedChangeListener(null)
                vh.cbMark.isChecked = item.item.isMarkedForDeletion
                vh.cbMark.setOnCheckedChangeListener { _, isChecked ->
                    item.item.isMarkedForDeletion = isChecked
                    onSelectionChanged()
                }
                vh.itemView.setOnClickListener {
                    vh.cbMark.isChecked = !vh.cbMark.isChecked
                }
            }
        }
    }

    private class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tvGroupTitle)
        val tvWasted: TextView = view.findViewById(R.id.tvGroupWasted)
    }

    private class FileViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cbMark: CheckBox = view.findViewById(R.id.cbMarkDelete)
        val tvName: TextView = view.findViewById(R.id.tvFileName)
        val tvPath: TextView = view.findViewById(R.id.tvFilePath)
        val tvDate: TextView = view.findViewById(R.id.tvFileDate)
    }
}
