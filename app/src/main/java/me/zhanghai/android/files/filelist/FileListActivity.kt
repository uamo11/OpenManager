/*
 * OpenManager - Dual-Pane File Explorer (MT Manager Style)
 * Supports side-by-side Panel A & Panel B navigation with instant synchronization,
 * unified top toolbar, MT-style bottom action bar, MT-style navigation drawer, and 3-dots overflow popup.
 */

package me.zhanghai.android.files.filelist

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings as AndroidSettings
import android.text.format.Formatter
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContract
import androidx.annotation.DrawableRes
import androidx.appcompat.view.menu.MenuPopupHelper
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.ImageViewCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.provider.common.createDirectories
import me.zhanghai.android.files.provider.common.exists
import me.zhanghai.android.files.provider.common.isDirectory
import me.zhanghai.android.files.provider.common.isRegularFile
import java8.nio.file.Path
import java8.nio.file.Paths
import java.io.File
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.R
import me.zhanghai.android.files.about.AboutActivity
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.appmanager.InstalledAppsActivity
import me.zhanghai.android.files.appmanager.running.RunningAppsActivity
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.provider.archive.isArchivePath
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.provider.sftp.sftpAuthority
import me.zhanghai.android.files.file.createFallbackFileItem
import me.zhanghai.android.files.ftpserver.FtpServerActivity
import me.zhanghai.android.files.ftpserver.FtpServerService
import me.zhanghai.android.files.settings.PerformanceModeManager
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.settings.SettingsActivity
import me.zhanghai.android.files.theme.custom.OpenManagerPalette
import me.zhanghai.android.files.compat.getDescriptionCompat
import me.zhanghai.android.files.compat.isPrimaryCompat
import me.zhanghai.android.files.compat.pathCompat
import me.zhanghai.android.files.compat.uuidCompat
import me.zhanghai.android.files.storage.AddDocumentTreeActivity
import me.zhanghai.android.files.storage.AddStorageDialogActivity
import me.zhanghai.android.files.storage.FileSystemRoot
import me.zhanghai.android.files.storage.PrimaryStorageVolume
import me.zhanghai.android.files.storage.SecondaryStorageVolume
import me.zhanghai.android.files.storage.Storage
import me.zhanghai.android.files.storage.StorageVolumeListLiveData
import me.zhanghai.android.files.storage.Storages
import me.zhanghai.android.files.util.isMounted
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.valueCompat
import me.zhanghai.android.files.viewer.text.TextEditorActivity

class FileListActivity : AppActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var fragmentA: FileListFragment
    private lateinit var fragmentB: FileListFragment

    private lateinit var mainToolbar: Toolbar
    private lateinit var btnMainBack: ImageButton
    private lateinit var tvPathTitle: TextView
    private lateinit var tvStatsSubtitle: TextView
    private lateinit var btnMainMore: ImageButton
    private lateinit var etQuickFilter: EditText
    private lateinit var btnQuickFilterClose: ImageButton

    /** The panel being filtered and its folder: going to another folder closes the filter. */
    private var quickFilterTarget: FileListFragment? = null
    private var quickFilterPath: java8.nio.file.Path? = null

    private lateinit var containerA: FrameLayout
    private lateinit var containerB: FrameLayout
    private lateinit var paneDivider: View

    private lateinit var bottomBarNormal: LinearLayout
    private lateinit var bottomBarSelection: LinearLayout

    private lateinit var btnBottomBack: ImageButton
    private lateinit var btnBottomForward: ImageButton
    private lateinit var btnBottomAdd: ImageButton
    private lateinit var btnBottomSwap: ImageButton
    private lateinit var btnBottomHome: ImageButton

    private lateinit var btnSelectionClose: ImageButton
    private lateinit var btnCopyOpposite: ImageButton
    private lateinit var btnMoveOpposite: ImageButton
    private lateinit var btnDeleteSelection: ImageButton
    private lateinit var btnMoreSelection: ImageButton

    private lateinit var progressRootStorage: ProgressBar
    private lateinit var tvRootStorageStats: TextView
    private lateinit var progressInternalStorage: ProgressBar
    private lateinit var tvInternalStorageStats: TextView
    private lateinit var layoutCustomStorages: LinearLayout
    private var hasRequestedPermissionThisSession = false
    private var hasRequestedNotificationThisSession = false
    private var allFilesDialog: androidx.appcompat.app.AlertDialog? = null
    private var statusBarHeight: Int = 0
    /** The drawer header's own padding from the layout, read before any inset is added. */
    private val drawerHeaderBasePadding: IntArray by lazy {
        findViewById<View>(R.id.layoutDrawerHeader)?.let { intArrayOf(it.paddingLeft, it.paddingTop) } ?: intArrayOf(0, 0)
    }
    private var lastAppliedPalette: OpenManagerPalette? = null
    private var lastNavIconIsSelection: Boolean? = null
    private var lastNavIconTint: Int = 0
    private var isDiskStatsRefreshing = false

    private var activePanelIndex = 0 // 0 = Panel A, 1 = Panel B
    val sharedRecycledViewPool = androidx.recyclerview.widget.RecyclerView.RecycledViewPool().apply {
        val budget = PerformanceModeManager.currentBudget
        setMaxRecycledViews(0, budget.fileListPoolList)
        setMaxRecycledViews(1, budget.fileListPoolGrid)
        setMaxRecycledViews(2, budget.fileListPoolGrid)
    }

    private fun applyPerformanceModePoolSizing(enabled: Boolean) {
        val budget = PerformanceModeManager.budgetFor(enabled)
        sharedRecycledViewPool.setMaxRecycledViews(0, budget.fileListPoolList)
        sharedRecycledViewPool.setMaxRecycledViews(1, budget.fileListPoolGrid)
        sharedRecycledViewPool.setMaxRecycledViews(2, budget.fileListPoolGrid) // gallery
        if (enabled) {
            sharedRecycledViewPool.clear()
        }
    }

    val activeFragment: FileListFragment
        get() = if (activePanelIndex == 0) fragmentA else fragmentB

    fun getOppositeFragment(fragment: FileListFragment): FileListFragment =
        if (fragment == fragmentA) fragmentB else fragmentA

    private fun homeFor(fragment: FileListFragment): Path? {
        val current = try { fragment.currentPath } catch (_: Exception) { return null }
        me.zhanghai.android.files.terminal.remote.RemoteFilesLauncher.homeOf(current)?.let { return it }
        return if (isFragmentA(fragment)) {
            Settings.FILE_LIST_DEFAULT_DIRECTORY.valueCompat
        } else {
            Settings.FILE_LIST_DEFAULT_DIRECTORY_RIGHT.valueCompat
        }
    }

    /** The phone folder a panel goes back to when it leaves a VPS. */
    private fun localHomeFor(fragment: FileListFragment): Path {
        val configured = if (isFragmentA(fragment)) {
            Settings.FILE_LIST_DEFAULT_DIRECTORY.valueCompat
        } else {
            Settings.FILE_LIST_DEFAULT_DIRECTORY_RIGHT.valueCompat
        }
        return if (configured.sftpAuthority == null) configured else Paths.get("/storage/emulated/0")
    }

    /**
     * Leaves the VPS shown in [fragment] for the phone's storage; the server's file connection
     * and cached listings are released as soon as no panel shows it (see releaseRemoteIfUnused).
     */
    fun leaveRemote(fragment: FileListFragment) {
        fragment.navigateTo(localHomeFor(fragment))
    }

    /** Closes the file connection to [authority] when neither panel is on that server. */
    fun releaseRemoteIfUnused(authority: me.zhanghai.android.files.provider.sftp.client.Authority) {
        if (isFinishing && !isChangingConfigurations) return
        val inUse = listOfNotNull(
            if (::fragmentA.isInitialized) fragmentA else null,
            if (::fragmentB.isInitialized) fragmentB else null
        ).any { runCatching { it.currentPath.sftpAuthority == authority }.getOrDefault(false) }
        if (!inUse) me.zhanghai.android.files.provider.sftp.SftpSessions.release(authority)
    }

    fun isFragmentA(fragment: FileListFragment): Boolean =
        (::fragmentA.isInitialized && fragment === fragmentA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.file_list_activity)
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        statusBarHeight = if (resId > 0) resources.getDimensionPixelSize(resId) else (28 * resources.displayMetrics.density).toInt()
        val navResId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        if (navResId > 0) {
            systemNavBarBottom = resources.getDimensionPixelSize(navResId)
        }
        findViewById<View>(R.id.activityRoot)?.updatePadding(top = statusBarHeight)
        findViewById<View>(R.id.layoutDrawerHeader)?.let {
            it.updatePadding(top = drawerHeaderBasePadding[1] + statusBarHeight)
        }

        if (!PerformanceModeManager.isEnabled || Settings.REQUEST_SHELL_PERMISSION.valueCompat) {
            me.zhanghai.android.files.provider.root.ShizukuManager.init(this)
        }

        drawerLayout = findViewById(R.id.drawerLayout)
        // Permanently lock closed swipe gestures so swiping from screen edge never triggers the drawer (MT Manager style)
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
                updateDrawerStats()
                refreshCustomStoragesList()
            }
            override fun onDrawerClosed(drawerView: View) {
                drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
            }
        })
        mainToolbar = findViewById(R.id.mainToolbar)
        mainToolbar.setNavigationIcon(R.drawable.menu_icon_control_normal_24dp)
        mainToolbar.navigationIcon?.mutate()?.setTint(OpenManagerPalette.from(this).iconTint)
        mainToolbar.setNavigationOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        btnMainBack = findViewById(R.id.btnMainBack)
        btnMainBack.setOnClickListener {
            activeFragment.navigateUp()
        }

        btnMainMore = findViewById(R.id.btnMainMore)
        btnMainMore.setOnClickListener {
            showMTMoreMenu(it)
        }

        tvPathTitle = findViewById(R.id.tvPathTitle)
        tvStatsSubtitle = findViewById(R.id.tvStatsSubtitle)
        etQuickFilter = findViewById(R.id.etQuickFilter)
        btnQuickFilterClose = findViewById(R.id.btnQuickFilterClose)
        btnQuickFilterClose.setOnClickListener { closeQuickFilter() }
        etQuickFilter.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                quickFilterTarget?.quickFilter = s?.toString().orEmpty()
            }
        })
        etQuickFilter.setOnEditorActionListener { v, _, _ ->
            // Done hides the keyboard and keeps the filter, to browse the filtered list.
            (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                ?.hideSoftInputFromWindow(v.windowToken, 0)
            true
        }
        findViewById<View>(R.id.layoutPathTitle)?.let { layout ->
            layout.setOnClickListener {
                showJumpToPathDialog()
            }
            layout.setOnLongClickListener {
                try {
                    val fullPath = activeFragment.currentPath.toDisplayPath(trailingSlash = true)
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("path", fullPath))
                    android.widget.Toast.makeText(this, getString(R.string.om_path_copied_format, fullPath), android.widget.Toast.LENGTH_SHORT).show()
                    true
                } catch (e: Exception) {
                    false
                }
            }
        }

        containerA = findViewById(R.id.containerPanelA)
        containerB = findViewById(R.id.containerPanelB)
        paneDivider = findViewById(R.id.paneDivider)

        containerA.setOnClickListener { onPanelFocused(fragmentA) }
        containerB.setOnClickListener { onPanelFocused(fragmentB) }

        bottomBarNormal = findViewById(R.id.bottomBarNormal)
        bottomBarSelection = findViewById(R.id.bottomBarSelection)

        btnBottomBack = findViewById(R.id.btnBottomBack)
        btnBottomForward = findViewById(R.id.btnBottomForward)
        btnBottomAdd = findViewById(R.id.btnBottomAdd)
        btnBottomSwap = findViewById(R.id.btnBottomSwap)
        btnBottomHome = findViewById(R.id.btnBottomHome)

        btnSelectionClose = findViewById(R.id.btnSelectionClose)
        btnCopyOpposite = findViewById(R.id.btnCopyOpposite)
        btnMoveOpposite = findViewById(R.id.btnMoveOpposite)
        btnDeleteSelection = findViewById(R.id.btnDeleteSelection)
        btnMoreSelection = findViewById(R.id.btnMoreSelection)

        if (savedInstanceState == null) {
            val intentA = Intent(intent)
            if (intentA.extraPath == null) {
                intentA.extraPath = Settings.FILE_LIST_DEFAULT_DIRECTORY.valueCompat
            }
            fragmentA = FileListFragment().putArgs(FileListFragment.Args(intentA))

            val intentB = Intent().apply {
                extraPath = Settings.FILE_LIST_DEFAULT_DIRECTORY_RIGHT.valueCompat
            }
            fragmentB = FileListFragment().putArgs(FileListFragment.Args(intentB))
            intent.putExtra(EXTRA_HANDLED_ON_CREATE, true)

            supportFragmentManager.commit {
                add(R.id.containerPanelA, fragmentA, TAG_PANEL_A)
                add(R.id.containerPanelB, fragmentB, TAG_PANEL_B)
            }
        } else {
            fragmentA = supportFragmentManager.findFragmentByTag(TAG_PANEL_A) as FileListFragment
            fragmentB = supportFragmentManager.findFragmentByTag(TAG_PANEL_B) as FileListFragment
            activePanelIndex = savedInstanceState.getInt(KEY_ACTIVE_PANEL, 0)
        }

        if (!Settings.MOVE_TO_RECYCLE_BIN_ON_DELETE.valueCompat && Settings.ENABLE_RECYCLE_BIN.valueCompat) {
            Settings.ENABLE_RECYCLE_BIN.putValue(false)
        }

        if (Settings.AUTO_START_SERVICES.valueCompat == "1") {
            try {
                FtpServerService.start(this)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (Settings.REQUEST_ROOT_PERMISSION.valueCompat) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    me.zhanghai.android.files.provider.root.LibSuFileServiceLauncher.initBuilder()
                    com.topjohnwu.superuser.Shell.getShell()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } else if (Settings.REQUEST_SHELL_PERMISSION.valueCompat) {
            if (me.zhanghai.android.files.provider.root.ShizukuManager.isAvailable() &&
                !me.zhanghai.android.files.provider.root.ShizukuManager.hasPermission()
            ) {
                me.zhanghai.android.files.provider.root.ShizukuManager.requestPermission(this) {
                    me.zhanghai.android.files.provider.root.SuperuserDetector.cachedStatus = null
                }
            }
        }

        setupDrawerControls()
        setupBottomBarControls()
        setupBackPressedHandler()
        checkAutoCleanRecycleBin()

        Settings.VISUAL_PERFORMANCE_MODE.observe(this) { applyPerformanceModePoolSizing(it) }

        if (BuildConfig.DEBUG) {
            // Test hooks only: any app could send this broadcast.
            val demoFilter = IntentFilter("me.zhanghai.android.files.action.DEMO")
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                demoReceiver,
                demoFilter,
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED
            )
        }
    }

    override fun onStart() {
        super.onStart()
        // Every time the app is opened, also when Android kept it in memory, and in Ultra RAM mode
        // too: the check is one small request after the first frame.
        me.zhanghai.android.files.update.UpdateChecker.checkOnForeground(this)
    }

    override fun onStop() {
        super.onStop()
        if (PerformanceModeManager.isEnabled) {
            sharedRecycledViewPool.clear()
            PerformanceModeManager.trimTransientCaches(aggressive = false)
        }
    }

    override fun onDestroy() {
        // Leaving the explorer: its VPS file connections go too (a terminal keeps its own SSH).
        if (isFinishing && !isChangingConfigurations) {
            listOfNotNull(
                if (::fragmentA.isInitialized) fragmentA else null,
                if (::fragmentB.isInitialized) fragmentB else null
            ).mapNotNull { runCatching { it.currentPath.sftpAuthority }.getOrNull() }
                .distinct()
                .forEach { me.zhanghai.android.files.provider.sftp.SftpSessions.release(it) }
        }
        super.onDestroy()
        try {
            unregisterReceiver(demoReceiver)
        } catch (ignored: Exception) {}
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        applyVisualTheme()
        observeVisualThemeSettings()
        updateTopBarInfo()
        syncBottomBarSelectionState()
        observePanelPathsAndStats()
        updateDrawerStats()
        updateMainToolbarNavigationIcon()
        handleDemoIntent(intent)
        handleApktoolIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        applyVisualTheme()
        checkAndRequestStoragePermissions()
        refreshRootStatusOnResume()
        updateDrawerStats()
        handleDemoIntent(intent)
        handleApktoolIntent(intent)
    }

    private fun refreshRootStatusOnResume() {
        if (!Settings.REQUEST_ROOT_PERMISSION.valueCompat) return
        lifecycleScope.launch(Dispatchers.IO) {
            val wasGranted = me.zhanghai.android.files.provider.root.SuperuserDetector.cachedStatus?.isRootGranted == true
            me.zhanghai.android.files.provider.root.SuperuserDetector.invalidateCache()
            val status = me.zhanghai.android.files.provider.root.SuperuserDetector.detect(this@FileListActivity)
            if (status.isRootGranted && !wasGranted) {
                withContext(Dispatchers.Main) {
                    updateDrawerStats()
                    val path = activeFragment.currentPath.toString()
                    if (path == "/" || path.startsWith("/data") || path.startsWith("/system")) {
                        activeFragment.reload()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDemoIntent(intent)
        handleApktoolIntent(intent)
    }

    private val demoReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.getStringExtra("action") ?: return
            android.util.Log.i("DemoReceiver", "Received demo action: $action")
            handleDemoAction(action)
        }
    }

    private fun handleApktoolIntent(intent: Intent?) {
        if (intent == null) return

        if (intent.getBooleanExtra(EXTRA_SHOW_APKTOOL_PROGRESS, false)) {
            intent.removeExtra(EXTRA_SHOW_APKTOOL_PROGRESS)
            if (ApktoolExecutionService.isRunning()) {
                ApktoolHelper.showProgressDialog(this)
            }
        }

        val highlightFile = intent.getStringExtra(EXTRA_HIGHLIGHT_FILE_PATH)?.also {
            intent.removeExtra(EXTRA_HIGHLIGHT_FILE_PATH)
        }
        val alreadyHandled = intent.getBooleanExtra(EXTRA_HANDLED_ON_CREATE, false)
        if (alreadyHandled) {
            intent.removeExtra(EXTRA_NAVIGATE_TO_PATH)
            return
        }

        val navigatePath = intent.getStringExtra(EXTRA_NAVIGATE_TO_PATH)
        if (!navigatePath.isNullOrBlank()) {
            intent.removeExtra(EXTRA_NAVIGATE_TO_PATH)
            intent.putExtra(EXTRA_HANDLED_ON_CREATE, true)
            try {
                val path = Paths.get(navigatePath)
                if (this::fragmentA.isInitialized) {
                    activeFragment.navigateToAndHighlight(path, highlightFile)
                }
            } catch (ignored: Throwable) {}
            return
        }

        if (intent.action == Intent.ACTION_VIEW) {
            val viewPath = intent.extraPath
            if (viewPath != null && this::fragmentA.isInitialized) {
                intent.putExtra(EXTRA_HANDLED_ON_CREATE, true)
                val resolvedDir = if (!viewPath.isArchivePath && viewPath.isLinuxPath && viewPath.toFile().isFile) {
                    viewPath.parent ?: viewPath
                } else {
                    viewPath
                }
                val targetHighlight = highlightFile ?: if (resolvedDir != viewPath) viewPath.toString() else null
                activeFragment.navigateToAndHighlight(resolvedDir, targetHighlight)
            }
        }
    }

    /**
     * Debug-only fixtures for the CI end-to-end test (tools/remote-terminal-e2e.sh): the emulator
     * reaches the runner's OpenSSH (2222) and Telnet (2323) test servers through 10.0.2.2.
     */
    private fun handleRemoteTerminalDemo(action: String) {
        if (!BuildConfig.DEBUG) return
        val store = me.zhanghai.android.files.terminal.remote.RemoteProfileStore
        val profiles = me.zhanghai.android.files.terminal.remote.ConnectionsActivity
        val ssh = me.zhanghai.android.files.terminal.remote.RemoteProfile(
            id = "ci-ssh",
            name = "CI OpenSSH",
            protocol = me.zhanghai.android.files.terminal.remote.RemoteProtocol.SSH,
            host = "10.0.2.2",
            port = 2222,
            username = "omtest",
            password = "omtest-Pass1",
            startupCommands = "echo OM_SSH_OK_\$((6*7)) > /tmp/om_ssh_probe\n" +
                "printf '\\033[1;31mred \\033[1;32mgreen \\033[1;34mblue \\033[38;5;208m256-orange \\033[38;2;255;0;255mtruecolor\\033[0m\\n'\n" +
                "ls --color=always /\n" +
                // Same OSC 0 title sequence a stock bash prompt sends ("user@host: dir").
                "printf '\\033]0;omtest@ci: ~/probe\\007'",
            shortcuts = listOf(
                me.zhanghai.android.files.terminal.remote.RemoteShortcut(
                    "probe", "echo OM_SHORTCUT_OK > /tmp/om_shortcut_probe"
                ),
                me.zhanghai.android.files.terminal.remote.RemoteShortcut("tmux", "tmux new -A -s main"),
                me.zhanghai.android.files.terminal.remote.RemoteShortcut("htop", "htop")
            )
        )
        val telnet = me.zhanghai.android.files.terminal.remote.RemoteProfile(
            id = "ci-telnet",
            name = "CI Telnet",
            protocol = me.zhanghai.android.files.terminal.remote.RemoteProtocol.TELNET,
            host = "10.0.2.2",
            port = 2323,
            username = "omtest",
            password = "omtest-Pass1",
            startupCommands = "echo OM_TELNET_OK_\$((6*7)) > /tmp/om_telnet_probe"
        )
        // Like a typical VPS: root (uid 0), a home with only hidden files, and a server that
        // takes the password only through keyboard-interactive (stock Ubuntu cloud images).
        val vpsRoot = me.zhanghai.android.files.terminal.remote.RemoteProfile(
            id = "ci-vps-root",
            name = "My VPS",
            protocol = me.zhanghai.android.files.terminal.remote.RemoteProtocol.SSH,
            host = "10.0.2.2",
            port = 2222,
            username = "vpsroot",
            password = "Root-Pass1",
            startupCommands = "printf '\\033]0;vpsroot@vps: ~\\007'"
        )
        if (store.get(this, ssh.id) == null) store.save(this, ssh)
        if (store.get(this, telnet.id) == null) store.save(this, telnet)
        if (store.get(this, vpsRoot.id) == null) store.save(this, vpsRoot)
        when (action) {
            "remote_profiles" -> startActivity(profiles.createIntent(this))
            "remote_add_sheet" -> profiles.showAddSheet(this)
            "remote_ssh" -> profiles.connect(this, store.get(this, ssh.id)!!)
            "remote_telnet" -> profiles.connect(this, store.get(this, telnet.id)!!)
            "remote_vps_root" -> profiles.connect(this, store.get(this, vpsRoot.id)!!)
            "remote_chooser" -> me.zhanghai.android.files.terminal.remote.TerminalLaunchChooser.show(this) {
                activeFragment.openTerminal()
            }
            "remote_edit" -> startActivity(
                me.zhanghai.android.files.terminal.remote.RemoteProfileEditActivity.createIntent(this, ssh.id)
            )
        }
    }

    /** Debug-only fixtures for the command guard E2E test (tools/remote-terminal-e2e.sh). */
    private fun handleToolsDemo(action: String) {
        if (!BuildConfig.DEBUG) return
        when (action) {
            "batch_rename" -> {
                val dir = java.io.File("/sdcard/om_batch_test").apply { deleteRecursively(); mkdirs() }
                listOf("IMG_003.jpg", "IMG_001.jpg", "IMG_002.jpg", "keep.txt").forEach {
                    java.io.File(dir, it).writeText(it)
                }
                fragmentA.navigateTo(java8.nio.file.Paths.get(dir.path))
                var tries = 0
                val open = object : Runnable {
                    override fun run() {
                        if (isFinishing || isDestroyed) return
                        val all = fragmentA.viewModel.fileListStateful.value.orEmpty()
                        val jpgs = all.filter { it.name.endsWith(".jpg") }
                        if (jpgs.size < 3 && tries++ < 20) {
                            window.decorView.postDelayed(this, 500)
                            return
                        }
                        BatchRenameDialog.show(this@FileListActivity, fragmentA.currentPath, jpgs, all.map { it.name }.toSet()) {
                            fragmentA.reload()
                        }
                    }
                }
                window.decorView.postDelayed(open, 1000)
            }
            "web_access" -> startActivity(
                Intent(this, me.zhanghai.android.files.webaccess.WebAccessActivity::class.java)
                    .putExtra(me.zhanghai.android.files.webaccess.WebAccessActivity.EXTRA_START, true)
            )
            "compare_files" -> {
                val dir = java.io.File("/sdcard/om_compare_test").apply { deleteRecursively(); mkdirs() }
                val lines = (1..50).map { "line $it" }
                java.io.File(dir, "a.txt").writeText(lines.joinToString("\n", postfix = "\n"))
                java.io.File(dir, "b.txt").writeText(
                    lines.map { if (it == "line 25") "line twenty-five" else it }.plus("extra").joinToString("\n", postfix = "\n")
                )
                startActivity(
                    FileCompareActivity.createIntent(
                        this, java8.nio.file.Paths.get("${dir.path}/a.txt"), java8.nio.file.Paths.get("${dir.path}/b.txt")
                    )
                )
            }
        }
    }

    private fun handleGuardDemo(action: String) {
        if (!BuildConfig.DEBUG) return
        val dir = java.io.File("/sdcard/om_guard_test").apply { mkdirs() }
        when (action) {
            "guard_script" -> {
                java.io.File(dir, "victim.txt").writeText("keep me unless allowed\n")
                val script = java.io.File(dir, "delete_victim.sh")
                script.writeText(
                    "#!/system/bin/sh\n" +
                        "rm /sdcard/om_guard_test/victim.txt\n" +
                        "echo \"rm exit=\$?\" > /sdcard/om_guard_test/script_ran.txt\n"
                )
                startActivity(
                    me.zhanghai.android.files.terminal.TerminalActivity.createIntent(
                        context = this,
                        path = dir.absolutePath,
                        command = "sh '${script.absolutePath}'",
                        activeScriptPath = script.absolutePath
                    )
                )
            }
            "multi_sessions" -> {
                // Two scripts started one after the other must both keep running.
                fun launch(name: String) {
                    val script = java.io.File(dir, "$name.sh")
                    script.writeText(
                        "#!/system/bin/sh\n" +
                            "i=0; while [ \$i -lt 25 ]; do echo \$i >> /sdcard/om_guard_test/$name.log; i=\$((i+1)); sleep 1; done\n"
                    )
                    startActivity(
                        me.zhanghai.android.files.terminal.TerminalActivity.createIntent(
                            context = this,
                            path = dir.absolutePath,
                            command = "sh '${script.absolutePath}'",
                            activeScriptPath = script.absolutePath
                        )
                    )
                }
                launch("multi_a")
                window.decorView.postDelayed({ launch("multi_b") }, 2500)
            }
            "guard_deadman" -> startActivity(
                me.zhanghai.android.files.terminal.TerminalActivity.createIntent(
                    context = this,
                    path = dir.absolutePath,
                    // Relative path from "/": only path resolution can tell this is /system. The
                    // emulator app has no root, so the debug build lets the guard treat it as root.
                    command = "export OM_GUARD_SIM_ROOT=1; cd / && rm -rf system/om_fake_dir; " +
                        "echo \"rm exit=\$?\" > /sdcard/om_guard_test/deadman_ran.txt"
                )
            )
            "guard_dfroot" -> startActivity(
                me.zhanghai.android.files.terminal.TerminalActivity.createIntent(
                    context = this,
                    path = dir.absolutePath,
                    command = "export OM_GUARD_SIM_ROOT=1; chmod 755 /system/bin/sh; " +
                        "echo \"chmod exit=\$?\" > /sdcard/om_guard_test/dfroot_ran.txt"
                )
            )
            "guard_mass" -> {
                // More files than the "massive" threshold, deleted by a script without root.
                Thread {
                    val mass = java.io.File(dir, "mass").apply { mkdirs() }
                    for (i in 0 until me.zhanghai.android.files.provider.root.CommandGuard.MASS_DELETE_THRESHOLD + 200) {
                        java.io.File(mass, "f$i.txt").writeText("x")
                    }
                    val script = java.io.File(dir, "wipe_mass.sh")
                    script.writeText(
                        "#!/system/bin/sh\n" +
                            "rm -rf /sdcard/om_guard_test/mass\n" +
                            "echo \"rm exit=\$?\" > /sdcard/om_guard_test/mass_ran.txt\n"
                    )
                    java.io.File(dir, "mass_ready.txt").writeText("ready")
                    runOnUiThread {
                        startActivity(
                            me.zhanghai.android.files.terminal.TerminalActivity.createIntent(
                                context = this,
                                path = dir.absolutePath,
                                command = "sh '${script.absolutePath}'",
                                activeScriptPath = script.absolutePath
                            )
                        )
                    }
                }.start()
            }
            "seatbelt_export" -> java.io.File(dir, me.zhanghai.android.files.terminal.RootSeatbelt.SCRIPT_NAME)
                .writeText(me.zhanghai.android.files.terminal.RootSeatbelt.SCRIPT)
        }
    }

    private fun handleDemoIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        val action = intent?.getStringExtra("SHOW_DEMO_DIALOG") ?: return
        intent.removeExtra("SHOW_DEMO_DIALOG")
        handleDemoAction(action)
    }

    fun handleDemoAction(action: String) {
        if (!BuildConfig.DEBUG) return
        android.util.Log.i("DemoReceiver", "Executing demo action: $action")
        window.decorView.postDelayed({
            try {
                when (action) {
                    "drawer" -> drawerLayout.openDrawer(GravityCompat.START)
                    "close_drawer" -> drawerLayout.closeDrawer(GravityCompat.START)
                    "more_menu" -> findViewById<View>(R.id.btnMainMore)?.performClick()
                    "create_choice" -> showCreateDialog()
                    "batch_rename", "compare_files", "web_access" -> handleToolsDemo(action)
                    "update_cooldown_expire" ->
                        androidx.preference.PreferenceManager.getDefaultSharedPreferences(this).edit()
                            .putLong(me.zhanghai.android.files.update.UpdateChecker.PREF_LAST_CHECK_TIME, 1L)
                            .commit()
                    "show_update_dialog" -> {
                        val mockAsset = me.zhanghai.android.files.update.ReleaseAsset(
                            name = "OpenManager-v1.9.61-release.apk",
                            size = 30541982L,
                            downloadUrl = "https://github.com/uamo11/OpenManager/releases"
                        )
                        val mockRelease = me.zhanghai.android.files.update.ReleaseInfo(
                            tagName = "v1.9.61",
                            version = "1.9.61",
                            name = "OpenManager v1.9.61",
                            body = "# Novedades v1.9.61:\n- Barra superior MT-style visible y correctamente separada de la barra de estado.\n- Diálogo de actualización rediseñado con botón completo e imposible de comprimir.",
                            htmlUrl = "https://github.com/uamo11/OpenManager/releases",
                            assets = listOf(mockAsset),
                            targetAsset = mockAsset,
                            isDebugChannel = false
                        )
                        me.zhanghai.android.files.update.UpdateChecker.showUpdateDialog(this, mockRelease)
                    }
                    "keystore" -> {
                        if (this::fragmentA.isInitialized) {
                            activeFragment.showKeystoreGeneratorDialog()
                        }
                    }
                    "apktool_hub" -> {
                        if (this::fragmentA.isInitialized) {
                            ApktoolHelper.showApktoolHubDialog(this, activeFragment.currentPath)
                        }
                    }
                    "apps" -> startActivity(Intent(this, InstalledAppsActivity::class.java))
                    "running", "running_apps" -> startActivity(Intent(this, RunningAppsActivity::class.java))
                    "editor" -> {
                        val sampleFile = java.io.File(filesDir, "sample_test.kt")
                        if (!sampleFile.exists()) {
                            sampleFile.writeText("// OpenManager Fast Code Editor\npackage demo\n\nfun main() {\n    println(\"OpenManager 100% Verified!\")\n}\n")
                        }
                        val samplePath = java8.nio.file.Paths.get(sampleFile.absolutePath)
                        val editIntent = Intent(this, me.zhanghai.android.files.viewer.text.TextEditorActivity::class.java).apply {
                            extraPath = samplePath
                        }
                        startActivity(editIntent)
                    }
                    "settings" -> startActivity(Intent(this, me.zhanghai.android.files.settings.SettingsActivity::class.java))
                    "add_storage" -> startActivity(Intent(this, me.zhanghai.android.files.storage.AddStorageDialogActivity::class.java))
                    "ftp" -> startActivity(Intent(this, me.zhanghai.android.files.ftpserver.FtpServerActivity::class.java))
                    "about" -> startActivity(Intent(this, me.zhanghai.android.files.about.AboutActivity::class.java))
                    "script_dialog" -> {
                        val sampleScript = java.io.File(filesDir, "install_module.sh")
                        sampleScript.writeText("#!/system/bin/sh\n# Script de prueba OpenManager\necho \"Ejecutando script con permisos 777...\"\nid\n")
                        sampleScript.setExecutable(true, false)
                        val scriptPath = java8.nio.file.Paths.get(sampleScript.absolutePath)
                        me.zhanghai.android.files.fileproperties.permission.MtPermissionsHelper.applyPermissions(
                            context = this,
                            path = scriptPath,
                            newMode = me.zhanghai.android.files.fileproperties.permission.MtPermissionsHelper.modeFromInt(0b111_111_111)
                        )
                        ScriptRunnerHelper.showScriptActionDialog(this, scriptPath)
                    }
                    "permissions_dialog" -> {
                        val sampleScript = java.io.File(filesDir, "install_module.sh")
                        if (!sampleScript.exists()) {
                            sampleScript.writeText("#!/system/bin/sh\necho \"Hello\"\n")
                        }
                        val scriptPath = java8.nio.file.Paths.get(sampleScript.absolutePath)
                        me.zhanghai.android.files.fileproperties.permission.MtPermissionsHelper.showPermissionsDialog(
                            context = this,
                            path = scriptPath
                        )
                    }
                    "file_properties" -> {
                        if (this::fragmentA.isInitialized) {
                            val sampleFile = java.io.File(filesDir, "sample_test.kt")
                            if (!sampleFile.exists()) {
                                sampleFile.writeText("// OpenManager Fast Code Editor\npackage demo\n\nfun main() {\n    println(\"OpenManager 100% Verified!\")\n}\n")
                            }
                            val samplePath = java8.nio.file.Paths.get(sampleFile.absolutePath)
                            val fileItem = samplePath.createFallbackFileItem(sampleFile)
                            if (fileItem != null) {
                                activeFragment.showPropertiesDialog(fileItem)
                            }
                        }
                    }
                    "root_unprivileged" -> {
                        if (this::fragmentA.isInitialized && this::fragmentB.isInitialized) {
                            fragmentA.navigateTo(java8.nio.file.Paths.get("/"))
                            fragmentB.navigateTo(java8.nio.file.Paths.get("/data"))
                        }
                    }
                    "guard_script", "guard_deadman", "guard_dfroot", "guard_mass", "seatbelt_export",
                    "multi_sessions" -> handleGuardDemo(action)
                    "remote_profiles", "remote_add_sheet", "remote_ssh", "remote_telnet", "remote_chooser", "remote_edit",
                    "remote_vps_root" ->
                        handleRemoteTerminalDemo(action)
                    "terminal" -> {
                        startActivity(
                            me.zhanghai.android.files.terminal.TerminalActivity.createIntent(
                                context = this,
                                path = "/storage/emulated/0"
                            )
                        )
                    }
                    "terminal_script_es" -> {
                        val targetDir = java.io.File("/storage/emulated/0").takeIf { it.exists() && it.canWrite() }
                            ?: filesDir
                        val scriptFile = java.io.File(targetDir, "a.sh")
                        scriptFile.writeText(
                            "#!/system/bin/sh\n" +
                                "echo \"Creando /storage/emulated/0/Music/prueba_openmanager.txt ...\"\n" +
                                "echo \"Contenido guardado:\"\n" +
                                "echo \"Hola, este es un archivo de prueba creado por script.\"\n" +
                                "echo \"Fecha: \$(date)\"\n" +
                                "printf \"¿Borrar este archivo? (s/n): \"\n" +
                                "read -t 25 ans || true\n"
                        )
                        scriptFile.setExecutable(true, false)
                        startActivity(
                            me.zhanghai.android.files.terminal.TerminalActivity.createIntent(
                                context = this,
                                path = targetDir.absolutePath,
                                command = "sh '${scriptFile.absolutePath}'",
                                activeScriptPath = scriptFile.absolutePath
                            )
                        )
                    }
                    "terminal_imgui" -> {
                        startActivity(
                            me.zhanghai.android.files.terminal.TerminalActivity.createIntent(
                                context = this,
                                path = "/data/local/tmp",
                                command = "/data/local/tmp/test_script"
                            )
                        )
                    }
                    "terminal_deadman" -> {
                        startActivity(
                            me.zhanghai.android.files.terminal.TerminalActivity.createIntent(
                                context = this,
                                path = "/data/local/tmp",
                                command = "export OM_DEAD_MAN_ACTIVE=1; /data/local/tmp/test_script"
                            )
                        )
                    }
                    "selection_bar" -> {
                        if (this::fragmentA.isInitialized) {
                            activeFragment.selectAllFiles()
                        }
                    }
                    "editor_search" -> {
                        val sampleFile = java.io.File(filesDir, "sample_test.kt")
                        if (!sampleFile.exists()) {
                            sampleFile.writeText("// OpenManager Fast Code Editor\npackage demo\n\nfun main() {\n    println(\"OpenManager 100% Verified!\")\n}\n")
                        }
                        val samplePath = java8.nio.file.Paths.get(sampleFile.absolutePath)
                        val editIntent = Intent(this, me.zhanghai.android.files.viewer.text.TextEditorActivity::class.java).apply {
                            extraPath = samplePath
                            putExtra("DEMO_OPEN_SEARCH", true)
                        }
                        startActivity(editIntent)
                    }
                    "editor_preferences" -> {
                        val sampleFile = java.io.File(filesDir, "sample_test.kt")
                        if (!sampleFile.exists()) {
                            sampleFile.writeText("// OpenManager Fast Code Editor\npackage demo\n\nfun main() {\n    println(\"OpenManager 100% Verified!\")\n}\n")
                        }
                        val samplePath = java8.nio.file.Paths.get(sampleFile.absolutePath)
                        val editIntent = Intent(this, me.zhanghai.android.files.viewer.text.TextEditorActivity::class.java).apply {
                            extraPath = samplePath
                            putExtra("DEMO_OPEN_PREFS", true)
                        }
                        startActivity(editIntent)
                    }
                    "editor_syntax" -> {
                        val sampleFile = java.io.File(filesDir, "sample_test.kt")
                        if (!sampleFile.exists()) {
                            sampleFile.writeText("// OpenManager Fast Code Editor\npackage demo\n\nfun main() {\n    println(\"OpenManager 100% Verified!\")\n}\n")
                        }
                        val samplePath = java8.nio.file.Paths.get(sampleFile.absolutePath)
                        val editIntent = Intent(this, me.zhanghai.android.files.viewer.text.TextEditorActivity::class.java).apply {
                            extraPath = samplePath
                            putExtra("DEMO_OPEN_SYNTAX", true)
                        }
                        startActivity(editIntent)
                    }
                    "large_list_stress" -> {
                        if (this::fragmentA.isInitialized) {
                            fragmentA.navigateTo(java8.nio.file.Paths.get("/storage/emulated/0/OM_PerfStress_1500"))
                        }
                    }
                    "dfroot_protected" -> {
                        me.zhanghai.android.files.provider.root.DfrootProtectedFilesDialogFragment.show(this)
                    }
                    "dirtyflag_dialog" -> {
                        me.zhanghai.android.files.provider.root.DeadManProtectionManager.showDirtyFlagProtectionDialog(this)
                    }
                    "deadman_rules" -> {
                        me.zhanghai.android.files.provider.root.DeadManProtectedRulesDialogFragment.show(this)
                    }
                    "deadman_dialog" -> {
                        me.zhanghai.android.files.provider.root.DeadManProtectionManager.showDeadManProtectionDialog(
                            this,
                            "/system",
                            "rm -rf /system"
                        )
                    }
                }
            } catch (e: Throwable) {
                android.util.Log.e("DemoReceiver", "Error executing demo action $action", e)
            }
        }, 300)
    }

    private fun checkAndRequestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                if (!hasRequestedPermissionThisSession) {
                    hasRequestedPermissionThisSession = true
                    showAllFilesPermissionDialog()
                }
            } else {
                allFilesDialog?.dismiss()
                allFilesDialog = null
                if (::fragmentA.isInitialized && ::fragmentB.isInitialized) {
                    fragmentA.reload()
                    fragmentB.reload()
                }
                checkAndRequestNotificationPermission()
            }
        } else {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            ) {
                if (!hasRequestedPermissionThisSession) {
                    hasRequestedPermissionThisSession = true
                    requestPermissions(
                        arrayOf(
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                        ),
                        1001
                    )
                }
            } else {
                if (::fragmentA.isInitialized && ::fragmentB.isInitialized) {
                    fragmentA.reload()
                    fragmentB.reload()
                }
                checkAndRequestNotificationPermission()
            }
        }
    }

    private fun showAllFilesPermissionDialog() {
        if (allFilesDialog?.isShowing == true) return
        allFilesDialog = MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.sd_card_icon_white_24dp)
            .setTitle(R.string.all_files_access_title)
            .setMessage(R.string.om_all_files_access_message)
            .setPositiveButton(R.string.om_grant_permission) { _, _ ->
                allFilesDialog = null
                try {
                    val intent = Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    try {
                        val intent = Intent(AndroidSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        startActivity(intent)
                    } catch (e2: Exception) {
                        showToast(R.string.om_all_files_settings_error)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                dialog.dismiss()
                allFilesDialog = null
            }
            .setCancelable(false)
            .show()
    }

    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                if (!hasRequestedNotificationThisSession) {
                    hasRequestedNotificationThisSession = true
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1002)
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                if (::fragmentA.isInitialized && ::fragmentB.isInitialized) {
                    fragmentA.reload()
                    fragmentB.reload()
                }
                checkAndRequestNotificationPermission()
            }
        } else if (requestCode == 1002) {
            // Notification response received from Android prompt. Handled cleanly without nagging settings dialog.
        }
    }

    private val isDarkThemeShown: Boolean
        get() = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun setupDrawerControls() {
        findViewById<ImageView>(R.id.btnDrawerNightMode)?.apply {
            val dark = isDarkThemeShown
            setImageResource(if (dark) R.drawable.ic_light_mode_24dp else R.drawable.ic_night_mode_24dp)
            contentDescription = getString(if (dark) R.string.om_theme_switch_light else R.string.om_theme_switch_dark)
            setOnClickListener {
                // The other one of what is on screen now ("follow system" becomes a fixed choice);
                // the AMOLED black setting is kept for the dark theme.
                val next = if (isDarkThemeShown) me.zhanghai.android.files.theme.night.NightMode.OFF
                else me.zhanghai.android.files.theme.night.NightMode.ON
                drawerLayout.closeDrawer(GravityCompat.START)
                Settings.NIGHT_MODE.putValue(next)
                me.zhanghai.android.files.theme.night.NightModeHelper.sync()
            }
        }
        findViewById<View>(R.id.btnDrawerSettings).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<View>(R.id.drawerItemRoot).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            try {
                if (activeFragment.isAdded) {
                    if (me.zhanghai.android.files.provider.root.SuperuserDetector.cachedStatus?.isRootGranted != true) {
                        me.zhanghai.android.files.provider.root.SuperuserDetector.invalidateCache()
                    }
                    activeFragment.navigateToRoot(Paths.get("/"))
                }
            } catch (e: Throwable) {
                android.util.Log.e("FileListActivity", "Error navigating to root", e)
            }
        }

        findViewById<View>(R.id.drawerItemInternal).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            try {
                if (activeFragment.isAdded) {
                    activeFragment.navigateToRoot(Paths.get("/storage/emulated/0"))
                }
            } catch (e: Throwable) {
                android.util.Log.e("FileListActivity", "Error navigating to internal storage", e)
            }
        }

        findViewById<View>(R.id.drawerItemDownloads)?.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            try {
                if (activeFragment.isAdded) {
                    activeFragment.navigateTo(Paths.get("/storage/emulated/0/Download"))
                }
            } catch (e: Throwable) {
                android.util.Log.e("FileListActivity", "Error navigating to downloads", e)
            }
        }

        findViewById<View>(R.id.drawerItemAndroidData)?.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            try {
                if (activeFragment.isAdded) {
                    val resolved = PathNavigator.resolve("/storage/emulated/0/Android/data", activeFragment.currentPath)
                    if (resolved != null) {
                        activeFragment.navigateTo(resolved)
                    } else {
                        activeFragment.navigateTo(Paths.get("/storage/emulated/0/Android/data"))
                    }
                }
            } catch (e: Throwable) {
                android.util.Log.e("FileListActivity", "Error navigating to Android/data", e)
            }
        }

        layoutCustomStorages = findViewById(R.id.layoutCustomStorages)
        findViewById<View>(R.id.drawerItemAddStorage).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(AddStorageDialogActivity::class.createIntent())
        }
        observeCustomStorages()

        findViewById<View>(R.id.drawerItemApktool)?.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            ApktoolHelper.showApktoolHubDialog(this, activeFragment.currentPath)
        }

        findViewById<View>(R.id.drawerItemInstalledApps)?.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, InstalledAppsActivity::class.java))
        }

        findViewById<View>(R.id.drawerItemRunningApps)?.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, RunningAppsActivity::class.java))
        }

        findViewById<View>(R.id.drawerItemTextEditor).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            // A new document; "Save as" suggests the folder open in the active panel.
            startActivity(Intent(this, TextEditorActivity::class.java).apply {
                val dir = runCatching { activeFragment.currentPath }.getOrNull()
                if (dir != null && dir.isLinuxPath) {
                    putExtra(TextEditorActivity.EXTRA_TARGET_DIR, dir.toFile().absolutePath)
                }
            })
        }

        // Connections hub replaces the old FTP server entry (the FTP server lives inside it).
        findViewById<View>(R.id.drawerItemConnections).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(me.zhanghai.android.files.terminal.remote.ConnectionsActivity.createIntent(this))
        }

        findViewById<View>(R.id.drawerItemTerminal)?.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            activeFragment.openTerminal()
        }
        // Long press: choose local shell or an SSH/Telnet profile.
        findViewById<View>(R.id.drawerItemTerminal)?.setOnLongClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            drawerLayout.closeDrawer(GravityCompat.START)
            me.zhanghai.android.files.terminal.remote.TerminalLaunchChooser.show(this) {
                activeFragment.openTerminal()
            }
            true
        }

        val drawerItemRecycleBin = findViewById<View>(R.id.drawerItemRecycleBin)
        val updateRecycleBinVisibility = {
            drawerItemRecycleBin?.isVisible = Settings.isRecycleBinActive
        }
        updateRecycleBinVisibility()
        Settings.MOVE_TO_RECYCLE_BIN_ON_DELETE.observe(this) { updateRecycleBinVisibility() }
        Settings.ENABLE_RECYCLE_BIN.observe(this) { updateRecycleBinVisibility() }

        drawerItemRecycleBin?.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            if (Settings.isRecycleBinActive) {
                val recyclePath = Settings.recycleBinDirectory
                val recycleDir = java.io.File(recyclePath.toString())
                if (!recycleDir.exists()) {
                    recycleDir.mkdirs()
                }
                val nomedia = java.io.File(recycleDir, ".nomedia")
                if (!nomedia.exists()) {
                    try {
                        nomedia.createNewFile()
                    } catch (_: Throwable) {}
                }
                activeFragment.navigateTo(recyclePath)
            } else {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.recycle_bin_disabled_dialog_title)
                    .setMessage(R.string.recycle_bin_disabled_dialog_message)
                    .setPositiveButton(R.string.recycle_bin_disabled_dialog_enable_action) { _, _ ->
                        Settings.MOVE_TO_RECYCLE_BIN_ON_DELETE.putValue(true)
                        Settings.ENABLE_RECYCLE_BIN.putValue(true)
                        val recyclePath = Settings.recycleBinDirectory
                        val recycleDir = java.io.File(recyclePath.toString())
                        if (!recycleDir.exists()) {
                            recycleDir.mkdirs()
                        }
                        val nomedia = java.io.File(recycleDir, ".nomedia")
                        if (!nomedia.exists()) {
                            try {
                                nomedia.createNewFile()
                            } catch (_: Throwable) {}
                        }
                        activeFragment.navigateTo(recyclePath)
                        showToast(getString(R.string.recycle_bin_enabled_toast))
                    }
                    .setNeutralButton(R.string.settings_title) { _, _ ->
                        startActivity(Intent(this, SettingsActivity::class.java))
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }

        findViewById<View>(R.id.drawerItemSettings).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<View>(R.id.drawerItemAbout).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, AboutActivity::class.java))
        }

        // Closes every screen and takes OpenManager out of the recent apps (user request).
        findViewById<View>(R.id.drawerItemExit).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            finishAndRemoveTask()
        }

        progressRootStorage = findViewById(R.id.progressRootStorage)
        tvRootStorageStats = findViewById(R.id.tvRootStorageStats)
        progressInternalStorage = findViewById(R.id.progressInternalStorage)
        tvInternalStorageStats = findViewById(R.id.tvInternalStorageStats)
    }

    private data class StorageSpaceStats(
        val percent: Int,
        val formattedText: String,
        val resolvedLinuxPath: String
    )

    private data class DetectedVolumeInfo(
        val uuid: String,
        val pathStr: String,
        val title: String
    )

    /**
     * Resolves a real Linux filesystem path (e.g. /storage/XXXX-XXXX) for any Storage entry
     * (SecondaryStorageVolume, DocumentTree with externalstorage UUID, etc.) if it maps to a local volume.
     */
    private fun resolveStorageFilesystemPath(storage: Storage): String? {
        storage.linuxPath?.takeIf { it.isNotBlank() }?.let { return it }
        if (storage is me.zhanghai.android.files.storage.DocumentTree) {
            val uriStr = Uri.decode(storage.uri.value.toString())
            // Match e.g. content://com.android.externalstorage.documents/tree/1234-5678: or .../tree/primary:
            val treeMarker = "/tree/"
            val idx = uriStr.indexOf(treeMarker)
            if (idx != -1) {
                val docId = uriStr.substring(idx + treeMarker.length).substringBefore('/')
                val volId = docId.substringBefore(':')
                val subPath = docId.substringAfter(':', "").trim('/')
                val base = if (volId.equals("primary", ignoreCase = true)) {
                    "/storage/emulated/0"
                } else if (volId.matches(Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")) || File("/storage/$volId").exists()) {
                    "/storage/$volId"
                } else {
                    null
                }
                if (base != null) {
                    return if (subPath.isEmpty()) base else "$base/$subPath"
                }
            }
        }
        return null
    }

    /**
     * Computes total/used/available space safely for a storage path (such as an SD card),
     * falling back to the app-specific external files directory on the same volume if root StatFs throws EACCES.
     */
    private fun computeStorageSpaceStats(rawPathStr: String?): StorageSpaceStats? {
        if (rawPathStr.isNullOrBlank()) return null
        val cleanPath = if (rawPathStr.length > 1) rawPathStr.trimEnd('/') else rawPathStr
        val candidates = mutableListOf(cleanPath)
        if (cleanPath.startsWith("/storage/") && !cleanPath.startsWith("/storage/emulated")) {
            val volRoot = "/storage/" + cleanPath.removePrefix("/storage/").substringBefore('/')
            if (volRoot !in candidates) candidates.add(volRoot)
            candidates.add("$volRoot/Android/data/$packageName/files")
            candidates.add("$volRoot/Android/data")
        }
        try {
            getExternalFilesDirs(null)?.forEach { dir ->
                if (dir != null && dir.absolutePath.startsWith(cleanPath)) {
                    candidates.add(dir.absolutePath)
                }
            }
        } catch (_: Throwable) {}

        for (candidate in candidates) {
            try {
                val stat = StatFs(candidate)
                val total = stat.totalBytes
                if (total <= 0L) continue
                val avail = stat.availableBytes.coerceIn(0L, total)
                val used = (total - avail).coerceAtLeast(0L)
                val percent = ((used * 100L) / total).toInt().coerceIn(0, 100)
                val usedStr = Formatter.formatFileSize(this, used)
                val availStr = Formatter.formatFileSize(this, avail)
                return StorageSpaceStats(
                    percent = percent,
                    formattedText = "$usedStr usado, $availStr disponible ($percent%)",
                    resolvedLinuxPath = cleanPath
                )
            } catch (_: Throwable) {
                // Try next candidate
            }
        }
        return null
    }

    /**
     * Discovers all mounted removable SD card / USB OTG volumes across Android versions,
     * combining StorageVolumeListLiveData, getExternalFilesDirs(null), and /storage scan.
     */
    private fun discoverRemovableVolumes(alreadyAddedPaths: Set<String>): List<DetectedVolumeInfo> {
        val result = LinkedHashMap<String, DetectedVolumeInfo>()
        val normalizedAdded = alreadyAddedPaths.map { it.trimEnd('/') }.toSet()

        // 1. From StorageVolumeListLiveData
        try {
            for (volume in StorageVolumeListLiveData.valueCompat) {
                if (volume.isPrimaryCompat) continue
                if (!volume.isMounted) continue
                val p = volume.pathCompat.trimEnd('/')
                if (p.isBlank() || p == "/storage/emulated/0" || p in normalizedAdded) continue
                val uuid = volume.uuidCompat ?: File(p).name
                val desc = volume.getDescriptionCompat(this).ifBlank { "Tarjeta SD ($uuid)" }
                result[p] = DetectedVolumeInfo(uuid = uuid, pathStr = p, title = desc)
            }
        } catch (_: Throwable) {}

        // 2. Fallback from getExternalFilesDirs(null) (always works for removable SD cards even without root)
        try {
            val extDirs = getExternalFilesDirs(null)
            if (extDirs != null) {
                for (dir in extDirs) {
                    if (dir == null) continue
                    val abs = dir.absolutePath
                    if (abs.startsWith("/storage/emulated/")) continue
                    val markerIdx = abs.indexOf("/Android/data")
                    val volPath = if (markerIdx > 0) abs.substring(0, markerIdx).trimEnd('/') else continue
                    if (volPath !in normalizedAdded && !result.containsKey(volPath)) {
                        val uuid = File(volPath).name
                        result[volPath] = DetectedVolumeInfo(
                            uuid = uuid,
                            pathStr = volPath,
                            title = getString(R.string.om_sd_card_named, uuid)
                        )
                    }
                }
            }
        } catch (_: Throwable) {}

        // 3. Fallback from /storage/<UUID> scan
        try {
            val storageRoot = File("/storage")
            storageRoot.listFiles()?.forEach { child ->
                val name = child.name
                if (name != "emulated" && name != "self" && name != "enc_emulated") {
                    val volPath = child.absolutePath.trimEnd('/')
                    if (volPath !in normalizedAdded && !result.containsKey(volPath)) {
                        val stats = computeStorageSpaceStats(volPath)
                        if (stats != null) {
                            result[volPath] = DetectedVolumeInfo(
                                uuid = name,
                                pathStr = volPath,
                                title = getString(R.string.om_sd_card_named, name)
                            )
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        return result.values.toList()
    }

    private fun refreshCustomStoragesList() {
        if (!this::layoutCustomStorages.isInitialized) return
        layoutCustomStorages.removeAllViews()
        val storages = Settings.STORAGES.valueCompat
        val addedPaths = mutableSetOf<String>()

        for (storage in storages) {
            if (storage is FileSystemRoot || storage is PrimaryStorageVolume || !storage.isVisible) {
                continue
            }
            val resolvedPath = resolveStorageFilesystemPath(storage)
            if (resolvedPath != null) {
                addedPaths.add(resolvedPath.trimEnd('/'))
            }
            val itemView = layoutInflater.inflate(R.layout.drawer_custom_storage_item, layoutCustomStorages, false)
            val iconView = itemView.findViewById<ImageView>(R.id.ivStorageIcon)
            val titleView = itemView.findViewById<TextView>(R.id.tvStorageTitle)
            val progressView = itemView.findViewById<ProgressBar>(R.id.progressCustomStorage)
            val subtitleView = itemView.findViewById<TextView>(R.id.tvStorageSubtitle)

            val isSdCard = storage is SecondaryStorageVolume ||
                (resolvedPath != null && resolvedPath.startsWith("/storage/") && !resolvedPath.startsWith("/storage/emulated"))
            iconView.setImageResource(if (isSdCard) R.drawable.sd_card_icon_white_24dp else storage.iconRes)
            titleView.text = storage.getName(this)

            val spaceStats = computeStorageSpaceStats(resolvedPath)
            if (spaceStats != null) {
                progressView?.isVisible = true
                progressView?.progress = spaceStats.percent
                subtitleView.text = spaceStats.formattedText
                itemView.tag = resolvedPath
            } else {
                progressView?.isVisible = false
                subtitleView.text = resolvedPath ?: storage.description
                itemView.tag = resolvedPath
            }

            itemView.setOnClickListener {
                drawerLayout.closeDrawer(GravityCompat.START)
                // Prefer direct fast LinuxPath when readable (e.g. MANAGE_EXTERNAL_STORAGE granted on Android 11+)
                val fastLinuxDir = resolvedPath?.let { File(it) }
                if (fastLinuxDir != null && fastLinuxDir.exists() && fastLinuxDir.canRead()) {
                    activeFragment.navigateTo(Paths.get(fastLinuxDir.absolutePath))
                } else {
                    storage.path?.let { path -> activeFragment.navigateTo(path) }
                }
            }
            itemView.setOnLongClickListener {
                showCustomStorageOptions(storage)
                true
            }
            layoutCustomStorages.addView(itemView)
        }

        // Auto-detect mounted non-primary volumes (MicroSD card, USB drives)
        val detectedVolumes = discoverRemovableVolumes(addedPaths)
        for (volume in detectedVolumes) {
            val pathStr = volume.pathStr
            val itemView = layoutInflater.inflate(R.layout.drawer_custom_storage_item, layoutCustomStorages, false)
            val iconView = itemView.findViewById<ImageView>(R.id.ivStorageIcon)
            val titleView = itemView.findViewById<TextView>(R.id.tvStorageTitle)
            val progressView = itemView.findViewById<ProgressBar>(R.id.progressCustomStorage)
            val subtitleView = itemView.findViewById<TextView>(R.id.tvStorageSubtitle)

            iconView.setImageResource(R.drawable.sd_card_icon_white_24dp)
            titleView.text = volume.title
            itemView.tag = pathStr

            val spaceStats = computeStorageSpaceStats(pathStr)
            if (spaceStats != null) {
                progressView?.isVisible = true
                progressView?.progress = spaceStats.percent
                subtitleView.text = spaceStats.formattedText
            } else {
                progressView?.isVisible = false
                subtitleView.text = pathStr
            }

            itemView.setOnClickListener {
                drawerLayout.closeDrawer(GravityCompat.START)
                activeFragment.navigateTo(Paths.get(pathStr))
            }
            itemView.setOnLongClickListener {
                val options = arrayOf(getString(R.string.storage_add_to_my_storages))
                MaterialAlertDialogBuilder(this)
                    .setTitle(volume.title)
                    .setItems(options) { _, which ->
                        if (which == 0) {
                            Storages.addOrReplace(SecondaryStorageVolume(volume.uuid, null, true))
                            showToast(getString(R.string.storage_added_success))
                        }
                    }
                    .show()
                true
            }
            layoutCustomStorages.addView(itemView)
        }
        refreshDrawerBookmarks()
        applyDrawerPalette(OpenManagerPalette.from(this))
    }

    /** Bookmarks in the side menu: tap opens in the active panel, long press shows its menu. */
    private fun refreshDrawerBookmarks() {
        val container = findViewById<LinearLayout>(R.id.layoutDrawerBookmarks) ?: return
        val header = findViewById<View>(R.id.tvDrawerBookmarksHeader)
        container.removeAllViews()
        val bookmarks = if (Settings.SHOW_BOOKMARKS_SIDEBAR.valueCompat) {
            Settings.BOOKMARK_DIRECTORIES.valueCompat
        } else {
            emptyList()
        }
        header?.isVisible = bookmarks.isNotEmpty()
        for (bookmark in bookmarks) {
            val itemView = layoutInflater.inflate(R.layout.drawer_custom_storage_item, container, false)
            itemView.findViewById<ImageView>(R.id.ivStorageIcon).setImageResource(R.drawable.bookmark_icon_white_24dp)
            itemView.findViewById<TextView>(R.id.tvStorageTitle).text = bookmark.name
            itemView.findViewById<ProgressBar>(R.id.progressCustomStorage)?.isVisible = false
            itemView.findViewById<TextView>(R.id.tvStorageSubtitle).text = bookmark.path.toUserFriendlyString()
            itemView.setOnClickListener {
                drawerLayout.closeDrawer(GravityCompat.START)
                openBookmark(bookmark.path, activePanelIndex)
            }
            // Long press: the bookmark's menu (add to home screen, other panel, edit, remove).
            itemView.setOnLongClickListener { anchor ->
                BookmarkMenu.show(
                    this, anchor, bookmark, activePanelIndex, findViewById(R.id.activityRoot)
                ) { drawerLayout.closeDrawer(GravityCompat.START) }
                true
            }
            container.addView(itemView)
        }
    }

    private fun observeCustomStorages() {
        Settings.STORAGES.observe(this) { refreshCustomStoragesList() }
        StorageVolumeListLiveData.observe(this) { refreshCustomStoragesList() }
        Settings.BOOKMARK_DIRECTORIES.observe(this) { refreshCustomStoragesList() }
        Settings.SHOW_BOOKMARKS_SIDEBAR.observe(this) { refreshCustomStoragesList() }
    }

    private fun showCustomStorageOptions(storage: Storage) {
        val options = arrayOf(getString(R.string.edit), getString(R.string.delete))
        MaterialAlertDialogBuilder(this)
            .setTitle(storage.getName(this))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startActivity(storage.createEditIntent())
                    1 -> Storages.remove(storage)
                }
            }
            .show()
    }

    private fun updateDrawerStats() {
        lifecycleScope.launch(Dispatchers.IO) {
            val rootData = try {
                val rootStat = StatFs("/")
                val rootTotal = rootStat.totalBytes
                val rootAvail = rootStat.availableBytes
                val rootUsed = rootTotal - rootAvail
                val rootPercent = if (rootTotal > 0) ((rootUsed * 100) / rootTotal).toInt() else 100
                val rootUsedStr = Formatter.formatFileSize(this@FileListActivity, rootUsed)
                val rootAvailStr = Formatter.formatFileSize(this@FileListActivity, rootAvail)
                rootPercent to getString(R.string.om_storage_used_avail_format, rootUsedStr, rootAvailStr, rootPercent)
            } catch (e: Exception) {
                100 to getString(R.string.om_nav_root_subtitle)
            }

            val emulatedData = try {
                val emulatedStat = StatFs("/storage/emulated/0")
                val emulatedTotal = emulatedStat.totalBytes
                val emulatedAvail = emulatedStat.availableBytes
                val emulatedUsed = emulatedTotal - emulatedAvail
                val emulatedPercent = if (emulatedTotal > 0) ((emulatedUsed * 100) / emulatedTotal).toInt() else 80
                val emulatedUsedStr = Formatter.formatFileSize(this@FileListActivity, emulatedUsed)
                val emulatedAvailStr = Formatter.formatFileSize(this@FileListActivity, emulatedAvail)
                emulatedPercent to getString(R.string.om_storage_used_avail_format, emulatedUsedStr, emulatedAvailStr, emulatedPercent)
            } catch (e: Exception) {
                80 to "/storage/emulated/0"
            }

            val customTags = mutableListOf<String>()
            withContext(Dispatchers.Main) {
                if (this@FileListActivity::layoutCustomStorages.isInitialized) {
                    for (i in 0 until layoutCustomStorages.childCount) {
                        val child = layoutCustomStorages.getChildAt(i) ?: continue
                        val pathTag = child.tag as? String ?: continue
                        customTags.add(pathTag)
                    }
                }
            }

            val customStatsMap = mutableMapOf<String, StorageSpaceStats>()
            for (pathTag in customTags) {
                val stats = computeStorageSpaceStats(pathTag)
                if (stats != null) {
                    customStatsMap[pathTag] = stats
                }
            }

            withContext(Dispatchers.Main) {
                if (this@FileListActivity::progressRootStorage.isInitialized) {
                    progressRootStorage.progress = rootData.first
                    tvRootStorageStats.text = rootData.second
                }
                if (this@FileListActivity::progressInternalStorage.isInitialized) {
                    progressInternalStorage.progress = emulatedData.first
                    tvInternalStorageStats.text = emulatedData.second
                }
                if (this@FileListActivity::layoutCustomStorages.isInitialized) {
                    for (i in 0 until layoutCustomStorages.childCount) {
                        val child = layoutCustomStorages.getChildAt(i) ?: continue
                        val pathTag = child.tag as? String ?: continue
                        val stats = customStatsMap[pathTag] ?: continue
                        val progressView = child.findViewById<ProgressBar>(R.id.progressCustomStorage)
                        val subtitleView = child.findViewById<TextView>(R.id.tvStorageSubtitle)
                        progressView?.isVisible = true
                        progressView?.progress = stats.percent
                        subtitleView?.text = stats.formattedText
                    }
                }
            }
        }
    }

    /**
     * Single-color menu icons take the theme's control color, like the menu text. Several were
     * white drawables and disappeared on the light theme's white menu.
     */
    private fun tintMenuIcons(menu: android.view.Menu, anchor: View) {
        val color = com.google.android.material.color.MaterialColors.getColor(
            anchor, androidx.appcompat.R.attr.colorControlNormal
        )
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            if (item.itemId in COLORED_MENU_ICONS) continue
            val icon = item.icon ?: continue
            item.icon = androidx.core.graphics.drawable.DrawableCompat.wrap(icon.mutate()).also {
                androidx.core.graphics.drawable.DrawableCompat.setTint(it, color)
            }
        }
    }

    private fun showMTMoreMenu(anchor: View) {
        val popup = PopupMenu(
            androidx.appcompat.view.ContextThemeWrapper(this, R.style.ThemeOverlay_OpenManager_CompactPopupMenu),
            anchor, GravityCompat.END
        )
        popup.inflate(R.menu.mt_overflow_menu)
        try {
            val field = popup.javaClass.getDeclaredField("mPopup")
            field.isAccessible = true
            val menuPopupHelper = field.get(popup) as? MenuPopupHelper
            menuPopupHelper?.setForceShowIcon(true)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            (popup.menu as? androidx.appcompat.view.menu.MenuBuilder)?.setOptionalIconsVisible(true)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        tintMenuIcons(popup.menu, anchor)
        val showHidden = Settings.FILE_LIST_SHOW_HIDDEN_FILES.valueCompat
        // Translated title with a check mark (it used to be a fixed Spanish text).
        popup.menu.findItem(R.id.mt_menu_hidden_files)?.apply {
            isCheckable = true
            isChecked = showHidden
        }
        popup.menu.findItem(R.id.mt_menu_search_results)?.isVisible =
            activeFragment.canShowLastSearchResults
        popup.menu.findItem(R.id.mt_menu_empty_recycle_bin)?.isVisible =
            activeFragment.currentPath.isInsideRecycleBin
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.mt_menu_empty_recycle_bin -> {
                    activeFragment.emptyRecycleBin()
                    true
                }
                R.id.mt_menu_search -> {
                    showSearchDialog()
                    true
                }
                R.id.mt_menu_search_text -> {
                    showFileTextSearchDialog()
                    true
                }
                R.id.mt_menu_find_duplicates -> {
                    showDuplicateFinderDialog()
                    true
                }
                R.id.mt_menu_select_all -> {
                    activeFragment.selectAllFiles()
                    true
                }
                R.id.mt_menu_filter -> {
                    openQuickFilter()
                    true
                }
                R.id.mt_menu_search_results -> {
                    closeQuickFilter()
                    activeFragment.showLastSearchResults()
                    true
                }
                R.id.mt_menu_sort -> {
                    activeFragment.showSortOptionsDialog()
                    true
                }
                R.id.mt_menu_hidden_files -> {
                    val current = Settings.FILE_LIST_SHOW_HIDDEN_FILES.valueCompat
                    val newSetting = !current
                    Settings.FILE_LIST_SHOW_HIDDEN_FILES.putValue(newSetting)
                    true
                }
                R.id.mt_menu_bookmarks -> {
                    showBookmarks()
                    true
                }
                R.id.mt_menu_set_as_home -> {
                    showToast(R.string.om_path_set_as_home)
                    true
                }
                R.id.mt_menu_swap_windows -> {
                    btnBottomSwap.performClick()
                    true
                }
                R.id.mt_menu_export_file_list -> {
                    val allFiles = activeFragment.viewModel.fileListStateful.value ?: emptyList()
                    ExportFileListHelper.showDialog(this, activeFragment.currentPath, allFiles, activeFragment.selectedFiles.toList()) {
                        activeFragment.clearSelectedFiles()
                    }
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun checkAutoCleanRecycleBin() {
        if (!Settings.isRecycleBinActive) {
            return
        }
        val days = Settings.autoCleanRecycleBinDays
        if (days <= 0) {
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val recycleDir = java.io.File(Settings.recycleBinDirectory.toString())
                if (!recycleDir.exists() || !recycleDir.isDirectory) {
                    return@launch
                }
                val cutoff = System.currentTimeMillis() - (days.toLong() * 24L * 60L * 60L * 1000L)
                recycleDir.listFiles()?.forEach { file ->
                    if (file.name != ".nomedia" && file.lastModified() < cutoff) {
                        file.deleteRecursively()
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    private var isKeyboardVisible = false
    private var systemNavBarBottom = 0

    private fun setupBottomBarControls() {
        val bottomBar = findViewById<View>(R.id.bottomBar)
        if (bottomBar != null) {
            ViewCompat.setOnApplyWindowInsetsListener(drawerLayout) { _, insets ->
                val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
                val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
                val cutouts = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
                val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())

                val newNavBarBottom = navBars.bottom
                var needUpdate = false
                if (systemNavBarBottom != newNavBarBottom) {
                    systemNavBarBottom = newNavBarBottom
                    needUpdate = true
                }
                if (isKeyboardVisible != imeVisible) {
                    isKeyboardVisible = imeVisible
                    needUpdate = true
                }

                val statusBarInset = maxOf(
                    statusBars.top,
                    cutouts.top,
                    statusBarHeight
                )
                val insetLeft = maxOf(navBars.left, cutouts.left)
                val insetRight = maxOf(navBars.right, cutouts.right)

                findViewById<View>(R.id.activityRoot)?.updatePadding(
                    left = insetLeft,
                    top = statusBarInset,
                    right = insetRight
                )
                // Insets add to the header's own padding; replacing it put the logo on the edge.
                findViewById<View>(R.id.layoutDrawerHeader)?.updatePadding(
                    left = drawerHeaderBasePadding[0] + insetLeft,
                    top = drawerHeaderBasePadding[1] + statusBarInset
                )
                findViewById<View>(R.id.drawerScrollView)?.updatePadding(
                    left = insetLeft,
                    bottom = newNavBarBottom
                )

                if (needUpdate) {
                    updateBottomBarMargin()
                }
                insets
            }
            ViewCompat.requestApplyInsets(drawerLayout)
        }
        updateBottomBarMargin()
        Settings.ADD_BOTTOM_MARGIN.observe(this) { updateBottomBarMargin() }
        Settings.BOTTOM_MARGIN_SIZE.observe(this) { updateBottomBarMargin() }

        // Normal Mode Buttons: [<] [>] [+] [⇆] [↑]
        btnBottomBack.setOnClickListener {
            if (activeFragment.canNavigateUp) {
                activeFragment.navigateUp()
            } else {
                onBackPressedDispatcher.onBackPressed()
            }
        }

        // Back down to the folder this panel came up from (">" undoes "<").
        btnBottomForward.setOnClickListener {
            activeFragment.navigateForward()
        }

        btnBottomAdd.setOnClickListener {
            showCreateDialog()
        }

        btnBottomSwap.setOnClickListener {
            try {
                val pathA = fragmentA.currentPath
                val pathB = fragmentB.currentPath
                fragmentA.navigateTo(pathB)
                fragmentB.navigateTo(pathA)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Home of the active panel: the VPS home on a VPS, else that panel's start folder (the
        // phone's storage by default). Going up one folder stays in the top bar's arrow.
        btnBottomHome.setOnClickListener {
            val current = runCatching { activeFragment.currentPath }.getOrNull()
            val home = homeFor(activeFragment)
            if (current?.sftpAuthority != null && (home == null || home == current)) {
                // Already at the VPS home: Home leaves the server (and closes its connection).
                leaveRemote(activeFragment)
            } else if (home != null && home != current) {
                activeFragment.navigateTo(home)
            }
        }
        // Long press on home: the bookmarks, the other places worth jumping to.
        btnBottomHome.setOnLongClickListener {
            showBookmarks()
            true
        }

        // Selection Mode: 5 Icon Buttons (MT Manager Style matching bottomBarNormal)
        btnSelectionClose.setOnClickListener {
            activeFragment.clearSelectedFiles()
        }
        btnSelectionClose.setOnLongClickListener {
            activeFragment.selectAllFiles()
            Toast.makeText(this, R.string.om_all_files_selected, Toast.LENGTH_SHORT).show()
            true
        }

        btnCopyOpposite.setOnClickListener {
            val selected = activeFragment.selectedFiles
            if (selected.isNotEmpty()) {
                val opposite = getOppositeFragment(activeFragment)
                if (opposite.currentPath.toString().startsWith("/dev/block") || opposite.currentPath.toString().startsWith("/dev/")) {
                    Toast.makeText(this, R.string.om_sec_cannot_write_sys_part, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!activeFragment.canWriteTo(opposite.currentPath)) {
                    val msgRes = if (opposite.currentPath.isArchivePath) {
                        R.string.om_err_copy_into_archive
                    } else {
                        R.string.om_err_perm_denied_dest_ro
                    }
                    Toast.makeText(this, msgRes, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                activeFragment.copyFilesTo(selected, opposite.currentPath)
            }
        }

        btnMoveOpposite.setOnClickListener {
            val selected = activeFragment.selectedFiles
            if (selected.isNotEmpty()) {
                if (selected.any { it.path.toString().startsWith("/dev/block") || it.path.toString().startsWith("/dev/") }) {
                    Toast.makeText(this, R.string.om_sec_cannot_move_partitions, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val opposite = getOppositeFragment(activeFragment)
                if (activeFragment.currentPath == opposite.currentPath) {
                    Toast.makeText(this, R.string.om_err_same_src_dest, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (opposite.currentPath.toString().startsWith("/dev/block") || opposite.currentPath.toString().startsWith("/dev/")) {
                    Toast.makeText(this, R.string.om_sec_cannot_move_to_sys_part, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!activeFragment.canWriteTo(activeFragment.currentPath) || !activeFragment.canWriteTo(opposite.currentPath)) {
                    val msgRes = if (opposite.currentPath.isArchivePath) {
                        R.string.om_err_move_into_archive
                    } else {
                        R.string.om_err_perm_denied_move_ro
                    }
                    Toast.makeText(this, msgRes, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                activeFragment.moveFilesTo(selected, opposite.currentPath)
            }
        }

        btnDeleteSelection.setOnClickListener {
            if (activeFragment.selectedFiles.isNotEmpty()) {
                if (activeFragment.selectedFiles.any { it.path.toString().startsWith("/dev/block") || it.path.toString().startsWith("/dev/") }) {
                    Toast.makeText(this, R.string.om_sec_cannot_delete_partitions, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!activeFragment.canWriteTo(activeFragment.currentPath)) {
                    Toast.makeText(this, R.string.om_err_perm_denied_dir_ro, Toast.LENGTH_SHORT).show()
                } else {
                    activeFragment.deleteSelectedFiles()
                }
            }
        }

        btnMoreSelection.setOnClickListener { view ->
            showMoreSelectionMenu(view)
        }
    }

    private fun updateSelectionButtons(count: Int) {
        val isPanelA = (activePanelIndex == 0)
        val isArchive = activeFragment.currentPath.isArchivePath
        val copyVerb = getString(if (isArchive) R.string.om_verb_extract else R.string.om_verb_copy)

        if (isArchive) {
            btnCopyOpposite.setImageResource(R.drawable.extract_icon_white_24dp)
        } else {
            btnCopyOpposite.setImageResource(R.drawable.copy_icon_white_24dp)
        }
        val copyDesc = getString(
            if (isPanelA) R.string.om_verb_to_right_panel_format else R.string.om_verb_to_left_panel_format,
            copyVerb
        )
        btnCopyOpposite.contentDescription = copyDesc
        TooltipCompat.setTooltipText(btnCopyOpposite, copyDesc)

        if (isPanelA) {
            btnMoveOpposite.setImageResource(R.drawable.move_icon_white_24dp)
            val moveRight = getString(R.string.om_move_to_right_panel)
            btnMoveOpposite.contentDescription = moveRight
            TooltipCompat.setTooltipText(btnMoveOpposite, moveRight)
        } else {
            btnMoveOpposite.setImageResource(R.drawable.move_left_icon_white_24dp)
            val moveLeft = getString(R.string.om_move_to_left_panel)
            btnMoveOpposite.contentDescription = moveLeft
            TooltipCompat.setTooltipText(btnMoveOpposite, moveLeft)
        }

        val isReadOnly = !activeFragment.canWriteTo(activeFragment.currentPath)
        val opposite = getOppositeFragment(activeFragment)
        val oppositeIsArchive = opposite.currentPath.isArchivePath
        val isSameDir = activeFragment.currentPath == opposite.currentPath
        val moveDisabled = isArchive || isReadOnly || oppositeIsArchive || isSameDir
        btnMoveOpposite.alpha = if (moveDisabled) 0.35f else 1.0f
        btnMoveOpposite.isEnabled = !moveDisabled

        TooltipCompat.setTooltipText(btnSelectionClose, getString(R.string.om_action_cancel_selection))
        TooltipCompat.setTooltipText(btnDeleteSelection, getString(R.string.om_action_delete_selected))
        TooltipCompat.setTooltipText(btnMoreSelection, getString(R.string.om_more_options))

        val totalSelectable = activeFragment.selectableItemCount
        val disk = getDiskStats()
        tvStatsSubtitle.text = if (disk.isNotEmpty()) {
            getString(R.string.om_selected_stats_disk_format, count, totalSelectable, disk)
        } else {
            getString(R.string.om_selected_stats_format, count, totalSelectable)
        }
    }

    private fun updateBottomBarMargin() {
        val bottomBar = findViewById<View>(R.id.bottomBar) ?: return
        val addMargin = Settings.ADD_BOTTOM_MARGIN.valueCompat
        val marginDp = if (addMargin) {
            Settings.BOTTOM_MARGIN_SIZE.valueCompat.toIntOrNull() ?: 12
        } else {
            0
        }
        val density = resources.displayMetrics.density
        val customMarginPx = (marginDp * density).toInt()
        val baseHeightPx = (44 * density).toInt()

        val bottomInsetPx = if (!isKeyboardVisible) {
            systemNavBarBottom + customMarginPx
        } else {
            0
        }

        val lp = bottomBar.layoutParams
        lp.height = baseHeightPx + bottomInsetPx
        bottomBar.layoutParams = lp
        bottomBar.setPadding(0, 0, 0, bottomInsetPx)
    }

    private var lastBackPressTime = 0L

    private val returnOnBackPath: Path? by lazy {
        intent?.getStringExtra(EXTRA_RETURN_ON_BACK_AT)?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { Paths.get(java.net.URI(it)) }.getOrNull() }
    }

    /** True on the folder this explorer was opened at for its caller, or any folder above it. */
    fun isBackAtReturnPath(): Boolean {
        val start = returnOnBackPath ?: return false
        val current = runCatching { activeFragment.currentPath }.getOrNull() ?: return false
        val atOrAbove = runCatching {
            current == start || (current.fileSystem == start.fileSystem && start.startsWith(current))
        }.getOrDefault(false)
        if (BuildConfig.DEBUG) android.util.Log.d("OMReturnOnBack", "start=$start current=$current -> $atOrAbove")
        return atOrAbove
    }

    private fun setupBackPressedHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                    return
                }
                if (isQuickFilterOpen) {
                    closeQuickFilter()
                    return
                }
                // Only the active panel: its search, selection, paste bar or folder.
                if (activeFragment.handleBackInPanel()) {
                    return
                }
                if (activeFragment.selectedFiles.isNotEmpty()) {
                    activeFragment.clearSelectedFiles()
                    return
                }
                if (isBackAtReturnPath()) {
                    // Opened from the VPS terminal: back from its start folder (or above) goes
                    // back to the session instead of climbing the server's folders.
                    finish()
                    return
                }
                if (activeFragment.canNavigateUp) {
                    activeFragment.navigateUp()
                    return
                }
                val now = System.currentTimeMillis()
                if (now - lastBackPressTime < 2000L) {
                    finish()
                } else {
                    lastBackPressTime = now
                    showToast(R.string.press_back_again_to_exit)
                }
            }
        })
    }

    private val panelHitRect = android.graphics.Rect()

    // Swipe up on the bottom bar opens the bookmarks (MT Manager gesture).
    private var bookmarkSwipeDownY = Float.NaN
    private var bookmarkSwipeDownX = 0f
    private var bookmarkSwipeConsumed = false

    /**
     * Watches touches that start on the bottom bar: a swipe up opens the bookmarks and cancels the
     * button under the finger, a tap still goes to the button as usual.
     */
    private fun handleBookmarkSwipe(ev: android.view.MotionEvent): Boolean {
        if (!this::bottomBarNormal.isInitialized) return false
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                bookmarkSwipeConsumed = false
                bookmarkSwipeDownY = if (bottomBarNormal.isShown &&
                    bottomBarNormal.getGlobalVisibleRect(panelHitRect) &&
                    panelHitRect.contains(ev.rawX.toInt(), ev.rawY.toInt())
                ) ev.rawY else Float.NaN
                bookmarkSwipeDownX = ev.rawX
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (bookmarkSwipeConsumed) return true
                if (!bookmarkSwipeDownY.isNaN()) {
                    val threshold = 32 * resources.displayMetrics.density
                    if (bookmarkSwipeDownY - ev.rawY > threshold) {
                        bookmarkSwipeConsumed = true
                        bookmarkSwipeDownY = Float.NaN
                        val cancel = android.view.MotionEvent.obtain(ev).apply {
                            action = android.view.MotionEvent.ACTION_CANCEL
                        }
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                        showBookmarks(bookmarkPanelForSwipe(bookmarkSwipeDownX))
                        return true
                    }
                }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                bookmarkSwipeDownY = Float.NaN
                if (bookmarkSwipeConsumed) {
                    bookmarkSwipeConsumed = false
                    return true
                }
            }
        }
        return false
    }

    private val isSecondPanelShown: Boolean
        get() = this::containerB.isInitialized && containerB.isShown

    /** Panel a bottom-bar swipe at [rawX] opens bookmarks for (see [BookmarkSwipe]). */
    private fun bookmarkPanelForSwipe(rawX: Float): Int =
        BookmarkSwipe.targetPanel(
            positionAware = Settings.BOOKMARK_SWIPE_AWARE.valueCompat,
            bothPanelsShown = isSecondPanelShown,
            activePanel = activePanelIndex,
            touchX = rawX,
            screenWidth = window.decorView.width.toFloat(),
            isRtl = window.decorView.layoutDirection == View.LAYOUT_DIRECTION_RTL
        )

    /** Opens the bookmarks panel; a bookmark tapped there opens in panel [panelIndex]. */
    fun showBookmarks(panelIndex: Int = activePanelIndex) {
        if (supportFragmentManager.isStateSaved) return
        if (supportFragmentManager.findFragmentByTag(BookmarksSheetFragment.TAG) != null) return
        BookmarksSheetFragment.newInstance(panelIndex)
            .show(supportFragmentManager, BookmarksSheetFragment.TAG)
    }

    internal fun panelFragment(panelIndex: Int): FileListFragment =
        if (panelIndex == 1) fragmentB else fragmentA

    internal val canOpenInOtherPanel: Boolean
        get() = isSecondPanelShown

    /** Opens [path] in panel [panelIndex] and makes that panel the active one. */
    internal fun openBookmark(path: Path, panelIndex: Int) {
        val fragment = panelFragment(panelIndex)
        onPanelFocused(fragment)
        if (fragment.currentPath != path) fragment.navigateTo(path)
    }

    /**
     * Any touch inside a panel makes it the active one before the touch is handled, whatever was
     * touched (file list, breadcrumb, empty or error view and its buttons, fast scroller...).
     * Previously only the file list did this, so navigating a panel through its breadcrumb or
     * error view left the other panel active and the back button acted on the wrong side.
     */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (handleBookmarkSwipe(ev)) return true
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN &&
            this::containerA.isInitialized && this::fragmentA.isInitialized && this::fragmentB.isInitialized &&
            this::drawerLayout.isInitialized && !drawerLayout.isDrawerOpen(GravityCompat.START)
        ) {
            val x = ev.rawX.toInt()
            val y = ev.rawY.toInt()
            when {
                isTouchInside(containerA, x, y) -> onPanelFocused(fragmentA)
                isTouchInside(containerB, x, y) -> onPanelFocused(fragmentB)
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun isTouchInside(view: View, x: Int, y: Int): Boolean =
        view.isShown && view.getGlobalVisibleRect(panelHitRect) && panelHitRect.contains(x, y)

    fun onPanelFocused(fragment: FileListFragment) {
        val newIndex = if (fragment == fragmentA) 0 else 1
        if (activePanelIndex != newIndex) {
            activePanelIndex = newIndex
            syncPanelColors()
            updateTopBarInfo()
            syncBottomBarSelectionState()
            updateMainToolbarNavigationIcon()
            fragmentA.updateBackCallbackState()
            fragmentB.updateBackCallbackState()
            invalidateOptionsMenu()
        }
    }

    private fun observeVisualThemeSettings() {
        Settings.NIGHT_MODE.observe(this) { applyVisualTheme() }
        Settings.BLACK_NIGHT_MODE.observe(this) { applyVisualTheme() }
        Settings.THEME_COLOR.observe(this) { applyVisualTheme() }
        Settings.MATERIAL_DESIGN_3.observe(this) { applyVisualTheme() }
        Settings.DUAL_PANEL_CONTRAST.observe(this) { applyVisualTheme() }
    }

    private fun applyVisualTheme() {
        val palette = OpenManagerPalette.from(this)
        if (lastAppliedPalette == palette) return
        lastAppliedPalette = palette
        window.statusBarColor = palette.toolbarBg
        window.navigationBarColor = palette.bottomBarBg
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = palette.isLightStatusBar
            isAppearanceLightNavigationBars = palette.isLightStatusBar
        }

        findViewById<View>(R.id.activityRoot)?.setBackgroundColor(palette.toolbarBg)
        mainToolbar.setBackgroundColor(palette.toolbarBg)
        tvPathTitle.setTextColor(palette.textPrimary)
        tvStatsSubtitle.setTextColor(palette.textSecondary)
        etQuickFilter.setTextColor(palette.textPrimary)
        etQuickFilter.setHintTextColor(palette.textSecondary)
        val iconTintList = ColorStateList.valueOf(palette.iconTint)
        ImageViewCompat.setImageTintList(btnQuickFilterClose, iconTintList)
        ImageViewCompat.setImageTintList(btnMainBack, iconTintList)
        ImageViewCompat.setImageTintList(btnMainMore, iconTintList)
        updateMainToolbarNavigationIcon()

        paneDivider.setBackgroundColor(palette.paneDivider)
        syncPanelColors(palette)

        findViewById<View>(R.id.bottomBar)?.setBackgroundColor(palette.bottomBarBg)
        listOf(
            btnBottomBack, btnBottomForward, btnBottomAdd, btnBottomSwap, btnBottomHome,
            btnSelectionClose, btnCopyOpposite, btnMoveOpposite, btnDeleteSelection, btnMoreSelection
        ).forEach { ImageViewCompat.setImageTintList(it, iconTintList) }

        applyDrawerPalette(palette)
    }

    private fun applyDrawerPalette(palette: OpenManagerPalette) {
        val drawerView = findViewById<View>(R.id.navigationDrawerView) ?: return
        drawerView.setBackgroundColor(palette.drawerBg)
        findViewById<View>(R.id.layoutDrawerHeader)?.setBackgroundColor(palette.drawerHeaderBg)
        findViewById<ImageView>(R.id.btnDrawerSettings)?.let {
            ImageViewCompat.setImageTintList(it, ColorStateList.valueOf(palette.iconTint))
        }
        findViewById<ImageView>(R.id.btnDrawerNightMode)?.let {
            ImageViewCompat.setImageTintList(it, ColorStateList.valueOf(palette.iconTint))
        }
        applyPaletteToDrawerTree(drawerView, palette)
    }

    private fun applyPaletteToDrawerTree(view: View, palette: OpenManagerPalette) {
        val id = view.id
        if (id == R.id.btnDrawerSettings || id == R.id.btnDrawerNightMode) {
            return
        }
        if (view is android.widget.ProgressBar) {
            view.progressTintList = ColorStateList.valueOf(palette.accentColor)
            view.progressBackgroundTintList = ColorStateList.valueOf(palette.dividerLineColor)
        } else if (view is android.widget.FrameLayout && view.background != null &&
            id != R.id.layoutDrawerHeader
        ) {
            ViewCompat.setBackgroundTintList(view, ColorStateList.valueOf(palette.drawerCircleBg))
        } else if (view is TextView) {
            var role = view.getTag(R.id.tag_palette_role) as? String
            if (role == null) {
                val color = view.currentTextColor and 0x00FFFFFF
                val parentRowId = (view.parent as? View)?.id
                role = when {
                    parentRowId == R.id.drawerItemAddStorage || color == 0x42A5F5 -> ROLE_ACCENT
                    color == 0xFFFFFF -> ROLE_PRIMARY
                    else -> ROLE_SECONDARY
                }
                view.setTag(R.id.tag_palette_role, role)
            }
            view.setTextColor(
                when (role) {
                    ROLE_ACCENT -> palette.accentColor
                    ROLE_PRIMARY -> palette.textPrimary
                    else -> palette.textSecondary
                }
            )
        } else if (view is ImageView) {
            var role = view.getTag(R.id.tag_palette_role) as? String
            if (role == null) {
                val currentTint = ImageViewCompat.getImageTintList(view)?.defaultColor
                val grandParentId = (view.parent?.parent as? View)?.id
                role = when {
                    grandParentId == R.id.drawerItemAddStorage -> ROLE_ICON_ACCENT
                    currentTint != null && (currentTint and 0x00FFFFFF) == 0xDCDCDC -> ROLE_ICON_NEUTRAL
                    else -> ROLE_KEEP
                }
                view.setTag(R.id.tag_palette_role, role)
            }
            when (role) {
                ROLE_ICON_ACCENT -> ImageViewCompat.setImageTintList(view, ColorStateList.valueOf(palette.accentColor))
                ROLE_ICON_NEUTRAL -> ImageViewCompat.setImageTintList(view, ColorStateList.valueOf(palette.drawerIconTint))
            }
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                applyPaletteToDrawerTree(view.getChildAt(i), palette)
            }
        }
    }

    private fun syncPanelColors(palette: OpenManagerPalette = OpenManagerPalette.from(this)) {
        if (activePanelIndex == 0) {
            containerA.setBackgroundColor(palette.activePanelBg)
            containerB.setBackgroundColor(palette.inactivePanelBg)
        } else {
            containerA.setBackgroundColor(palette.inactivePanelBg)
            containerB.setBackgroundColor(palette.activePanelBg)
        }
    }

    private var cachedDiskStats: String = ""
    private var lastDiskStatsTime: Long = 0L

    private fun getDiskStats(): String {
        // The phone's free space means nothing next to a folder on a server (the VPS panel).
        if (::fragmentA.isInitialized && ::fragmentB.isInitialized && activeFragment.isAdded &&
            isServerPath(activeFragment.currentPath)
        ) {
            return ""
        }
        val now = android.os.SystemClock.uptimeMillis()
        if ((cachedDiskStats.isEmpty() || now - lastDiskStatsTime >= 4000L) && !isDiskStatsRefreshing) {
            isDiskStatsRefreshing = true
            lifecycleScope.launch(Dispatchers.IO) {
                val res = try {
                    val stat = StatFs(android.os.Environment.getExternalStorageDirectory().path)
                    val available = Formatter.formatShortFileSize(this@FileListActivity, stat.availableBytes).replace(" ", "")
                    val total = Formatter.formatShortFileSize(this@FileListActivity, stat.totalBytes).replace(" ", "")
                    getString(R.string.om_stats_disk, available, total)
                } catch (_: Exception) {
                    cachedDiskStats
                }
                withContext(Dispatchers.Main) {
                    isDiskStatsRefreshing = false
                    cachedDiskStats = res
                    lastDiskStatsTime = android.os.SystemClock.uptimeMillis()
                    if (!isDestroyed && !isFinishing && ::tvStatsSubtitle.isInitialized && ::fragmentA.isInitialized && ::fragmentB.isInitialized && activeFragment.isAdded) {
                        val stats = activeFragment.currentStatsSubtitle
                        // The phone's free space means nothing next to a folder on a server.
                        val disk = if (isServerPath(activeFragment.currentPath)) "" else res
                        tvStatsSubtitle.text = when {
                            stats.isNotEmpty() && disk.isNotEmpty() -> "$stats  $disk"
                            stats.isNotEmpty() -> stats
                            disk.isNotEmpty() -> disk
                            else -> getString(R.string.om_status_ready)
                        }
                    }
                }
            }
        }
        return cachedDiskStats
    }

    private fun updateTopBarInfo() {
        if (!::fragmentA.isInitialized || !::fragmentB.isInitialized) return
        if (!activeFragment.isAdded) return
        try {
            // Another folder in the filtered panel: the filter was for the folder it left.
            quickFilterTarget?.let { target ->
                if (target.isAdded && target.currentPath != quickFilterPath) closeQuickFilter()
            }
            val path = activeFragment.currentPath
            val pathStr = path.toDisplayPath(trailingSlash = true)
            tvPathTitle.text = pathStr
            val stats = activeFragment.currentStatsSubtitle
            val disk = getDiskStats()
            tvStatsSubtitle.text = when {
                stats.isNotEmpty() && disk.isNotEmpty() -> "$stats  $disk"
                stats.isNotEmpty() -> stats
                disk.isNotEmpty() -> disk
                else -> getString(R.string.om_status_ready)
            }
            updateMainToolbarNavigationIcon()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** ">" is dimmed when the active panel has nothing to go forward to. */
    private fun updateForwardButton() {
        if (!::btnBottomForward.isInitialized) return
        val canForward = try { activeFragment.canNavigateForward } catch (_: Exception) { false }
        btnBottomForward.isEnabled = canForward
        btnBottomForward.alpha = if (canForward) 1f else 0.35f
    }

    private fun updateMainToolbarNavigationIcon() {
        if (!::mainToolbar.isInitialized || !::fragmentA.isInitialized || !::fragmentB.isInitialized) return
        if (!activeFragment.isAdded) return

        val selectedCount = try { activeFragment.selectedFiles.size } catch (e: Exception) { 0 }
        val isSelection = selectedCount > 0
        val canNavigateUp = try { activeFragment.canNavigateUp } catch (e: Exception) { false }

        if (::btnMainBack.isInitialized) {
            btnMainBack.visibility = if (!isSelection && canNavigateUp) View.VISIBLE else View.GONE
        }
        updateForwardButton()

        val navTint = (lastAppliedPalette ?: OpenManagerPalette.from(this)).iconTint
        if (isSelection == lastNavIconIsSelection && navTint == lastNavIconTint) {
            return
        }
        lastNavIconIsSelection = isSelection
        lastNavIconTint = navTint

        if (isSelection) {
            mainToolbar.setNavigationIcon(R.drawable.close_icon_control_normal_24dp)
            mainToolbar.navigationIcon?.mutate()?.setTint(navTint)
            mainToolbar.navigationContentDescription = getString(android.R.string.cancel)
            mainToolbar.setNavigationOnClickListener {
                activeFragment.clearSelectedFiles()
            }
        } else {
            mainToolbar.setNavigationIcon(R.drawable.menu_icon_control_normal_24dp)
            mainToolbar.navigationIcon?.mutate()?.setTint(navTint)
            mainToolbar.navigationContentDescription = getString(R.string.open_navigation_drawer)
            mainToolbar.setNavigationOnClickListener {
                drawerLayout.openDrawer(GravityCompat.START)
            }
        }

        // Allow long press on navigation icon to open drawer at any time (MT Manager style)
        for (i in 0 until mainToolbar.childCount) {
            val child = mainToolbar.getChildAt(i)
            if (child is android.widget.ImageButton) {
                child.setOnLongClickListener {
                    if (!drawerLayout.isDrawerOpen(GravityCompat.START)) {
                        drawerLayout.openDrawer(GravityCompat.START)
                        true
                    } else {
                        false
                    }
                }
            }
        }
    }

    private fun showJumpToPathDialog() {
        if (!::fragmentA.isInitialized || !::fragmentB.isInitialized) return
        val currentDisplayPath = activeFragment.currentPath.toDisplayPath(trailingSlash = true)
        val context = this
        val palette = OpenManagerPalette.from(context)
        val editText = androidx.appcompat.widget.AppCompatEditText(context).apply {
            setText(currentDisplayPath)
            setSingleLine(true)
            selectAll()
            setTextColor(palette.textPrimary)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            supportBackgroundTintList = android.content.res.ColorStateList.valueOf(palette.accentColor)
            val paddingPx = (16 * resources.displayMetrics.density).toInt()
            setPadding(paddingPx, paddingPx / 2, paddingPx, paddingPx / 2)
        }

        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
            addView(editText)

            val buttonBar = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
            }

            fun createChip(
                text: String,
                @DrawableRes iconRes: Int? = null,
                iconTint: Int? = null,
                chipTextColor: Int = android.graphics.Color.parseColor("#90CAF9"),
                onClick: () -> Unit
            ): android.widget.TextView {
                return android.widget.TextView(context).apply {
                    this.text = text
                    setTextColor(if (palette.isDark) chipTextColor else palette.textPrimary)
                    textSize = 12f
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    val padH = (10 * resources.displayMetrics.density).toInt()
                    val padV = (6 * resources.displayMetrics.density).toInt()
                    setPadding(padH, padV, padH, padV)
                    if (iconRes != null) {
                        val iconSize = (16 * resources.displayMetrics.density).toInt()
                        val drawable = androidx.core.content.ContextCompat.getDrawable(context, iconRes)?.mutate()
                        if (drawable != null) {
                            if (iconTint != null) {
                                androidx.core.graphics.drawable.DrawableCompat.setTint(drawable, iconTint)
                            }
                            drawable.setBounds(0, 0, iconSize, iconSize)
                            setCompoundDrawables(drawable, null, null, null)
                            compoundDrawablePadding = (6 * resources.displayMetrics.density).toInt()
                        }
                    }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        val chipBg = when {
                            !palette.isDark -> 0xFFF0F2F5.toInt()
                            palette.isAmoled -> 0xFF141414.toInt()
                            else -> 0xFF262626.toInt()
                        }
                        setColor(chipBg)
                        cornerRadius = 8 * resources.displayMetrics.density
                        setStroke((1 * resources.displayMetrics.density).toInt(), palette.dividerLineColor)
                    }
                    val params = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = (8 * resources.displayMetrics.density).toInt() }
                    layoutParams = params
                    setOnClickListener { onClick() }
                }
            }

            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            buttonBar.addView(createChip(
                getString(R.string.om_action_copy),
                iconRes = R.drawable.copy_icon_control_normal_24dp,
                iconTint = android.graphics.Color.parseColor("#B0BEC5"),
                chipTextColor = android.graphics.Color.parseColor("#CFD8DC")
            ) {
                val text = editText.text?.toString() ?: ""
                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("path", text))
                android.widget.Toast.makeText(context, R.string.om_path_copied, android.widget.Toast.LENGTH_SHORT).show()
            })
            buttonBar.addView(createChip(
                getString(R.string.paste),
                iconRes = R.drawable.paste_icon_control_normal_24dp,
                iconTint = android.graphics.Color.parseColor("#B0BEC5"),
                chipTextColor = android.graphics.Color.parseColor("#CFD8DC")
            ) {
                val clipText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
                if (!clipText.isNullOrEmpty()) {
                    editText.setText(clipText)
                    editText.setSelection(clipText.length)
                }
            })
            buttonBar.addView(createChip(
                getString(R.string.om_chip_internal),
                iconRes = R.drawable.home_icon_white_24dp,
                iconTint = android.graphics.Color.parseColor("#FFB300"),
                chipTextColor = android.graphics.Color.parseColor("#FFD54F")
            ) {
                editText.setText("/storage/emulated/0/")
                editText.setSelection(editText.text?.length ?: 0)
            })
            buttonBar.addView(createChip(
                getString(R.string.om_nav_downloads),
                iconRes = R.drawable.download_icon_white_24dp,
                iconTint = android.graphics.Color.parseColor("#42A5F5"),
                chipTextColor = android.graphics.Color.parseColor("#90CAF9")
            ) {
                editText.setText("/storage/emulated/0/Download/")
                editText.setSelection(editText.text?.length ?: 0)
            })
            buttonBar.addView(createChip(
                "Android/data",
                iconRes = R.drawable.directory_icon_white_24dp,
                iconTint = android.graphics.Color.parseColor("#66BB6A"),
                chipTextColor = android.graphics.Color.parseColor("#A5D6A7")
            ) {
                editText.setText("/storage/emulated/0/Android/data/")
                editText.setSelection(editText.text?.length ?: 0)
            })
            buttonBar.addView(createChip(
                getString(R.string.om_chip_root),
                iconRes = R.drawable.device_icon_white_24dp,
                iconTint = android.graphics.Color.parseColor("#FF7043"),
                chipTextColor = android.graphics.Color.parseColor("#FFAB91")
            ) {
                editText.setText("/")
                editText.setSelection(editText.text?.length ?: 0)
            })

            val detectedVolumes = StorageVolumeListLiveData.valueCompat.filter {
                !it.isPrimaryCompat && it.isMounted
            }
            for (volume in detectedVolumes) {
                val volName = volume.getDescriptionCompat(context)
                val volPath = volume.pathCompat
                buttonBar.addView(createChip(
                    volName,
                    iconRes = R.drawable.sd_card_icon_white_24dp,
                    iconTint = android.graphics.Color.parseColor("#AB47BC"),
                    chipTextColor = android.graphics.Color.parseColor("#CE93D8")
                ) {
                    editText.setText(if (volPath.endsWith("/")) volPath else "$volPath/")
                    editText.setSelection(editText.text?.length ?: 0)
                })
            }

            buttonBar.addView(createChip(
                getString(R.string.om_chip_add_storage),
                iconRes = R.drawable.add_icon_white_24dp,
                iconTint = android.graphics.Color.parseColor("#29B6F6"),
                chipTextColor = android.graphics.Color.parseColor("#81D4FA")
            ) {
                startActivity(AddStorageDialogActivity::class.createIntent())
            })

            val hsv = android.widget.HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(buttonBar)
            }
            addView(hsv)
        }

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
            .setTitle(R.string.jump_to_path)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val target = editText.text?.toString()?.trim() ?: ""
                if (target.isNotEmpty()) {
                    jumpToPathWithValidation(target)
                }
            }
            .create()

        dialog.setOnShowListener {
            editText.requestFocus()
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(editText, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }

        dialog.show()
    }

    private fun jumpToPathWithValidation(target: String) {
        try {
            val resolved = PathNavigator.resolve(target, activeFragment.currentPath)
            if (resolved == activeFragment.currentPath) {
                activeFragment.reload()
                return
            }

            lifecycleScope.launch(Dispatchers.IO) {
                var exists = false
                var isDirectory = false
                var isRegularFile = false
                var accessDenied = false

                val targetStr = target.trim()
                val isSystemOrDev = targetStr.startsWith("/dev") ||
                    targetStr.startsWith("/data") ||
                    targetStr.startsWith("/system") ||
                    targetStr.startsWith("/vendor") ||
                    targetStr.startsWith("/product") ||
                    targetStr.startsWith("/odm") ||
                    targetStr.startsWith("/apex")

                try {
                    exists = resolved.exists()
                    if (exists) {
                        isDirectory = resolved.isDirectory()
                        isRegularFile = resolved.isRegularFile()
                    }
                } catch (e: Exception) {
                    val msg = e.message ?: ""
                    if (e is java8.nio.file.AccessDeniedException ||
                        e is me.zhanghai.android.files.provider.remote.RemoteFileSystemException ||
                        msg.contains("Permission denied", ignoreCase = true) ||
                        msg.contains("Root isn't available", ignoreCase = true) ||
                        msg.contains("lstat", ignoreCase = true)) {
                        accessDenied = true
                    }
                }

                if (isSystemOrDev && !exists) {
                    accessDenied = true
                }

                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext

                    if (accessDenied) {
                        // Permission restricted path (e.g. /data, /system, or Android/data)
                        // Navigate directly so FileListFragment displays the proper SAF or Root action card!
                        activeFragment.navigateTo(resolved)
                    } else if (!exists) {
                        val displayPath = resolved.toDisplayPath(trailingSlash = true)
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(this@FileListActivity)
                            .setTitle(R.string.path_not_found_title)
                            .setMessage(getString(R.string.path_not_found_prompt, displayPath))
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(R.string.action_create_and_enter) { _, _ ->
                                lifecycleScope.launch(Dispatchers.IO) {
                                    try {
                                        resolved.createDirectories()
                                        withContext(Dispatchers.Main) {
                                            activeFragment.navigateTo(resolved)
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            android.widget.Toast.makeText(
                                                this@FileListActivity,
                                                getString(R.string.om_create_folder_error_format, e.localizedMessage ?: e.message ?: ""),
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                }
                            }
                            .show()
                    } else if (isRegularFile) {
                        val parent = resolved.parent
                        if (parent != null) {
                            activeFragment.navigateTo(parent)
                            android.widget.Toast.makeText(
                                this@FileListActivity,
                                getString(R.string.om_file_in_path_format, parent.toDisplayPath()),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            activeFragment.navigateTo(resolved)
                        }
                    } else {
                        activeFragment.navigateTo(resolved)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            android.widget.Toast.makeText(
                this,
                getString(R.string.om_path_error_format, e.localizedMessage ?: e.message ?: ""),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun syncBottomBarSelectionState() {
        if (!::fragmentA.isInitialized || !::fragmentB.isInitialized) return
        if (!activeFragment.isAdded) return
        val selected = activeFragment.selectedFiles
        if (selected.isNotEmpty()) {
            bottomBarNormal.visibility = View.GONE
            bottomBarSelection.visibility = View.VISIBLE
            updateSelectionButtons(selected.size)
        } else {
            bottomBarNormal.visibility = View.VISIBLE
            bottomBarSelection.visibility = View.GONE
        }
        updateMainToolbarNavigationIcon()
    }

    private fun observePanelPathsAndStats() {
        fragmentA.observeCurrentPath(this) { path ->
            if (activePanelIndex == 0) {
                updateTopBarInfo()
            }
        }
        fragmentB.observeCurrentPath(this) { path ->
            if (activePanelIndex == 1) {
                updateTopBarInfo()
            }
        }

        fragmentA.onPathOrStatsChanged = { path, stats ->
            if (activePanelIndex == 0) {
                val pathStr = path.toDisplayPath(trailingSlash = true)
                tvPathTitle.text = pathStr
                val disk = getDiskStats()
                tvStatsSubtitle.text = if (disk.isNotEmpty()) "$stats  $disk" else stats
                updateMainToolbarNavigationIcon()
            }
        }
        fragmentB.onPathOrStatsChanged = { path, stats ->
            if (activePanelIndex == 1) {
                val pathStr = path.toDisplayPath(trailingSlash = true)
                tvPathTitle.text = pathStr
                val disk = getDiskStats()
                tvStatsSubtitle.text = if (disk.isNotEmpty()) "$stats  $disk" else stats
                updateMainToolbarNavigationIcon()
            }
        }

        val selectionObserver: (FileListFragment, Boolean, Int) -> Unit = { frag, hasSelection, count ->
            if (frag == activeFragment) {
                if (hasSelection) {
                    bottomBarNormal.visibility = View.GONE
                    bottomBarSelection.visibility = View.VISIBLE
                    updateSelectionButtons(count)
                } else {
                    bottomBarNormal.visibility = View.VISIBLE
                    bottomBarSelection.visibility = View.GONE
                    updateTopBarInfo()
                }
                updateMainToolbarNavigationIcon()
            }
        }
        fragmentA.onSelectionChanged = selectionObserver
        fragmentB.onSelectionChanged = selectionObserver
    }

    private fun showCreateDialog() {
        val currentPathStr = try {
            activeFragment.currentPath.toFile().absolutePath
        } catch (e: Exception) {
            activeFragment.currentPath.toString()
        }
        CreateChoiceBottomSheetDialogFragment.show(
            supportFragmentManager,
            currentPath = currentPathStr,
            onFolder = { activeFragment.showCreateDirectoryDialog() },
            onFile = { activeFragment.showCreateFileDialog() },
            onZip = { activeFragment.showCreateArchiveDialog(fileItemSetOf()) },
            onKeystore = { activeFragment.showKeystoreGeneratorDialog() }
        )
    }

    val isQuickFilterOpen: Boolean
        get() = ::etQuickFilter.isInitialized && etQuickFilter.isVisible

    /** MT-style filter: the toolbar becomes a text field that filters the active panel as you type. */
    fun openQuickFilter() {
        if (!activeFragment.isAdded) return
        quickFilterTarget?.takeIf { it !== activeFragment }?.quickFilter = ""
        quickFilterTarget = activeFragment
        quickFilterPath = activeFragment.currentPath
        findViewById<View>(R.id.layoutPathTitle)?.isVisible = false
        etQuickFilter.isVisible = true
        btnQuickFilterClose.isVisible = true
        etQuickFilter.setText(activeFragment.quickFilter)
        etQuickFilter.setSelection(etQuickFilter.text?.length ?: 0)
        etQuickFilter.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
            ?.showSoftInput(etQuickFilter, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    fun closeQuickFilter() {
        if (!::etQuickFilter.isInitialized) return
        quickFilterTarget?.quickFilter = ""
        quickFilterTarget = null
        quickFilterPath = null
        if (!etQuickFilter.isVisible) return
        (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
            ?.hideSoftInputFromWindow(etQuickFilter.windowToken, 0)
        etQuickFilter.isVisible = false
        btnQuickFilterClose.isVisible = false
        etQuickFilter.setText("")
        findViewById<View>(R.id.layoutPathTitle)?.isVisible = true
        updateTopBarInfo()
    }

    fun showSearchDialog() {
        val options = arrayOf(
            getString(R.string.om_search_opt_filename),
            getString(R.string.om_search_opt_text_replace),
            getString(R.string.om_search_opt_duplicates)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.om_search_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showFileNameSearchPrompt()
                    1 -> showFileTextSearchDialog()
                    2 -> showDuplicateFinderDialog()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showFileNameSearchPrompt() {
        val input = EditText(this).apply {
            hint = getString(R.string.om_search_filename_hint)
            setSingleLine()
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.om_search_current_panel_title)
            .setView(input)
            .setPositiveButton(R.string.om_search_btn) { _, _ ->
                val query = input.text.toString().trim()
                if (query.isNotEmpty()) {
                    activeFragment.searchFiles(query)
                }
            }
            .setNegativeButton(R.string.reset) { _, _ ->
                activeFragment.stopSearching()
            }
            .show()
    }

    fun showFileTextSearchDialog() {
        val currentPathStr = try {
            activeFragment.currentPath.toFile().absolutePath
        } catch (_: Throwable) {
            activeFragment.currentPath.toString()
        }
        me.zhanghai.android.files.filelist.textsearch.FileTextSearchDialog.show(this, currentPathStr) {
            activeFragment.reload()
        }
    }

    fun showDuplicateFinderDialog() {
        val currentPathStr = try {
            activeFragment.currentPath.toFile().absolutePath
        } catch (_: Throwable) {
            activeFragment.currentPath.toString()
        }
        me.zhanghai.android.files.filelist.duplicate.DuplicateFinderDialog.show(this, currentPathStr) {
            activeFragment.reload()
        }
    }

    /** The file with the same name in the other panel, to compare two versions of one file. */
    private fun fileWithSameNameInOtherPanel(file: me.zhanghai.android.files.file.FileItem): me.zhanghai.android.files.file.FileItem? {
        if (file.attributes.isDirectory) return null
        val opposite = getOppositeFragment(activeFragment)
        if (opposite.currentPath == activeFragment.currentPath) return null
        return opposite.viewModel.fileListStateful.value.orEmpty().firstOrNull {
            it.name == file.name && !it.attributes.isDirectory
        }
    }

    private fun showMoreSelectionMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        val isPanelA = (activePanelIndex == 0)
        val moveLabel = getString(if (isPanelA) R.string.om_mt_move_right_short else R.string.om_mt_move_left_short)
        popup.menu.apply {
            add(0, 10, 0, moveLabel)
            add(0, 11, 0, getString(R.string.delete))
            if (activeFragment.selectedFiles.size == 1) {
                add(0, 12, 0, getString(R.string.rename))
                add(0, 5, 0, getString(R.string.file_item_action_properties))
            }
            if (activeFragment.selectedFiles.size == 2) {
                add(0, 3, 0, getString(R.string.action_swap_file_names))
            }
            val selectedNow = activeFragment.selectedFiles.toList()
            if (selectedNow.size >= 2 && !activeFragment.currentPath.isArchivePath) {
                add(0, 14, 0, getString(R.string.om_batch_rename_menu))
            }
            if (selectedNow.size == 2 && selectedNow.none { it.attributes.isDirectory }) {
                add(0, 15, 0, getString(R.string.om_compare_menu))
            }
            if (selectedNow.size == 1 && fileWithSameNameInOtherPanel(selectedNow[0]) != null) {
                add(0, 16, 0, getString(R.string.om_compare_with_other_panel))
            }
            add(0, 6, 0, getString(R.string.om_menu_compress_zip))
            add(0, 13, 0, getString(R.string.share))
            add(0, 2, 0, getString(R.string.action_export_file_list))
            val totalSelectable = activeFragment.selectableItemCount
            val currentSelected = activeFragment.selectedFiles.size
            if (currentSelected < totalSelectable) {
                add(0, 7, 0, getString(R.string.om_menu_select_all))
            }
            add(0, 4, 0, getString(R.string.om_menu_invert_selection))
            add(0, 1, 0, getString(R.string.om_action_uncheck_all))
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                7 -> {
                    activeFragment.selectAllFiles()
                    true
                }
                14 -> {
                    val fragment = activeFragment
                    val names = fragment.viewModel.fileListStateful.value.orEmpty().map { it.name }.toSet()
                    BatchRenameDialog.show(this, fragment.currentPath, fragment.selectedFiles.toList(), names) {
                        fragment.clearSelectedFiles()
                        fragment.reload()
                    }
                    true
                }
                15 -> {
                    val files = activeFragment.selectedFiles.toList()
                    if (files.size == 2) startActivity(FileCompareActivity.createIntent(this, files[0].path, files[1].path))
                    true
                }
                16 -> {
                    val file = activeFragment.selectedFiles.firstOrNull()
                    val other = file?.let { fileWithSameNameInOtherPanel(it) }
                    if (file != null && other != null) {
                        val (left, right) = if (activePanelIndex == 0) file.path to other.path else other.path to file.path
                        startActivity(FileCompareActivity.createIntent(this, left, right))
                    }
                    true
                }
                10 -> {
                    val selected = activeFragment.selectedFiles
                    if (selected.isNotEmpty()) {
                        val opposite = getOppositeFragment(activeFragment)
                        if (activeFragment.currentPath == opposite.currentPath) {
                            Toast.makeText(this, R.string.om_err_same_src_dest, Toast.LENGTH_SHORT).show()
                        } else if (!activeFragment.canWriteTo(activeFragment.currentPath) || !activeFragment.canWriteTo(opposite.currentPath)) {
                            val msgRes = if (opposite.currentPath.isArchivePath) {
                                R.string.om_err_move_into_archive
                            } else {
                                R.string.om_err_perm_denied_move_ro
                            }
                            Toast.makeText(this, msgRes, Toast.LENGTH_SHORT).show()
                        } else {
                            activeFragment.moveFilesTo(selected, opposite.currentPath)
                        }
                    }
                    true
                }
                11 -> {
                    if (activeFragment.selectedFiles.isNotEmpty()) {
                        if (!activeFragment.canWriteTo(activeFragment.currentPath)) {
                            Toast.makeText(this, R.string.om_err_perm_denied_dir_ro, Toast.LENGTH_SHORT).show()
                        } else {
                            activeFragment.deleteSelectedFiles()
                        }
                    }
                    true
                }
                12 -> {
                    val file = activeFragment.selectedFiles.firstOrNull()
                    if (file != null) {
                        activeFragment.showRenameFileDialog(file)
                    }
                    true
                }
                13 -> {
                    if (activeFragment.selectedFiles.isNotEmpty()) {
                        activeFragment.shareFiles(activeFragment.selectedFiles)
                    }
                    true
                }
                1 -> {
                    activeFragment.clearSelectedFiles()
                    true
                }
                2 -> {
                    val allFiles = activeFragment.viewModel.fileListStateful.value ?: emptyList()
                    ExportFileListHelper.showDialog(this, activeFragment.currentPath, allFiles, activeFragment.selectedFiles.toList()) {
                        activeFragment.clearSelectedFiles()
                    }
                    true
                }
                3 -> {
                    val files = activeFragment.selectedFiles.toList()
                    if (files.size == 2) {
                        FileSwapNamesHelper.swap(this, files[0].path, files[1].path) {
                            activeFragment.clearSelectedFiles()
                            activeFragment.reload()
                        }
                    }
                    true
                }
                4 -> {
                    activeFragment.invertSelection()
                    true
                }
                5 -> {
                    val file = activeFragment.selectedFiles.firstOrNull()
                    if (file != null) {
                        activeFragment.showPropertiesDialog(file)
                    }
                    true
                }
                6 -> {
                    activeFragment.showCreateArchiveDialog(activeFragment.selectedFiles)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_ACTIVE_PANEL, activePanelIndex)
    }

    override fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean {
        if (activeFragment.onKeyShortcut(keyCode, event)) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    companion object {
        /** Menu entries whose icons keep their own colors. */
        private val COLORED_MENU_ICONS = setOf(R.id.mt_menu_search_text, R.id.mt_menu_export_file_list)
        const val EXTRA_SHOW_APKTOOL_PROGRESS = "me.zhanghai.android.files.filelist.extra.SHOW_APKTOOL_PROGRESS"
        const val EXTRA_NAVIGATE_TO_PATH = "me.zhanghai.android.files.filelist.extra.NAVIGATE_TO_PATH"
        const val EXTRA_HIGHLIGHT_FILE_PATH = "me.zhanghai.android.files.filelist.extra.HIGHLIGHT_FILE_PATH"
        /** Path URI: back on it (or a parent of it) closes this explorer and returns to the caller. */
        const val EXTRA_RETURN_ON_BACK_AT = "me.zhanghai.android.files.filelist.extra.RETURN_ON_BACK_AT"
        private const val EXTRA_HANDLED_ON_CREATE = "me.zhanghai.android.files.filelist.extra.HANDLED_ON_CREATE"
        private const val TAG_PANEL_A = "panel_a"
        private const val TAG_PANEL_B = "panel_b"
        private const val KEY_ACTIVE_PANEL = "active_panel"
        private const val ROLE_PRIMARY = "primary"
        private const val ROLE_SECONDARY = "secondary"
        private const val ROLE_ACCENT = "accent"
        private const val ROLE_ICON_ACCENT = "icon_accent"
        private const val ROLE_ICON_NEUTRAL = "icon_neutral"
        private const val ROLE_KEEP = "keep"

        fun createViewIntent(path: Path): Intent =
            FileListActivity::class.createIntent()
                .setAction(Intent.ACTION_VIEW)
                .apply { extraPath = path }

        fun createLocateFileIntent(context: Context, filePath: Path): Intent {
            val parentDir = filePath.parent ?: Paths.get("/storage/emulated/0")
            return Intent(context, FileListActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                extraPath = parentDir
                putExtra(EXTRA_NAVIGATE_TO_PATH, parentDir.toString())
                putExtra(EXTRA_HIGHLIGHT_FILE_PATH, filePath.toString())
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        }
    }

    class OpenFileContract : ActivityResultContract<List<MimeType>, Path?>() {
        override fun createIntent(context: Context, input: List<MimeType>): Intent =
            FileListActivity::class.createIntent()
                .setAction(Intent.ACTION_OPEN_DOCUMENT)
                .setType(MimeType.ANY.value)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_MIME_TYPES, input.map { it.value }.toTypedArray())

        override fun parseResult(resultCode: Int, intent: Intent?): Path? =
            if (resultCode == RESULT_OK) intent?.extraPath else null
    }

    class CreateFileContract : ActivityResultContract<Triple<MimeType, String?, Path?>, Path?>() {
        override fun createIntent(
            context: Context,
            input: Triple<MimeType, String?, Path?>
        ): Intent =
            FileListActivity::class.createIntent()
                .setAction(Intent.ACTION_CREATE_DOCUMENT)
                .setType(input.first.value)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .apply {
                    input.second?.let { putExtra(Intent.EXTRA_TITLE, it) }
                    input.third?.let { extraPath = it }
                }

        override fun parseResult(resultCode: Int, intent: Intent?): Path? =
            if (resultCode == RESULT_OK) intent?.extraPath else null
    }

    class OpenDirectoryContract : ActivityResultContract<Path?, Path?>() {
        override fun createIntent(context: Context, input: Path?): Intent =
            FileListActivity::class.createIntent()
                .setAction(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .apply { input?.let { extraPath = it } }

        override fun parseResult(resultCode: Int, intent: Intent?): Path? =
            if (resultCode == RESULT_OK) intent?.extraPath else null
    }
}
