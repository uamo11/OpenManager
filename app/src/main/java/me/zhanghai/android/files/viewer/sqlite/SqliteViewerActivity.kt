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
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import java8.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.linux.isLinuxPath
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

    private var db: SQLiteDatabase? = null
    private var tempFile: File? = null
    private var currentTable: String = ""
    private var currentPage: Int = 1
    private var totalRows: Int = 0
    private val pageSize: Int = 100
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

        toolbar = findViewById(R.id.toolbarSqlite)
        spnTables = findViewById(R.id.spnTables)
        tvTableInfo = findViewById(R.id.tvTableInfo)
        tableData = findViewById(R.id.tableData)
        pbLoading = findViewById(R.id.pbLoading)
        tvEmpty = findViewById(R.id.tvEmpty)
        btnPrevPage = findViewById(R.id.btnPrevPage)
        btnNextPage = findViewById(R.id.btnNextPage)
        tvPageInfo = findViewById(R.id.tvPageInfo)

        toolbar.setNavigationOnClickListener { finish() }

        val path = intent.extraPath
        if (path == null) {
            Toast.makeText(this, "Ruta no válida", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        toolbar.title = path.fileName.toString()
        toolbar.subtitle = "Base de datos SQLite"

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

        initDatabaseAsync(path)
    }

    private fun escapeSqlIdentifier(name: String): String = "`" + name.replace("`", "``") + "`"

    private fun initDatabaseAsync(path: Path) {
        pbLoading.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dbFile: File
                if (path.isLinuxPath) {
                    dbFile = path.toFile()
                } else {
                    val temp = File(cacheDir, "sqlite_${System.currentTimeMillis()}.db")
                    path.newInputStream().use { input ->
                        FileOutputStream(temp).use { output ->
                            input.copyTo(output)
                        }
                    }
                    tempFile = temp
                    dbFile = temp
                }

                val openedDb = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                db = openedDb
                val tables = queryTables(openedDb)

                withContext(Dispatchers.Main) {
                    pbLoading.visibility = View.GONE
                    if (tables.isEmpty()) {
                        tvEmpty.text = "No se encontraron tablas en la base de datos"
                        tvEmpty.visibility = View.VISIBLE
                        return@withContext
                    }

                    val adapter = ArrayAdapter(this@SqliteViewerActivity, android.R.layout.simple_spinner_dropdown_item, tables)
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
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    pbLoading.visibility = View.GONE
                    Toast.makeText(this@SqliteViewerActivity, "Error al abrir SQLite: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
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

        loadJob?.cancel()

        tableData.removeAllViews()
        pbLoading.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE

        val tableToQuery = currentTable
        val pageToQuery = currentPage

        loadJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val escapedTable = escapeSqlIdentifier(tableToQuery)
                var rowCount = 0

                // Count rows defensively
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
                    while (c.moveToNext()) {
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

                val totalPages = ((rowCount + pageSize - 1) / pageSize).coerceAtLeast(1)
                val pageData = TablePageData(colNames, rowsData, rowCount, pageToQuery, totalPages)

                withContext(Dispatchers.Main) {
                    renderTableData(pageData)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    pbLoading.visibility = View.GONE
                    Toast.makeText(this@SqliteViewerActivity, "Error al consultar tabla: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun renderTableData(data: TablePageData) {
        totalRows = data.totalRows
        tvTableInfo.text = "${data.totalRows} filas, ${data.columnNames.size} cols"
        tvPageInfo.text = "Pág. ${data.page} / ${data.totalPages}"
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

        // Header Row
        val headerRow = TableRow(this).apply {
            setBackgroundColor(Color.parseColor("#333333"))
        }
        for (name in data.columnNames) {
            val tv = TextView(this).apply {
                text = name
                setTextColor(Color.parseColor("#2196F3"))
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setPadding(padH, padV, padH, padV)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }
            headerRow.addView(tv)
        }
        tableData.addView(headerRow)

        // Data Rows
        for ((rowIndex, rowData) in data.rows.withIndex()) {
            val row = TableRow(this).apply {
                setBackgroundColor(if (rowIndex % 2 == 0) Color.parseColor("#242424") else Color.parseColor("#1E1E1E"))
            }
            for (value in rowData) {
                val tv = TextView(this).apply {
                    text = value
                    setTextColor(if (value == "<null>") Color.parseColor("#777777") else Color.WHITE)
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

    override fun onDestroy() {
        super.onDestroy()
        loadJob?.cancel()
        try {
            db?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        db = null
        tempFile?.delete()
        tempFile = null
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
