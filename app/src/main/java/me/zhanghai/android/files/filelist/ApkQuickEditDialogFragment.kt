/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.app.Dialog
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.util.getPackageArchiveInfoCompat
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.show
import java.io.File
import java.io.FileOutputStream

class ApkQuickEditDialogFragment : AppCompatDialogFragment() {

    private val args by args<Args>()

    private var selectedIconBytes: ByteArray? = null

    private val pickIconLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            try {
                val bytes = requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null && bytes.isNotEmpty()) {
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) {
                        dialog?.findViewById<ImageView>(R.id.ivQuickEditIcon)?.setImageBitmap(bitmap)
                        selectedIconBytes = bytes
                        Toast.makeText(requireContext(), "Icono seleccionado", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), "Formato de imagen no compatible", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(requireContext(), "Error al cargar icono: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val file = args.file

        val view = LayoutInflater.from(context).inflate(R.layout.dialog_apk_quick_edit, null)
        val ivIcon = view.findViewById<ImageView>(R.id.ivQuickEditIcon)
        val tvSubtitle = view.findViewById<TextView>(R.id.tvQuickEditSubtitle)
        val progressBar = view.findViewById<ProgressBar>(R.id.progressBar)
        val layoutForm = view.findViewById<LinearLayout>(R.id.layoutForm)

        val tilAppName = view.findViewById<TextInputLayout>(R.id.tilAppName)
        val etAppName = view.findViewById<TextInputEditText>(R.id.etAppName)

        val tilPackageName = view.findViewById<TextInputLayout>(R.id.tilPackageName)
        val etPackageName = view.findViewById<TextInputEditText>(R.id.etPackageName)

        val etVersionName = view.findViewById<TextInputEditText>(R.id.etVersionName)
        val etVersionCode = view.findViewById<TextInputEditText>(R.id.etVersionCode)

        val actvMinSdk = view.findViewById<AutoCompleteTextView>(R.id.actvMinSdk)
        val actvTargetSdk = view.findViewById<AutoCompleteTextView>(R.id.actvTargetSdk)
        val actvInstallLocation = view.findViewById<AutoCompleteTextView>(R.id.actvInstallLocation)
        val cbAutoSign = view.findViewById<MaterialCheckBox>(R.id.cbAutoSign)
        val cbDebuggable = view.findViewById<MaterialCheckBox>(R.id.cbDebuggable)
        val cbExtractNativeLibs = view.findViewById<MaterialCheckBox>(R.id.cbExtractNativeLibs)

        tvSubtitle.text = file.name

        ivIcon.setOnClickListener {
            pickIconLauncher.launch("image/*")
        }

        // Setup SDK version suggestions
        val sdkSuggestions = listOf(
            "21 (Android 5.0 Lollipop)",
            "22 (Android 5.1 Lollipop MR1)",
            "23 (Android 6.0 Marshmallow)",
            "24 (Android 7.0 Nougat)",
            "25 (Android 7.1 Nougat MR1)",
            "26 (Android 8.0 Oreo)",
            "27 (Android 8.1 Oreo MR1)",
            "28 (Android 9.0 Pie)",
            "29 (Android 10 Q)",
            "30 (Android 11 R)",
            "31 (Android 12 S)",
            "32 (Android 12L S_V2)",
            "33 (Android 13 Tiramisu)",
            "34 (Android 14 Upside Down Cake)",
            "35 (Android 15 Vanilla Ice Cream)",
            "36 (Android 16 Baklava)"
        )
        val sdkAdapter = ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, sdkSuggestions)
        actvMinSdk.setAdapter(sdkAdapter)
        actvTargetSdk.setAdapter(sdkAdapter)

        // Setup install location options
        val installLocationMap = listOf(
            0 to getString(R.string.apk_quick_edit_install_auto),
            1 to getString(R.string.apk_quick_edit_install_internal),
            2 to getString(R.string.apk_quick_edit_install_external),
            -1 to getString(R.string.apk_quick_edit_install_default)
        )
        val locationNames = installLocationMap.map { it.second }
        val locationAdapter = ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, locationNames)
        actvInstallLocation.setAdapter(locationAdapter)

        // Load existing APK launcher icon if possible
        try {
            val (packageInfo, closeable) = context.packageManager.getPackageArchiveInfoCompat(file.path, 0)
            closeable.use {
                val appInfo = packageInfo?.applicationInfo
                if (appInfo != null) {
                    try {
                        appInfo.sourceDir = file.path.toUserFriendlyString()
                        appInfo.publicSourceDir = file.path.toUserFriendlyString()
                    } catch (e: Exception) {}
                    val iconDrawable = appInfo.loadIcon(context.packageManager)
                    if (iconDrawable != null) {
                        ivIcon.setImageDrawable(iconDrawable)
                    }
                }
            }
        } catch (ignored: Exception) {}

        // Load APK info in background
        lifecycleScope.launch(Dispatchers.IO) {
            val localApk: File = try {
                if (file.path.isLinuxPath) {
                    file.path.toFile()
                } else {
                    val tmp = File(context.cacheDir, "quickedit_info_${System.currentTimeMillis()}_${file.name}")
                    file.path.newInputStream().use { inp ->
                        FileOutputStream(tmp).use { out -> inp.copyTo(out) }
                    }
                    tmp
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error al acceder al archivo APK: ${e.message}", Toast.LENGTH_SHORT).show()
                    dismiss()
                }
                return@launch
            }

            try {
                val info = ApkQuickEditEngine.loadQuickEditInfo(localApk)
                withContext(Dispatchers.Main) {
                    progressBar.isVisible = false
                    layoutForm.isVisible = true

                    etAppName.setText(info.appName)
                    etPackageName.setText(info.packageName)
                    etVersionName.setText(info.versionName)
                    etVersionCode.setText(info.versionCode.toString())
                    actvMinSdk.setText(info.minSdkVersion.toString(), false)
                    actvTargetSdk.setText(info.targetSdkVersion.toString(), false)

                    val defaultLocationText = installLocationMap.find { it.first == (info.installLocation ?: -1) }?.second
                        ?: locationNames.last()
                    actvInstallLocation.setText(defaultLocationText, false)

                    cbDebuggable.isChecked = info.isDebuggable
                    cbExtractNativeLibs.isChecked = info.isExtractNativeLibs ?: true
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    progressBar.isVisible = false
                    Toast.makeText(context, "Error al analizar manifiesto del APK: ${e.message}", Toast.LENGTH_LONG).show()
                    dismiss()
                }
            } finally {
                if (!file.path.isLinuxPath && localApk.exists()) {
                    localApk.delete()
                }
            }
        }

        val alertDialog = MaterialAlertDialogBuilder(context)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        alertDialog.setOnShowListener {
            val saveBtn = alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)
            saveBtn.setOnClickListener {
                val newPackage = etPackageName.text?.toString()?.trim().orEmpty()
                if (newPackage.isNotEmpty() && !newPackage.matches(Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$"))) {
                    tilPackageName.error = getString(R.string.apk_quick_edit_invalid_package)
                    return@setOnClickListener
                }
                tilPackageName.error = null

                val newAppName = etAppName.text?.toString()?.trim()
                val newVerName = etVersionName.text?.toString()?.trim()
                val newVerCode = etVersionCode.text?.toString()?.trim()?.toIntOrNull()

                val minSdkRaw = actvMinSdk.text?.toString()?.takeWhile { it.isDigit() }?.toIntOrNull()
                val targetSdkRaw = actvTargetSdk.text?.toString()?.takeWhile { it.isDigit() }?.toIntOrNull()

                val selectedLocText = actvInstallLocation.text?.toString()
                val selectedLoc = installLocationMap.find { it.second == selectedLocText }?.first ?: -1

                val autoSign = cbAutoSign.isChecked

                val savingDialog = MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.apk_quick_edit)
                    .setMessage(R.string.apk_quick_edit_saving)
                    .setCancelable(false)
                    .create()
                savingDialog.show()

                lifecycleScope.launch(Dispatchers.IO) {
                    var tempInputFile: File? = null
                    try {
                        val inputApk: File = if (file.path.isLinuxPath) {
                            file.path.toFile()
                        } else {
                            val tmp = File(context.cacheDir, "qe_in_${System.currentTimeMillis()}_${file.name}")
                            file.path.newInputStream().use { inp ->
                                FileOutputStream(tmp).use { out -> inp.copyTo(out) }
                            }
                            tempInputFile = tmp
                            tmp
                        }

                        val baseName = file.name.removeSuffix(".apk")
                        val parentDir = if (file.path.isLinuxPath) {
                            file.path.toFile().parentFile ?: context.cacheDir
                        } else {
                            context.cacheDir
                        }

                        var outputApk = File(parentDir, "${baseName}_edited.apk")
                        var counter = 1
                        while (outputApk.exists()) {
                            outputApk = File(parentDir, "${baseName}_edited_$counter.apk")
                            counter++
                        }

                        val options = ApkQuickEditEngine.QuickEditOptions(
                            appName = newAppName,
                            packageName = newPackage.takeIf { it.isNotBlank() },
                            versionCode = newVerCode,
                            versionName = newVerName,
                            minSdkVersion = minSdkRaw,
                            targetSdkVersion = targetSdkRaw,
                            installLocation = selectedLoc,
                            newIconBytes = selectedIconBytes,
                            debuggable = cbDebuggable.isChecked,
                            extractNativeLibs = cbExtractNativeLibs.isChecked,
                            autoSign = autoSign,
                            signingScheme = ApkSignerHelper.SignScheme.V1_AND_V2
                        )

                        ApkQuickEditEngine.applyQuickEdit(inputApk, outputApk, options) { progress ->
                            lifecycleScope.launch(Dispatchers.Main) {
                                savingDialog.setMessage(progress)
                            }
                        }

                        withContext(Dispatchers.Main) {
                            savingDialog.dismiss()
                            alertDialog.dismiss()

                            (activity as? FileListActivity)?.activeFragment?.reload()

                            val rootLayout = activity?.findViewById<View>(android.R.id.content)
                            if (rootLayout != null) {
                                val snackbar = Snackbar.make(
                                    rootLayout,
                                    context.getString(R.string.apk_quick_edit_success, outputApk.name),
                                    Snackbar.LENGTH_LONG
                                )
                                snackbar.setAction(R.string.install) {
                                    (activity as? FileListActivity)?.activeFragment?.installApkFile(outputApk)
                                }
                                snackbar.show()
                            } else {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.apk_quick_edit_success, outputApk.name),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        me.zhanghai.android.files.app.CrashHandler.logHandledException(e, "ApkQuickEdit")
                        withContext(Dispatchers.Main) {
                            savingDialog.dismiss()
                            Toast.makeText(
                                context,
                                context.getString(R.string.apk_quick_edit_error, e.message ?: e.javaClass.simpleName),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } finally {
                        tempInputFile?.delete()
                    }
                }
            }
        }

        return alertDialog
    }

    @Parcelize
    class Args(val file: FileItem) : ParcelableArgs

    companion object {
        fun show(file: FileItem, fragment: Fragment) {
            ApkQuickEditDialogFragment().putArgs(Args(file)).show(fragment)
        }
    }
}
