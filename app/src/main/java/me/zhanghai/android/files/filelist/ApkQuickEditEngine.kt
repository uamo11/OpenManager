/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.value.ValueType
import java.io.File

object ApkQuickEditEngine {

    const val ATTR_INSTALL_LOCATION = 0x010102b7

    data class QuickEditInfo(
        val appName: String,
        val packageName: String,
        val versionCode: Int,
        val versionName: String,
        val minSdkVersion: Int,
        val targetSdkVersion: Int,
        val installLocation: Int?,
        val iconResourceId: Int = 0,
        val isDebuggable: Boolean = false,
        val isExtractNativeLibs: Boolean? = null
    )

    data class QuickEditOptions(
        val appName: String? = null,
        val packageName: String? = null,
        val versionCode: Int? = null,
        val versionName: String? = null,
        val minSdkVersion: Int? = null,
        val targetSdkVersion: Int? = null,
        val installLocation: Int? = null,
        val newIconBytes: ByteArray? = null,
        val debuggable: Boolean? = null,
        val extractNativeLibs: Boolean? = null,
        val autoSign: Boolean = true,
        val signingScheme: ApkSignerHelper.SignScheme = ApkSignerHelper.SignScheme.V1_AND_V2
    )

    @Throws(Exception::class)
    fun loadQuickEditInfo(apkFile: File): QuickEditInfo {
        ApkModule.loadApkFile(apkFile).use { module ->
            val manifest = module.androidManifest
                ?: throw IllegalStateException("No se pudo cargar el AndroidManifest.xml del APK")

            var appName = manifest.applicationLabelString ?: ""
            if (appName.isEmpty()) {
                val labelRef = manifest.applicationLabelReference
                if (labelRef != null && labelRef != 0 && module.hasTableBlock()) {
                    try {
                        val table = module.tableBlock
                        val res = table.getResource(labelRef)
                        if (res != null) {
                            appName = res.get()?.valueAsString ?: ""
                        }
                    } catch (ignored: Exception) {}
                }
            }

            val pkgName = try { manifest.packageName ?: "" } catch (e: Exception) { "" }
            val verCode = try { manifest.versionCode ?: 0 } catch (e: Exception) { 0 }
            val verName = try { manifest.versionName ?: "" } catch (e: Exception) { "" }
            val minSdk = try { manifest.minSdkVersion ?: 1 } catch (e: Exception) { 1 }
            val targetSdk = try { manifest.targetSdkVersion ?: 1 } catch (e: Exception) { 1 }

            val installAttr = try { manifest.manifestElement?.searchAttributeByResourceId(ATTR_INSTALL_LOCATION) } catch (e: Exception) { null }
            val installLoc = installAttr?.data

            val iconResId = try { manifest.iconResourceId } catch (e: Exception) { 0 }
            val isDebug = try { manifest.isDebuggable } catch (e: Exception) { false }
            val isExtractNative = try { manifest.isExtractNativeLibs } catch (e: Exception) { null }

            return QuickEditInfo(
                appName = appName,
                packageName = pkgName,
                versionCode = verCode,
                versionName = verName,
                minSdkVersion = minSdk,
                targetSdkVersion = targetSdk,
                installLocation = installLoc,
                iconResourceId = iconResId,
                isDebuggable = isDebug,
                isExtractNativeLibs = isExtractNative
            )
        }
    }

    @Throws(Exception::class)
    fun applyQuickEdit(
        inputApk: File,
        outputApk: File,
        options: QuickEditOptions,
        onProgress: ((String) -> Unit)? = null
    ): File {
        onProgress?.invoke("Cargando APK...")
        val unsignedFile = if (options.autoSign) {
            File(outputApk.parentFile ?: inputApk.parentFile, "${outputApk.nameWithoutExtension}_unsigned.tmp")
        } else {
            outputApk
        }

        try {
            ApkModule.loadApkFile(inputApk).use { module ->
                val manifest = module.androidManifest
                    ?: throw IllegalStateException("AndroidManifest.xml no encontrado en el APK")

                onProgress?.invoke("Actualizando atributos del manifiesto...")
                options.packageName?.takeIf { it.isNotBlank() }?.let {
                    manifest.packageName = it.trim()
                }
                options.appName?.takeIf { it.isNotBlank() }?.let {
                    manifest.setApplicationLabel(it.trim())
                }
                options.versionCode?.let {
                    manifest.versionCode = it
                }
                options.versionName?.takeIf { it.isNotBlank() }?.let {
                    manifest.versionName = it.trim()
                }
                options.minSdkVersion?.let {
                    manifest.minSdkVersion = it
                }
                options.targetSdkVersion?.let {
                    manifest.targetSdkVersion = it
                }

                options.installLocation?.let { loc ->
                    val root = manifest.manifestElement
                    if (loc in 0..2) {
                        val attr = root.getOrCreateAndroidAttribute("installLocation", ATTR_INSTALL_LOCATION)
                        attr.valueType = ValueType.DEC
                        attr.data = loc
                    } else if (loc == -1) {
                        val attr = root.searchAttributeByResourceId(ATTR_INSTALL_LOCATION)
                        attr?.removeSelf()
                    }
                }

                options.debuggable?.let { debug ->
                    try { manifest.isDebuggable = debug } catch (ignored: Exception) {}
                }
                options.extractNativeLibs?.let { extract ->
                    try { manifest.isExtractNativeLibs = extract } catch (ignored: Exception) {}
                }

                // Replace launcher icon if new bytes are provided
                options.newIconBytes?.let { iconBytes ->
                    onProgress?.invoke("Actualizando iconos de la aplicación...")
                    val iconResId = try { manifest.iconResourceId } catch (e: Exception) { 0 }
                    if (iconResId != 0) {
                        try {
                            val resFiles = module.listResFiles(iconResId, null)
                            for (rf in resFiles) {
                                if (!rf.isBinaryXml) {
                                    module.add(ByteInputSource(iconBytes, rf.filePath))
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    val roundIconResId = manifest.roundIconResourceId
                    if (roundIconResId != 0 && roundIconResId != iconResId) {
                        try {
                            val resFiles = module.listResFiles(roundIconResId, null)
                            for (rf in resFiles) {
                                if (!rf.isBinaryXml) {
                                    module.add(ByteInputSource(iconBytes, rf.filePath))
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }

                onProgress?.invoke("Guardando archivo APK modificado...")
                module.writeApk(unsignedFile)
            }

            if (options.autoSign) {
                onProgress?.invoke("Firmando y alineando APK...")
                ApkSignerHelper.signFileDirect(unsignedFile, outputApk, options.signingScheme)
                return outputApk
            } else {
                return unsignedFile
            }
        } finally {
            if (options.autoSign && unsignedFile.exists()) {
                unsignedFile.delete()
            }
        }
    }
}
