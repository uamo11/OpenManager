/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive

import java8.nio.file.FileSystemException
import java8.nio.file.Path

class ArchiveSecurityException(
    file: Path,
    message: String,
    val cveIdentifier: String
) : FileSystemException(file.toString(), null, "[$cveIdentifier] $message")
