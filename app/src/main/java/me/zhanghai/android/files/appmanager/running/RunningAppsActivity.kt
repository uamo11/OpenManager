/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.appmanager.running

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.appmanager.InstalledAppsActivity
import me.zhanghai.android.files.appmanager.NativeAppIconLoader
import me.zhanghai.android.files.databinding.ActivityRunningAppsBinding
import me.zhanghai.android.files.databinding.DialogProcessDetailsBinding
import me.zhanghai.android.files.ui.ThemedFastScroller

class RunningAppsActivity : AppActivity() {

    private lateinit var binding: ActivityRunningAppsBinding
    private val allProcesses = mutableListOf<RunningAppModel>()
    private val displayedProcesses = mutableListOf<RunningAppModel>()
    private lateinit var adapter: RunningAppsAdapter

    private var currentTab = 0 // 0: User apps, 1: System apps, 2: All
    private var searchQuery = ""
    private var currentSortOrder = 0 // 0: RAM descending, 1: Name A-Z, 2: PID ascending

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRunningAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. Consistent dark status bar
        window.statusBarColor = Color.parseColor("#1F1F1F")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
        }

        // 2. Query status bar height directly from framework dimensions
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBarHeight = if (resId > 0) {
            resources.getDimensionPixelSize(resId)
        } else {
            (28 * resources.displayMetrics.density).toInt()
        }

        binding.appBarLayout.updatePadding(top = statusBarHeight)

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val displayCutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val topInset = maxOf(statusBars.top, displayCutout.top, statusBarHeight)
            binding.appBarLayout.updatePadding(top = topInset)

            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            binding.recyclerView.updatePadding(bottom = navBars.bottom + (80 * resources.displayMetrics.density).toInt())
            insets
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupTabs()
        setupRecyclerView()

        binding.fabRefresh.setOnClickListener {
            loadProcesses()
        }

        loadProcesses()
    }

    private fun setupTabs() {
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.running_apps_tab_user))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.running_apps_tab_system))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.running_apps_tab_all))

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentTab = tab?.position ?: 0
                filterAndSortProcesses()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupRecyclerView() {
        adapter = RunningAppsAdapter(
            scope = lifecycleScope,
            items = displayedProcesses,
            onItemClick = { item -> showProcessDetails(item) },
            onKillClick = { item -> killProcess(item) },
            onForceStopClick = { item -> forceStopProcess(item) }
        )
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@RunningAppsActivity)
            setHasFixedSize(true)
            setItemViewCacheSize(30)
            adapter = this@RunningAppsActivity.adapter
            ThemedFastScroller.create(this)
        }
    }

    private fun loadProcesses() {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvEmptyState.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            val ram = RunningAppsLoader.getRamStats(this@RunningAppsActivity)
            val list = RunningAppsLoader.loadRunningProcesses(this@RunningAppsActivity)

            withContext(Dispatchers.Main) {
                // Update RAM monitor card
                val totalStr = Formatter.formatFileSize(this@RunningAppsActivity, ram.totalBytes)
                val usedStr = Formatter.formatFileSize(this@RunningAppsActivity, ram.usedBytes)
                val availStr = Formatter.formatFileSize(this@RunningAppsActivity, ram.availBytes)

                binding.tvRamUsageTitle.text = getString(R.string.running_apps_ram_usage, usedStr, totalStr, ram.usedPercent)
                binding.tvRamAvailable.text = getString(R.string.running_apps_ram_available, availStr)
                binding.pbRamUsage.progress = ram.usedPercent

                allProcesses.clear()
                allProcesses.addAll(list)

                updateCounters()
                filterAndSortProcesses()

                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun updateCounters() {
        val total = allProcesses.size
        var userCount = 0
        var sysCount = 0
        for (p in allProcesses) {
            if (p.isApp && !p.isSystem) userCount++ else sysCount++
        }

        binding.tvProcessCounters.text = "$total procesos en ejecución • $userCount apps de usuario"
        binding.tabLayout.getTabAt(0)?.text = "${getString(R.string.running_apps_tab_user)} ($userCount)"
        binding.tabLayout.getTabAt(1)?.text = "${getString(R.string.running_apps_tab_system)} ($sysCount)"
        binding.tabLayout.getTabAt(2)?.text = "${getString(R.string.running_apps_tab_all)} ($total)"
    }

    private fun filterAndSortProcesses() {
        displayedProcesses.clear()
        val query = searchQuery.trim().lowercase()

        for (p in allProcesses) {
            val matchesTab = when (currentTab) {
                0 -> p.isApp && !p.isSystem
                1 -> p.isSystem
                else -> true
            }
            if (!matchesTab) continue

            if (query.isNotEmpty()) {
                val matchesQuery = p.label.lowercase().contains(query) ||
                    p.processName.lowercase().contains(query) ||
                    (p.packageName?.lowercase()?.contains(query) == true) ||
                    p.pid.toString().contains(query)
                if (!matchesQuery) continue
            }

            displayedProcesses.add(p)
        }

        when (currentSortOrder) {
            0 -> displayedProcesses.sortByDescending { it.memoryBytes }
            1 -> displayedProcesses.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            2 -> displayedProcesses.sortBy { it.pid }
        }

        adapter.notifyDataSetChanged()
        binding.tvEmptyState.visibility = if (displayedProcesses.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showProcessDetails(item: RunningAppModel) {
        val dialogBinding = DialogProcessDetailsBinding.inflate(layoutInflater)

        dialogBinding.dialogProcessLabel.text = item.label
        dialogBinding.dialogProcessName.text = item.processName
        dialogBinding.dialogProcessState.text = "ESTADO: ${item.state} (${if (item.isApp) "App" else "Proceso"})"
        dialogBinding.dialogTvPidPpid.text = "${item.pid} / ${item.ppid}"
        val memStr = if (item.memoryBytes > 0) Formatter.formatFileSize(this, item.memoryBytes) else "Desconocido"
        dialogBinding.dialogTvMemory.text = "$memStr (VmRSS)"
        dialogBinding.dialogTvUidUser.text = "${item.uid} (${item.user})"
        dialogBinding.dialogTvThreads.text = "${item.threadCount} hilos"
        dialogBinding.dialogTvSelinux.text = if (item.selinuxContext.isNotEmpty()) item.selinuxContext else "u:r:default:s0"
        dialogBinding.dialogTvCmdline.text = if (item.cmdline.isNotEmpty()) item.cmdline else item.processName

        if (item.packageName != null) {
            val cached = NativeAppIconLoader.getCached(item.packageName)
            if (cached != null) {
                dialogBinding.dialogProcessIcon.setImageDrawable(cached)
            } else {
                lifecycleScope.launch(Dispatchers.IO) {
                    val icon = NativeAppIconLoader.loadIcon(this@RunningAppsActivity, item.packageName)
                    if (icon != null) {
                        withContext(Dispatchers.Main) {
                            dialogBinding.dialogProcessIcon.setImageDrawable(icon)
                        }
                    }
                }
            }
            dialogBinding.btnDialogForceStop.visibility = View.VISIBLE
            dialogBinding.btnDialogAppDetails.visibility = View.VISIBLE
            dialogBinding.btnDialogAppSettings.visibility = View.VISIBLE
            dialogBinding.btnDialogLaunchApp.visibility = View.VISIBLE
        } else {
            dialogBinding.dialogProcessIcon.setImageResource(R.drawable.ic_running_apps)
            dialogBinding.btnDialogForceStop.visibility = View.GONE
            dialogBinding.btnDialogAppDetails.visibility = View.GONE
            dialogBinding.btnDialogAppSettings.visibility = View.GONE
            dialogBinding.btnDialogLaunchApp.visibility = View.GONE
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .create()

        // 1. Kill
        dialogBinding.btnDialogKillProcess.setOnClickListener {
            dialog.dismiss()
            killProcess(item)
        }

        // 2. Force stop
        dialogBinding.btnDialogForceStop.setOnClickListener {
            dialog.dismiss()
            forceStopProcess(item)
        }

        // 3. App Details
        dialogBinding.btnDialogAppDetails.setOnClickListener {
            dialog.dismiss()
            val intent = Intent(this, InstalledAppsActivity::class.java).apply {
                putExtra("package_name", item.packageName)
            }
            startActivity(intent)
        }

        // 4. App settings
        dialogBinding.btnDialogAppSettings.setOnClickListener {
            dialog.dismiss()
            if (item.packageName != null) {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${item.packageName}")
                }
                startActivity(intent)
            }
        }

        // 5. Launch app
        dialogBinding.btnDialogLaunchApp.setOnClickListener {
            dialog.dismiss()
            if (item.packageName != null) {
                val launchIntent = packageManager.getLaunchIntentForPackage(item.packageName)
                if (launchIntent != null) {
                    startActivity(launchIntent)
                } else {
                    Toast.makeText(this, "Esta aplicación no tiene interfaz ejecutable", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // 6. Copy process info
        dialogBinding.btnDialogCopyProcessInfo.setOnClickListener {
            dialog.dismiss()
            val text = """
                Label: ${item.label}
                Process: ${item.processName}
                Package: ${item.packageName ?: "N/A"}
                PID: ${item.pid}
                PPID: ${item.ppid}
                UID: ${item.uid}
                User: ${item.user}
                RAM: $memStr
                State: ${item.state}
                Threads: ${item.threadCount}
                Cmdline: ${item.cmdline}
            """.trimIndent()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("Process Info", text))
            Toast.makeText(this, "Información del proceso copiada", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    private fun killProcess(item: RunningAppModel) {
        lifecycleScope.launch(Dispatchers.IO) {
            val success = RunningAppsLoader.killProcess(item.pid)
            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(this@RunningAppsActivity, getString(R.string.running_apps_kill_success, item.pid), Toast.LENGTH_SHORT).show()
                    loadProcesses()
                } else {
                    Toast.makeText(this@RunningAppsActivity, getString(R.string.running_apps_kill_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun forceStopProcess(item: RunningAppModel) {
        val pkg = item.packageName ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val success = RunningAppsLoader.forceStopApp(pkg)
            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(this@RunningAppsActivity, getString(R.string.running_apps_force_stop_success), Toast.LENGTH_SHORT).show()
                    loadProcesses()
                } else {
                    // Fallback to launching App Settings
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$pkg")
                    }
                    startActivity(intent)
                    Toast.makeText(this@RunningAppsActivity, "Usa 'Forzar detención' en Ajustes", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_running_apps, menu)

        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem?.actionView as? SearchView
        searchView?.queryHint = "Buscar proceso o app..."
        searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                searchQuery = query.orEmpty()
                filterAndSortProcesses()
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                searchQuery = newText.orEmpty()
                filterAndSortProcesses()
                return true
            }
        })

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_sort -> {
                showSortDialog()
                true
            }
            R.id.action_refresh -> {
                loadProcesses()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showSortDialog() {
        val options = arrayOf(
            getString(R.string.running_apps_sort_ram),
            getString(R.string.running_apps_sort_name),
            getString(R.string.running_apps_sort_pid)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("Ordenar procesos")
            .setSingleChoiceItems(options, currentSortOrder) { dialog, which ->
                currentSortOrder = which
                filterAndSortProcesses()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
