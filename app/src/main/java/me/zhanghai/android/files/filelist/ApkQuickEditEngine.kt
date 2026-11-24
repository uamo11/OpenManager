/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import me.zhanghai.android.files.util.localizedText
import me.zhanghai.android.files.R
import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlAttribute
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.value.ValueType
import java.io.File

object ApkQuickEditEngine {

    const val ATTR_NAME = 0x01010003
    const val ATTR_MANAGE_SPACE_ACTIVITY = 0x01010004
    const val ATTR_PERMISSION = 0x01010006
    const val ATTR_READ_PERMISSION = 0x01010007
    const val ATTR_WRITE_PERMISSION = 0x01010008
    const val ATTR_SHARED_USER_ID = 0x0101000b
    const val ATTR_TASK_AFFINITY = 0x01010012
    const val ATTR_AUTHORITIES = 0x01010018
    const val ATTR_TARGET_ACTIVITY = 0x01010202
    const val ATTR_SHARED_USER_LABEL = 0x01010261
    const val ATTR_BACKUP_AGENT = 0x0101027f
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
                ?: throw IllegalStateException("Could not load AndroidManifest.xml from APK")

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
        onProgress?.invoke(localizedText(R.string.om_prog_loading_apk, "Loading APK…"))
        val unsignedFile = if (options.autoSign) {
            File(outputApk.parentFile ?: inputApk.parentFile, "${outputApk.nameWithoutExtension}_unsigned.tmp")
        } else {
            outputApk
        }

        try {
            ApkModule.loadApkFile(inputApk).use { module ->
                val manifest = module.androidManifest
                    ?: throw IllegalStateException("AndroidManifest.xml no encontrado en el APK")

                onProgress?.invoke(localizedText(R.string.om_prog_updating_manifest, "Updating manifest attributes…"))
                val oldPackage = try { manifest.packageName?.trim().orEmpty() } catch (_: Exception) { "" }
                val newPackage = options.packageName?.trim()?.takeIf { it.isNotEmpty() }

                if (newPackage != null && newPackage != oldPackage) {
                    clonePackageInManifest(module, manifest, oldPackage, newPackage)
                } else if (newPackage != null) {
                    manifest.packageName = newPackage
                }

                if (options.autoSign || (newPackage != null && newPackage != oldPackage)) {
                    try {
                        val root = manifest.manifestElement
                        root?.removeAttributesWithId(ATTR_SHARED_USER_ID)
                        root?.removeAttributesWithName("sharedUserId")
                        root?.removeAttributesWithId(ATTR_SHARED_USER_LABEL)
                        root?.removeAttributesWithName("sharedUserLabel")
                    } catch (_: Exception) {}
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
                    try {
                        module.setExtractNativeLibs(extract)
                    } catch (_: Exception) {
                        try { manifest.isExtractNativeLibs = extract } catch (ignored: Exception) {}
                    }
                }

                // Replace launcher icon if new bytes are provided
                options.newIconBytes?.let { iconBytes ->
                    onProgress?.invoke(localizedText(R.string.om_prog_updating_icons, "Updating app icons…"))
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

                onProgress?.invoke(localizedText(R.string.om_prog_aligning_packaging, "Aligning and packaging APK…"))
                AntiSplitEngine.writeAlignedUnsignedApk(module, unsignedFile, options.extractNativeLibs)
            }

            if (options.autoSign) {
                onProgress?.invoke(localizedText(R.string.om_prog_signing_aligning, "Signing and aligning APK…"))
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

    private fun clonePackageInManifest(
        module: ApkModule,
        manifest: AndroidManifestBlock,
        oldPackage: String,
        newPackage: String
    ) {
        if (oldPackage.isNotEmpty()) {
            // 1. Expand relative component class names (.MainActivity -> oldPackage.MainActivity) BEFORE changing package
            try {
                manifest.ensureFullClassNames()
            } catch (_: Exception) {}

            val manifestEl = manifest.manifestElement
            if (manifestEl != null) {
                val classAttrTargets = listOf(
                    ATTR_TARGET_ACTIVITY to "targetActivity",
                    ATTR_BACKUP_AGENT to "backupAgent",
                    ATTR_MANAGE_SPACE_ACTIVITY to "manageSpaceActivity"
                )
                val componentTags = setOf(
                    "application", "activity", "activity-alias", "service", "receiver", "provider"
                )
                val allElementsIter = manifestEl.recursiveElements()
                while (allElementsIter.hasNext()) {
                    val el = allElementsIter.next() ?: continue
                    if (el.name in componentTags) {
                        expandRelativeClassAttr(el, ATTR_NAME, "name", oldPackage)
                    }
                    for ((attrId, attrName) in classAttrTargets) {
                        expandRelativeClassAttr(el, attrId, attrName, oldPackage)
                    }
                }

                // 2. Rename custom <permission> definitions to avoid INSTALL_FAILED_DUPLICATE_PERMISSION (Android 13-16)
                val renamedPermissions = LinkedHashMap<String, String>()
                val permIter = manifestEl.getElements("permission")
                while (permIter.hasNext()) {
                    val permEl = permIter.next() ?: continue
                    val nameAttr = findAttr(permEl, ATTR_NAME, "name") ?: continue
                    val oldPerm = resolveAttrString(module, nameAttr) ?: continue
                    val newPerm = when {
                        oldPerm.contains(oldPackage) -> oldPerm.replace(oldPackage, newPackage)
                        !oldPerm.startsWith("android.permission.") && !oldPerm.contains(newPackage) ->
                            "$newPackage.permission.${oldPerm.substringAfterLast('.')}"
                        else -> oldPerm
                    }
                    if (newPerm != oldPerm) {
                        nameAttr.setValueAsString(newPerm)
                        renamedPermissions[oldPerm] = newPerm
                    }
                }

                // 3. Rewrite permission references and taskAffinity across all manifest elements
                val refIter = manifestEl.recursiveElements()
                while (refIter.hasNext()) {
                    val el = refIter.next() ?: continue
                    val tag = el.name
                    if (tag == "uses-permission" || tag == "uses-permission-sdk-23") {
                        val nameAttr = findAttr(el, ATTR_NAME, "name")
                        if (nameAttr != null) {
                            rewritePermissionAttr(module, nameAttr, oldPackage, newPackage, renamedPermissions)
                        }
                    }
                    findAttr(el, ATTR_PERMISSION, "permission")?.let {
                        rewritePermissionAttr(module, it, oldPackage, newPackage, renamedPermissions)
                    }
                    findAttr(el, ATTR_READ_PERMISSION, "readPermission")?.let {
                        rewritePermissionAttr(module, it, oldPackage, newPackage, renamedPermissions)
                    }
                    findAttr(el, ATTR_WRITE_PERMISSION, "writePermission")?.let {
                        rewritePermissionAttr(module, it, oldPackage, newPackage, renamedPermissions)
                    }
                    findAttr(el, ATTR_TASK_AFFINITY, "taskAffinity")?.let { attr ->
                        val cur = resolveAttrString(module, attr)
                        if (cur != null) {
                            val updated = when {
                                cur == oldPackage -> newPackage
                                cur.startsWith("$oldPackage.") -> newPackage + cur.removePrefix(oldPackage)
                                else -> cur
                            }
                            if (updated != cur) {
                                attr.setValueAsString(updated)
                            }
                        }
                    }
                }
            }

            // 4. Rewrite <provider android:authorities> to prevent INSTALL_FAILED_CONFLICTING_PROVIDER
            val appEl = manifest.applicationElement
            if (appEl != null) {
                val provIter = appEl.getElements("provider")
                while (provIter.hasNext()) {
                    val provEl = provIter.next() ?: continue
                    val authAttr = findAttr(provEl, ATTR_AUTHORITIES, "authorities") ?: continue
                    val rawAuth = resolveAttrString(module, authAttr) ?: continue
                    val rewritten = rawAuth.split(';')
                        .mapNotNull { token ->
                            val auth = token.trim()
                            if (auth.isEmpty()) {
                                null
                            } else when {
                                auth.contains(oldPackage) -> auth.replace(oldPackage, newPackage)
                                !auth.contains(newPackage) -> "$newPackage.provider.$auth"
                                else -> auth
                            }
                        }
                        .joinToString(";")
                    if (rewritten.isNotEmpty() && rewritten != rawAuth) {
                        authAttr.setValueAsString(rewritten)
                    }
                }
            }
        }

        // 5. Set the new package name last
        manifest.packageName = newPackage
    }

    private fun findAttr(el: ResXmlElement, resId: Int, fallbackName: String): ResXmlAttribute? =
        el.searchAttributeByResourceId(resId) ?: el.searchAttributeByName(fallbackName)

    private fun resolveAttrString(module: ApkModule, attr: ResXmlAttribute): String? {
        if (attr.valueType == ValueType.STRING) {
            return attr.valueAsString
        }
        if (attr.valueType == ValueType.REFERENCE && module.hasTableBlock()) {
            return try {
                module.tableBlock?.getResource(attr.data)?.get()?.valueAsString
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    private fun expandRelativeClassAttr(
        el: ResXmlElement,
        resId: Int,
        attrName: String,
        oldPackage: String
    ) {
        val attr = findAttr(el, resId, attrName) ?: return
        if (attr.valueType != ValueType.STRING) return
        val cur = attr.valueAsString?.trim() ?: return
        if (cur.isEmpty() || cur.startsWith("@")) return
        val expanded = when {
            cur.startsWith(".") -> "$oldPackage$cur"
            !cur.contains(".") -> "$oldPackage.$cur"
            else -> cur
        }
        if (expanded != cur) {
            attr.setValueAsString(expanded)
        }
    }

    private fun rewritePermissionAttr(
        module: ApkModule,
        attr: ResXmlAttribute,
        oldPackage: String,
        newPackage: String,
        renamedPermissions: Map<String, String>
    ) {
        val cur = resolveAttrString(module, attr) ?: return
        val mapped = renamedPermissions[cur] ?: when {
            cur.startsWith("android.permission.") -> cur
            cur.contains(oldPackage) -> cur.replace(oldPackage, newPackage)
            else -> cur
        }
        if (mapped != cur) {
            attr.setValueAsString(mapped)
        }
    }
}
