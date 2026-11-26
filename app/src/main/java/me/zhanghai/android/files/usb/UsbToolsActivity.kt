/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.theme.custom.OpenManagerPalette
import me.zhanghai.android.files.ui.applyEdgeToEdgeInsets
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Another phone on an OTG cable: ADB when it runs Android with USB debugging on (info, commands,
 * install an APK, reboot), fastboot when it is in its bootloader (variables, flash an image,
 * reboot). Android's own USB host API; no library.
 */
class UsbToolsActivity : AppActivity() {

    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var actions: LinearLayout
    private lateinit var commandRow: LinearLayout
    private lateinit var commandInput: EditText

    private val usbManager by lazy { getSystemService(UsbManager::class.java) }
    private val lock = Mutex()
    private var adb: AdbClient? = null
    private var fastboot: FastbootClient? = null
    private var device: UsbDevice? = null

    private val pickApk = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { installApk(it) }
    }
    private val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { askPartitionAndFlash(it) }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PERMISSION -> if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    connectAttached()
                } else {
                    status.setText(R.string.om_usb_permission_denied)
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> connectAttached()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> disconnect(R.string.om_usb_none)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val palette = OpenManagerPalette.from(this)
        val dp = resources.displayMetrics.density
        val toolbar = MaterialToolbar(this).apply {
            setTitle(R.string.om_usb_title)
            setBackgroundColor(palette.toolbarBg)
            setTitleTextColor(palette.textPrimary)
            setNavigationIcon(R.drawable.arrow_back_icon_white_24dp)
            navigationIcon?.setTint(palette.iconTint)
            setNavigationOnClickListener { finish() }
            minimumHeight = (56 * dp).toInt()
        }
        status = TextView(this).apply {
            setTextColor(palette.textPrimary)
            textSize = 15f
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
        }
        actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
        }
        commandInput = EditText(this).apply {
            hint = "getprop ro.product.model"
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
        }
        commandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
            addView(commandInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(MaterialButton(context).apply {
                setText(R.string.om_usb_run)
                setOnClickListener { runCommand(commandInput.text.toString()) }
            })
        }
        output = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(palette.textPrimary)
            setTextIsSelectable(true)
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(actions)
            addView(commandRow)
            addView(output)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.screenBg)
            addView(toolbar)
            addView(ScrollView(context).apply { addView(content) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        toolbar.applyEdgeToEdgeInsets(applyTop = true, applyHorizontal = true)
        content.applyEdgeToEdgeInsets(applyBottom = true, applyHorizontal = true)
        showActions(null)
        status.setText(R.string.om_usb_none)

        val filter = IntentFilter().apply {
            addAction(ACTION_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        connectAttached()
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        runCatching { adb?.close() }
        runCatching { fastboot?.close() }
        super.onDestroy()
    }

    /** The first attached device speaking ADB or fastboot; asks for USB permission if needed. */
    private fun connectAttached() {
        val manager = usbManager ?: return
        val found = manager.deviceList.values.firstNotNullOfOrNull { d ->
            UsbBulkPipe.findInterface(d)?.let { d to it }
        }
        if (found == null) {
            disconnect(R.string.om_usb_none)
            return
        }
        val (usbDevice, intfMode) = found
        if (!manager.hasPermission(usbDevice)) {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val intent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_PERMISSION).setPackage(packageName), flags)
            manager.requestPermission(usbDevice, intent)
            return
        }
        if (device == usbDevice && (adb != null || fastboot != null)) return
        device = usbDevice
        val (intf, mode) = intfMode
        status.text = getString(R.string.om_usb_connecting, usbDevice.productName ?: usbDevice.deviceName)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val connection = manager.openDevice(usbDevice) ?: error("USB open failed")
                    val pipe = UsbBulkPipe.open(connection, intf)
                    if (mode == UsbToolMode.ADB) {
                        val client = AdbClient(pipe, loadKeys(), "OpenManager@" + Build.MODEL)
                        client.connect {
                            runOnUiThread { status.setText(R.string.om_usb_accept_on_device) }
                        }
                        adb = client
                        client.banner
                    } else {
                        val client = FastbootClient(pipe)
                        fastboot = client
                        "fastboot: " + runCatching { client.getvar("product") }.getOrDefault("?")
                    }
                }
            }
            result.onSuccess { banner ->
                status.text = getString(
                    if (mode == UsbToolMode.ADB) R.string.om_usb_connected_adb else R.string.om_usb_connected_fastboot,
                    describeBanner(banner)
                )
                showActions(mode)
            }.onFailure {
                disconnect(R.string.om_usb_failed)
                output.text = it.message
            }
        }
    }

    private fun describeBanner(banner: String): String {
        val props = banner.substringAfter("::").split(';').mapNotNull {
            val (k, v) = it.split('=', limit = 2).takeIf { p -> p.size == 2 } ?: return@mapNotNull null
            k to v
        }.toMap()
        return props["ro.product.model"] ?: props["ro.product.name"] ?: banner.take(60)
    }

    private fun disconnect(statusRes: Int) {
        runCatching { adb?.close() }
        runCatching { fastboot?.close() }
        adb = null
        fastboot = null
        device = null
        status.setText(statusRes)
        showActions(null)
    }

    private fun showActions(mode: UsbToolMode?) {
        actions.removeAllViews()
        commandRow.visibility = if (mode == UsbToolMode.ADB) android.view.View.VISIBLE else android.view.View.GONE
        fun button(textRes: Int, onClick: () -> Unit) = actions.addView(MaterialButton(this).apply {
            setText(textRes)
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        when (mode) {
            UsbToolMode.ADB -> {
                button(R.string.om_usb_device_info) {
                    runCommand(
                        "getprop ro.product.manufacturer; getprop ro.product.model; " +
                            "getprop ro.build.version.release; getprop ro.build.display.id; " +
                            "getprop ro.boot.slot_suffix; df -h /data | tail -1"
                    )
                }
                button(R.string.om_usb_install_apk) { pickApk.launch(arrayOf("application/vnd.android.package-archive")) }
                button(R.string.om_usb_reboot) { chooseReboot(listOf("", "bootloader", "recovery", "fastboot")) }
            }
            UsbToolMode.FASTBOOT -> {
                button(R.string.om_usb_variables) { work { fastboot!!.allVariables().joinToString("\n") } }
                button(R.string.om_usb_flash) { pickImage.launch(arrayOf("*/*")) }
                button(R.string.om_usb_reboot) { chooseReboot(listOf("", "bootloader", "recovery", "fastboot")) }
            }
            null -> output.setText(R.string.om_usb_help)
        }
    }

    /** Runs [block] off the main thread, one at a time, and shows its result. */
    private fun work(block: suspend () -> String) {
        output.setText(R.string.om_usb_working)
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                lock.withLock { runCatching { block() }.fold({ it }, { "✗ " + (it.message ?: it.javaClass.simpleName) }) }
            }
            output.text = text
        }
    }

    private fun runCommand(command: String) {
        val client = adb ?: return
        if (command.isBlank()) return
        work { client.shell(command).ifBlank { "✓" } }
    }

    private fun chooseReboot(targets: List<String>) {
        val labels = targets.map {
            when (it) {
                "" -> getString(R.string.om_usb_reboot_system)
                "bootloader" -> "Bootloader"
                "recovery" -> "Recovery"
                else -> "Fastbootd"
            }
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.om_usb_reboot)
            .setItems(labels) { _, which ->
                val target = targets[which]
                work {
                    adb?.reboot(target) ?: fastboot?.reboot(target)
                    getString(R.string.om_usb_rebooting)
                }
            }
            .show()
    }

    private fun sizeOf(uri: Uri): Long =
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) it.getLong(0) else -1L
        } ?: -1L

    private fun installApk(uri: Uri) {
        val client = adb ?: return
        val size = sizeOf(uri)
        work {
            contentResolver.openInputStream(uri)!!.use { input ->
                client.install(input, size) { sent ->
                    if (size > 0) runOnUiThread { output.text = getString(R.string.om_usb_progress, sent * 100 / size) }
                }
            }
        }
    }

    private fun askPartitionAndFlash(uri: Uri) {
        val client = fastboot ?: return
        val size = sizeOf(uri)
        val input = EditText(this).apply {
            hint = "boot"
            setSingleLine()
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.om_usb_flash_partition)
            .setView(input)
            .setPositiveButton(R.string.om_usb_flash) { _, _ ->
                val partition = input.text.toString().trim()
                if (partition.isEmpty()) return@setPositiveButton
                // Writing a partition can leave the other phone unable to start: say it plainly.
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.om_usb_flash)
                    .setMessage(getString(R.string.om_usb_flash_confirm, partition))
                    .setPositiveButton(R.string.om_usb_flash) { _, _ ->
                        work {
                            contentResolver.openInputStream(uri)!!.use { stream ->
                                client.flash(partition, stream, size) { sent ->
                                    if (size > 0) runOnUiThread { output.text = getString(R.string.om_usb_progress, sent * 100 / size) }
                                }
                            }
                            getString(R.string.om_usb_flashed, partition)
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** One RSA key for this phone, kept in private storage (the other phone remembers it). */
    private fun loadKeys(): KeyPair {
        val dir = File(noBackupFilesDir, "adb").apply { mkdirs() }
        val private = File(dir, "adbkey")
        val public = File(dir, "adbkey.pub")
        if (private.exists() && public.exists()) {
            runCatching {
                val factory = KeyFactory.getInstance("RSA")
                return KeyPair(
                    factory.generatePublic(X509EncodedKeySpec(public.readBytes())),
                    factory.generatePrivate(PKCS8EncodedKeySpec(private.readBytes()))
                )
            }
        }
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        private.writeBytes(keys.private.encoded)
        public.writeBytes(keys.public.encoded)
        return keys
    }

    companion object {
        private const val ACTION_PERMISSION = "me.zhanghai.android.files.usb.PERMISSION"
    }
}
