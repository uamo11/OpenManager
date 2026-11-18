/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.sqlite

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import java8.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.provider.archive.isArchivePath
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.theme.custom.OpenManagerPalette
import me.zhanghai.android.files.ui.applyEdgeToEdgeInsets
import me.zhanghai.android.files.ui.isUiAlive
import me.zhanghai.android.files.util.extraPath
import java.io.File
import java.io.FileOutputStream

class SqliteViewerActivity : AppActivity() {
    private lateinit var toolbar: Toolbar
    private lateinit var spnTables: Spinner
    private lateinit var tvTableInfo: TextView
    private lateinit var tableData: TableLayout
    private lateinit var pbLoading: ProgressBar
    private lateinit var tvEmpty: TextView
    private lateinit var btnPrevPage: Button
    private lateinit var btnNextPage: Button
    private lateinit var tvPageInfo: TextView
    private lateinit var palette: OpenManagerPalette

    private var db: SQLiteDatabase? = null
    private var tempFile: File? = null
    private var currentTable: String = ""
    private var currentPage: Int = 1
    private var totalRows: Int = 0
    private val pageSize: Int = 100
    private var initJob: Job? = null
    private var loadJob: Job? = null

    private data class TablePageData(
        val columnNames: List<String>,
        val rows: List<List<String>>,
        val totalRows: Int,
        val page: Int,
        val totalPages: Int
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sqlite_viewer)
        palette = OpenManagerPalette.from(this)

        toolbar = findViewById(R.id.toolbarSqlite)
        spnTables = findViewById(R.id.spnTables)
        tvTableInfo = findViewById(R.id.tvTableInfo)
        tableData = findViewById(R.id.tableData)
        pbLoading = findViewById(R.id.pbLoading)
        tvEmpty = findViewById(R.id.tvEmpty)
        btnPrevPage = findViewById(R.id.btnPrevPage)
        btnNextPage = findViewById(R.id.btnNextPage)
        tvPageInfo = findViewById(R.id.tvPageInfo)
        toolbar.applyEdgeToEdgeInsets(applyTop = true, applyHorizontal = true)
        findViewById<View>(R.id.layoutPagination).applyEdgeToEdgeInsets(applyBottom = true, applyHorizontal = true)
        applyPaletteTheme()

        toolbar.setNavigationOnClickListener { finish() }

        val path = intent.extraPath
        val externalUri = intent.data
        if (path == null && externalUri == null) {
            Toast.makeText(this, R.string.om_sqlite_invalid_path, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val displayName = path?.fileName?.toString()
            ?: externalUri?.lastPathSegment?.substringAfterLast('/')?.ifBlank { "database.db" }
            ?: "database.db"
        toolbar.title = displayName
        toolbar.subtitle = getString(R.string.om_sqlite_subtitle)

        btnPrevPage.setOnClickListener {
            if (currentPage > 1) {
                currentPage--
                loadTableData()
            }
        }

        btnNextPage.setOnClickListener {
            if (currentPage * pageSize < totalRows) {
                currentPage++
                loadTableData()
            }
        }

        initDatabaseAsync(path = path, externalUri = externalUri)
    }

    private fun applyPaletteTheme() {
        if (palette.isDark && !palette.isAmoled) return
        findViewById<View>(R.id.rootSqlite)?.setBackgroundColor(palette.screenBg)
        toolbar.setBackgroundColor(palette.toolbarBg)
        toolbar.setTitleTextColor(palette.textPrimary)
        toolbar.setSubtitleTextColor(palette.textSecondary)
        toolbar.navigationIcon?.setTint(palette.iconTint)
        findViewById<View>(R.id.layoutTableSelector)?.setBackgroundColor(palette.appBarBg)
        findViewById<ImageView>(R.id.ivTableIcon)?.setColorFilter(palette.iconTint)
        spnTables.setPopupBackgroundDrawable(ColorDrawable(palette.dialogBg))
        tvTableInfo.setTextColor(palette.textSecondary)
        findViewById<View>(R.id.dividerSqlite)?.setBackgroundColor(palette.dividerLineColor)
        tvEmpty.setTextColor(palette.textSecondary)
        findViewById<View>(R.id.layoutPagination)?.setBackgroundColor(palette.bottomBarBg)
        tvPageInfo.setTextColor(palette.textPrimary)
        btnPrevPage.setTextColor(palette.accentColor)
        btnNextPage.setTextColor(palette.accentColor)
        window.statusBarColor = palette.toolbarBg
        window.navigationBarColor = palette.bottomBarBg
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = palette.isLightStatusBar
            isAppearanceLightNavigationBars = palette.isLightStatusBar
        }
    }

    private fun escapeSqlIdentifier(name: String): String = "`" + name.replace("`", "``") + "`"

    private fun initDatabaseAsync(path: Path?, externalUri: Uri?) {
        pbLoading.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE

        initJob?.cancel()
        initJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                tempFile?.delete()
                tempFile = null

                val dbFile: File
                if (path != null && !path.isArchivePath && path.isLinuxPath) {
                    dbFile = path.toFile()
                } else {
                    val temp = File(cacheDir, "sqlite_${System.currentTimeMillis()}.db")
                    val inStream = when {
                        path != null -> path.newInputStream()
                        externalUri != null -> contentResolver.openInputStream(externalUri)
                            ?: throw IllegalStateException("Cannot open URI stream")
                        else -> throw IllegalStateException("No database source")
                    }
                    inStream.use { input ->
                        FileOutputStream(temp).use { output ->
                            input.copyTo(output, bufferSize = 65536)
                        }
                    }
                    tempFile = temp
                    dbFile = temp
                }

                val openedDb = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                if (!isActive || isDestroyed || isFinishing) {
                    runCatching { openedDb.close() }
                    tempFile?.delete()
                    tempFile = null
                    return@launch
                }
                db = openedDb
                val tables = queryTables(openedDb)

                withContext(Dispatchers.Main) {
                    if (!isUiAlive) return@withContext
                    pbLoading.visibility = View.GONE
                    if (tables.isEmpty()) {
                        tvEmpty.text = getString(R.string.om_sqlite_no_tables)
                        tvEmpty.visibility = View.VISIBLE
                        return@withContext
                    }

                    val spinnerTextColor = if (palette.isDark && !palette.isAmoled) Color.WHITE else palette.textPrimary
                    val adapter = object : ArrayAdapter<String>(
                        this@SqliteViewerActivity,
                        android.R.layout.simple_spinner_dropdown_item,
                        tables
                    ) {
                        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                            return super.getView(position, convertView, parent).apply {
                                (this as? TextView)?.setTextColor(spinnerTextColor)
                            }
                        }

                        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                            return super.getDropDownView(position, convertView, parent).apply {
                                if (!palette.isDark || palette.isAmoled) {
                                    setBackgroundColor(palette.dialogBg)
                                }
                                (this as? TextView)?.setTextColor(spinnerTextColor)
                            }
                        }
                    }
                    spnTables.adapter = adapter
                    spnTables.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                            currentTable = tables[position]
                            currentPage = 1
                            loadTableData()
                        }

                        override fun onNothingSelected(parent: AdapterView<*>?) {}
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    if (!isUiAlive) return@withContext
                    pbLoading.visibility = View.GONE
                    Toast.makeText(
                        this@SqliteViewerActivity,
                        getString(R.string.om_sqlite_open_error_format, e.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            }
        }
        initJob?.invokeOnCompletion {
            if (isDestroyed) closeAndCleanupDb()
        }
    }

    private fun queryTables(database: SQLiteDatabase): List<String> {
        val list = mutableListOf<String>()
        val cursor = database.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
            null
        )
        cursor?.use {
            while (it.moveToNext()) {
                list.add(it.getString(0))
            }
        }
        return list
    }

    private fun loadTableData() {
        val database = db ?: return
        if (currentTable.isEmpty()) return

        val previousJob = loadJob
        previousJob?.cancel()

        tableData.removeAllViews()
        pbLoading.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE

        val tableToQuery = currentTable
        val pageToQuery = currentPage

        loadJob = lifecycleScope.launch(Dispatchers.IO) {
            previousJob?.join()
            if (!isActive || !database.isOpen) return@launch
            try {
                val escapedTable = escapeSqlIdentifier(tableToQuery)
                var rowCount = 0

                val countCursor = database.rawQuery("SELECT COUNT(*) FROM $escapedTable", null)
                countCursor.use {
                    if (it.moveToFirst()) {
                        rowCount = it.getInt(0)
                    }
                }

                val offset = (pageToQuery - 1) * pageSize
                val cursor = database.rawQuery(
                    "SELECT * FROM $escapedTable LIMIT $pageSize OFFSET $offset",
                    null
                )

                val colNames = mutableListOf<String>()
                val rowsData = mutableListOf<List<String>>()

                cursor.use { c ->
                    colNames.addAll(c.columnNames)
                    while (isActive && c.moveToNext()) {
                        val row = mutableListOf<String>()
                        for (i in 0 until c.columnCount) {
                            val value = try {
                                c.getString(i) ?: "<null>"
                            } catch (e: Exception) {
                                "<blob>"
                            }
                            row.add(value)
                        }
                        rowsData.add(row)
                    }
                }
                if (!isActive) return@launch

                val totalPages = ((rowCount + pageSize - 1) / pageSize).coerceAtLeast(1)
                val pageData = TablePageData(colNames, rowsData, rowCount, pageToQuery, totalPages)

                withContext(Dispatchers.Main) {
                    if (!isUiAlive) return@withContext
                    renderTableData(pageData)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    if (!isUiAlive) return@withContext
                    pbLoading.visibility = View.GONE
                    Toast.makeText(
                        this@SqliteViewerActivity,
                        getString(R.string.om_sqlite_query_error_format, e.message ?: ""),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        loadJob?.invokeOnCompletion {
            if (isDestroyed) closeAndCleanupDb()
        }
    }

    private fun renderTableData(data: TablePageData) {
        totalRows = data.totalRows
        tvTableInfo.text = getString(R.string.om_sqlite_table_info_format, data.totalRows, data.columnNames.size)
        tvPageInfo.text = getString(R.string.om_sqlite_page_info_format, data.page, data.totalPages)
        btnPrevPage.isEnabled = data.page > 1
        btnNextPage.isEnabled = data.page < data.totalPages

        pbLoading.visibility = View.GONE

        if (data.totalRows == 0 || data.rows.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            return
        }

        val density = resources.displayMetrics.density
        val padH = (12 * density).toInt()
        val padV = (10 * density).toInt()

        val isDefaultDark = palette.isDark && !palette.isAmoled
        val headerBg = when {
            palette.isAmoled -> 0xFF121212.toInt()
            !palette.isDark -> 0xFFE8ECF2.toInt()
            else -> Color.parseColor("#333333")
        }
        val headerText = if (isDefaultDark) Color.parseColor("#2196F3") else palette.accentColor
        val evenBg = when {
            palette.isAmoled -> 0xFF080808.toInt()
            !palette.isDark -> 0xFFFFFFFF.toInt()
            else -> Color.parseColor("#242424")
        }
        val oddBg = when {
            palette.isAmoled -> 0xFF000000.toInt()
            !palette.isDark -> 0xFFF4F6F9.toInt()
            else -> Color.parseColor("#1E1E1E")
        }
        val nullTextColor = if (isDefaultDark) Color.parseColor("#777777") else palette.textSecondary
        val cellTextColor = if (isDefaultDark) Color.WHITE else palette.textPrimary

        val headerRow = TableRow(this).apply {
            setBackgroundColor(headerBg)
        }
        for (name in data.columnNames) {
            val tv = TextView(this).apply {
                text = name
                setTextColor(headerText)
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setPadding(padH, padV, padH, padV)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }
            headerRow.addView(tv)
        }
        tableData.addView(headerRow)

        for ((rowIndex, rowData) in data.rows.withIndex()) {
            val row = TableRow(this).apply {
                setBackgroundColor(if (rowIndex % 2 == 0) evenBg else oddBg)
            }
            for (value in rowData) {
                val tv = TextView(this).apply {
                    text = value
                    setTextColor(if (value == "<null>") nullTextColor else cellTextColor)
                    textSize = 12f
                    setPadding(padH, padV, padH, padV)
                    setTextIsSelectable(true)
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                }
                row.addView(tv)
            }
            tableData.addView(row)
        }
    }

    @Synchronized
    private fun closeAndCleanupDb() {
        runCatching { db?.close() }
        db = null
        tempFile?.delete()
        tempFile = null
    }

    override fun onDestroy() {
        super.onDestroy()
        initJob?.cancel()
        loadJob?.cancel()
        if ((initJob?.isActive != true) && (loadJob?.isActive != true)) {
            closeAndCleanupDb()
        }
    }

    companion object {
        fun start(context: Context, path: Path) {
            val intent = Intent(context, SqliteViewerActivity::class.java).apply {
                extraPath = path
            }
            context.startActivity(intent)
        }
    }
}
