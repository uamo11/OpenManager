/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java8.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.filelist.FileCompareEngine.Kind
import me.zhanghai.android.files.filelist.FileCompareEngine.Result
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.size
import me.zhanghai.android.files.theme.custom.OpenManagerPalette
import me.zhanghai.android.files.ui.applyEdgeToEdgeInsets
import me.zhanghai.android.files.ui.isUiAlive
import me.zhanghai.android.files.util.extraPathList

/** Side-by-side meaning of two files: their differing lines, or byte facts for binaries. */
class FileCompareActivity : AppActivity() {

    private lateinit var palette: OpenManagerPalette
    private lateinit var tvSummary: TextView
    private lateinit var list: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button
    private lateinit var nav: LinearLayout
    private var rows: List<FileCompareEngine.Row> = emptyList()
    private var changeStarts: List<Int> = emptyList()
    private var changeCursor = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        palette = OpenManagerPalette.from(this)
        val paths = intent.extraPathList
        if (paths.size != 2) {
            finish()
            return
        }
        val (pathA, pathB) = paths
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val toolbar = Toolbar(this).apply {
            setBackgroundColor(palette.toolbarBg)
            setTitleTextColor(palette.textPrimary)
            setSubtitleTextColor(palette.textSecondary)
            title = getString(R.string.om_compare_title)
            subtitle = "${pathA.fileName} ⇄ ${pathB.fileName}"
            setNavigationIcon(R.drawable.arrow_back_icon_white_24dp)
            navigationIcon?.setTint(palette.iconTint)
            setNavigationOnClickListener { finish() }
            minimumHeight = dp(56)
        }
        tvSummary = TextView(this).apply {
            setTextColor(palette.textPrimary)
            setPadding(dp(16), dp(10), dp(16), dp(6))
            setTextIsSelectable(true)
        }
        btnPrev = Button(this).apply {
            setText(R.string.om_compare_previous)
            setOnClickListener { jump(-1) }
        }
        btnNext = Button(this).apply {
            setText(R.string.om_compare_next)
            setOnClickListener { jump(+1) }
        }
        nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), 0, dp(12), 0)
            addView(btnPrev, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnNext, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        progress = ProgressBar(this)
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@FileCompareActivity)
            adapter = RowAdapter()
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.screenBg)
            addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(tvSummary)
            addView(nav)
            addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            })
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        toolbar.applyEdgeToEdgeInsets(applyTop = true, applyHorizontal = true)
        list.applyEdgeToEdgeInsets(applyBottom = true, applyHorizontal = true)
        nav.visibility = android.view.View.GONE

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { compare(pathA, pathB) } }
            if (!isUiAlive) return@launch
            progress.visibility = android.view.View.GONE
            result.onSuccess { show(it, pathA, pathB) }
                .onFailure { tvSummary.text = getString(R.string.om_compare_error, it.message ?: it.toString()) }
        }
    }

    private fun compare(a: Path, b: Path): Result {
        val sizeA = a.size()
        val sizeB = b.size()
        val asText = sizeA <= FileCompareEngine.MAX_TEXT_BYTES && sizeB <= FileCompareEngine.MAX_TEXT_BYTES
        if (asText) {
            val bytesA = a.newInputStream().use { it.readBytes() }
            val bytesB = b.newInputStream().use { it.readBytes() }
            if (!FileCompareEngine.looksBinary(bytesA) && !FileCompareEngine.looksBinary(bytesB)) {
                return FileCompareEngine.compareText(bytesA, bytesB)
            }
        }
        return FileCompareEngine.compareBinary({ a.newInputStream() }, { b.newInputStream() })
    }

    private fun show(result: Result, a: Path, b: Path) {
        when (result) {
            is Result.Text -> {
                tvSummary.text = when {
                    result.identical -> getString(R.string.om_compare_identical)
                    result.onlyLineEndings -> getString(R.string.om_compare_only_line_endings)
                    else -> getString(R.string.om_compare_text_summary, result.removed, result.added)
                }
                rows = result.rows
                changeStarts = rows.indices.filter { i ->
                    rows[i].kind == Kind.ADDED || rows[i].kind == Kind.REMOVED
                }.filter { i -> i == 0 || rows[i - 1].kind == Kind.SAME || rows[i - 1].kind == Kind.GAP }
                list.adapter?.notifyDataSetChanged()
                nav.visibility = if (changeStarts.size > 1) android.view.View.VISIBLE else android.view.View.GONE
            }
            is Result.TooDifferent -> tvSummary.text =
                getString(R.string.om_compare_too_different, result.oldLines, result.newLines)
            is Result.Binary -> tvSummary.text = if (result.identical) {
                getString(R.string.om_compare_identical) + "\n\nSHA-256 " + result.sha256A
            } else {
                getString(
                    R.string.om_compare_binary_summary,
                    a.fileName.toString(), result.sizeA,
                    b.fileName.toString(), result.sizeB,
                    result.firstDifference?.let { "0x" + it.toString(16).uppercase() } ?: "-",
                    result.differingBytes,
                    result.sha256A, result.sha256B
                )
            }
        }
    }

    private fun jump(direction: Int) {
        if (changeStarts.isEmpty()) return
        changeCursor = (changeCursor + direction).mod(changeStarts.size)
        (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(changeStarts[changeCursor], 0)
    }

    private inner class RowAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount(): Int = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val tv = TextView(parent.context).apply {
                typeface = Typeface.MONOSPACE
                textSize = 12f
                val pad = (4 * resources.displayMetrics.density).toInt()
                setPadding(pad * 2, pad / 2, pad * 2, pad / 2)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            return object : RecyclerView.ViewHolder(tv) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val tv = holder.itemView as TextView
            val row = rows[position]
            fun no(n: Int) = if (n > 0) n.toString().padStart(5) else "     "
            when (row.kind) {
                Kind.GAP -> {
                    tv.text = getString(R.string.om_compare_gap, row.oldNo)
                    tv.setTextColor(palette.textSecondary)
                    tv.setBackgroundColor(0)
                }
                Kind.SAME -> {
                    tv.text = "${no(row.oldNo)} ${no(row.newNo)}   ${row.text}"
                    tv.setTextColor(palette.textPrimary)
                    tv.setBackgroundColor(0)
                }
                Kind.REMOVED -> {
                    tv.text = "${no(row.oldNo)} ${no(0)} − ${row.text}"
                    tv.setTextColor(palette.textPrimary)
                    tv.setBackgroundColor(0x40E53935)
                }
                Kind.ADDED -> {
                    tv.text = "${no(0)} ${no(row.newNo)} + ${row.text}"
                    tv.setTextColor(palette.textPrimary)
                    tv.setBackgroundColor(0x4000C853)
                }
            }
        }
    }

    companion object {
        fun createIntent(context: Context, a: Path, b: Path): Intent =
            Intent(context, FileCompareActivity::class.java).apply { extraPathList = listOf(a, b) }
    }
}
