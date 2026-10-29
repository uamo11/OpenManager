/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.fileSize
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.show
import java.io.File
import java.io.FileOutputStream
import java8.nio.file.Paths

class SplitApkDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()

    private val listener: Listener
        get() = requireParentFragment() as Listener

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val file = args.file
        val sizeStr = file.attributes.fileSize.formatHumanReadable(context)

        val view = LayoutInflater.from(context).inflate(R.layout.dialog_split_apk_actions, null)
        val tvTitle = view.findViewById<TextView>(R.id.tvSplitApkTitle)
        val tvSubtitle = view.findViewById<TextView>(R.id.tvSplitApkSubtitle)
        val btnAntiSplit = view.findViewById<MaterialButton>(R.id.btnActionAntiSplit)
        val btnInstall = view.findViewById<MaterialButton>(R.id.btnActionInstall)
        val btnExplore = view.findViewById<MaterialButton>(R.id.btnActionExplore)
        val btnSign = view.findViewById<MaterialButton>(R.id.btnActionSign)

        tvTitle.text = file.name
        tvSubtitle.text = "Paquete dividido • $sizeStr"

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(view)
            .create()

        btnAntiSplit.setOnClickListener {
            dialog.dismiss()
            performAntiSplit(file)
        }

        btnInstall.setOnClickListener {
            dialog.dismiss()
            if (ApkInstallHelper.isRootAvailable()) {
                listener.installApkWithRoot(file)
            } else {
                listener.installApk(file)
            }
        }

        btnExplore.setOnClickListener {
            dialog.dismiss()
            listener.viewApk(file)
        }

        btnSign.setOnClickListener {
            dialog.dismiss()
            ApkSignerHelper.showSignDialog(requireActivity(), file.path) {
                (activity as? FileListActivity)?.activeFragment?.reload()
            }
        }

        return dialog
    }

    private fun performAntiSplit(file: FileItem) {
        val activity = requireActivity()
        val path = file.path
        val progressDialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.antisplit_title)
            .setMessage(getString(R.string.antisplit_merging_progress))
            .setCancelable(false)
            .show()

        lifecycleScope.launch(Dispatchers.IO) {
            val localArchive = if (!path.isLinuxPath) {
                val tempFile = File(activity.cacheDir, "split_in_${System.currentTimeMillis()}.zip")
                try {
                    path.newInputStream().use { ins ->
                        FileOutputStream(tempFile).use { outs -> ins.copyTo(outs) }
                    }
                    tempFile
                } catch (e: Exception) {
                    null
                }
            } else {
                File(path.toString())
            }

            if (localArchive == null || !localArchive.exists()) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(activity, "Error al acceder al archivo dividido", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val parentDir = localArchive.parentFile ?: activity.cacheDir
            val baseName = file.name.substringBeforeLast('.')
            val outputApk = File(parentDir, "${baseName}_merged.apk")

            val success = AntiSplitEngine.mergeArchive(
                context = activity,
                archiveFile = localArchive,
                outputFile = outputApk,
                logger = { msg ->
                    activity.runOnUiThread {
                        progressDialog.setMessage(msg)
                    }
                }
            )

            if (!path.isLinuxPath && localArchive.absolutePath.contains(activity.cacheDir.absolutePath)) {
                localArchive.delete()
            }

            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                if (success && outputApk.exists() && outputApk.length() > 0) {
                    (activity as? FileListActivity)?.activeFragment?.reload()
                    val snackbar = Snackbar.make(
                        activity.findViewById(android.R.id.content),
                        getString(R.string.antisplit_success, outputApk.name),
                        Snackbar.LENGTH_LONG
                    )
                    snackbar.setAction(R.string.install) {
                        (activity as? FileListActivity)?.activeFragment?.installApkFile(outputApk)
                    }
                    snackbar.show()
                } else {
                    Toast.makeText(activity, "Error durante la fusión AntiSplit", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    @Parcelize
    class Args(val file: FileItem) : ParcelableArgs

    interface Listener {
        fun installApk(file: FileItem)
        fun installApkWithRoot(file: FileItem)
        fun viewApk(file: FileItem)
    }

    companion object {
        fun show(file: FileItem, fragment: Fragment) {
            SplitApkDialogFragment().putArgs(Args(file)).show(fragment)
        }
    }
}
