/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.app.Activity
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.system.Os
import android.webkit.MimeTypeMap
import android.widget.Toast
import java8.nio.file.Path
import java8.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.archive.isArchivePath
import me.zhanghai.android.files.provider.common.newDirectoryStream
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.getParcelableExtraSafe
import me.zhanghai.android.files.util.startActivitySafe
import java.io.File
import java.io.FileOutputStream

object SmartUriPathResolver {

    private const val STAGED_DIR_NAME = "Recibidos"
    private const val STAGE_RETENTION_MS = 7L * 24 * 60 * 60 * 1000

    data class ResolvedIncomingTarget(
        val kind: SmartOpenKind,
        val displayName: String,
        val mimeType: String?,
        val sourceSize: Long,
        val sourceUri: Uri?,
        val directFile: File?,
        val isParentBrowsable: Boolean
    )

    suspend fun inspect(context: Context, intent: Intent): ResolvedIncomingTarget? =
        withContext(Dispatchers.IO) {
            val incomingUri = extractIncomingUri(intent)
            val fallbackExtraPath = try {
                intent.extraPath
            } catch (_: Throwable) {
                null
            }

            if (incomingUri == null && fallbackExtraPath == null) {
                return@withContext null
            }

            if (incomingUri == null && fallbackExtraPath != null) {
                val linuxFile = if (fallbackExtraPath.isLinuxPath && !fallbackExtraPath.isArchivePath) {
                    fallbackExtraPath.toFile()
                } else {
                    null
                }
                val name = SmartFileOpenEngine.sanitizeDisplayName(
                    fallbackExtraPath.fileName?.toString(),
                    "file"
                )
                val header = linuxFile?.takeIf { it.isFile && it.canRead() }?.let { readHeaderBytes(it) }
                val kind = SmartFileOpenEngine.classifySmartOpen(
                    mimeType = intent.type,
                    displayName = name,
                    headerBytes = header,
                    isDirectory = linuxFile?.isDirectory == true
                )
                val browsable = linuxFile?.parentFile?.let { isDirectoryListable(it) } ?: false
                return@withContext ResolvedIncomingTarget(
                    kind = kind,
                    displayName = name,
                    mimeType = intent.type,
                    sourceSize = linuxFile?.takeIf { it.isFile }?.length() ?: -1L,
                    sourceUri = null,
                    directFile = linuxFile,
                    isParentBrowsable = browsable
                )
            }

            val uri = incomingUri!!
            val cr = context.contentResolver
            val resolvedMime = resolveMimeType(cr, uri, intent.type)
            val (queriedName, queriedSize, dataColPath) = queryMetadata(cr, uri)

            val directFile = resolveRealFile(context, cr, uri, dataColPath, queriedName, queriedSize)
            val rawName = queriedName
                ?: directFile?.name
                ?: uri.lastPathSegment?.substringAfterLast('/')
            val sanitizedName = SmartFileOpenEngine.sanitizeDisplayName(
                rawName,
                buildFallbackName(resolvedMime)
            )
            val finalName = appendExtensionIfNeeded(sanitizedName, resolvedMime)

            val headerBytes = readHeaderBytes(directFile, cr, uri)
            val kind = SmartFileOpenEngine.classifySmartOpen(
                mimeType = resolvedMime,
                displayName = finalName,
                headerBytes = headerBytes,
                isDirectory = directFile?.isDirectory == true
            )
            val sourceSize = when {
                queriedSize > 0L -> queriedSize
                directFile != null && directFile.isFile -> directFile.length()
                else -> -1L
            }
            val isParentBrowsable = directFile?.parentFile?.let { isDirectoryListable(it) } ?: false

            ResolvedIncomingTarget(
                kind = kind,
                displayName = finalName,
                mimeType = resolvedMime,
                sourceSize = sourceSize,
                sourceUri = uri,
                directFile = directFile,
                isParentBrowsable = isParentBrowsable
            )
        }

    suspend fun ensureBrowsableFilePath(
        context: Context,
        target: ResolvedIncomingTarget
    ): Pair<Path, Boolean> = withContext(Dispatchers.IO) {
        val direct = target.directFile
        if (direct != null && direct.exists() && target.isParentBrowsable) {
            return@withContext Paths.get(direct.absolutePath) to false
        }

        val stageDir = getStageDirectory(context)
        val existingMap = HashMap<String, Long>()
        stageDir.listFiles()?.forEach { child ->
            if (child.isFile) {
                existingMap[child.name] = child.length()
            }
        }

        val (targetName, reuseExisting) = SmartFileOpenEngine.resolveUniqueStageFileName(
            existingNamesWithSizes = existingMap,
            desiredName = target.displayName,
            sourceSize = target.sourceSize
        )
        val destFile = File(stageDir, targetName)
        if (reuseExisting && destFile.exists()) {
            return@withContext Paths.get(destFile.absolutePath) to false
        }

        val tmpFile = File(stageDir, ".$targetName.${System.currentTimeMillis()}.part")
        try {
            val copied = copyTargetToFile(context, target, tmpFile)
            if (!copied) {
                tmpFile.delete()
                throw IllegalStateException("Cannot read incoming URI or file stream")
            }
            if (destFile.exists()) {
                destFile.delete()
            }
            if (!tmpFile.renameTo(destFile)) {
                tmpFile.inputStream().use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                    }
                }
                tmpFile.delete()
            }
            Paths.get(destFile.absolutePath) to true
        } catch (t: Throwable) {
            tmpFile.delete()
            throw t
        }
    }

    suspend fun openTargetLocationInExplorer(
        activity: Activity,
        target: ResolvedIncomingTarget
    ): Boolean {
        val (browsablePath, wasStaged) = ensureBrowsableFilePath(activity, target)
        withContext(Dispatchers.Main) {
            if (activity.isFinishing || activity.isDestroyed) return@withContext
            if (wasStaged) {
                val parentLabel = browsablePath.parent?.toString() ?: ""
                Toast.makeText(
                    activity,
                    activity.getString(R.string.om_smart_open_staged_toast, parentLabel),
                    Toast.LENGTH_LONG
                ).show()
            }
            activity.startActivitySafe(FileListActivity.createLocateFileIntent(activity, browsablePath))
        }
        return true
    }

    suspend fun locateCurrentOrIncomingInExplorer(
        activity: Activity,
        intent: Intent,
        currentPath: Path?
    ): Boolean {
        if (currentPath != null && !currentPath.isArchivePath && currentPath.isLinuxPath) {
            val listable = withContext(Dispatchers.IO) {
                currentPath.toFile().parentFile?.let { isDirectoryListable(it) } ?: false
            }
            if (listable) {
                withContext(Dispatchers.Main) {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        activity.startActivitySafe(
                            FileListActivity.createLocateFileIntent(activity, currentPath)
                        )
                    }
                }
                return true
            }
        }
        val target = inspect(activity, intent) ?: return false
        return openTargetLocationInExplorer(activity, target)
    }

    private fun extractIncomingUri(intent: Intent): Uri? {
        intent.data?.let { return it }
        intent.getParcelableExtraSafe<Uri>(Intent.EXTRA_STREAM)?.let { return it }
        val clip = intent.clipData
        if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0)?.uri?.let { return it }
        }
        return null
    }

    private fun resolveMimeType(
        cr: ContentResolver,
        uri: Uri,
        intentType: String?
    ): String? {
        val crType = try {
            if (uri.scheme == ContentResolver.SCHEME_CONTENT) cr.getType(uri) else null
        } catch (_: Throwable) {
            null
        }
        val effective = when {
            !crType.isNullOrBlank() && crType != "*/*" && crType != "application/octet-stream" -> crType
            !intentType.isNullOrBlank() && intentType != "*/*" -> intentType
            else -> crType ?: intentType
        }
        return effective?.trim()?.substringBefore(';')?.takeIf { it.isNotEmpty() }
    }

    private data class QueriedUriMetadata(
        val displayName: String?,
        val size: Long,
        val dataPath: String?
    )

    private fun queryMetadata(cr: ContentResolver, uri: Uri): QueriedUriMetadata {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            return QueriedUriMetadata(null, -1L, null)
        }
        var displayName: String? = null
        var size = -1L
        var dataPath: String? = null
        try {
            cr.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                        displayName = cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }
                    }
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        size = cursor.getLong(sizeIdx)
                    }
                    @Suppress("DEPRECATION")
                    val dataIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (dataIdx >= 0 && !cursor.isNull(dataIdx)) {
                        dataPath = cursor.getString(dataIdx)?.takeIf { it.isNotBlank() }
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return QueriedUriMetadata(displayName, size, dataPath)
    }

    private fun resolveRealFile(
        context: Context,
        cr: ContentResolver,
        uri: Uri,
        dataColPath: String?,
        displayName: String?,
        queriedSize: Long
    ): File? {
        if (uri.scheme == ContentResolver.SCHEME_FILE || uri.scheme == null) {
            val normalized = SmartFileOpenEngine.normalizeKernelOrProviderPath(uri.path)
            if (normalized != null) {
                val f = File(normalized)
                if (f.exists()) return f
            }
        }

        if (DocumentsContract.isDocumentUri(context, uri)) {
            val docId = try {
                DocumentsContract.getDocumentId(uri)
            } catch (_: Throwable) {
                null
            }
            when (uri.authority) {
                "com.android.externalstorage.documents" -> {
                    val decoded = SmartFileOpenEngine.decodeExternalStorageDocumentId(docId)
                        ?.let { SmartFileOpenEngine.normalizeKernelOrProviderPath(it) }
                    if (decoded != null) {
                        val f = File(decoded)
                        if (f.exists()) return f
                    }
                }
                "com.android.providers.downloads.documents" -> {
                    SmartFileOpenEngine.decodeDownloadsDocumentId(docId)?.let { decoded ->
                        val f = File(decoded)
                        if (f.exists()) return f
                    }
                    val numericId = docId?.substringAfter(':')?.toLongOrNull() ?: docId?.toLongOrNull()
                    if (numericId != null) {
                        val downloadUris = mutableListOf(
                            ContentUris.withAppendedId(Uri.parse("content://downloads/public_downloads"), numericId),
                            ContentUris.withAppendedId(Uri.parse("content://downloads/my_downloads"), numericId),
                            ContentUris.withAppendedId(Uri.parse("content://downloads/all_downloads"), numericId)
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            downloadUris.add(
                                ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, numericId)
                            )
                        }
                        for (dlUri in downloadUris) {
                            queryDataColumn(cr, dlUri, null, null)?.let { p ->
                                val f = File(p)
                                if (f.exists()) return f
                            }
                        }
                    }
                }
                "com.android.providers.media.documents" -> {
                    if (docId != null && docId.contains(':')) {
                        val parts = docId.split(':', limit = 2)
                        val type = parts[0]
                        val id = parts[1]
                        val mediaUri = when (type) {
                            "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                            "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                            "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                            else -> MediaStore.Files.getContentUri("external")
                        }
                        queryDataColumn(cr, mediaUri, "_id=?", arrayOf(id))?.let { p ->
                            val f = File(p)
                            if (f.exists()) return f
                        }
                    }
                }
            }
        }

        SmartFileOpenEngine.normalizeKernelOrProviderPath(dataColPath)?.let { p ->
            val f = File(p)
            if (f.exists()) return f
        }

        readProcFdPath(cr, uri)?.let { fdPath ->
            if (!fdPath.startsWith("/dev/") && !fdPath.startsWith("/memfd:")) {
                val f = File(fdPath)
                if (f.exists()) return f
            }
        }

        val candidates = SmartFileOpenEngine.extractEmbeddedStorageCandidates(uri.path, displayName)
        for (candidate in candidates) {
            val f = File(candidate)
            if (f.exists() && f.isFile) {
                if (queriedSize <= 0L || f.length() == queriedSize) {
                    return f
                }
            }
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun queryDataColumn(
        cr: ContentResolver,
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?
    ): String? {
        return try {
            cr.query(uri, arrayOf(MediaStore.MediaColumns.DATA), selection, selectionArgs, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                        if (idx >= 0 && !cursor.isNull(idx)) {
                            SmartFileOpenEngine.normalizeKernelOrProviderPath(cursor.getString(idx))
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                }
        } catch (_: Throwable) {
            null
        }
    }

    private fun readProcFdPath(cr: ContentResolver, uri: Uri): String? {
        return try {
            cr.openFileDescriptor(uri, "r")?.use { pfd ->
                val link = Os.readlink("/proc/self/fd/${pfd.fd}")
                SmartFileOpenEngine.normalizeKernelOrProviderPath(link)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun readHeaderBytes(file: File): ByteArray? {
        return try {
            file.inputStream().use { input ->
                val buf = ByteArray(16)
                val read = input.read(buf)
                if (read > 0) buf.copyOf(read) else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun readHeaderBytes(directFile: File?, cr: ContentResolver, uri: Uri): ByteArray? {
        if (directFile != null && directFile.isFile && directFile.canRead()) {
            readHeaderBytes(directFile)?.let { return it }
        }
        return try {
            cr.openInputStream(uri)?.use { input ->
                val buf = ByteArray(16)
                val read = input.read(buf)
                if (read > 0) buf.copyOf(read) else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun isDirectoryListable(dir: File): Boolean {
        if (dir.isDirectory && dir.list() != null) {
            return true
        }
        return try {
            Paths.get(dir.absolutePath).newDirectoryStream().use { stream ->
                stream.iterator().hasNext() || true
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Where files received from other apps without a real path are copied so the explorer can show
     * them: an app-specific "Received" folder (no permission needed, removed with the app) whose
     * copies are deleted after [STAGE_RETENTION_MS]; keeping one means copying or moving it.
     */
    private fun getStageDirectory(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val stage = File(base, STAGED_DIR_NAME)
        if (!stage.exists()) stage.mkdirs()
        val cutoff = System.currentTimeMillis() - STAGE_RETENTION_MS
        stage.listFiles()?.forEach { if (it.isFile && it.lastModified() < cutoff) it.delete() }
        return stage
    }

    private fun copyTargetToFile(
        context: Context,
        target: ResolvedIncomingTarget,
        destFile: File
    ): Boolean {
        val uri = target.sourceUri
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                    }
                    return true
                }
            } catch (_: Throwable) {
            }
        }
        val direct = target.directFile
        if (direct != null && direct.isFile && direct.canRead()) {
            try {
                direct.inputStream().use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                    }
                }
                return true
            } catch (_: Throwable) {
            }
        }
        return false
    }

    private fun buildFallbackName(mimeType: String?): String {
        val ext = mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        return if (!ext.isNullOrBlank()) "archivo.$ext" else "archivo"
    }

    private fun appendExtensionIfNeeded(name: String, mimeType: String?): String {
        val dot = name.lastIndexOf('.')
        if (dot > 0 && dot < name.length - 1) {
            return name
        }
        if (mimeType.isNullOrBlank() || mimeType == "*/*" || mimeType == "application/octet-stream") {
            return name
        }
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
        return if (!ext.isNullOrBlank()) "$name.$ext" else name
    }
}
