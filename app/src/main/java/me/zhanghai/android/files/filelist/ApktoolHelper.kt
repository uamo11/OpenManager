/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.File
import java.io.FileOutputStream
import java8.nio.file.Files
import java8.nio.file.Path
import java8.nio.file.Paths
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.zhanghai.android.files.R
import me.zhanghai.android.files.appmanager.InstalledAppsActivity
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.linux.isLinuxPath

object ApktoolHelper {

    private var activeProgressDialog: AppCompatDialog? = null

    fun isApktoolProject(dirPath: Path): Boolean {
        return try {
            Files.isDirectory(dirPath) && (
                Files.exists(dirPath.resolve("apktool.yml")) ||
                Files.exists(dirPath.resolve("AndroidManifest.xml"))
            )
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Resolves a local File instance for an input Path, copying if remote/SAF.
     */
    private fun getLocalFileForPath(activity: Activity, path: Path, prefix: String = "apk_"): File {
        return if (path.isLinuxPath) {
            path.toFile()
        } else {
            val tmp = File(activity.cacheDir, "${prefix}${System.currentTimeMillis()}_${path.fileName}")
            path.newInputStream().use { inp ->
                FileOutputStream(tmp).use { out -> inp.copyTo(out) }
            }
            tmp
        }
    }

    /**
     * Decompiles an APK using Apktool into:
     * OpenManager/ApkTool/<packageName>/decomp_src
     */
    fun decompileApk(
        activity: Activity,
        apkPath: Path,
        onComplete: ((Path?) -> Unit)? = null
    ) {
        if (ApktoolExecutionService.isRunning()) {
            Toast.makeText(activity, "Ya hay una tarea de Apktool en ejecución", Toast.LENGTH_SHORT).show()
            showProgressDialog(activity)
            return
        }

        val apkFile = try {
            getLocalFileForPath(activity, apkPath, "apktool_")
        } catch (e: Exception) {
            Toast.makeText(activity, "Error al preparar archivo APK: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        val packageName = ApktoolPaths.resolvePackageName(activity, apkFile)
        val outputDir = ApktoolPaths.getApktoolDecompileDir(packageName)

        ApktoolExecutionService.startTask(
            context = activity,
            taskType = ApktoolExecutionService.TASK_TYPE_DECOMPILE_APK,
            inputPath = apkFile.absolutePath,
            outputPath = outputDir.absolutePath,
            packageName = packageName,
            displayTitle = activity.getString(R.string.apktool_decompile_apk)
        )

        showProgressDialog(activity) { successPath ->
            onComplete?.invoke(successPath)
        }
    }

    /**
     * Decompiles an APK directly to Java source files using JADX into:
     * OpenManager/ApkTool/<packageName>/decomp_java
     */
    fun decompileJava(
        activity: Activity,
        apkPath: Path,
        onComplete: ((Path?) -> Unit)? = null
    ) {
        if (ApktoolExecutionService.isRunning()) {
            Toast.makeText(activity, "Ya hay una tarea de Apktool en ejecución", Toast.LENGTH_SHORT).show()
            showProgressDialog(activity)
            return
        }

        val apkFile = try {
            getLocalFileForPath(activity, apkPath, "jadx_")
        } catch (e: Exception) {
            Toast.makeText(activity, "Error al preparar archivo APK: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        val packageName = ApktoolPaths.resolvePackageName(activity, apkFile)
        val outputDir = ApktoolPaths.getJavaDecompileDir(packageName)

        ApktoolExecutionService.startTask(
            context = activity,
            taskType = ApktoolExecutionService.TASK_TYPE_DECOMPILE_JAVA,
            inputPath = apkFile.absolutePath,
            outputPath = outputDir.absolutePath,
            packageName = packageName,
            displayTitle = activity.getString(R.string.apktool_decompile_java)
        )

        showProgressDialog(activity) { successPath ->
            onComplete?.invoke(successPath)
        }
    }

    /**
     * Recompiles an Apktool project folder into:
     * OpenManager/ApkTool/<packageName>/<packageName>_recompiled.apk
     */
    fun recompileApk(
        activity: Activity,
        projectDir: Path,
        onComplete: ((Path?) -> Unit)? = null
    ) {
        if (ApktoolExecutionService.isRunning()) {
            Toast.makeText(activity, "Ya hay una tarea de Apktool en ejecución", Toast.LENGTH_SHORT).show()
            showProgressDialog(activity)
            return
        }

        val projectFile = if (projectDir.isLinuxPath) {
            projectDir.toFile()
        } else {
            Toast.makeText(activity, "La carpeta del proyecto debe residir en el almacenamiento accesible", Toast.LENGTH_LONG).show()
            return
        }

        // Determine package name from parent dir or manifest
        var packageName = projectFile.parentFile?.name ?: ""
        if (packageName.isBlank() || packageName.equals("ApkTool", ignoreCase = true)) {
            val manifestFile = File(projectFile, "AndroidManifest.xml")
            if (manifestFile.exists()) {
                try {
                    val xml = manifestFile.readText()
                    val match = Regex("""package\s*=\s*"([^"]+)"""").find(xml)
                    if (match != null) {
                        packageName = match.groupValues[1]
                    }
                } catch (ignored: Throwable) {}
            }
        }
        if (packageName.isBlank()) {
            packageName = projectFile.name.removeSuffix("_src").removeSuffix("_decomp")
        }

        val outputApkFile = ApktoolPaths.getRecompiledApkFile(packageName)

        ApktoolExecutionService.startTask(
            context = activity,
            taskType = ApktoolExecutionService.TASK_TYPE_RECOMPILE_APK,
            inputPath = projectFile.absolutePath,
            outputPath = outputApkFile.absolutePath,
            packageName = packageName,
            displayTitle = activity.getString(R.string.apktool_recompile_apk)
        )

        showProgressDialog(activity) { successPath ->
            onComplete?.invoke(successPath)
        }
    }

    /**
     * Shows or restores the active Apktool / JADX progress dialog.
     * Features:
     * - Cancel button: immediately aborts task, cleans up incomplete output, dismisses dialog.
     * - Hide button: dismisses dialog while task continues executing in foreground service.
     */
    fun showProgressDialog(
        activity: Activity,
        onSuccess: ((Path) -> Unit)? = null
    ) {
        if (activeProgressDialog?.isShowing == true) {
            return
        }

        val appCompatActivity = activity as? AppCompatActivity ?: return
        val dialog = AppCompatDialog(activity)
        activeProgressDialog = dialog
        dialog.supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_apktool_progress, null)
        dialog.setContentView(view)

        dialog.setCancelable(false)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val displayMetrics = activity.resources.displayMetrics
            val targetWidth = (displayMetrics.widthPixels * 0.92).toInt().coerceAtMost(
                (450 * displayMetrics.density).toInt()
            )
            setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val ivIcon = view.findViewById<ImageView>(R.id.ivApktoolIcon)
        val tvTitle = view.findViewById<TextView>(R.id.tvApktoolTitle)
        val tvPackage = view.findViewById<TextView>(R.id.tvApktoolPackage)
        val tvStatus = view.findViewById<TextView>(R.id.tvApktoolStatus)
        val pbProgress = view.findViewById<LinearProgressIndicator>(R.id.pbApktoolProgress)
        val tvDetail = view.findViewById<TextView>(R.id.tvApktoolDetail)
        val btnCancel = view.findViewById<Button>(R.id.btnCancelApktool)
        val btnHide = view.findViewById<Button>(R.id.btnHideApktool)

        btnHide.setOnClickListener {
            dialog.dismiss()
            activeProgressDialog = null
            Toast.makeText(
                activity,
                activity.getString(R.string.apktool_running_toast),
                Toast.LENGTH_LONG
            ).show()
        }

        btnCancel.setOnClickListener {
            ApktoolExecutionService.cancelCurrentTask(activity)
            dialog.dismiss()
            activeProgressDialog = null
            Toast.makeText(
                activity,
                activity.getString(R.string.apktool_cancelled_toast),
                Toast.LENGTH_SHORT
            ).show()
        }

        appCompatActivity.lifecycleScope.launch {
            ApktoolExecutionService.taskState.collectLatest { state ->
                if (state == null) {
                    if (dialog.isShowing) {
                        dialog.dismiss()
                        activeProgressDialog = null
                    }
                    return@collectLatest
                }

                if (!dialog.isShowing) {
                    return@collectLatest
                }

                tvTitle.text = state.title
                tvPackage.text = state.packageName
                tvStatus.text = state.status
                tvDetail.text = state.detail

                when (state.taskType) {
                    ApktoolExecutionService.TASK_TYPE_DECOMPILE_JAVA -> {
                        ivIcon.setImageResource(R.drawable.ic_open_as_code)
                        ivIcon.setColorFilter(Color.parseColor("#64B5F6"))
                    }
                    ApktoolExecutionService.TASK_TYPE_RECOMPILE_APK -> {
                        ivIcon.setImageResource(R.drawable.ic_apktool_recompile)
                        ivIcon.setColorFilter(Color.parseColor("#42A5F5"))
                    }
                    ApktoolExecutionService.TASK_TYPE_DECOMPILE_APK -> {
                        ivIcon.setImageResource(R.drawable.ic_apktool_decompile)
                        ivIcon.setColorFilter(Color.parseColor("#FFA000"))
                    }
                    else -> {
                        ivIcon.setImageResource(R.drawable.ic_apktool)
                        ivIcon.setColorFilter(Color.parseColor("#4CAF50"))
                    }
                }

                pbProgress.isIndeterminate = state.isIndeterminate
                if (!state.isIndeterminate) {
                    pbProgress.progress = state.progress.coerceIn(0, 100)
                }

                if (!state.isRunning) {
                    dialog.dismiss()
                    activeProgressDialog = null

                    if (state.isCompleted) {
                        Toast.makeText(
                            activity,
                            "${state.title}: ${activity.getString(R.string.apktool_completed)}",
                            Toast.LENGTH_LONG
                        ).show()

                        val targetPath = Paths.get(state.outputPath)
                        onSuccess?.invoke(targetPath)

                        if (state.taskType == ApktoolExecutionService.TASK_TYPE_RECOMPILE_APK) {
                            // Launch signature dialog for newly recompiled APK
                            ApkSignerHelper.showSignDialog(activity, targetPath) { signedPath ->
                                (activity as? FileListActivity)?.activeFragment?.reload()
                            }
                        } else {
                            // Automatically navigate file explorer to decompiled directory
                            (activity as? FileListActivity)?.activeFragment?.navigateTo(targetPath)
                        }
                    } else if (!state.isCancelled && state.errorMessage != null) {
                        val errMsg = state.errorMessage
                        MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.apktool_error_title)
                            .setMessage(errMsg)
                            .setPositiveButton(android.R.string.ok, null)
                            .setNeutralButton("Copiar") { _, _ ->
                                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Apktool Error", errMsg))
                                Toast.makeText(activity, "Error copiado al portapapeles", Toast.LENGTH_SHORT).show()
                            }
                            .show()
                    }
                }
            }
        }

        dialog.show()
    }

    /**
     * Displays the Apktool Management Hub dialog
     */
    fun showApktoolHubDialog(activity: Activity, currentPath: Path) {
        val context = activity
        val dialog = AppCompatDialog(context)
        dialog.supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_mt_apktool_hub, null)
        dialog.setContentView(view)

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val displayMetrics = context.resources.displayMetrics
            val targetWidth = (displayMetrics.widthPixels * 0.92).toInt().coerceAtMost(
                (450 * displayMetrics.density).toInt()
            )
            setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        view.findViewById<Button>(R.id.btnCloseApktoolHub).setOnClickListener {
            dialog.dismiss()
        }

        // 1. Descompilar APK (Apktool)
        view.findViewById<LinearLayout>(R.id.itemDecompileApk).setOnClickListener {
            dialog.dismiss()
            pickApkInDirectory(activity, currentPath, "Selecciona el APK a descompilar") { selectedApk ->
                decompileApk(activity, selectedApk)
            }
        }

        // 1b. Descompilar APK a Java (JADX)
        view.findViewById<LinearLayout>(R.id.itemDecompileJava).setOnClickListener {
            dialog.dismiss()
            pickApkInDirectory(activity, currentPath, "Selecciona el APK a descompilar a Java") { selectedApk ->
                decompileJava(activity, selectedApk)
            }
        }

        // 2. Recompilar APK
        view.findViewById<LinearLayout>(R.id.itemRecompileApk).setOnClickListener {
            dialog.dismiss()
            if (isApktoolProject(currentPath)) {
                recompileApk(activity, currentPath)
            } else {
                pickProjectInDirectory(activity, currentPath, "Selecciona la carpeta de proyecto a recompilar") { selectedProject ->
                    recompileApk(activity, selectedProject)
                }
            }
        }

        // 3. Firmar APK
        view.findViewById<LinearLayout>(R.id.itemSignApk).setOnClickListener {
            dialog.dismiss()
            pickApkInDirectory(activity, currentPath, "Selecciona el APK a firmar") { selectedApk ->
                ApkSignerHelper.showSignDialog(activity, selectedApk) {
                    (activity as? FileListActivity)?.activeFragment?.reload()
                }
            }
        }

        // 4. Verificar firma
        view.findViewById<LinearLayout>(R.id.itemVerifySignature).setOnClickListener {
            dialog.dismiss()
            pickApkInDirectory(activity, currentPath, "Selecciona el APK para verificar firma") { selectedApk ->
                ApkSignerHelper.showVerifySignatureDialog(activity, selectedApk)
            }
        }

        // 5. Extraer aplicaciones
        view.findViewById<LinearLayout>(R.id.itemExtractApps).setOnClickListener {
            dialog.dismiss()
            activity.startActivity(Intent(activity, InstalledAppsActivity::class.java))
        }

        dialog.show()
    }

    private fun pickApkInDirectory(
        activity: Activity,
        directory: Path,
        title: String,
        onPicked: (Path) -> Unit
    ) {
        val apkList = mutableListOf<Path>()
        try {
            if (Files.isDirectory(directory)) {
                Files.newDirectoryStream(directory).use { stream ->
                    for (p in stream) {
                        if (p.fileName.toString().endsWith(".apk", ignoreCase = true)) {
                            apkList.add(p)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (apkList.isEmpty()) {
            Toast.makeText(activity, "No se encontraron archivos .apk en este directorio:\n${directory.fileName}", Toast.LENGTH_LONG).show()
            return
        }

        val adapter = object : android.widget.ArrayAdapter<Path>(
            activity,
            R.layout.item_dialog_action_with_icon,
            apkList
        ) {
            override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup): android.view.View {
                val row = convertView ?: LayoutInflater.from(context)
                    .inflate(R.layout.item_dialog_action_with_icon, parent, false)
                val item = getItem(position)!!
                val iv = row.findViewById<ImageView>(R.id.ivActionIcon)
                val tv = row.findViewById<TextView>(R.id.tvActionTitle)
                iv.setImageResource(R.drawable.file_apk_icon)
                tv.text = item.fileName.toString()
                return row
            }
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setAdapter(adapter) { _, which ->
                onPicked(apkList[which])
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun pickProjectInDirectory(
        activity: Activity,
        directory: Path,
        title: String,
        onPicked: (Path) -> Unit
    ) {
        val projList = mutableListOf<Path>()
        try {
            if (Files.isDirectory(directory)) {
                Files.newDirectoryStream(directory).use { stream ->
                    for (p in stream) {
                        if (Files.isDirectory(p) && isApktoolProject(p)) {
                            projList.add(p)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (projList.isEmpty()) {
            Toast.makeText(activity, "No se encontraron carpetas de proyecto Apktool en:\n${directory.fileName}", Toast.LENGTH_LONG).show()
            return
        }

        val adapter = object : android.widget.ArrayAdapter<Path>(
            activity,
            R.layout.item_dialog_action_with_icon,
            projList
        ) {
            override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup): android.view.View {
                val row = convertView ?: LayoutInflater.from(context)
                    .inflate(R.layout.item_dialog_action_with_icon, parent, false)
                val item = getItem(position)!!
                val iv = row.findViewById<ImageView>(R.id.ivActionIcon)
                val tv = row.findViewById<TextView>(R.id.tvActionTitle)
                iv.setImageResource(R.drawable.ic_apktool)
                iv.setColorFilter(Color.parseColor("#FFA000"))
                tv.text = item.fileName.toString()
                return row
            }
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setAdapter(adapter) { _, which ->
                onPicked(projList[which])
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
