/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.Context
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.reandroid.apk.ApkModule
import com.reandroid.archive.FileInputSource
import me.zhanghai.android.files.viewer.dex.DexEngine
import java.io.File
import java.io.FileOutputStream
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

object ToastDialogInjectorEngine {

    data class ToastOptions(
        val message: String,
        val durationLong: Boolean = true,
        val targetActivity: String? = null
    )

    data class DialogOptions(
        val title: String,
        val message: String,
        val positiveButton: String = "OK",
        val positiveUrl: String? = null,
        val negativeButton: String? = null,
        val cancelable: Boolean = true,
        val targetActivity: String? = null
    )

    data class CleanOptions(
        val cleanToasts: Boolean = true,
        val cleanDialogs: Boolean = true
    )

    data class CleanResult(
        val toastsRemoved: Int,
        val dialogsRemoved: Int,
        val dexFilesModified: Int,
        val outputFile: File
    )

    val TOAST_PATTERN: Pattern = Pattern.compile(
        "^[ \\t]*invoke-[^ \\t]+[ \\t]+.*Landroid/widget/Toast;->show\\(\\)V.*$",
        Pattern.MULTILINE
    )

    val DIALOG_PATTERN: Pattern = Pattern.compile(
        "^[ \\t]*invoke-[^ \\t]+[ \\t]+.*L(?:android/app|androidx/appcompat/app|android/support/v7/app|com/google/android/material/dialog)/(?:AlertDialog(?:\\\$Builder)?|Dialog|MaterialAlertDialogBuilder);->show\\(.*$(?:\\r?\\n[ \\t]*move-result(?:-object)?[ \\t]+(v\\d+|p\\d+))?",
        Pattern.MULTILINE
    )

    fun isDialogClass(defClass: String): Boolean {
        return defClass == "Landroid/app/AlertDialog;" ||
            defClass == "Landroid/app/AlertDialog\$Builder;" ||
            defClass == "Landroid/app/Dialog;" ||
            defClass == "Landroidx/appcompat/app/AlertDialog;" ||
            defClass == "Landroidx/appcompat/app/AlertDialog\$Builder;" ||
            defClass == "Landroid/support/v7/app/AlertDialog;" ||
            defClass == "Landroid/support/v7/app/AlertDialog\$Builder;" ||
            defClass == "Lcom/google/android/material/dialog/MaterialAlertDialogBuilder;"
    }

    fun findActivities(apkFile: File): List<String> {
        val activities = mutableListOf<String>()
        var mainActivity: String? = null

        try {
            ApkModule.loadApkFile(apkFile).use { module ->
                val manifest = module.androidManifest
                if (manifest != null) {
                    mainActivity = manifest.mainActivityClassName
                    val actList = manifest.listActivities()
                    for (act in actList) {
                        val nameAttr = act.searchAttributeByName("name")
                        val actName = nameAttr?.valueAsString
                        if (!actName.isNullOrBlank()) {
                            val fullName = manifest.fullClassName(actName)
                            if (!activities.contains(fullName)) {
                                activities.add(fullName)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Put main launcher activity first if identified
        if (!mainActivity.isNullOrBlank() && activities.contains(mainActivity)) {
            activities.remove(mainActivity)
            activities.add(0, mainActivity!!)
        }

        return activities
    }

    fun escapeSmaliString(input: String): String {
        val sb = StringBuilder(input.length + 16)
        for (c in input) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000c' -> sb.append("\\f")
                else -> {
                    if (c.code < 0x20 || c.code > 0x7E) {
                        sb.append(String.format("\\u%04x", c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        return sb.toString()
    }

    @Throws(Exception::class)
    fun injectToast(
        context: Context,
        inputApk: File,
        outputApk: File,
        options: ToastOptions,
        onProgress: ((String) -> Unit)? = null
    ): File {
        val activities = findActivities(inputApk)
        val targetClass = options.targetActivity?.takeIf { it.isNotBlank() }
            ?: activities.firstOrNull()
            ?: throw IllegalStateException("No se encontraron actividades en el APK")

        val targetDescriptor = "L${targetClass.replace('.', '/')};"
        val workDir = File(context.cacheDir, "inject_toast_${System.currentTimeMillis()}").apply { mkdirs() }

        try {
            onProgress?.invoke("Extrayendo archivos DEX...")
            val dexFiles = extractDexFiles(inputApk, workDir)

            var targetDexEntry: String? = null
            var targetDexFile: File? = null

            for ((entryName, dex) in dexFiles) {
                val dexBacked = DexFileFactory.loadDexFile(dex, DexEngine.defaultOpcodes)
                if (dexBacked.classes.any { it.type == targetDescriptor }) {
                    targetDexEntry = entryName
                    targetDexFile = dex
                    break
                }
            }

            if (targetDexFile == null || targetDexEntry == null) {
                throw IllegalStateException("No se pudo localizar la actividad $targetClass en los archivos DEX")
            }

            onProgress?.invoke("Desensamblando $targetClass...")
            val originalSmali = DexEngine.disassembleClass(targetDexFile, targetDescriptor)
            if (originalSmali.startsWith("# Error")) {
                throw IllegalStateException("Error al desensamblar $targetClass: $originalSmali")
            }

            onProgress?.invoke("Inyectando llamada a Toast...")
            val escapedMsg = escapeSmaliString(options.message)
            val durationConst = if (options.durationLong) "0x1" else "0x0"

            val toastMethodSmali = """
.method private showOpenManagerToast()V
    .registers 3

    const-string v0, "$escapedMsg"
    const/4 v1, $durationConst
    invoke-static {p0, v0, v1}, Landroid/widget/Toast;->makeText(Landroid/content/Context;Ljava/lang/CharSequence;I)Landroid/widget/Toast;
    move-result-object v0
    invoke-virtual {v0}, Landroid/widget/Toast;->show()V
    return-void
.end method
""".trimIndent()

            val modifiedSmali = injectMethodCallIntoClass(
                originalSmali,
                targetDescriptor,
                "showOpenManagerToast",
                toastMethodSmali
            )

            onProgress?.invoke("Reensamblando DEX...")
            val (success, errorMsg) = DexEngine.assembleClassSmali(
                context,
                targetDexFile,
                modifiedSmali,
                targetDescriptor
            )
            if (!success) {
                throw IllegalStateException("Error al reensamblar clase: $errorMsg")
            }

            onProgress?.invoke("Empaquetando APK...")
            val unsignedTemp = File(workDir, "unsigned.apk")
            ApkModule.loadApkFile(inputApk).use { module ->
                module.add(FileInputSource(targetDexFile, targetDexEntry))
                module.writeApk(unsignedTemp)
            }

            onProgress?.invoke("Firmando APK...")
            ApkSignerHelper.signFileDirect(unsignedTemp, outputApk)
            return outputApk
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Throws(Exception::class)
    fun injectDialog(
        context: Context,
        inputApk: File,
        outputApk: File,
        options: DialogOptions,
        onProgress: ((String) -> Unit)? = null
    ): File {
        val activities = findActivities(inputApk)
        val targetClass = options.targetActivity?.takeIf { it.isNotBlank() }
            ?: activities.firstOrNull()
            ?: throw IllegalStateException("No se encontraron actividades en el APK")

        val targetDescriptor = "L${targetClass.replace('.', '/')};"
        val workDir = File(context.cacheDir, "inject_dialog_${System.currentTimeMillis()}").apply { mkdirs() }

        try {
            onProgress?.invoke("Extrayendo archivos DEX...")
            val dexFiles = extractDexFiles(inputApk, workDir)

            var targetDexEntry: String? = null
            var targetDexFile: File? = null

            for ((entryName, dex) in dexFiles) {
                val dexBacked = DexFileFactory.loadDexFile(dex, DexEngine.defaultOpcodes)
                if (dexBacked.classes.any { it.type == targetDescriptor }) {
                    targetDexEntry = entryName
                    targetDexFile = dex
                    break
                }
            }

            if (targetDexFile == null || targetDexEntry == null) {
                throw IllegalStateException("No se pudo localizar la actividad $targetClass en los archivos DEX")
            }

            onProgress?.invoke("Desensamblando $targetClass...")
            val originalSmali = DexEngine.disassembleClass(targetDexFile, targetDescriptor)
            if (originalSmali.startsWith("# Error")) {
                throw IllegalStateException("Error al desensamblar $targetClass: $originalSmali")
            }

            onProgress?.invoke("Inyectando AlertDialog...")
            val escapedTitle = escapeSmaliString(options.title)
            val fullMessage = if (!options.positiveUrl.isNullOrBlank()) {
                "${options.message}\n\n${options.positiveUrl}"
            } else {
                options.message
            }
            val escapedMsg = escapeSmaliString(fullMessage)
            val escapedPos = escapeSmaliString(options.positiveButton.ifBlank { "OK" })
            val cancelableInt = if (options.cancelable) "0x1" else "0x0"

            val negativeBlock = if (!options.negativeButton.isNullOrBlank()) {
                val escapedNeg = escapeSmaliString(options.negativeButton)
                """
    const-string v1, "$escapedNeg"
    const/4 v2, 0x0
    invoke-virtual {v0, v1, v2}, Landroid/app/AlertDialog${'$'}Builder;->setNegativeButton(Ljava/lang/CharSequence;Landroid/content/DialogInterface${'$'}OnClickListener;)Landroid/app/AlertDialog${'$'}Builder;
""".trimIndent()
            } else {
                ""
            }

            val dialogMethodSmali = """
.method private showOpenManagerDialog()V
    .registers 5

    new-instance v0, Landroid/app/AlertDialog${'$'}Builder;
    invoke-direct {v0, p0}, Landroid/app/AlertDialog${'$'}Builder;-><init>(Landroid/content/Context;)V

    const-string v1, "$escapedTitle"
    invoke-virtual {v0, v1}, Landroid/app/AlertDialog${'$'}Builder;->setTitle(Ljava/lang/CharSequence;)Landroid/app/AlertDialog${'$'}Builder;

    const-string v1, "$escapedMsg"
    invoke-virtual {v0, v1}, Landroid/app/AlertDialog${'$'}Builder;->setMessage(Ljava/lang/CharSequence;)Landroid/app/AlertDialog${'$'}Builder;

    const-string v1, "$escapedPos"
    const/4 v2, 0x0
    invoke-virtual {v0, v1, v2}, Landroid/app/AlertDialog${'$'}Builder;->setPositiveButton(Ljava/lang/CharSequence;Landroid/content/DialogInterface${'$'}OnClickListener;)Landroid/app/AlertDialog${'$'}Builder;
$negativeBlock
    const/4 v1, $cancelableInt
    invoke-virtual {v0, v1}, Landroid/app/AlertDialog${'$'}Builder;->setCancelable(Z)Landroid/app/AlertDialog${'$'}Builder;

    invoke-virtual {v0}, Landroid/app/AlertDialog${'$'}Builder;->show()Landroid/app/AlertDialog;
    return-void
.end method
""".trimIndent()

            val modifiedSmali = injectMethodCallIntoClass(
                originalSmali,
                targetDescriptor,
                "showOpenManagerDialog",
                dialogMethodSmali
            )

            onProgress?.invoke("Reensamblando DEX...")
            val (success, errorMsg) = DexEngine.assembleClassSmali(
                context,
                targetDexFile,
                modifiedSmali,
                targetDescriptor
            )
            if (!success) {
                throw IllegalStateException("Error al reensamblar clase: $errorMsg")
            }

            onProgress?.invoke("Empaquetando APK...")
            val unsignedTemp = File(workDir, "unsigned.apk")
            ApkModule.loadApkFile(inputApk).use { module ->
                module.add(FileInputSource(targetDexFile, targetDexEntry))
                module.writeApk(unsignedTemp)
            }

            onProgress?.invoke("Firmando APK...")
            ApkSignerHelper.signFileDirect(unsignedTemp, outputApk)
            return outputApk
        } finally {
            workDir.deleteRecursively()
        }
    }

    fun injectMethodCallIntoClass(
        smaliCode: String,
        classDescriptor: String,
        helperMethodName: String,
        helperMethodSmali: String
    ): String {
        val lines = smaliCode.lines().toMutableList()
        val invokeInstruction = "    invoke-direct {p0}, $classDescriptor->$helperMethodName()V"

        // Search for onCreate(Landroid/os/Bundle;)V
        var onCreateIndex = -1
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.startsWith(".method") && line.contains("onCreate(Landroid/os/Bundle;)V")) {
                onCreateIndex = i
                break
            }
        }

        if (onCreateIndex != -1) {
            // Find insertion point right after registers/locals and prologue, skipping any annotations
            var insertPos = onCreateIndex + 1
            var inAnnotation = false
            var inParam = false
            while (insertPos < lines.size) {
                val l = lines[insertPos].trim()
                if (l == ".end method") {
                    break
                }
                if (l.startsWith(".annotation")) {
                    inAnnotation = true
                    insertPos++
                    continue
                }
                if (inAnnotation) {
                    if (l.startsWith(".end annotation")) {
                        inAnnotation = false
                    }
                    insertPos++
                    continue
                }
                if (l.startsWith(".param")) {
                    if (!l.endsWith("\"") && !l.contains(",")) {
                        inParam = true
                    }
                    insertPos++
                    continue
                }
                if (inParam) {
                    if (l.startsWith(".end param")) {
                        inParam = false
                    }
                    insertPos++
                    continue
                }
                if (l.startsWith(".registers") || l.startsWith(".locals")) {
                    insertPos++
                    continue
                }
                if (l.startsWith(".prologue")) {
                    insertPos++
                    break
                }
                break
            }
            lines.add(insertPos, invokeInstruction)
        } else {
            // Find superclass from .super directive
            var superClass = "Landroid/app/Activity;"
            for (line in lines) {
                val t = line.trim()
                if (t.startsWith(".super ")) {
                    superClass = t.removePrefix(".super ").trim()
                    break
                }
            }
            val synthesizedOnCreate = """
.method protected onCreate(Landroid/os/Bundle;)V
    .registers 2
    .param p1, "savedInstanceState"

    invoke-super {p0, p1}, $superClass->onCreate(Landroid/os/Bundle;)V

$invokeInstruction

    return-void
.end method
""".trimIndent()
            lines.add(lines.size - 1, synthesizedOnCreate)
        }

        // Append helper method at the end before .end class or at file end
        lines.add(lines.size - 1, "\n$helperMethodSmali\n")
        return lines.joinToString("\n")
    }

    @Throws(Exception::class)
    fun cleanToastsAndDialogs(
        context: Context,
        inputApk: File,
        outputApk: File,
        options: CleanOptions,
        onProgress: ((String) -> Unit)? = null
    ): CleanResult {
        val workDir = File(context.cacheDir, "clean_toasts_${System.currentTimeMillis()}").apply { mkdirs() }
        var totalToasts = 0
        var totalDialogs = 0
        val modifiedDexEntries = mutableMapOf<String, File>()

        try {
            onProgress?.invoke("Extrayendo archivos DEX...")
            val dexFiles = extractDexFiles(inputApk, workDir)

            for ((entryName, dexFile) in dexFiles) {
                onProgress?.invoke("Analizando $entryName...")
                val dexBacked = DexFileFactory.loadDexFile(dexFile, DexEngine.defaultOpcodes)

                // Scan classes for candidates
                val candidateClasses = mutableSetOf<ClassDef>()
                for (cls in dexBacked.classes) {
                    var matches = false
                    for (m in cls.methods) {
                        val impl = m.implementation ?: continue
                        for (inst in impl.instructions) {
                            if (inst is ReferenceInstruction) {
                                val ref = inst.reference
                                if (ref is MethodReference) {
                                    val defClass = ref.definingClass
                                    val name = ref.name
                                    if (options.cleanToasts && defClass == "Landroid/widget/Toast;" && name == "show") {
                                        matches = true
                                        break
                                    }
                                    if (options.cleanDialogs && isDialogClass(defClass) && name == "show") {
                                        matches = true
                                        break
                                    }
                                }
                            }
                        }
                        if (matches) break
                    }
                    if (matches) {
                        candidateClasses.add(cls)
                    }
                }

                if (candidateClasses.isEmpty()) {
                    continue
                }

                var dexModified = false
                for (cls in candidateClasses) {
                    val originalSmali = DexEngine.disassembleClass(dexFile, cls.type)
                    var smali = originalSmali

                    if (options.cleanToasts) {
                        val matcher = TOAST_PATTERN.matcher(smali)
                        var count = 0
                        val sb = StringBuffer()
                        while (matcher.find()) {
                            matcher.appendReplacement(sb, Matcher.quoteReplacement("    nop # Neutralized Toast.show"))
                            count++
                        }
                        matcher.appendTail(sb)
                        if (count > 0) {
                            totalToasts += count
                            smali = sb.toString()
                        }
                    }

                    if (options.cleanDialogs) {
                        val matcher = DIALOG_PATTERN.matcher(smali)
                        var count = 0
                        val sb = StringBuffer()
                        while (matcher.find()) {
                            val reg = matcher.group(1)
                            val rep = if (reg != null) {
                                "    nop # Neutralized Dialog.show\n    const/4 $reg, 0x0"
                            } else {
                                "    nop # Neutralized Dialog.show"
                            }
                            matcher.appendReplacement(sb, Matcher.quoteReplacement(rep))
                            count++
                        }
                        matcher.appendTail(sb)
                        if (count > 0) {
                            totalDialogs += count
                            smali = sb.toString()
                        }
                    }

                    if (smali != originalSmali) {
                        val (success, _) = DexEngine.assembleClassSmali(context, dexFile, smali, cls.type)
                        if (success) {
                            dexModified = true
                        }
                    }
                }

                if (dexModified) {
                    modifiedDexEntries[entryName] = dexFile
                }
            }

            if (modifiedDexEntries.isEmpty()) {
                throw IllegalStateException("No se encontraron llamadas activas a Toasts o Diálogos para neutralizar")
            }

            onProgress?.invoke("Empaquetando APK limpio...")
            val unsignedTemp = File(workDir, "unsigned.apk")
            ApkModule.loadApkFile(inputApk).use { module ->
                for ((entryName, dexFile) in modifiedDexEntries) {
                    module.add(FileInputSource(dexFile, entryName))
                }
                module.writeApk(unsignedTemp)
            }

            onProgress?.invoke("Firmando y alineando APK...")
            ApkSignerHelper.signFileDirect(unsignedTemp, outputApk)

            return CleanResult(
                toastsRemoved = totalToasts,
                dialogsRemoved = totalDialogs,
                dexFilesModified = modifiedDexEntries.size,
                outputFile = outputApk
            )
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun extractDexFiles(inputApk: File, targetDir: File): Map<String, File> {
        val result = mutableMapOf<String, File>()
        ZipFile(inputApk).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.isDirectory && entry.name.endsWith(".dex", ignoreCase = true)) {
                    val outFile = File(targetDir, entry.name)
                    outFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(outFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    result[entry.name] = outFile
                }
            }
        }
        return result
    }
}
