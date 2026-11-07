/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.archiver

import androidx.preference.PreferenceManager
import java8.nio.channels.SeekableByteChannel
import java8.nio.charset.StandardCharsets
import java8.nio.file.Path
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.common.DelegateForceableSeekableByteChannel
import me.zhanghai.android.files.provider.common.DelegateInputStream
import me.zhanghai.android.files.provider.common.DelegateNonForceableSeekableByteChannel
import me.zhanghai.android.files.provider.common.ForceableChannel
import me.zhanghai.android.files.provider.common.PosixFileMode
import me.zhanghai.android.files.provider.common.PosixFileType
import me.zhanghai.android.files.provider.common.newByteChannel
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.root.isRunningAsRoot
import me.zhanghai.android.files.provider.root.rootContext
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset

object ArchiveReader {
    @Throws(IOException::class)
    fun readEntries(
        file: Path,
        passwords: List<String>,
        rootPath: Path
    ): Pair<Map<Path, ReadArchive.Entry>, Map<Path, List<Path>>> {
        val entries = mutableMapOf<Path, ReadArchive.Entry>()
        val rawEntries = readEntries(file, passwords)
        for (entry in rawEntries) {
            var path = rootPath.resolve(entry.name)
            // Normalize an absolute path to prevent path traversal attack.
            if (!path.isAbsolute) {
                // TODO: Will this actually happen?
                throw AssertionError("Path must be absolute: $path")
            }
            if (path.nameCount > 0) {
                path = path.normalize()
                if (path.nameCount == 0) {
                    // Don't allow a path to become the root path only after normalization.
                    continue
                }
            } else {
                if (!entry.isDirectory) {
                    // Ignore a root path that's not a directory
                    continue
                }
            }
            entries.getOrPut(path) { entry }
        }
        entries.getOrPut(rootPath) { createDirectoryEntry("") }
        val tree = mutableMapOf<Path, MutableList<Path>>()
        tree[rootPath] = mutableListOf()
        val paths = entries.keys.toList()
        for (path in paths) {
            var path = path
            while (true) {
                val parentPath = path.parent ?: break
                val entry = entries[path]!!
                if (entry.isDirectory) {
                    tree.getOrPut(path) { mutableListOf() }
                }
                tree.getOrPut(parentPath) { mutableListOf() }.add(path)
                if (entries.containsKey(parentPath)) {
                    break
                }
                entries[parentPath] = createDirectoryEntry(parentPath.toString())
                path = parentPath
            }
        }
        return entries to tree
    }

    private fun createDirectoryEntry(name: String): ReadArchive.Entry {
        require(!name.endsWith("/")) { "name $name should not end with a slash" }
        return ReadArchive.Entry(
            name, false, null, null, null, PosixFileType.DIRECTORY, 0, null, null,
            PosixFileMode.DIRECTORY_DEFAULT, null
        )
    }

    @Throws(IOException::class)
    private fun readEntries(file: Path, passwords: List<String>): List<ReadArchive.Entry> {
        val charset = archiveFileNameCharset
        return try {
            val (archive, closeable) = openArchive(file, passwords)
            closeable.use {
                buildList {
                    while (true) {
                        this += archive.readEntry(charset) ?: break
                    }
                }
            }
        } catch (e: Exception) {
            // If reading entries failed (for example, channel mode opened, but readEntry failed
            // due to central directory seek issues, Zip64 seek incompatibility, or an appended OTA/ROM
            // signature block), fall back to pure sequential streaming mode to read the entries.
            tryReadingEntriesViaStream(file, passwords, charset, e)
        }
    }

    private fun tryReadingEntriesViaStream(
        file: Path,
        passwords: List<String>,
        charset: Charset,
        originalException: Exception
    ): List<ReadArchive.Entry> {
        return try {
            val inputStream = file.newInputStream()
            val archive = ReadArchive(inputStream, passwords)
            ArchiveCloseable(archive, inputStream).use {
                buildList {
                    while (true) {
                        this += archive.readEntry(charset) ?: break
                    }
                }
            }
        } catch (streamException: Exception) {
            originalException.addSuppressed(streamException)
            if (originalException is IOException) throw originalException
            throw IOException(originalException)
        }
    }

    @Throws(IOException::class)
    fun newInputStream(file: Path, passwords: List<String>, entry: ReadArchive.Entry): InputStream? {
        val charset = archiveFileNameCharset
        return try {
            openEntryInputStream(file, passwords, entry, charset, forceStreamMode = false)
        } catch (e: Exception) {
            openEntryInputStream(file, passwords, entry, charset, forceStreamMode = true)
        }
    }

    private fun openEntryInputStream(
        file: Path,
        passwords: List<String>,
        entry: ReadArchive.Entry,
        charset: Charset,
        forceStreamMode: Boolean
    ): InputStream? {
        val (archive, closeable) = if (forceStreamMode) {
            val inputStream = file.newInputStream()
            var successful = false
            try {
                val archive = ReadArchive(inputStream, passwords)
                successful = true
                archive to ArchiveCloseable(archive, inputStream)
            } finally {
                if (!successful) {
                    try { inputStream.close() } catch (_: Throwable) {}
                }
            }
        } else {
            openArchive(file, passwords)
        }
        var successful = false
        return try {
            while (true) {
                val currentEntry = archive.readEntry(charset) ?: break
                if (currentEntry.name != entry.name) {
                    continue
                }
                successful = true
                break
            }
            if (successful) {
                CloseableInputStream(archive.newDataInputStream(), closeable)
            } else {
                null
            }
        } finally {
            if (!successful) {
                try { closeable.close() } catch (_: Throwable) {}
            }
        }
    }

    @Throws(IOException::class)
    private fun openArchive(
        file: Path,
        passwords: List<String>
    ): Pair<ReadArchive, ArchiveCloseable> {
        // Guard against CVE-2026-14164 (RAR5 Double-Free in unpacking state reinitialization)
        try {
            val headerBytes = ByteArray(8)
            val bytesRead = file.newInputStream().use { it.read(headerBytes) }
            if (bytesRead >= 8 && ArchiveSecuritySanitizer.isRar5Header(headerBytes)) {
                if (!ArchiveSecurityPreferences.isRar5Enabled()) {
                    ArchiveSecurityPreferences.recordCve2026_14164Blocked()
                    throw me.zhanghai.android.files.provider.archive.ArchiveSecurityException(
                        file,
                        "Formato RAR5 bloqueado por protección activa contra la vulnerabilidad crítica CVE-2026-14164 (doble liberación en libarchive). Puedes activarlo bajo tu propio riesgo en Ajustes.",
                        "CVE-2026-14164"
                    )
                }

                // Even if RAR5 is explicitly enabled by user, validate the header structure and CRC32
                // to prevent CVE-2026-14164 double-free crashes in native libarchive memory.
                val validation = file.newInputStream().use { ArchiveSecuritySanitizer.validateRar5(it) }
                if (validation is ArchiveSecuritySanitizer.Rar5ValidationResult.Corrupt) {
                    ArchiveSecurityPreferences.recordCve2026_14164Blocked()
                    throw me.zhanghai.android.files.provider.archive.ArchiveSecurityException(
                        file,
                        "Archivo RAR5 bloqueado por validación de seguridad: ${validation.reason}. Prevenida vulnerabilidad crítica CVE-2026-14164 (Double-Free en memoria nativa).",
                        "CVE-2026-14164"
                    )
                }
            }
        } catch (e: me.zhanghai.android.files.provider.archive.ArchiveSecurityException) {
            throw e
        } catch (_: Throwable) {}

        val channel = try {
            CacheSizeSeekableByteChannel(file.newByteChannel())
        } catch (e: Throwable) {
            null
        }
        if (channel != null) {
            var successful = false
            try {
                val archive = ReadArchive(channel, passwords)
                successful = true
                return archive to ArchiveCloseable(archive, channel)
            } catch (e: Throwable) {
                // If opening via SeekableByteChannel fails (e.g. invalid central directory,
                // SD card seek error, or unsupported seek format), cleanly fall back to streaming InputStream.
            } finally {
                if (!successful) {
                    try { channel.close() } catch (_: Throwable) {}
                }
            }
        }
        val inputStream = file.newInputStream()
        var successful = false
        try {
            val archive = ReadArchive(inputStream, passwords)
            successful = true
            return archive to ArchiveCloseable(archive, inputStream)
        } finally {
            if (!successful) {
                try { inputStream.close() } catch (_: Throwable) {}
            }
        }
    }

    // size() may be called repeatedly for ZIP and 7Z, so make it cached to improve performance.
    private fun CacheSizeSeekableByteChannel(channel: SeekableByteChannel): SeekableByteChannel =
        if (channel is ForceableChannel) {
            CacheSizeForceableSeekableByteChannel(channel)
        } else {
            CacheSizeNonForceableSeekableByteChannel(channel)
        }

    private class CacheSizeNonForceableSeekableByteChannel(
        channel: SeekableByteChannel
    ) : DelegateNonForceableSeekableByteChannel(channel) {
        private val size: Long by lazy { super.size() }

        override fun size(): Long = size
    }

    private class CacheSizeForceableSeekableByteChannel(
        channel: SeekableByteChannel
    ) : DelegateForceableSeekableByteChannel(channel) {
        private val size: Long by lazy { super.size() }

        override fun size(): Long = size
    }

    private val archiveFileNameCharset: Charset
        get() =
            if (isRunningAsRoot) {
                try {
                    val sharedPreferences =
                        PreferenceManager.getDefaultSharedPreferences(rootContext)
                    val key = rootContext.getString(R.string.pref_key_archive_file_name_encoding)
                    val defaultValue = rootContext.getString(
                        R.string.pref_default_value_archive_file_name_encoding
                    )
                    Charset.forName(sharedPreferences.getString(key, defaultValue)!!)
                } catch (e: Exception) {
                    e.printStackTrace()
                    StandardCharsets.UTF_8
                }
            } else {
                Charset.forName(Settings.ARCHIVE_FILE_NAME_ENCODING.valueCompat)
            }

    private class ArchiveCloseable(
        private val archive: ReadArchive,
        private val closeable: Closeable
    ) : Closeable {
        override fun close() {
            @Suppress("ConvertTryFinallyToUseCall")
            try {
                archive.close()
            } finally {
                closeable.close()
            }
        }
    }

    private class CloseableInputStream(
        inputStream: InputStream,
        private val closeable: Closeable
    ) : DelegateInputStream(inputStream) {
        @Throws(IOException::class)
        override fun close() {
            super.close()

            closeable.close()
        }
    }
}
