/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.file

import android.os.Parcelable
import android.system.Os
import androidx.annotation.WorkerThread
import java8.nio.file.LinkOption
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileTime
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.filelist.getCollationKeyForFileName
import me.zhanghai.android.files.filelist.name
import me.zhanghai.android.files.provider.common.AbstractBasicFileAttributes
import me.zhanghai.android.files.provider.common.AndroidFileTypeDetector
import me.zhanghai.android.files.provider.common.BasicFileType
import me.zhanghai.android.files.provider.common.ByteString
import me.zhanghai.android.files.provider.common.isHidden
import me.zhanghai.android.files.provider.common.readAttributes
import me.zhanghai.android.files.provider.common.readSymbolicLinkByteString
import me.zhanghai.android.files.provider.common.toByteString
import me.zhanghai.android.files.provider.linux.DirentMetadataCache
import me.zhanghai.android.files.provider.linux.LinuxPath
import me.zhanghai.android.files.util.ParcelableParceler
import java.io.IOException
import java.text.CollationKey
import java.text.Collator

@Parcelize
data class FileItem(
    val path: @WriteWith<ParcelableParceler> Path,
    val nameCollationKey: @WriteWith<ParcelableParceler> CollationKey,
    val attributesNoFollowLinks: @WriteWith<ParcelableParceler> BasicFileAttributes,
    val symbolicLinkTarget: String?,
    private val symbolicLinkTargetAttributes: @WriteWith<ParcelableParceler> BasicFileAttributes?,
    val isHidden: Boolean,
    val mimeType: MimeType
) : Parcelable {
    val attributes: BasicFileAttributes
        get() = symbolicLinkTargetAttributes ?: attributesNoFollowLinks

    val isSymbolicLinkBroken: Boolean
        get() {
            check(attributesNoFollowLinks.isSymbolicLink) { "Not a symbolic link" }
            return symbolicLinkTargetAttributes == null
        }
}

@Parcelize
class FallbackBasicFileAttributes(
    private val lastModifiedMillis: Long,
    private val fileType: BasicFileType,
    private val fileSize: Long,
    private val key: ByteString
) : AbstractBasicFileAttributes() {
    override val lastModifiedTime: FileTime get() = FileTime.fromMillis(lastModifiedMillis)
    override val lastAccessTime: FileTime get() = FileTime.fromMillis(lastModifiedMillis)
    override val creationTime: FileTime get() = FileTime.fromMillis(lastModifiedMillis)
    override val type: BasicFileType get() = fileType
    override val size: Long get() = fileSize
    override val fileKey: Parcelable get() = key
}

private val KNOWN_ANDROID_DIRECTORIES = setOf(
    "/", "/acct", "/apex", "/bin", "/bugreports", "/cache", "/config", "/d",
    "/data", "/data_mirror", "/debug_ramdisk", "/dev", "/efs", "/etc",
    "/linkerconfig", "/lost+found", "/metadata", "/mnt", "/odm", "/odm_dlkm",
    "/oem", "/omr", "/optics", "/postinstall", "/prism", "/proc", "/product",
    "/root", "/sbin", "/sdcard", "/second_stage_resources", "/spu", "/storage",
    "/sys", "/system", "/system_dlkm", "/system_ext", "/vendor", "/vendor_dlkm",
    "/storage/emulated", "/storage/emulated/0", "/storage/self",
    "/data/app", "/data/data", "/data/local", "/data/local/tmp", "/data/local/traces",
    "/data/media", "/data/media/0", "/data/misc", "/data/system", "/data/user",
    "/data/user/0", "/data/user_de", "/data/vendor", "/data/adb",
    "/mnt/sdcard", "/mnt/user", "/mnt/runtime", "/mnt/pass_through", "/mnt/installer"
)

fun Path.createFallbackFileItem(
    localFile: java.io.File? = null,
    forcedType: BasicFileType? = null
): FileItem? {
    return try {
        val pathStr = this.toString().let { if (it.length > 1) it.trimEnd('/') else it }
        val file = localFile ?: (this as? LinuxPath)?.toFile() ?: java.io.File(pathStr)
        val cachedDirentType = DirentMetadataCache.getCachedType(pathStr)

        var symlinkTarget: String? = null
        val isSymlink = when {
            forcedType == BasicFileType.SYMBOLIC_LINK || cachedDirentType == BasicFileType.SYMBOLIC_LINK -> {
                symlinkTarget = try { Os.readlink(pathStr) } catch (_: Throwable) { null }
                true
            }
            else -> {
                val target = try { Os.readlink(pathStr) } catch (_: Throwable) { null }
                if (target != null) {
                    symlinkTarget = target
                    true
                } else {
                    try {
                        java.nio.file.Files.isSymbolicLink(file.toPath())
                    } catch (_: Throwable) {
                        false
                    }
                }
            }
        }

        val isKnownDir = pathStr.lowercase() in KNOWN_ANDROID_DIRECTORIES ||
            pathStr.startsWith("/storage/emulated/0/Android/data/") ||
            pathStr.startsWith("/storage/emulated/0/Android/obb/") ||
            pathStr.startsWith("/data/data/") ||
            pathStr.startsWith("/data/user/0/")

        val isDir = when {
            forcedType == BasicFileType.DIRECTORY || cachedDirentType == BasicFileType.DIRECTORY -> true
            forcedType == BasicFileType.REGULAR_FILE || cachedDirentType == BasicFileType.REGULAR_FILE -> false
            isKnownDir -> true
            isSymlink && symlinkTarget != null -> {
                val resolvedTarget = if (symlinkTarget!!.startsWith("/")) {
                    symlinkTarget!!
                } else {
                    java.io.File(file.parentFile ?: java.io.File("/"), symlinkTarget!!).absolutePath
                }
                resolvedTarget.lowercase() in KNOWN_ANDROID_DIRECTORIES ||
                    java.io.File(resolvedTarget).isDirectory
            }
            else -> file.isDirectory || (!this.name.contains('.') && file.parent == "/")
        }

        val type = when {
            forcedType != null -> forcedType
            cachedDirentType != null -> cachedDirentType
            isSymlink -> BasicFileType.SYMBOLIC_LINK
            isDir -> BasicFileType.DIRECTORY
            else -> BasicFileType.REGULAR_FILE
        }

        val lastModMs = file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
        val size = if (isDir) 0L else file.length()
        val attrs = FallbackBasicFileAttributes(
            lastModifiedMillis = lastModMs,
            fileType = type,
            fileSize = size,
            key = pathStr.toByteString()
        )
        val targetAttrs = if (type == BasicFileType.SYMBOLIC_LINK && isDir) {
            FallbackBasicFileAttributes(
                lastModifiedMillis = lastModMs,
                fileType = BasicFileType.DIRECTORY,
                fileSize = 0L,
                key = (symlinkTarget ?: pathStr).toByteString()
            )
        } else {
            null
        }
        val nameCollationKey = Collator.getInstance().getCollationKeyForFileName(this.name)
        val effectiveIsDir = isDir || type == BasicFileType.DIRECTORY || targetAttrs?.isDirectory == true
        val mimeType = if (effectiveIsDir) {
            MimeType.DIRECTORY
        } else {
            AndroidFileTypeDetector.getMimeType(this, targetAttrs ?: attrs).asMimeType()
        }
        FileItem(
            this,
            nameCollationKey,
            attrs,
            symlinkTarget,
            targetAttrs,
            this.name.startsWith("."),
            mimeType
        )
    } catch (_: Throwable) {
        null
    }
}

@WorkerThread
@Throws(IOException::class)
fun Path.loadFileItem(): FileItem {
    val nameCollationKey = Collator.getInstance().getCollationKeyForFileName(name)
    val attributes = readAttributes(BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    val isHidden = isHidden
    if (!attributes.isSymbolicLink) {
        val mimeType = AndroidFileTypeDetector.getMimeType(this, attributes).asMimeType()
        return FileItem(this, nameCollationKey, attributes, null, null, isHidden, mimeType)
    }
    val symbolicLinkTarget = try {
        readSymbolicLinkByteString().toString()
    } catch (_: IOException) {
        try { Os.readlink(this.toString()) } catch (_: Throwable) { "" }
    }
    val symbolicLinkTargetAttributes = try {
        readAttributes(BasicFileAttributes::class.java)
    } catch (e: IOException) {
        val resolvedTarget = if (symbolicLinkTarget.startsWith("/")) {
            symbolicLinkTarget
        } else {
            java.io.File(java.io.File(this.toString()).parentFile ?: java.io.File("/"), symbolicLinkTarget).absolutePath
        }
        if (resolvedTarget.lowercase() in KNOWN_ANDROID_DIRECTORIES || java.io.File(resolvedTarget).isDirectory) {
            FallbackBasicFileAttributes(
                lastModifiedMillis = System.currentTimeMillis(),
                fileType = BasicFileType.DIRECTORY,
                fileSize = 0L,
                key = resolvedTarget.toByteString()
            )
        } else {
            null
        }
    }
    val mimeType = AndroidFileTypeDetector.getMimeType(
        this, symbolicLinkTargetAttributes ?: attributes
    ).asMimeType()
    return FileItem(
        this, nameCollationKey, attributes, symbolicLinkTarget, symbolicLinkTargetAttributes,
        isHidden, mimeType
    )
}
