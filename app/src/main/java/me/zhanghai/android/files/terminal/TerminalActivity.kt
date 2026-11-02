/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.PowerManager
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.Menu
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import com.google.android.material.appbar.MaterialToolbar
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.provider.root.DeadManProtectionManager
import me.zhanghai.android.files.terminal.tracer.BinaryTracerEngine
import me.zhanghai.android.files.terminal.tracer.BinaryTracerDialog
import java.io.File
import kotlin.math.roundToInt

class TerminalActivity : AppActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var terminalView: TerminalView
    private lateinit var extraKeysView: TerminalExtraKeysView
    private var btnZoomOut: TextView? = null
    private var btnZoomIn: TextView? = null
    private var tvZoomSize: TextView? = null
    private var btnCopyScreen: ImageButton? = null
    private var btnScriptAudit: ImageButton? = null
    private var btnDeadManSkull: ImageButton? = null
    private var btnOverflowMenu: ImageButton? = null
    private var layoutDeadManAlertBadge: android.widget.LinearLayout? = null
    private var lastDeadManTarget: String = ""
    private var lastDeadManCmd: String = ""

    private var terminalSession: TerminalSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentPid: Int = -1
    private lateinit var pinchScaleDetector: ScaleGestureDetector
    private var isPinching: Boolean = false

    companion object {
        const val EXTRA_PATH = "me.zhanghai.android.files.terminal.extra.PATH"
        const val EXTRA_CWD = "me.zhanghai.android.files.terminal.extra.CWD"
        const val EXTRA_COMMAND = "me.zhanghai.android.files.terminal.extra.COMMAND"
        const val EXTRA_FORCE_ROOT = "me.zhanghai.android.files.terminal.extra.FORCE_ROOT"
        const val EXTRA_FORCE_TERMEX = "me.zhanghai.android.files.terminal.extra.FORCE_TERMEX"
        const val EXTRA_ACTIVE_SCRIPT_PATH = "me.zhanghai.android.files.terminal.extra.ACTIVE_SCRIPT_PATH"

        fun createIntent(
            context: Context,
            path: String? = null,
            command: String? = null,
            forceRoot: Boolean? = null,
            forceTermex: Boolean? = null,
            activeScriptPath: String? = null
        ): Intent {
            return Intent(context, TerminalActivity::class.java).apply {
                if (path != null) {
                    putExtra(EXTRA_PATH, path)
                    putExtra(EXTRA_CWD, path)
                }
                if (command != null) {
                    putExtra(EXTRA_COMMAND, command)
                }
                if (forceRoot != null) {
                    putExtra(EXTRA_FORCE_ROOT, forceRoot)
                }
                if (forceTermex != null) {
                    putExtra(EXTRA_FORCE_TERMEX, forceTermex)
                }
                if (activeScriptPath != null) {
                    putExtra(EXTRA_ACTIVE_SCRIPT_PATH, activeScriptPath)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)

        toolbar = findViewById(R.id.toolbar)
        terminalView = findViewById(R.id.terminalView)
        extraKeysView = findViewById(R.id.extraKeysView)
        btnZoomOut = findViewById(R.id.btnZoomOut)
        btnZoomIn = findViewById(R.id.btnZoomIn)
        tvZoomSize = findViewById(R.id.tvZoomSize)
        btnCopyScreen = findViewById(R.id.btnCopyScreen)
        btnScriptAudit = findViewById(R.id.btnScriptAudit)
        btnDeadManSkull = findViewById(R.id.btnDeadManSkull)
        btnOverflowMenu = findViewById(R.id.btnOverflowMenu)
        layoutDeadManAlertBadge = findViewById(R.id.layoutDeadManAlertBadge)

        btnScriptAudit?.setOnClickListener {
            showScriptAuditDialog()
        }
        btnOverflowMenu?.setOnClickListener {
            showOverflowMenu(it)
        }

        btnDeadManSkull?.setOnClickListener {
            if (lastDeadManTarget.isNotEmpty()) {
                DeadManProtectionManager.showDeadManProtectionDialog(this, lastDeadManTarget, lastDeadManCmd)
            }
        }
        layoutDeadManAlertBadge?.setOnClickListener {
            if (lastDeadManTarget.isNotEmpty()) {
                DeadManProtectionManager.showDeadManProtectionDialog(this, lastDeadManTarget, lastDeadManCmd)
            }
        }

        setupToolbar()
        setupPinchZoomDetector()
        setupExtraKeys()
        applyPreferences()
        startTerminalSession()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val cmd = intent.getStringExtra(EXTRA_COMMAND)
        if (cmd != null && cmd != ScriptExecutionManager.activeCommand) {
            startTerminalSession()
        }
    }

    fun showScriptAuditDialog() {
        val activeScript = ScriptExecutionManager.activeScriptPath
            ?: intent.getStringExtra(EXTRA_ACTIVE_SCRIPT_PATH)
            ?: intent.getStringExtra(EXTRA_PATH)
        me.zhanghai.android.files.terminal.tracer.ScriptAuditDialogFragment.show(this, activeScript)
    }

    fun showDeadManSkullAlert(target: String, cmd: String) {
        lastDeadManTarget = target
        lastDeadManCmd = cmd
        runOnUiThread {
            btnDeadManSkull?.visibility = View.VISIBLE
            layoutDeadManAlertBadge?.visibility = View.VISIBLE
            DeadManProtectionManager.showDeadManProtectionDialog(this, target, cmd)
        }
    }

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener {
            finish()
        }

        btnZoomOut?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            changeFontSizeBy(-1)
        }

        btnZoomIn?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            changeFontSizeBy(+1)
        }

        tvZoomSize?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            TerminalSettings.setFontSize(this, TerminalSettings.DEFAULT_FONT_SIZE_SP)
            applyPreferences()
            Toast.makeText(this, "Zoom restablecido (${TerminalSettings.DEFAULT_FONT_SIZE_SP}sp)", Toast.LENGTH_SHORT).show()
        }

        btnCopyScreen?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            copyTerminalTranscript()
        }
    }

    private fun setupPinchZoomDetector() {
        pinchScaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                private var accumulatedFactor = 1.0f

                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    isPinching = true
                    accumulatedFactor = 1.0f
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    accumulatedFactor *= detector.scaleFactor
                    if (accumulatedFactor < 0.91f || accumulatedFactor > 1.09f) {
                        val delta = if (accumulatedFactor > 1.0f) 1 else -1
                        changeFontSizeBy(delta)
                        accumulatedFactor = 1.0f
                    }
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    isPinching = false
                    accumulatedFactor = 1.0f
                }
            }
        )
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.pointerCount >= 2 || isPinching) {
            pinchScaleDetector.onTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                isPinching = false
            }
            if (ev.pointerCount >= 2) {
                return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun changeFontSizeBy(delta: Int) {
        val currentSize = TerminalSettings.getFontSize(this)
        val newSize = (currentSize + delta).coerceIn(
            TerminalSettings.MIN_FONT_SIZE_SP,
            TerminalSettings.MAX_FONT_SIZE_SP
        )
        if (newSize != currentSize) {
            TerminalSettings.setFontSize(this, newSize)
            applyPreferences()
        }
    }

    private fun setupExtraKeys() {
        extraKeysView.onKeyString = { keyStr ->
            if (terminalSession?.isRunning == false) {
                if (keyStr == "\r" || keyStr == "\n") {
                    finish()
                }
            } else {
                terminalSession?.write(keyStr)
            }
        }
        extraKeysView.onOverflowMenuClick = { anchor ->
            showOverflowMenu(anchor)
        }
    }

    private fun applyPreferences() {
        val fontSizeSp = TerminalSettings.getFontSize(this)
        val fontSizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            fontSizeSp.toFloat(),
            resources.displayMetrics
        ).roundToInt().coerceAtLeast(10)
        terminalView.setTextSize(fontSizePx)
        terminalView.setTypeface(Typeface.MONOSPACE)
        tvZoomSize?.text = "${fontSizeSp}sp"

        if (TerminalSettings.isKeepBlackTheme(this)) {
            terminalView.setBackgroundColor(Color.BLACK)
            window.statusBarColor = Color.BLACK
            window.navigationBarColor = Color.BLACK
        }
    }

    private fun startTerminalSession(forceRootOverride: Boolean? = null) {
        val initialCommand = intent.getStringExtra(EXTRA_COMMAND)

        val sessionClient = object : TerminalSessionClient {
            override fun onTextChanged(changedSession: TerminalSession) {
                terminalView.onScreenUpdated()
                ScriptExecutionManager.notifyOutputUpdated()
            }

            override fun onTerminalDataReceived(session: TerminalSession, data: ByteArray, offset: Int, length: Int) {
                val chunk = String(data, offset, length, Charsets.UTF_8)
                val activeScript = ScriptExecutionManager.activeScriptPath
                    ?: intent.getStringExtra(EXTRA_ACTIVE_SCRIPT_PATH)
                    ?: intent.getStringExtra(EXTRA_PATH)
                BinaryTracerEngine.parseTerminalChunk(chunk, this@TerminalActivity, session, activeScript)
            }

            override fun onTitleChanged(changedSession: TerminalSession) {
                runOnUiThread {
                    val title = changedSession.title
                    if (!title.isNullOrEmpty()) {
                        toolbar.subtitle = title
                        ScriptExecutionManager.notifyTitleChanged(title)
                    }
                }
            }

            override fun onSessionFinished(finishedSession: TerminalSession) {
                runOnUiThread {
                    toolbar.subtitle = "[Terminado (código ${finishedSession.exitStatus})]"
                }
                ScriptExecutionManager.onSessionFinished(finishedSession.exitStatus, this@TerminalActivity)
            }

            override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("terminal", text))
                Toast.makeText(this@TerminalActivity, R.string.terminal_copy, Toast.LENGTH_SHORT).show()
            }

            override fun onPasteTextFromClipboard(session: TerminalSession?) {
                pasteFromClipboard()
            }

            override fun onBell(session: TerminalSession) {}

            override fun onColorsChanged(session: TerminalSession) {
                terminalView.onScreenUpdated()
            }

            override fun onTerminalCursorStateChange(state: Boolean) {}

            override fun setTerminalShellPid(session: TerminalSession, pid: Int) {
                currentPid = pid
                ScriptExecutionManager.activePid = pid
                ScriptExecutionService.updateNotification(this@TerminalActivity)
            }

            override fun getTerminalCursorStyle(): Int {
                return TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK
            }

            override fun logError(tag: String?, message: String?) {}
            override fun logWarn(tag: String?, message: String?) {}
            override fun logInfo(tag: String?, message: String?) {}
            override fun logDebug(tag: String?, message: String?) {}
            override fun logVerbose(tag: String?, message: String?) {}
            override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
            override fun logStackTrace(tag: String?, e: Exception?) {}
        }

        val viewClient = object : TerminalViewClient {
            override fun onScale(scale: Float): Float {
                if (scale < 0.90f || scale > 1.10f) {
                    val delta = if (scale > 1.0f) 1 else -1
                    changeFontSizeBy(delta)
                    return 1.0f
                }
                return scale
            }

            override fun onSingleTapUp(e: MotionEvent?) {
                terminalView.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
            }

            override fun shouldBackButtonBeMappedToEscape(): Boolean = false
            override fun shouldEnforceCharBasedInput(): Boolean = false
            override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
            override fun isTerminalViewSelected(): Boolean = true

            override fun copyModeChanged(copyMode: Boolean) {}

            override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean {
                if (session != null && !session.isRunning) {
                    if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                        finish()
                        return true
                    }
                }
                // Volume Up / Down zoom shortcuts
                if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                    changeFontSizeBy(+1)
                    return true
                }
                if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                    changeFontSizeBy(-1)
                    return true
                }
                return false
            }

            override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean {
                if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                    return true
                }
                return false
            }

            override fun onLongPress(event: MotionEvent?): Boolean = false

            override fun readControlKey(): Boolean {
                return extraKeysView.consumeCtrl()
            }

            override fun readAltKey(): Boolean {
                return extraKeysView.consumeAlt()
            }

            override fun readShiftKey(): Boolean = false
            override fun readFnKey(): Boolean = false

            override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
                return false
            }

            override fun onEmulatorSet() {}

            override fun logError(tag: String?, message: String?) {}
            override fun logWarn(tag: String?, message: String?) {}
            override fun logInfo(tag: String?, message: String?) {}
            override fun logDebug(tag: String?, message: String?) {}
            override fun logVerbose(tag: String?, message: String?) {}
            override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
            override fun logStackTrace(tag: String?, e: Exception?) {}
        }

        // 1. Check if we can seamlessly reattach to existing running session
        if (initialCommand == null && ScriptExecutionManager.hasActiveSession()) {
            val existingSession = ScriptExecutionManager.activeSession!!
            existingSession.updateTerminalSessionClient(sessionClient)
            terminalSession = existingSession
            currentPid = ScriptExecutionManager.activePid

            terminalView.setTerminalViewClient(viewClient)
            terminalView.attachSession(existingSession)

            val mode = if (ScriptExecutionManager.isRoot) "Root (#)" else if (ScriptExecutionManager.isTermex) "Termex (bash)" else "Sistema (sh)"
            toolbar.title = ScriptExecutionManager.activeScriptName
            toolbar.subtitle = if (existingSession.isRunning) "$mode • En ejecución..." else "[Terminado (código ${existingSession.exitStatus})]"
            return
        }

        terminalSession?.finishIfRunning()

        if (!initialCommand.isNullOrEmpty() &&
            DeadManProtectionManager.isDfrootProtectionActive(this) &&
            DeadManProtectionManager.containsDangerousSystemCommand(initialCommand)
        ) {
            DeadManProtectionManager.showDirtyFlagProtectionDialog(this) {
                finish()
            }
            return
        }

        val overridePath = intent.getStringExtra(EXTRA_PATH)
            ?: intent.getStringExtra(EXTRA_CWD)
            ?: intent.data?.path

        val forceRoot = forceRootOverride
            ?: if (intent.hasExtra(EXTRA_FORCE_ROOT)) intent.getBooleanExtra(EXTRA_FORCE_ROOT, false) else null
        val forceTermex = if (intent.hasExtra(EXTRA_FORCE_TERMEX)) intent.getBooleanExtra(EXTRA_FORCE_TERMEX, false) else null

        val activeScript = intent.getStringExtra(EXTRA_ACTIVE_SCRIPT_PATH)
            ?: intent.getStringExtra(EXTRA_PATH)
            ?: intent.getStringExtra(EXTRA_CWD)

        val config = TerminalExpansionManager.prepareLaunchConfig(
            this,
            forceRoot = forceRoot,
            forceTermex = forceTermex,
            overrideCwd = overridePath,
            initialCommand = initialCommand,
            activeScriptPath = activeScript
        )

        val session = TerminalSession(
            config.executable,
            config.cwd,
            config.args,
            config.env,
            5000,
            sessionClient
        )
        terminalSession = session

        val scriptName = if (initialCommand != null) {
            val lastPathPart = initialCommand.split(" ", ";").lastOrNull { it.contains("/") } ?: initialCommand
            val fileName = File(lastPathPart.trim().trim('"', '\'', ')', '(')).name
            if (fileName.isNotEmpty()) fileName else initialCommand
        } else {
            val dirName = File(config.cwd).name.ifEmpty { "/" }
            "Terminal ($dirName)"
        }

        ScriptExecutionManager.startExecution(
            context = this,
            session = session,
            scriptName = scriptName,
            path = activeScript ?: overridePath,
            command = initialCommand,
            root = config.isRoot,
            termex = config.isTermex
        )

        terminalView.setTerminalViewClient(viewClient)
        terminalView.attachSession(session)

        val mode = if (config.isRoot) "Root (#)" else if (config.isTermex) "Termex (bash)" else "Sistema (sh)"
        toolbar.title = scriptName
        toolbar.subtitle = "$mode • Ejecutando..."
    }

    private fun copyTerminalTranscript() {
        val transcript = try {
            terminalSession?.emulator?.screen?.transcriptText?.trim()
        } catch (_: Throwable) {
            null
        }
        if (!transcript.isNullOrEmpty()) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("Terminal Output", transcript))
            Toast.makeText(this, "Salida de terminal copiada al portapapeles", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "No hay texto en pantalla para copiar", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showOverflowMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.apply {
            val wakeTitle = if (wakeLock?.isHeld == true) {
                "${getString(R.string.terminal_wakelock)} (✓)"
            } else {
                getString(R.string.terminal_wakelock)
            }
            add(Menu.NONE, 1, 1, wakeTitle)
            add(Menu.NONE, 12, 2, R.string.tracer_menu_item)
            add(Menu.NONE, 10, 3, R.string.script_menu_keep_background)
            add(Menu.NONE, 11, 4, R.string.script_menu_terminate)
            add(Menu.NONE, 5, 5, R.string.terminal_paste)
            add(Menu.NONE, 6, 5, "Copiar toda la pantalla")
            add(Menu.NONE, 7, 6, "Limpiar pantalla (clear)")
            add(Menu.NONE, 8, 7, "Cambiar a Root (su)")
            add(Menu.NONE, 9, 8, "Nueva sesión limpia")
            add(Menu.NONE, 3, 9, R.string.terminal_reset)
            add(Menu.NONE, 4, 10, R.string.terminal_preferences)
            add(Menu.NONE, 2, 11, R.string.terminal_close)
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    toggleWakeLock()
                    true
                }
                2 -> {
                    finish()
                    true
                }
                3 -> {
                    terminalSession?.reset()
                    terminalView.onScreenUpdated()
                    Toast.makeText(this, R.string.terminal_reset, Toast.LENGTH_SHORT).show()
                    true
                }
                4 -> {
                    val dialog = TerminalPreferencesDialog()
                    dialog.onSettingsChanged = {
                        applyPreferences()
                    }
                    dialog.show(supportFragmentManager, "TerminalPreferences")
                    true
                }
                5 -> {
                    pasteFromClipboard()
                    true
                }
                6 -> {
                    copyTerminalTranscript()
                    true
                }
                7 -> {
                    terminalSession?.write("clear\n")
                    true
                }
                8 -> {
                    terminalSession?.write("su\n")
                    true
                }
                9 -> {
                    startTerminalSession()
                    Toast.makeText(this, "Nueva sesión iniciada", Toast.LENGTH_SHORT).show()
                    true
                }
                10 -> {
                    ScriptExecutionManager.keepInBackground(this)
                    Toast.makeText(this, R.string.script_exec_kept_toast, Toast.LENGTH_SHORT).show()
                    moveTaskToBack(true)
                    true
                }
                11 -> {
                    ScriptExecutionManager.terminateShell(this)
                    Toast.makeText(this, "Proceso terminado", Toast.LENGTH_SHORT).show()
                    finish()
                    true
                }
                12 -> {
                    showScriptAuditDialog()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun toggleWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (wakeLock == null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenManager:TerminalWakeLock")
        }

        wakeLock?.let { wl ->
            if (wl.isHeld) {
                wl.release()
                Toast.makeText(this, R.string.terminal_wakelock_released, Toast.LENGTH_SHORT).show()
            } else {
                wl.acquire(60 * 60 * 1000L) // 1 hour max
                Toast.makeText(this, R.string.terminal_wakelock_acquired, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = cm.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).coerceToText(this)?.toString()
            if (!text.isNullOrEmpty()) {
                terminalSession?.write(text)
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (terminalSession?.isRunning == false && event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                finish()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onBackPressed() {
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        if (terminalSession?.isRunning == true) {
            Toast.makeText(this, R.string.script_exec_running_background_toast, Toast.LENGTH_SHORT).show()
        } else {
            ScriptExecutionManager.clearSession()
            ScriptExecutionService.stop(this)
        }
    }
}
