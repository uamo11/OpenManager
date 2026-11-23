/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import java.util.Locale

enum class SmartOpenKind {
    DIRECTORY,
    PDF,
    VIDEO,
    IMAGE,
    AUDIO,
    TEXT,
    DOCUMENT,
    /** Archives, APKs and anything else: shown in the explorer, which has the tools for them. */
    FILE_LOCATION
}

object SmartFileOpenEngine {
    private val DIRECTORY_MIME_TYPES = setOf(
        "inode/directory",
        "resource/folder",
        "vnd.android.document/directory"
    )

    private val PDF_MIME_TYPES = setOf(
        "application/pdf",
        "application/x-pdf"
    )

    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "mkv", "webm", "avi", "mov", "flv", "wmv",
        "3gp", "3gpp", "m4v", "mpeg", "mpg", "ogv", "m2ts", "mts", "vob"
    )

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif", "ico")
    private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "amr", "mid", "midi", "wma")
    private val DOCUMENT_EXTENSIONS = setOf("docx", "doc")
    private val DOCUMENT_MIME_TYPES = setOf(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/msword", "application/x-msword", "application/vnd.ms-word"
    )
    private val TEXT_MIME_TYPES = setOf(
        "application/ecmascript", "application/javascript", "application/json", "application/typescript",
        "application/x-sh", "application/x-shellscript", "application/xml", "application/yaml"
    )
    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "log", "csv", "json", "xml", "yaml", "yml", "ini", "conf", "cfg", "properties",
        "sh", "bash", "py", "js", "ts", "kt", "kts", "java", "c", "h", "cpp", "hpp", "go", "rs", "rb",
        "php", "html", "htm", "css", "sql", "smali", "gradle", "toml", "srt", "vtt"
    )

    private val MULTI_SLASH_REGEX = Regex("/+")
    private val MNT_EMULATED_REGEX = Regex("^/mnt/(?:user|pass_through|androidwritable|installer)/\\d+/emulated/(\\d+)(/.*)?$")
    private val MNT_RUNTIME_EMULATED_REGEX = Regex("^/mnt/runtime/(?:default|read|write|full)/emulated/(\\d+)(/.*)?$")
    private val DATA_MEDIA_REGEX = Regex("^/data/media/(\\d+)(/.*)?$")
    private val SDCARD_PREFIX_REGEX = Regex("^/(?:sdcard|mnt/sdcard|storage/self/primary)(/.*)?$")
    private val MNT_MEDIA_RW_REGEX = Regex("^/mnt/media_rw/([^/]+)(/.*)?$")
    private val MNT_RUNTIME_VOLUME_REGEX = Regex("^/mnt/runtime/(?:default|read|write|full)/([^/]+)(/.*)?$")

    private val FILE_PROVIDER_ROOT_ALIASES = setOf(
        "external_files",
        "external-path",
        "external",
        "files_path",
        "shared",
        "root",
        "root-path",
        "media",
        "downloads"
    )

    fun hasPdfMagicHeader(headerBytes: ByteArray?): Boolean {
        if (headerBytes == null || headerBytes.size < 5) {
            return false
        }
        val maxStart = minOf(headerBytes.size - 5, 12)
        for (i in 0..maxStart) {
            if (headerBytes[i] == 0x25.toByte() && // %
                headerBytes[i + 1] == 0x50.toByte() && // P
                headerBytes[i + 2] == 0x44.toByte() && // D
                headerBytes[i + 3] == 0x46.toByte() && // F
                headerBytes[i + 4] == 0x2D.toByte() // -
            ) {
                return true
            }
        }
        return false
    }

    fun classifySmartOpen(
        mimeType: String?,
        displayName: String?,
        headerBytes: ByteArray? = null,
        isDirectory: Boolean = false
    ): SmartOpenKind {
        val normalizedMime = mimeType?.trim()?.substringBefore(';')?.lowercase(Locale.ROOT)
        if (isDirectory || (normalizedMime != null && normalizedMime in DIRECTORY_MIME_TYPES)) {
            return SmartOpenKind.DIRECTORY
        }
        val trimmedName = displayName?.trim()
        if ((normalizedMime != null && normalizedMime in PDF_MIME_TYPES) ||
            trimmedName?.endsWith(".pdf", ignoreCase = true) == true ||
            hasPdfMagicHeader(headerBytes)
        ) {
            return SmartOpenKind.PDF
        }
        val ext = trimmedName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)
        if ((normalizedMime != null && normalizedMime.startsWith("video/")) ||
            (!ext.isNullOrEmpty() && ext != trimmedName.lowercase(Locale.ROOT) && ext in VIDEO_EXTENSIONS)
        ) {
            return SmartOpenKind.VIDEO
        }
        val hasExt = !ext.isNullOrEmpty() && ext != trimmedName?.lowercase(Locale.ROOT)
        val mime = normalizedMime.orEmpty()
        return when {
            mime.startsWith("image/") || (hasExt && ext in IMAGE_EXTENSIONS) -> SmartOpenKind.IMAGE
            mime.startsWith("audio/") || (hasExt && ext in AUDIO_EXTENSIONS) -> SmartOpenKind.AUDIO
            mime in DOCUMENT_MIME_TYPES || (hasExt && ext in DOCUMENT_EXTENSIONS) -> SmartOpenKind.DOCUMENT
            mime.startsWith("text/") || mime in TEXT_MIME_TYPES || (hasExt && ext in TEXT_EXTENSIONS) ->
                SmartOpenKind.TEXT
            else -> SmartOpenKind.FILE_LOCATION
        }
    }

    fun normalizeKernelOrProviderPath(rawPath: String?): String? {
        if (rawPath.isNullOrBlank()) {
            return null
        }
        val trimmed = rawPath.trim()
        if (trimmed.endsWith(" (deleted)")) {
            return null
        }
        var path = MULTI_SLASH_REGEX.replace(trimmed, "/")

        MNT_EMULATED_REGEX.matchEntire(path)?.let { match ->
            path = "/storage/emulated/${match.groupValues[1]}${match.groupValues[2]}"
        } ?: MNT_RUNTIME_EMULATED_REGEX.matchEntire(path)?.let { match ->
            path = "/storage/emulated/${match.groupValues[1]}${match.groupValues[2]}"
        } ?: DATA_MEDIA_REGEX.matchEntire(path)?.let { match ->
            path = "/storage/emulated/${match.groupValues[1]}${match.groupValues[2]}"
        } ?: SDCARD_PREFIX_REGEX.matchEntire(path)?.let { match ->
            path = "/storage/emulated/0${match.groupValues[1]}"
        } ?: MNT_MEDIA_RW_REGEX.matchEntire(path)?.let { match ->
            path = "/storage/${match.groupValues[1]}${match.groupValues[2]}"
        } ?: MNT_RUNTIME_VOLUME_REGEX.matchEntire(path)?.let { match ->
            val vol = match.groupValues[1]
            if (vol != "emulated" && vol != "self") {
                path = "/storage/$vol${match.groupValues[2]}"
            }
        }

        if (!path.startsWith("/")) {
            return null
        }
        return if (path.length > 1) path.trimEnd('/') else path
    }

    fun decodeExternalStorageDocumentId(docId: String?): String? {
        if (docId.isNullOrBlank()) {
            return null
        }
        val trimmed = docId.trim()
        if (trimmed.startsWith("raw:")) {
            return normalizeKernelOrProviderPath(trimmed.removePrefix("raw:"))
        }
        val colonIdx = trimmed.indexOf(':')
        if (colonIdx < 0) {
            return when (trimmed) {
                "primary" -> "/storage/emulated/0"
                "home" -> "/storage/emulated/0/Documents"
                else -> "/storage/$trimmed"
            }
        }
        val volId = trimmed.substring(0, colonIdx)
        val rel = trimmed.substring(colonIdx + 1).trimStart('/').trimEnd('/')
        val base = when (volId) {
            "primary" -> "/storage/emulated/0"
            "home" -> "/storage/emulated/0/Documents"
            "" -> return null
            else -> "/storage/$volId"
        }
        return if (rel.isEmpty()) base else "$base/$rel"
    }

    fun decodeDownloadsDocumentId(docId: String?): String? {
        if (docId.isNullOrBlank()) {
            return null
        }
        val trimmed = docId.trim()
        return when {
            trimmed.startsWith("raw:") -> normalizeKernelOrProviderPath(trimmed.removePrefix("raw:"))
            trimmed.startsWith("/storage/") || trimmed.startsWith("/sdcard/") ->
                normalizeKernelOrProviderPath(trimmed)
            else -> null
        }
    }

    fun extractEmbeddedStorageCandidates(uriPath: String?, displayName: String?): List<String> {
        val candidates = LinkedHashSet<String>()
        if (!uriPath.isNullOrBlank()) {
            val clean = MULTI_SLASH_REGEX.replace(uriPath.trim(), "/")
            val markers = listOf("/storage/", "/sdcard/", "/data/media/")
            for (marker in markers) {
                val idx = clean.indexOf(marker)
                if (idx >= 0) {
                    normalizeKernelOrProviderPath(clean.substring(idx))?.let { candidates.add(it) }
                }
            }
            val androidIdx = clean.indexOf("/Android/")
            if (androidIdx >= 0) {
                normalizeKernelOrProviderPath("/storage/emulated/0${clean.substring(androidIdx)}")
                    ?.let { candidates.add(it) }
            }

            val segments = clean.trimStart('/').split('/').filter { it.isNotEmpty() }
            if (segments.size > 1) {
                val firstLower = segments[0].lowercase(Locale.ROOT)
                if (firstLower in FILE_PROVIDER_ROOT_ALIASES) {
                    val rest = segments.drop(1).joinToString("/")
                    if (firstLower == "root" || firstLower == "root-path") {
                        normalizeKernelOrProviderPath("/$rest")?.let { candidates.add(it) }
                    }
                    if (firstLower == "downloads") {
                        normalizeKernelOrProviderPath("/storage/emulated/0/Download/$rest")
                            ?.let { candidates.add(it) }
                    }
                    normalizeKernelOrProviderPath("/storage/emulated/0/$rest")
                        ?.let { candidates.add(it) }
                }
            }
        }
        val cleanName = displayName?.takeIf { it.isNotBlank() }?.let { sanitizeDisplayName(it, "") }
        if (!cleanName.isNullOrEmpty()) {
            candidates.add("/storage/emulated/0/Download/$cleanName")
        }
        return candidates.toList()
    }

    fun sanitizeDisplayName(rawName: String?, fallbackName: String = "shared_file"): String {
        if (rawName.isNullOrBlank()) {
            return fallbackName
        }
        val base = rawName.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = buildString(base.length) {
            for (ch in base) {
                if (ch >= ' ' && ch != '\u007F') {
                    append(ch)
                }
            }
        }.trim()
        if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") {
            return fallbackName
        }
        return cleaned
    }

    fun resolveUniqueStageFileName(
        existingNamesWithSizes: Map<String, Long>,
        desiredName: String,
        sourceSize: Long
    ): Pair<String, Boolean> {
        val sanitized = sanitizeDisplayName(desiredName)
        val existingSize = existingNamesWithSizes[sanitized]
        if (existingSize == null) {
            return sanitized to false
        }
        if (sourceSize > 0L && existingSize == sourceSize) {
            return sanitized to true
        }
        val dotIndex = sanitized.lastIndexOf('.')
        val (stem, ext) = if (dotIndex > 0) {
            sanitized.substring(0, dotIndex) to sanitized.substring(dotIndex)
        } else {
            sanitized to ""
        }
        for (i in 1..999) {
            val candidate = "$stem ($i)$ext"
            val candidateSize = existingNamesWithSizes[candidate]
            if (candidateSize == null) {
                return candidate to false
            }
            if (sourceSize > 0L && candidateSize == sourceSize) {
                return candidate to true
            }
        }
        return "${System.currentTimeMillis()}_$sanitized" to false
    }
}
