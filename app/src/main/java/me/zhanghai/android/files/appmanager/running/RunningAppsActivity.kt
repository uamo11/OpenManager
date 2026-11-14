/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.appmanager.running

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.topjohnwu.superuser.Shell
import java8.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.appmanager.InstalledAppsActivity
import me.zhanghai.android.files.appmanager.NativeAppIconLoader
import me.zhanghai.android.files.databinding.ActivityRunningAppsBinding
import me.zhanghai.android.files.databinding.DialogProcessDetailsBinding
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.provider.root.LibSuFileServiceLauncher
import me.zhanghai.android.files.provider.root.ShizukuManager
import me.zhanghai.android.files.settings.Settings as AppSettings
import me.zhanghai.android.files.terminal.ScriptExecutionManager
import me.zhanghai.android.files.theme.custom.OpenManagerPalette
import me.zhanghai.android.files.ui.ThemedFastScroller
import me.zhanghai.android.files.ui.bindToLifecycle
import me.zhanghai.android.files.ui.isUiAlive
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class RunningAppsActivity : AppActivity() {

    private lateinit var binding: ActivityRunningAppsBinding
    private val allProcesses = mutableListOf<RunningAppModel>()
    private val displayedProcesses = mutableListOf<RunningAppModel>()
    private lateinit var adapter: RunningAppsAdapter

    private var currentTab = 0 // 0: User apps, 1: System apps, 2: SH, 3: All
    private var searchQuery = ""
    private var currentSortOrder = 0 // 0: RAM descending, 1: CPU descending, 2: Name A-Z, 3: PID ascending
    private var isBannerDismissed = false
    private var loadJob: Job? = null
    private var autoRefreshJob: Job? = null
    private val isLoading = AtomicBoolean(false)
    private val refreshGeneration = AtomicInteger(0)
    private var isUserScrolling = false
    private var hasPendingUiUpdate = false
    private var activeDetailsPid: Int = -1
    private var activeDetailsBinding: DialogProcessDetailsBinding? = null

    private val shizukuListener: () -> Unit = {
        runOnUiThread {
            updateElevationUi()
            loadProcesses()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRunningAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        isBannerDismissed = getSharedPreferences("running_apps_prefs", MODE_PRIVATE)
            .getBoolean("hide_permission_banner", false)

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
        setupElevationControls()
        applyVisualTheme()
        observeVisualTheme()

        ShizukuManager.init(this)
        ShizukuManager.addBinderListener(shizukuListener)

        binding.fabRefresh.setOnClickListener {
            loadProcesses()
        }

        checkAndPromptElevatedPermissions()
        loadProcesses()
    }

    private fun observeVisualTheme() {
        AppSettings.NIGHT_MODE.observe(this) { applyVisualTheme() }
        AppSettings.BLACK_NIGHT_MODE.observe(this) { applyVisualTheme() }
        AppSettings.THEME_COLOR.observe(this) { applyVisualTheme() }
        AppSettings.MATERIAL_DESIGN_3.observe(this) { applyVisualTheme() }
    }

    private fun applyVisualTheme() {
        val palette = OpenManagerPalette.from(this)
        window.statusBarColor = palette.appBarBg
        window.navigationBarColor = palette.screenBg
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = palette.isLightStatusBar
            isAppearanceLightNavigationBars = palette.isLightStatusBar
        }

        binding.rootLayout.setBackgroundColor(palette.screenBg)
        binding.appBarLayout.setBackgroundColor(palette.appBarBg)
        binding.toolbar.setBackgroundColor(palette.appBarBg)
        binding.toolbar.setTitleTextColor(palette.textPrimary)
        binding.toolbar.navigationIcon?.mutate()?.setTint(palette.iconTint)
        binding.toolbar.overflowIcon?.mutate()?.setTint(palette.iconTint)

        val tabAccent = if (palette.isDark && !palette.isAmoled) {
            Color.parseColor("#00E676")
        } else {
            palette.accentColor
        }
        binding.cardRamMonitor.setBackgroundColor(palette.cardBg)
        binding.tvRamUsageTitle.setTextColor(palette.textPrimary)
        binding.tvRamAvailable.setTextColor(tabAccent)
        binding.pbRamUsage.progressTintList = ColorStateList.valueOf(tabAccent)
        binding.pbRamUsage.progressBackgroundTintList = ColorStateList.valueOf(palette.dividerLineColor)
        binding.tvProcessCounters.setTextColor(palette.textSecondary)

        binding.tabLayout.setBackgroundColor(palette.appBarBg)
        binding.tabLayout.setSelectedTabIndicatorColor(tabAccent)
        binding.tabLayout.setTabTextColors(palette.textSecondary, tabAccent)
        binding.tvEmptyState.setTextColor(palette.textSecondary)
        binding.progressBar.indeterminateTintList = ColorStateList.valueOf(tabAccent)
        binding.fabRefresh.backgroundTintList = ColorStateList.valueOf(tabAccent)
        binding.fabRefresh.imageTintList = ColorStateList.valueOf(
            if (palette.isDark && !palette.isAmoled) 0xFF121212.toInt() else palette.onAccentColor
        )

        if (::adapter.isInitialized) {
            adapter.palette = palette
        }
    }

    override fun onResume() {
        super.onResume()
        applyVisualTheme()
        startAutoRefresh()
    }

    override fun onPause() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
        super.onPause()
    }

    override fun onDestroy() {
        autoRefreshJob?.cancel()
        loadJob?.cancel()
        activeDetailsBinding = null
        activeDetailsPid = -1
        ShizukuManager.removeBinderListener(shizukuListener)
        super.onDestroy()
    }

    private fun startAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = lifecycleScope.launch {
            while (isActive) {
                delay(AUTO_REFRESH_INTERVAL_MS)
                if (!isUiAlive) break
                if (!isUserScrolling && !adapter.isPopupMenuOpen) {
                    loadProcesses(isAutoRefresh = true)
                }
            }
        }
    }

    private fun setupTabs() {
        binding.tabLayout.tabMode = TabLayout.MODE_FIXED
        binding.tabLayout.tabGravity = TabLayout.GRAVITY_FILL
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.running_apps_tab_user))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.running_apps_tab_system))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.om_running_apps_tab_exec))
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
            onItemClick = { item -> showProcessDetails(item) },
            onKillClick = { item -> killProcess(item) },
            onForceStopClick = { item -> forceStopProcess(item) }
        )
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@RunningAppsActivity)
            setHasFixedSize(true)
            setItemViewCacheSize(30)
            (itemAnimator as? DefaultItemAnimator)?.supportsChangeAnimations = false
            adapter = this@RunningAppsActivity.adapter
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    isUserScrolling = newState != RecyclerView.SCROLL_STATE_IDLE
                    if (!isUserScrolling && hasPendingUiUpdate && !this@RunningAppsActivity.adapter.isPopupMenuOpen) {
                        hasPendingUiUpdate = false
                        filterAndSortProcesses()
                    }
                }
            })
            ThemedFastScroller.create(this)
        }
    }

    private fun setupElevationControls() {
        binding.btnBannerRequestRoot.setOnClickListener {
            requestRootPrivileges(interactive = true)
        }
        binding.btnBannerRequestShizuku.setOnClickListener {
            requestShizukuPrivileges()
        }
        binding.btnDismissBanner.setOnClickListener {
            isBannerDismissed = true
            getSharedPreferences("running_apps_prefs", MODE_PRIVATE)
                .edit()
                .putBoolean("hide_permission_banner", true)
                .apply()
            binding.cardPermissionBanner.visibility = View.GONE
        }
        binding.tvElevationBadge.setOnClickListener {
            showElevationChoiceDialog()
        }
    }

    private fun updateElevationUi() {
        val isRoot = RunningAppsLoader.isRootGranted()
        val isShizuku = RunningAppsLoader.isShizukuReady()

        when {
            isRoot -> {
                binding.tvElevationBadge.text = "🛡️ " + getString(R.string.running_apps_elevation_root)
                binding.tvElevationBadge.setTextColor(Color.parseColor("#00E676"))
                binding.tvElevationBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1B3B2B"))
                binding.cardPermissionBanner.visibility = View.GONE
            }
            isShizuku -> {
                binding.tvElevationBadge.text = "⚡ " + getString(R.string.running_apps_elevation_shizuku)
                binding.tvElevationBadge.setTextColor(Color.parseColor("#40C4FF"))
                binding.tvElevationBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#132D3F"))
                binding.cardPermissionBanner.visibility = View.GONE
            }
            else -> {
                binding.tvElevationBadge.text = "⚠️ " + getString(R.string.running_apps_elevation_unprivileged)
                binding.tvElevationBadge.setTextColor(Color.parseColor("#FFB300"))
                binding.tvElevationBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#33291A"))
                if (!isBannerDismissed) {
                    binding.cardPermissionBanner.visibility = View.VISIBLE
                } else {
                    binding.cardPermissionBanner.visibility = View.GONE
                }
            }
        }
    }

    private fun checkAndPromptElevatedPermissions() {
        updateElevationUi()
        if (RunningAppsLoader.hasElevatedAccess()) return

        // Silently check root if su is available; only auto-prompt Shizuku if banner wasn't dismissed
        if (LibSuFileServiceLauncher.isSuAvailable()) {
            requestRootPrivileges(interactive = false)
        } else if (!isBannerDismissed && ShizukuManager.isAvailable() && !ShizukuManager.hasPermission()) {
            requestShizukuPrivileges()
        }
    }

    private fun requestRootPrivileges(interactive: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                LibSuFileServiceLauncher.initBuilder()
                val result = Shell.cmd("id").exec()
                val isRoot = result.isSuccess && result.out.any { it.contains("uid=0") }
                withContext(Dispatchers.Main) {
                    if (isRoot) {
                        if (interactive) {
                            Toast.makeText(this@RunningAppsActivity, R.string.running_apps_root_granted_toast, Toast.LENGTH_SHORT).show()
                        }
                        updateElevationUi()
                        loadProcesses()
                    } else if (interactive) {
                        Toast.makeText(this@RunningAppsActivity, R.string.running_apps_root_failed_toast, Toast.LENGTH_SHORT).show()
                        updateElevationUi()
                    }
                }
            } catch (_: Throwable) {
                withContext(Dispatchers.Main) {
                    if (interactive) {
                        Toast.makeText(this@RunningAppsActivity, R.string.running_apps_root_failed_toast, Toast.LENGTH_SHORT).show()
                    }
                    updateElevationUi()
                }
            }
        }
    }

    private fun requestShizukuPrivileges() {
        if (!ShizukuManager.isInstalled(this)) {
            if (!isUiAlive) return
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.om_shizuku_not_installed_title)
                .setMessage(R.string.om_shizuku_not_installed_msg)
                .setPositiveButton(R.string.om_shizuku_view_btn) { _, _ ->
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/")))
                    } catch (_: Throwable) {}
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .bindToLifecycle(this)
                .show()
            return
        }

        if (!ShizukuManager.isAvailable()) {
            Toast.makeText(this, R.string.om_shizuku_service_not_running, Toast.LENGTH_LONG).show()
            return
        }

        ShizukuManager.requestPermission(this) { granted ->
            if (granted) {
                Toast.makeText(this, R.string.running_apps_shizuku_granted_toast, Toast.LENGTH_SHORT).show()
                updateElevationUi()
                loadProcesses()
            } else {
                Toast.makeText(this, R.string.om_shizuku_permission_denied, Toast.LENGTH_SHORT).show()
                updateElevationUi()
            }
        }
    }

    private fun showElevationChoiceDialog() {
        val isRoot = RunningAppsLoader.isRootGranted()
        val isShizuku = RunningAppsLoader.isShizukuReady()
        val statusText = when {
            isRoot -> getString(R.string.om_proc_elev_root_active)
            isShizuku -> getString(R.string.om_proc_elev_shizuku_active)
            else -> getString(R.string.om_proc_elev_standard)
        }

        if (!isUiAlive) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.om_proc_elev_title)
            .setMessage(getString(R.string.om_proc_elev_msg_format, statusText))
            .setPositiveButton(R.string.om_proc_req_root) { _, _ ->
                requestRootPrivileges(interactive = true)
            }
            .setNeutralButton(R.string.om_proc_req_shizuku) { _, _ ->
                requestShizukuPrivileges()
            }
            .setNegativeButton(R.string.close, null)
            .create()
            .bindToLifecycle(this)
            .show()
    }

    private fun loadProcesses(isAutoRefresh: Boolean = false) {
        if (isAutoRefresh && isLoading.get()) return
        if (!isAutoRefresh) {
            loadJob?.cancel()
            binding.progressBar.visibility = View.VISIBLE
            binding.tvEmptyState.visibility = View.GONE
        }
        val token = refreshGeneration.incrementAndGet()
        isLoading.set(true)

        loadJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val ram = RunningAppsLoader.getRamStats(this@RunningAppsActivity)
                val list = RunningAppsLoader.loadRunningProcesses(this@RunningAppsActivity)

                withContext(Dispatchers.Main) {
                    if (!isUiAlive || token != refreshGeneration.get()) return@withContext
                    updateElevationUi()

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
                    if (isAutoRefresh && (isUserScrolling || adapter.isPopupMenuOpen)) {
                        hasPendingUiUpdate = true
                    } else {
                        hasPendingUiUpdate = false
                        filterAndSortProcesses()
                    }

                    // Keep open Process Details Dialog metrics updated in real time
                    val openPid = activeDetailsPid
                    val openBinding = activeDetailsBinding
                    if (openPid > 0 && openBinding != null) {
                        val updatedItem = allProcesses.find { it.pid == openPid }
                        if (updatedItem != null) {
                            bindProcessDetailsMetrics(openBinding, updatedItem)
                        }
                    }

                    binding.progressBar.visibility = View.GONE
                }
            } finally {
                withContext(NonCancellable) {
                    if (token == refreshGeneration.get()) {
                        isLoading.set(false)
                    }
                }
            }
        }
    }

    private fun updateCounters() {
        val total = allProcesses.size
        var userCount = 0
        var sysCount = 0
        var shCount = 0
        for (p in allProcesses) {
            if (p.isApp && !p.isSystem) userCount++ else sysCount++
            if (p.isShellScript) shCount++
        }

        binding.tvProcessCounters.text = getString(R.string.om_proc_counters_format, total, userCount)
        binding.tabLayout.getTabAt(0)?.text = "${getString(R.string.running_apps_tab_user)} ($userCount)"
        binding.tabLayout.getTabAt(1)?.text = "${getString(R.string.running_apps_tab_system)} ($sysCount)"
        binding.tabLayout.getTabAt(2)?.text = "${getString(R.string.om_running_apps_tab_exec)} ($shCount)"
        binding.tabLayout.getTabAt(3)?.text = "${getString(R.string.running_apps_tab_all)} ($total)"
    }

    private fun filterAndSortProcesses() {
        displayedProcesses.clear()
        val query = searchQuery.trim().lowercase()

        for (p in allProcesses) {
            val matchesTab = when (currentTab) {
                0 -> p.isApp && !p.isSystem
                1 -> p.isSystem
                2 -> p.isShellScript
                else -> true
            }
            if (!matchesTab) continue

            if (query.isNotEmpty()) {
                val matchesQuery = p.label.lowercase().contains(query) ||
                    p.processName.lowercase().contains(query) ||
                    (p.packageName?.lowercase()?.contains(query) == true) ||
                    p.cmdline.lowercase().contains(query) ||
                    p.scriptPath.lowercase().contains(query) ||
                    p.elfArch.lowercase().contains(query) ||
                    p.pid.toString().contains(query)
                if (!matchesQuery) continue
            }

            displayedProcesses.add(p)
        }

        when (currentSortOrder) {
            0 -> displayedProcesses.sortByDescending { it.memoryBytes }
            1 -> displayedProcesses.sortWith(
                compareByDescending<RunningAppModel> { it.cpuPercent }
                    .thenByDescending { it.memoryBytes }
            )
            2 -> displayedProcesses.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            3 -> displayedProcesses.sortBy { it.pid }
        }

        adapter.submitList(displayedProcesses.toList())
        binding.tvEmptyState.visibility = if (displayedProcesses.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun bindProcessDetailsMetrics(
        dialogBinding: DialogProcessDetailsBinding,
        item: RunningAppModel
    ): String {
        dialogBinding.dialogProcessLabel.text = item.label
        dialogBinding.dialogProcessName.text = item.processName
        val typeLabel = when (item.executableType) {
            ExecutableType.APP -> getString(R.string.om_proc_type_app)
            ExecutableType.SHELL_SCRIPT -> getString(R.string.om_running_apps_type_sh)
            ExecutableType.ELF_64 -> {
                val base = getString(R.string.om_running_apps_type_elf64)
                if (item.elfArch.isNotEmpty()) "$base (${item.elfArch})" else base
            }
            ExecutableType.ELF_32 -> {
                val base = getString(R.string.om_running_apps_type_elf32)
                if (item.elfArch.isNotEmpty()) "$base (${item.elfArch})" else base
            }
            ExecutableType.ELF_GENERIC -> getString(R.string.om_running_apps_type_elf)
            ExecutableType.SYSTEM_DAEMON -> getString(R.string.om_running_apps_type_daemon)
        }
        val cpuStr = getString(R.string.om_running_apps_cpu_format, item.cpuPercent)
        dialogBinding.dialogProcessState.text = "${getString(R.string.om_proc_state_format, item.state, typeLabel)} • $cpuStr"
        dialogBinding.dialogTvPidPpid.text = "${item.pid} / ${item.ppid}"
        val memStr = if (item.memoryBytes > 0) Formatter.formatFileSize(this, item.memoryBytes) else getString(R.string.unknown)
        dialogBinding.dialogTvMemory.text = "$memStr (VmRSS)"
        dialogBinding.dialogTvUidUser.text = "${item.uid} (${item.user})"
        dialogBinding.dialogTvThreads.text = getString(R.string.om_proc_threads_format, item.threadCount)
        dialogBinding.dialogTvSelinux.text = if (item.selinuxContext.isNotEmpty()) item.selinuxContext else "u:r:default:s0"
        dialogBinding.dialogTvCmdline.text = if (item.cmdline.isNotEmpty()) item.cmdline else item.processName

        val hasScriptPath = item.isShellScript || item.scriptPath.isNotEmpty()
        if (hasScriptPath) {
            val resolvedDisplay = item.scriptPath.ifBlank { item.cmdline.ifBlank { item.processName } }
            dialogBinding.rowScriptPath.visibility = View.VISIBLE
            dialogBinding.dialogTvScriptPath.text = resolvedDisplay
        } else {
            dialogBinding.rowScriptPath.visibility = View.GONE
        }

        val rawFsPath = item.scriptPath.substringBefore(" (").trim()
        dialogBinding.btnDialogLocateScript.visibility = if (rawFsPath.startsWith("/")) {
            View.VISIBLE
        } else {
            View.GONE
        }
        return memStr
    }

    private fun showProcessDetails(item: RunningAppModel) {
        if (!isUiAlive) return
        val dialogBinding = DialogProcessDetailsBinding.inflate(layoutInflater)
        val memStr = bindProcessDetailsMetrics(dialogBinding, item)

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
            dialogBinding.dialogProcessIcon.setImageResource(RunningAppsAdapter.iconResForProcess(item))
            dialogBinding.btnDialogForceStop.visibility = View.GONE
            dialogBinding.btnDialogAppDetails.visibility = View.GONE
            dialogBinding.btnDialogAppSettings.visibility = View.GONE
            dialogBinding.btnDialogLaunchApp.visibility = View.GONE
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .create()
            .bindToLifecycle(this)

        activeDetailsPid = item.pid
        activeDetailsBinding = dialogBinding
        dialog.setOnDismissListener {
            if (activeDetailsPid == item.pid) {
                activeDetailsPid = -1
                activeDetailsBinding = null
            }
        }

        // 0. Tap script path to copy
        val copyScriptPathListener = View.OnClickListener {
            val currentItem = allProcesses.find { it.pid == item.pid } ?: item
            val pathToCopy = currentItem.scriptPath.ifBlank { currentItem.cmdline.ifBlank { currentItem.processName } }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("Script Path", pathToCopy))
            Toast.makeText(this, R.string.om_copied_to_clipboard, Toast.LENGTH_SHORT).show()
        }
        dialogBinding.rowScriptPath.setOnClickListener(copyScriptPathListener)
        dialogBinding.dialogTvScriptPath.setOnClickListener(copyScriptPathListener)

        // Locate script directory in explorer
        dialogBinding.btnDialogLocateScript.setOnClickListener {
            val currentItem = allProcesses.find { it.pid == item.pid } ?: item
            val rawPath = currentItem.scriptPath.substringBefore(" (").trim()
            val dirPath = try {
                val f = File(rawPath)
                if (f.isDirectory) f.absolutePath else (f.parentFile?.absolutePath ?: rawPath)
            } catch (_: Throwable) {
                rawPath
            }
            dialog.dismiss()
            if (dirPath.startsWith("/")) {
                try {
                    startActivity(FileListActivity.createViewIntent(Paths.get(dirPath)))
                } catch (_: Throwable) {
                    Toast.makeText(this, dirPath, Toast.LENGTH_SHORT).show()
                }
            }
        }

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
                    Toast.makeText(this, R.string.om_app_no_launchable_ui, Toast.LENGTH_SHORT).show()
                }
            }
        }

        // 6. Copy process info
        dialogBinding.btnDialogCopyProcessInfo.setOnClickListener {
            dialog.dismiss()
            val currentItem = allProcesses.find { it.pid == item.pid } ?: item
            val scriptLine = if (currentItem.scriptPath.isNotEmpty()) "\nScript Path: ${currentItem.scriptPath}" else ""
            val text = """
                Label: ${currentItem.label}
                Process: ${currentItem.processName}
                Package: ${currentItem.packageName ?: "N/A"}
                PID: ${currentItem.pid}
                PPID: ${currentItem.ppid}
                UID: ${currentItem.uid}
                User: ${currentItem.user}
                RAM: $memStr
                CPU: ${String.format("%.1f%%", currentItem.cpuPercent)}
                State: ${currentItem.state}
                Threads: ${currentItem.threadCount}
                Cmdline: ${currentItem.cmdline}$scriptLine
            """.trimIndent()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("Process Info", text))
            Toast.makeText(this, R.string.om_proc_info_copied, Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    private fun killProcess(item: RunningAppModel) {
        if (!RunningAppsLoader.hasElevatedAccess() && (item.isSystem || item.uid != android.os.Process.myUid())) {
            if (!isUiAlive) return
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.om_proc_elevated_required_title)
                .setMessage(R.string.running_apps_kill_unprivileged_warning)
                .setPositiveButton(R.string.running_apps_action_request_root) { _, _ ->
                    requestRootPrivileges(interactive = true)
                }
                .setNeutralButton(R.string.running_apps_action_request_shizuku) { _, _ ->
                    requestShizukuPrivileges()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .bindToLifecycle(this)
                .show()
            return
        }

        if (item.isShellScript) {
            showKillScriptConfirmDialog(item)
            return
        }

        executeKillProcess(item)
    }

    private fun showKillScriptConfirmDialog(item: RunningAppModel) {
        if (!isUiAlive) return
        val scriptLocation = item.scriptPath.ifBlank { item.cmdline.ifBlank { item.processName } }
        var countdownJob: Job? = null

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.om_proc_kill_script_confirm_title)
            .setMessage(getString(R.string.om_proc_kill_script_confirm_msg, item.label, item.pid, scriptLocation))
            .setPositiveButton(getString(R.string.om_proc_kill_script_wait_fmt, 3)) { _, _ ->
                executeKillProcess(item)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .bindToLifecycle(this)

        dialog.setOnShowListener {
            val positiveBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveBtn.isEnabled = false
            positiveBtn.alpha = 0.5f
            countdownJob = lifecycleScope.launch {
                for (sec in 3 downTo 1) {
                    if (!isUiAlive || !dialog.isShowing) return@launch
                    positiveBtn.text = getString(R.string.om_proc_kill_script_wait_fmt, sec)
                    delay(1000L)
                }
                if (isUiAlive && dialog.isShowing) {
                    positiveBtn.text = getString(R.string.om_proc_kill_script_now)
                    positiveBtn.isEnabled = true
                    positiveBtn.alpha = 1.0f
                }
            }
        }
        dialog.setOnDismissListener {
            countdownJob?.cancel()
            countdownJob = null
        }
        dialog.show()
    }

    private fun executeKillProcess(item: RunningAppModel) {
        if (item.isShellScript && ScriptExecutionManager.isRunning() &&
            (item.pid == ScriptExecutionManager.activePid || item.ppid == ScriptExecutionManager.activePid)
        ) {
            ScriptExecutionManager.terminateShell(this)
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val success = RunningAppsLoader.killProcess(item.pid)
            withContext(Dispatchers.Main) {
                if (!isUiAlive) return@withContext
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
        if (!RunningAppsLoader.hasElevatedAccess()) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$pkg")
            }
            startActivity(intent)
            Toast.makeText(this@RunningAppsActivity, R.string.om_proc_use_force_stop_settings, Toast.LENGTH_SHORT).show()
            return
        }

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
                    Toast.makeText(this@RunningAppsActivity, R.string.om_proc_use_force_stop_settings, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_running_apps, menu)

        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem?.actionView as? SearchView
        searchView?.queryHint = getString(R.string.om_proc_search_hint)
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
            getString(R.string.om_running_apps_sort_cpu),
            getString(R.string.running_apps_sort_name),
            getString(R.string.running_apps_sort_pid)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.om_running_apps_sort_title)
            .setSingleChoiceItems(options, currentSortOrder) { dialog, which ->
                currentSortOrder = which
                filterAndSortProcesses()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .bindToLifecycle(this)
            .show()
    }

    companion object {
        private const val AUTO_REFRESH_INTERVAL_MS = 2500L
    }
}
