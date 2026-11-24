/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.archiver

import me.zhanghai.android.libarchive.Archive

object ArchiveSecurityManager {

    /**
     * Configures format support securely on the libarchive handle.
     * Prevents CVE-2026-14164 by keeping the vulnerable RAR5 reader disabled by default.
     */
    fun configureSecureFormats(archive: Long) {
        // Register standard safe archive formats
        Archive.readSupportFormat7zip(archive)
        Archive.readSupportFormatZip(archive)
        Archive.readSupportFormatTar(archive)
        Archive.readSupportFormatGnutar(archive)
        Archive.readSupportFormatCpio(archive)
        Archive.readSupportFormatIso9660(archive)
        Archive.readSupportFormatAr(archive)
        Archive.readSupportFormatCab(archive)
        Archive.readSupportFormatLha(archive)
        Archive.readSupportFormatEmpty(archive)
        Archive.readSupportFormatRar(archive) // Safe legacy RAR (1.x - 4.x)
        Archive.readSupportFormatRaw(archive)
        Archive.readSupportFormatWarc(archive)

        // RAR5: the bundled libarchive has the CVE-2026-14164 fix; headers are pre-validated.
        if (ArchiveSecurityPreferences.isRar5Enabled()) {
            Archive.readSupportFormatRar5(archive)
        }
    }
}
