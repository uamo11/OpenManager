/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.show
import java.io.File
import java.io.FileOutputStream

class ToastDialogCleanDialogFragment : AppCompatDialogFragment() {

    private val args by args<Args>()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val file = args.file

        val view = LayoutInflater.from(context).inflate(R.layout.dialog_apk_clean_toasts_dialog, null)
        val tvSubtitle = view.findViewById<TextView>(R.id.tvCleanSubtitle)
        val cbCleanToasts = view.findViewById<MaterialCheckBox>(R.id.cbCleanToasts)
        val cbCleanDialogs = view.findViewById<MaterialCheckBox>(R.id.cbCleanDialogs)

        tvSubtitle.text = file.name

        val alertDialog = MaterialAlertDialogBuilder(context)
            .setView(view)
            .setPositiveButton(R.string.toast_dialog_clean_title, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        alertDialog.setOnShowListener {
            val cleanBtn = alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)
            cleanBtn.setOnClickListener {
                val cleanToasts = cbCleanToasts.isChecked
                val cleanDialogs = cbCleanDialogs.isChecked

                if (!cleanToasts && !cleanDialogs) {
                    Toast.makeText(context, "Selecciona al menos una opción para limpiar", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val progressDialog = MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.toast_dialog_clean_title)
                    .setMessage(R.string.toast_dialog_clean_progress)
                    .setCancelable(false)
                    .create()
                progressDialog.show()

                lifecycleScope.launch(Dispatchers.IO) {
                    var tempInFile: File? = null
                    try {
                        val inputApk: File = if (file.path.isLinuxPath) {
                            file.path.toFile()
                        } else {
                            val tmp = File(context.cacheDir, "clean_in_${System.currentTimeMillis()}_${file.name}")
                            file.path.newInputStream().use { inp ->
                                FileOutputStream(tmp).use { out -> inp.copyTo(out) }
                            }
                            tempInFile = tmp
                            tmp
                        }

                        val baseName = file.name.removeSuffix(".apk")
                        val parentDir = if (file.path.isLinuxPath) {
                            file.path.toFile().parentFile ?: context.cacheDir
                        } else {
                            context.cacheDir
                        }

                        var outputApk = File(parentDir, "${baseName}_cleaned.apk")
                        var counter = 1
                        while (outputApk.exists()) {
                            outputApk = File(parentDir, "${baseName}_cleaned_$counter.apk")
                            counter++
                        }

                        val options = ToastDialogInjectorEngine.CleanOptions(
                            cleanToasts = cleanToasts,
                            cleanDialogs = cleanDialogs
                        )

                        val result = ToastDialogInjectorEngine.cleanToastsAndDialogs(
                            context,
                            inputApk,
                            outputApk,
                            options
                        ) { p ->
                            lifecycleScope.launch(Dispatchers.Main) { progressDialog.setMessage(p) }
                        }

                        withContext(Dispatchers.Main) {
                            progressDialog.dismiss()
                            alertDialog.dismiss()
                            (activity as? FileListActivity)?.activeFragment?.reload()

                            val message = context.getString(
                                R.string.toast_dialog_clean_success,
                                result.toastsRemoved,
                                result.dialogsRemoved,
                                result.dexFilesModified,
                                outputApk.name
                            )

                            val rootLayout = activity?.findViewById<View>(android.R.id.content)
                            if (rootLayout != null) {
                                val snackbar = Snackbar.make(rootLayout, message, Snackbar.LENGTH_INDEFINITE)
                                snackbar.setAction(R.string.install) {
                                    (activity as? FileListActivity)?.activeFragment?.installApkFile(outputApk)
                                }
                                snackbar.show()
                            } else {
                                MaterialAlertDialogBuilder(context)
                                    .setTitle(R.string.toast_dialog_clean_title)
                                    .setMessage(message)
                                    .setPositiveButton(android.R.string.ok, null)
                                    .show()
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        withContext(Dispatchers.Main) {
                            progressDialog.dismiss()
                            Toast.makeText(
                                context,
                                context.getString(R.string.toast_dialog_error, e.message ?: e.javaClass.simpleName),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } finally {
                        tempInFile?.delete()
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
            ToastDialogCleanDialogFragment().putArgs(Args(file)).show(fragment)
        }
    }
}
