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
import androidx.fragment.app.FragmentActivity
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
import me.zhanghai.android.files.ui.bindToLifecycle
import me.zhanghai.android.files.ui.dismissSafely
import me.zhanghai.android.files.ui.isUiAlive
import me.zhanghai.android.files.ui.showSafeDialog
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
                    Toast.makeText(context, R.string.om_toast_clean_select_one, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val hostActivity = activity ?: return@setOnClickListener
                val progressDialog = MaterialAlertDialogBuilder(hostActivity)
                    .setTitle(R.string.toast_dialog_clean_title)
                    .setMessage(R.string.toast_dialog_clean_progress)
                    .setCancelable(false)
                    .create()
                    .bindToLifecycle(hostActivity)
                progressDialog.show()

                hostActivity.lifecycleScope.launch(Dispatchers.IO) {
                    var tempInFile: File? = null
                    try {
                        val inputApk: File = if (file.path.isLinuxPath) {
                            file.path.toFile()
                        } else {
                            val tmp = File(hostActivity.cacheDir, "clean_in_${System.currentTimeMillis()}_${file.name}")
                            file.path.newInputStream().use { inp ->
                                FileOutputStream(tmp).use { out -> inp.copyTo(out) }
                            }
                            tempInFile = tmp
                            tmp
                        }

                        val baseName = file.name.removeSuffix(".apk")
                        val parentDir = if (file.path.isLinuxPath) {
                            file.path.toFile().parentFile ?: hostActivity.cacheDir
                        } else {
                            hostActivity.cacheDir
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
                            hostActivity,
                            inputApk,
                            outputApk,
                            options
                        ) { p ->
                            hostActivity.lifecycleScope.launch(Dispatchers.Main.immediate) {
                                if (hostActivity.isUiAlive && progressDialog.isShowing) {
                                    progressDialog.setMessage(p)
                                }
                            }
                        }

                        withContext(Dispatchers.Main) {
                            progressDialog.dismissSafely()
                            alertDialog.dismissSafely()
                            if (!hostActivity.isUiAlive) return@withContext
                            (hostActivity as? FileListActivity)?.activeFragment?.reload()

                            val message = hostActivity.getString(
                                R.string.toast_dialog_clean_success,
                                result.toastsRemoved,
                                result.dialogsRemoved,
                                result.dexFilesModified,
                                outputApk.name
                            )

                            val rootLayout = hostActivity.findViewById<View>(android.R.id.content)
                            if (rootLayout != null) {
                                val snackbar = Snackbar.make(rootLayout, message, Snackbar.LENGTH_INDEFINITE)
                                snackbar.setAction(R.string.install) {
                                    (hostActivity as? FileListActivity)?.activeFragment?.installApkFile(outputApk)
                                }
                                snackbar.show()
                            } else {
                                hostActivity.showSafeDialog {
                                    setTitle(R.string.toast_dialog_clean_title)
                                    setMessage(message)
                                    setPositiveButton(android.R.string.ok, null)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        withContext(Dispatchers.Main) {
                            progressDialog.dismissSafely()
                            if (hostActivity.isUiAlive) {
                                Toast.makeText(
                                    hostActivity,
                                    hostActivity.getString(R.string.toast_dialog_error, e.message ?: e.javaClass.simpleName),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
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
        fun show(file: FileItem, activity: FragmentActivity) {
            ToastDialogCleanDialogFragment().putArgs(Args(file)).show(activity.supportFragmentManager, "ToastDialogCleanDialogFragment")
        }

        fun show(file: FileItem, fragment: Fragment) {
            val fm = try {
                fragment.parentFragmentManager
            } catch (e: Exception) {
                fragment.childFragmentManager
            }
            ToastDialogCleanDialogFragment().putArgs(Args(file)).show(fm, "ToastDialogCleanDialogFragment")
        }
    }
}
