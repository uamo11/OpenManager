/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.Context
import com.reandroid.apk.APKLogger
import com.reandroid.apk.ApkBundle
import com.reandroid.apk.ApkModule
import com.reandroid.apkeditor.common.AndroidManifestHelper
import com.reandroid.app.AndroidManifest
import com.reandroid.archive.ZipEntryMap
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.ResXmlAttribute
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.container.SpecTypePair
import com.reandroid.arsc.model.ResourceEntry
import com.reandroid.arsc.value.ResValue
import com.reandroid.arsc.value.ValueType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.appmanager.AppModel
import me.zhanghai.android.files.util.escapeShellArg
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipFile

object AntiSplitEngine {

    data class AntiSplitOptions(
        val autoSign: Boolean = true,
        val extractNativeLibs: Boolean = true,
        val signingScheme: ApkSignerHelper.SignScheme = ApkSignerHelper.SignScheme.V1_AND_V2
    )

    /**
     * Merges a split archive (.apks, .xapk, .apkm, or .zip) into a single standalone .apk.
     */
    suspend fun mergeArchive(
        context: Context,
        archiveFile: File,
        outputFile: File,
        options: AntiSplitOptions = AntiSplitOptions(),
        logger: ((String) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "antisplit_" + UUID.randomUUID())
        try {
            if (!tempDir.exists() && !tempDir.mkdirs()) {
                logger?.invoke("Error: no se pudo crear el directorio temporal")
                return@withContext false
            }

            logger?.invoke("Extrayendo partes de la aplicación...")
            var extractedCount = 0
            ZipFile(archiveFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)) {
                        val simpleName = File(entry.name).name
                        val targetApk = File(tempDir, simpleName)
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(targetApk).use { output ->
                                input.copyTo(output)
                            }
                        }
                        extractedCount++
                    }
                }
            }

            if (extractedCount == 0) {
                logger?.invoke("Error: no se encontraron archivos .apk dentro del paquete")
                return@withContext false
            }

            logger?.invoke("Se encontraron $extractedCount partes. Iniciando fusión...")
            return@withContext mergeDirectory(tempDir, outputFile, options, logger)
        } catch (e: Exception) {
            e.printStackTrace()
            logger?.invoke("Fallo en AntiSplit: ${e.message}")
            return@withContext false
        } finally {
            deleteRecursively(tempDir)
        }
    }

    /**
     * Merges a list of split .apk files into a single standalone .apk.
     */
    suspend fun mergeFiles(
        context: Context,
        apkFiles: List<File>,
        outputFile: File,
        options: AntiSplitOptions = AntiSplitOptions(),
        logger: ((String) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "antisplit_" + UUID.randomUUID())
        try {
            if (!tempDir.exists() && !tempDir.mkdirs()) {
                logger?.invoke("Error: no se pudo crear el directorio temporal")
                return@withContext false
            }

            logger?.invoke("Copiando partes para fusión...")
            for (file in apkFiles) {
                if (file.exists() && file.isFile && file.name.endsWith(".apk", ignoreCase = true)) {
                    val target = File(tempDir, file.name)
                    FileInputStream(file).use { input ->
                        FileOutputStream(target).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }

            return@withContext mergeDirectory(tempDir, outputFile, options, logger)
        } catch (e: Exception) {
            e.printStackTrace()
            logger?.invoke("Fallo en AntiSplit: ${e.message}")
            return@withContext false
        } finally {
            deleteRecursively(tempDir)
        }
    }

    /**
     * Merges an installed split application into a single standalone .apk.
     */
    suspend fun mergeInstalledApp(
        context: Context,
        app: AppModel,
        outputFile: File,
        options: AntiSplitOptions = AntiSplitOptions(),
        logger: ((String) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "antisplit_" + UUID.randomUUID())
        try {
            if (!tempDir.exists() && !tempDir.mkdirs()) {
                logger?.invoke("Error: no se pudo crear el directorio temporal")
                return@withContext false
            }

            logger?.invoke("Extrayendo archivos base y complementos...")
            val baseFile = File(app.sourceDir)
            if (baseFile.canRead()) {
                val targetBase = File(tempDir, "base.apk")
                FileInputStream(baseFile).use { input ->
                    FileOutputStream(targetBase).use { output ->
                        input.copyTo(output)
                    }
                }
            } else if (ApkInstallHelper.isRootAvailable()) {
                val targetBase = File(tempDir, "base.apk")
                com.topjohnwu.superuser.Shell.cmd("cp ${app.sourceDir.escapeShellArg()} ${targetBase.absolutePath.escapeShellArg()} && chmod 644 ${targetBase.absolutePath.escapeShellArg()}").exec()
            }

            for (splitPath in app.splitSourceDirs) {
                val splitFile = File(splitPath)
                val targetSplit = File(tempDir, splitFile.name)
                if (splitFile.canRead()) {
                    FileInputStream(splitFile).use { input ->
                        FileOutputStream(targetSplit).use { output ->
                            input.copyTo(output)
                        }
                    }
                } else if (ApkInstallHelper.isRootAvailable()) {
                    com.topjohnwu.superuser.Shell.cmd("cp ${splitPath.escapeShellArg()} ${targetSplit.absolutePath.escapeShellArg()} && chmod 644 ${targetSplit.absolutePath.escapeShellArg()}").exec()
                }
            }

            return@withContext mergeDirectory(tempDir, outputFile, options, logger)
        } catch (e: Exception) {
            e.printStackTrace()
            logger?.invoke("Fallo en AntiSplit: ${e.message}")
            return@withContext false
        } finally {
            deleteRecursively(tempDir)
        }
    }

    /**
     * Core merge execution using REAndroid on a prepared directory containing .apk files.
     */
    fun mergeDirectory(
        tempDir: File,
        outputFile: File,
        options: AntiSplitOptions = AntiSplitOptions(),
        logger: ((String) -> Unit)? = null
    ): Boolean {
        val apks = tempDir.listFiles { _, name -> name.endsWith(".apk", ignoreCase = true) }
        if (apks.isNullOrEmpty()) {
            logger?.invoke("No se encontraron APKs válidas para fusionar")
            return false
        }

        logger?.invoke("Analizando ${apks.size} módulos...")
        val unsignedApk = File(tempDir, "temp_merged_unsigned.apk")

        try {
            ApkBundle().use { bundle ->
                if (logger != null) {
                    bundle.setAPKLogger(object : APKLogger {
                        override fun logMessage(msg: String?) {
                            if (!msg.isNullOrBlank()) logger.invoke(msg)
                        }
                        override fun logError(msg: String?, tr: Throwable?) {
                            if (!msg.isNullOrBlank()) logger.invoke("Error: $msg")
                        }
                        override fun logVerbose(msg: String?) {}
                    })
                }

                bundle.loadApkDirectory(tempDir, false)

                logger?.invoke("Fusionando recursos, dex y librerías...")
                val mergedModule: ApkModule = bundle.mergeModules(false)

                logger?.invoke("Saneando AndroidManifest...")
                sanitizeManifest(mergedModule)

                try {
                    mergedModule.setExtractNativeLibs(options.extractNativeLibs)
                } catch (ignored: Exception) {}

                mergedModule.refreshTable()
                mergedModule.refreshManifest()

                logger?.invoke("Generando archivo APK consolidado...")
                mergedModule.writeApk(unsignedApk)
            }

            if (!unsignedApk.exists() || unsignedApk.length() == 0L) {
                logger?.invoke("Error: el archivo APK generado está vacío")
                return false
            }

            if (options.autoSign) {
                logger?.invoke("Firmando digitalmente (V1 + V2 + V3)...")
                val parent = outputFile.parentFile
                if (parent != null && !parent.exists()) parent.mkdirs()
                ApkSignerHelper.signFileDirect(unsignedApk, outputFile, options.signingScheme)
            } else {
                val parent = outputFile.parentFile
                if (parent != null && !parent.exists()) parent.mkdirs()
                unsignedApk.copyTo(outputFile, overwrite = true)
            }

            logger?.invoke("Fusión completada con éxito.")
            return outputFile.exists() && outputFile.length() > 0L
        } catch (e: Exception) {
            e.printStackTrace()
            logger?.invoke("Excepción durante la fusión: ${e.message}")
            return false
        }
    }

    /**
     * Sanitizes AndroidManifest to convert split APK definition into a standalone monolithic APK.
     */
    fun sanitizeManifest(apkModule: ApkModule) {
        if (!apkModule.hasAndroidManifest()) return
        val manifest = apkModule.androidManifest ?: return

        try {
            AndroidManifestHelper.removeAttributeFromManifestById(
                manifest, AndroidManifest.ID_requiredSplitTypes, null
            )
            AndroidManifestHelper.removeAttributeFromManifestById(
                manifest, AndroidManifest.ID_splitTypes, null
            )
            AndroidManifestHelper.removeAttributeFromManifestByName(
                manifest, AndroidManifest.NAME_splitTypes, null
            )
            AndroidManifestHelper.removeAttributeFromManifestByName(
                manifest, AndroidManifest.NAME_requiredSplitTypes, null
            )
            AndroidManifestHelper.removeAttributeFromManifestByName(
                manifest, AndroidManifest.NAME_splitTypes, null
            )
            AndroidManifestHelper.removeAttributeFromManifestAndApplication(
                manifest, AndroidManifest.ID_extractNativeLibs, null, AndroidManifest.NAME_extractNativeLibs
            )
            AndroidManifestHelper.removeAttributeFromManifestAndApplication(
                manifest, AndroidManifest.ID_isSplitRequired, null, AndroidManifest.NAME_isSplitRequired
            )

            val application: ResXmlElement? = manifest.applicationElement
            if (application != null) {
                val splitMetaDataElements = AndroidManifestHelper.listSplitRequired(application)
                var splitsRemoved = false
                for (meta in splitMetaDataElements) {
                    if (!splitsRemoved) {
                        splitsRemoved = removeSplitsTableEntry(meta, apkModule)
                    }
                    application.remove(meta)
                }
            }
            manifest.refresh()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeSplitsTableEntry(metaElement: ResXmlElement, apkModule: ApkModule): Boolean {
        try {
            val nameAttribute: ResXmlAttribute? = metaElement.searchAttributeByResourceId(AndroidManifest.ID_name)
            if (nameAttribute?.valueAsString != "com.android.vending.splits") {
                return false
            }
            val valueAttribute: ResXmlAttribute? =
                metaElement.searchAttributeByResourceId(AndroidManifest.ID_value)
                    ?: metaElement.searchAttributeByResourceId(AndroidManifest.ID_resource)
            if (valueAttribute == null || valueAttribute.valueType != ValueType.REFERENCE) {
                return false
            }
            if (!apkModule.hasTableBlock()) {
                return false
            }
            val tableBlock: TableBlock = apkModule.tableBlock ?: return false
            val resourceEntry: ResourceEntry = tableBlock.getResource(valueAttribute.data) ?: return false
            val zipEntryMap: ZipEntryMap = apkModule.zipEntryMap ?: return false
            for (entry in resourceEntry) {
                if (entry == null) continue
                val resValue: ResValue = entry.resValue ?: continue
                val path = resValue.valueAsString
                if (path != null) {
                    zipEntryMap.remove(path)
                }
                entry.isNull = true
                val specTypePair: SpecTypePair = entry.typeBlock.parentSpecTypePair
                specTypePair.removeNullEntries(entry.id)
            }
            return true
        } catch (e: Exception) {
            return false
        }
    }

    private fun deleteRecursively(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursively(it) }
        }
        file.delete()
    }
}
