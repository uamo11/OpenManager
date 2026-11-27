/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.saf

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.Build
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import me.zhanghai.android.files.R
import java.io.File
import java.io.FileNotFoundException
import java.util.Locale

/**
 * OpenManager in the system file picker ("Open from…", "Save to…"): other apps can browse the
 * phone's storage through it to open, save, rename or delete files. Document ids are absolute
 * paths. It only lists anything once OpenManager has storage access.
 */
class StorageDocumentsProvider : DocumentsProvider() {

    override fun onCreate(): Boolean = true

    private fun hasAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context?.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    /** Internal storage first, then SD cards and USB drives that are mounted. */
    private fun storageRoots(context: Context): List<Pair<String, File>> {
        val roots = mutableListOf("primary" to Environment.getExternalStorageDirectory())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val manager = context.getSystemService(StorageManager::class.java)
            manager?.storageVolumes?.forEach { volume ->
                val dir = volume.directory ?: return@forEach
                if (!volume.isPrimary && volume.state == Environment.MEDIA_MOUNTED) {
                    roots += (volume.uuid ?: dir.name) to dir
                }
            }
        }
        return roots
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val context = context ?: return result
        if (!hasAccess()) return result
        for ((id, dir) in storageRoots(context)) {
            result.newRow().apply {
                add(Root.COLUMN_ROOT_ID, id)
                add(Root.COLUMN_DOCUMENT_ID, dir.absolutePath)
                add(Root.COLUMN_TITLE, context.getString(R.string.app_name))
                add(
                    Root.COLUMN_SUMMARY,
                    if (id == "primary") context.getString(R.string.om_documents_internal_storage) else dir.name
                )
                add(
                    Root.COLUMN_FLAGS,
                    Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD or
                        Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_SEARCH
                )
                add(Root.COLUMN_ICON, R.mipmap.launcher_icon)
                add(Root.COLUMN_AVAILABLE_BYTES, dir.usableSpace)
            }
        }
        return result
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        addRow(result, fileOf(documentId))
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val parent = fileOf(parentDocumentId)
        // Every file, dot-files included: the system picker hides or shows them with its own
        // "Show hidden files" switch (user report: switched on, they still never appeared).
        parent.listFiles()?.forEach { addRow(result, it) }
        return result
    }

    override fun querySearchDocuments(rootId: String, query: String, projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val context = context ?: return result
        val root = storageRoots(context).firstOrNull { it.first == rootId }?.second ?: return result
        val needle = query.lowercase(Locale.ROOT)
        val pending = ArrayDeque<File>().apply { add(root) }
        var found = 0
        while (pending.isNotEmpty() && found < MAX_SEARCH_RESULTS) {
            val dir = pending.removeFirst()
            for (child in dir.listFiles().orEmpty()) {
                val hidden = child.name.startsWith(".")
                // Hidden matches are returned (the picker filters them as set); hidden folders
                // are not searched into, some (.thumbnails) are huge.
                if (child.name.lowercase(Locale.ROOT).contains(needle)) {
                    addRow(result, child)
                    if (++found >= MAX_SEARCH_RESULTS) break
                }
                if (child.isDirectory && !hidden) pending += child
            }
        }
        return result
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId == parentDocumentId || documentId.startsWith(parentDocumentId.trimEnd('/') + "/")

    override fun getDocumentType(documentId: String): String = mimeTypeOf(fileOf(documentId))

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val file = fileOf(documentId)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun openDocumentThumbnail(documentId: String, sizeHint: Point?, signal: CancellationSignal?): AssetFileDescriptor {
        // Images are their own thumbnails: the picker scales them.
        val file = fileOf(documentId)
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = fileOf(parentDocumentId)
        var target = File(parent, displayName)
        if (target.exists()) {
            val base = displayName.substringBeforeLast('.', displayName)
            val ext = displayName.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
            var n = 1
            while (target.exists()) target = File(parent, "$base ($n)$ext").also { n++ }
        }
        val ok = if (mimeType == Document.MIME_TYPE_DIR) target.mkdir() else target.createNewFile()
        if (!ok) throw FileNotFoundException("Could not create $target")
        return target.absolutePath
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val file = fileOf(documentId)
        val target = File(file.parentFile, displayName)
        if (!file.renameTo(target)) throw FileNotFoundException("Could not rename $file")
        return target.absolutePath
    }

    override fun deleteDocument(documentId: String) {
        val file = fileOf(documentId)
        if (!file.deleteRecursively()) throw FileNotFoundException("Could not delete $file")
    }

    /** Only paths inside the storage roots, never "/data" or ".." tricks. */
    private fun fileOf(documentId: String): File {
        if (!hasAccess()) throw FileNotFoundException("No storage access")
        val file = File(documentId).canonicalFile
        val context = context ?: throw FileNotFoundException(documentId)
        val allowed = storageRoots(context).any { (_, root) ->
            val rootPath = root.canonicalPath
            file.path == rootPath || file.path.startsWith("$rootPath/")
        }
        if (!allowed || !file.exists()) throw FileNotFoundException(documentId)
        return file
    }

    private fun addRow(result: MatrixCursor, file: File) {
        var flags = 0
        if (file.canWrite()) {
            flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
            flags = flags or if (file.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE
        }
        val mime = mimeTypeOf(file)
        if (mime.startsWith("image/")) flags = flags or Document.FLAG_SUPPORTS_THUMBNAIL
        result.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, file.absolutePath)
            add(Document.COLUMN_DISPLAY_NAME, file.name)
            add(Document.COLUMN_SIZE, if (file.isDirectory) null else file.length())
            add(Document.COLUMN_MIME_TYPE, mime)
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    private fun mimeTypeOf(file: File): String {
        if (file.isDirectory) return Document.MIME_TYPE_DIR
        val ext = file.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    companion object {
        private const val MAX_SEARCH_RESULTS = 200

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY,
            Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_AVAILABLE_BYTES
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS
        )
    }
}
