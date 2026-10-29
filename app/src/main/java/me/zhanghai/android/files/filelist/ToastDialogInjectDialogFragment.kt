/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
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
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.show
import java.io.File
import java.io.FileOutputStream

class ToastDialogInjectDialogFragment : AppCompatDialogFragment() {

    private val args by args<Args>()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val file = args.file

        val view = LayoutInflater.from(context).inflate(R.layout.dialog_apk_inject_toast_dialog, null)
        val tvSubtitle = view.findViewById<TextView>(R.id.tvInjectSubtitle)
        val rgInjectMode = view.findViewById<RadioGroup>(R.id.rgInjectMode)
        val rbModeToast = view.findViewById<RadioButton>(R.id.rbModeToast)
        val rbModeDialog = view.findViewById<RadioButton>(R.id.rbModeDialog)

        val layoutToastConfig = view.findViewById<LinearLayout>(R.id.layoutToastConfig)
        val tilToastMessage = view.findViewById<TextInputLayout>(R.id.tilToastMessage)
        val etToastMessage = view.findViewById<TextInputEditText>(R.id.etToastMessage)
        val rbDurationLong = view.findViewById<RadioButton>(R.id.rbDurationLong)

        val layoutDialogConfig = view.findViewById<LinearLayout>(R.id.layoutDialogConfig)
        val tilDialogTitle = view.findViewById<TextInputLayout>(R.id.tilDialogTitle)
        val etDialogTitle = view.findViewById<TextInputEditText>(R.id.etDialogTitle)
        val tilDialogMessage = view.findViewById<TextInputLayout>(R.id.tilDialogMessage)
        val etDialogMessage = view.findViewById<TextInputEditText>(R.id.etDialogMessage)
        val etPositiveBtn = view.findViewById<TextInputEditText>(R.id.etPositiveBtn)
        val etPositiveUrl = view.findViewById<TextInputEditText>(R.id.etPositiveUrl)
        val etNegativeBtn = view.findViewById<TextInputEditText>(R.id.etNegativeBtn)
        val cbCancelable = view.findViewById<MaterialCheckBox>(R.id.cbCancelable)

        val actvTargetActivity = view.findViewById<AutoCompleteTextView>(R.id.actvTargetActivity)

        tvSubtitle.text = file.name

        // Toggle between Toast and Dialog modes
        rgInjectMode.setOnCheckedChangeListener { _, checkedId ->
            val isToast = (checkedId == R.id.rbModeToast)
            layoutToastConfig.isVisible = isToast
            layoutDialogConfig.isVisible = !isToast
        }

        // Load activities in background
        lifecycleScope.launch(Dispatchers.IO) {
            val localApk: File = try {
                if (file.path.isLinuxPath) {
                    file.path.toFile()
                } else {
                    val tmp = File(context.cacheDir, "activities_${System.currentTimeMillis()}_${file.name}")
                    file.path.newInputStream().use { inp ->
                        FileOutputStream(tmp).use { out -> inp.copyTo(out) }
                    }
                    tmp
                }
            } catch (e: Exception) {
                return@launch
            }

            val activities = ToastDialogInjectorEngine.findActivities(localApk)
            withContext(Dispatchers.Main) {
                val adapter = ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, activities)
                actvTargetActivity.setAdapter(adapter)
                if (activities.isNotEmpty()) {
                    actvTargetActivity.setText(activities.first(), false)
                }
            }

            if (!file.path.isLinuxPath && localApk.exists()) {
                localApk.delete()
            }
        }

        val alertDialog = MaterialAlertDialogBuilder(context)
            .setView(view)
            .setPositiveButton(R.string.toast_dialog_inject_btn, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        alertDialog.setOnShowListener {
            val injectBtn = alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)
            injectBtn.setOnClickListener {
                val isToastMode = rbModeToast.isChecked
                val targetActivity = actvTargetActivity.text?.toString()?.trim()

                if (isToastMode) {
                    val msg = etToastMessage.text?.toString()?.trim().orEmpty()
                    if (msg.isEmpty()) {
                        tilToastMessage.error = "Ingresa el texto del Toast"
                        return@setOnClickListener
                    }
                    tilToastMessage.error = null
                } else {
                    val title = etDialogTitle.text?.toString()?.trim().orEmpty()
                    val msg = etDialogMessage.text?.toString()?.trim().orEmpty()
                    if (title.isEmpty()) {
                        tilDialogTitle.error = "Ingresa el título"
                        return@setOnClickListener
                    }
                    if (msg.isEmpty()) {
                        tilDialogMessage.error = "Ingresa el mensaje"
                        return@setOnClickListener
                    }
                    tilDialogTitle.error = null
                    tilDialogMessage.error = null
                }

                val progressDialog = MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.toast_dialog_inject_title)
                    .setMessage(R.string.toast_dialog_inject_progress)
                    .setCancelable(false)
                    .create()
                progressDialog.show()

                lifecycleScope.launch(Dispatchers.IO) {
                    var tempInFile: File? = null
                    try {
                        val inputApk: File = if (file.path.isLinuxPath) {
                            file.path.toFile()
                        } else {
                            val tmp = File(context.cacheDir, "inject_in_${System.currentTimeMillis()}_${file.name}")
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

                        val suffix = if (isToastMode) "_toast" else "_dialog"
                        var outputApk = File(parentDir, "${baseName}${suffix}.apk")
                        var counter = 1
                        while (outputApk.exists()) {
                            outputApk = File(parentDir, "${baseName}${suffix}_$counter.apk")
                            counter++
                        }

                        if (isToastMode) {
                            val options = ToastDialogInjectorEngine.ToastOptions(
                                message = etToastMessage.text?.toString()?.trim().orEmpty(),
                                durationLong = rbDurationLong.isChecked,
                                targetActivity = targetActivity
                            )
                            ToastDialogInjectorEngine.injectToast(context, inputApk, outputApk, options) { p ->
                                lifecycleScope.launch(Dispatchers.Main) { progressDialog.setMessage(p) }
                            }
                        } else {
                            val options = ToastDialogInjectorEngine.DialogOptions(
                                title = etDialogTitle.text?.toString()?.trim().orEmpty(),
                                message = etDialogMessage.text?.toString()?.trim().orEmpty(),
                                positiveButton = etPositiveBtn.text?.toString()?.trim()?.ifBlank { "OK" } ?: "OK",
                                positiveUrl = etPositiveUrl.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                                negativeButton = etNegativeBtn.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                                cancelable = cbCancelable.isChecked,
                                targetActivity = targetActivity
                            )
                            ToastDialogInjectorEngine.injectDialog(context, inputApk, outputApk, options) { p ->
                                lifecycleScope.launch(Dispatchers.Main) { progressDialog.setMessage(p) }
                            }
                        }

                        withContext(Dispatchers.Main) {
                            progressDialog.dismiss()
                            alertDialog.dismiss()
                            (activity as? FileListActivity)?.activeFragment?.reload()

                            val rootLayout = activity?.findViewById<View>(android.R.id.content)
                            if (rootLayout != null) {
                                val snackbar = Snackbar.make(
                                    rootLayout,
                                    context.getString(R.string.toast_dialog_inject_success, outputApk.name),
                                    Snackbar.LENGTH_LONG
                                )
                                snackbar.setAction(R.string.install) {
                                    (activity as? FileListActivity)?.activeFragment?.installApkFile(outputApk)
                                }
                                snackbar.show()
                            } else {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.toast_dialog_inject_success, outputApk.name),
                                    Toast.LENGTH_LONG
                                ).show()
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
            ToastDialogInjectDialogFragment().putArgs(Args(file)).show(fragment)
        }
    }
}
