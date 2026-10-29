/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.file

import android.os.Parcelable
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
    private val lastModified: FileTime,
    private val lastAccess: FileTime,
    private val creation: FileTime,
    private val fileType: BasicFileType,
    private val fileSize: Long,
    private val key: ByteString
) : AbstractBasicFileAttributes() {
    override val lastModifiedTime: FileTime get() = lastModified
    override val lastAccessTime: FileTime get() = lastAccess
    override val creationTime: FileTime get() = creation
    override val type: BasicFileType get() = fileType
    override val size: Long get() = fileSize
    override val fileKey: Parcelable get() = key
}

fun Path.createFallbackFileItem(localFile: java.io.File? = null): FileItem? {
    return try {
        val file = localFile ?: (this as? LinuxPath)?.toFile() ?: java.io.File(this.toString())
        val isDir = file.isDirectory
        val isSymlink = try {
            java.nio.file.Files.isSymbolicLink(file.toPath())
        } catch (_: Throwable) {
            false
        }
        val type = when {
            isSymlink -> BasicFileType.SYMBOLIC_LINK
            isDir -> BasicFileType.DIRECTORY
            else -> BasicFileType.REGULAR_FILE
        }
        val lastModMs = file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
        val fileTime = FileTime.fromMillis(lastModMs)
        val size = if (isDir) 0L else file.length()
        val attrs = FallbackBasicFileAttributes(
            lastModified = fileTime,
            lastAccess = fileTime,
            creation = fileTime,
            fileType = type,
            fileSize = size,
            key = this.toString().toByteString()
        )
        val nameCollationKey = Collator.getInstance().getCollationKeyForFileName(this.name)
        val mimeType = if (isDir) MimeType.DIRECTORY else AndroidFileTypeDetector.getMimeType(this, attrs).asMimeType()
        FileItem(this, nameCollationKey, attrs, null, null, this.name.startsWith("."), mimeType)
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
    val symbolicLinkTarget = readSymbolicLinkByteString().toString()
    val symbolicLinkTargetAttributes = try {
        readAttributes(BasicFileAttributes::class.java)
    } catch (e: IOException) {
        e.printStackTrace()
        null
    }
    val mimeType = AndroidFileTypeDetector.getMimeType(
        this, symbolicLinkTargetAttributes ?: attributes
    ).asMimeType()
    return FileItem(
        this, nameCollationKey, attributes, symbolicLinkTarget, symbolicLinkTargetAttributes,
        isHidden, mimeType
    )
}
