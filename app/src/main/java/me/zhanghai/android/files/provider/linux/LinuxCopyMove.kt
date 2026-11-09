/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.linux

import android.system.Os
import android.system.OsConstants
import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.FileSystemException
import java8.nio.file.StandardCopyOption
import me.zhanghai.android.files.provider.common.ByteString
import me.zhanghai.android.files.provider.common.CopyOptions
import me.zhanghai.android.files.provider.common.toByteString
import me.zhanghai.android.files.provider.linux.syscall.Constants
import me.zhanghai.android.files.provider.linux.syscall.StructStat
import me.zhanghai.android.files.provider.linux.syscall.StructTimespec
import me.zhanghai.android.files.provider.linux.syscall.Syscall
import me.zhanghai.android.files.provider.linux.syscall.SyscallException
import java.io.IOException
import java.io.InterruptedIOException

internal object LinuxCopyMove {
    private const val SEND_FILE_COUNT = 8 * 1024

    private val XATTR_NAME_PREFIX_USER = "user.".toByteString()

    @Throws(IOException::class)
    fun copy(source: ByteString, target: ByteString, copyOptions: CopyOptions) {
        if (copyOptions.atomicMove) {
            throw UnsupportedOperationException(StandardCopyOption.ATOMIC_MOVE.toString())
        }
        val sourceStat = try {
            if (copyOptions.noFollowLinks) Syscall.lstat(source) else Syscall.stat(source)
        } catch (e: SyscallException) {
            throw e.toFileSystemException(source.toString())
        }
        val targetStat = try {
            Syscall.lstat(target)
        } catch (e: SyscallException) {
            if (e.errno != OsConstants.ENOENT) {
                throw e.toFileSystemException(target.toString())
            }
            // Ignored.
            null
        }
        if (targetStat != null) {
            if (sourceStat.st_dev == targetStat.st_dev && sourceStat.st_ino == targetStat.st_ino) {
                copyOptions.progressListener?.invoke(sourceStat.st_size)
                return
            }
            if (!copyOptions.replaceExisting) {
                throw FileAlreadyExistsException(source.toString(), target.toString(), null)
            }
            // Symbolic links may not be supported so we cannot simply remove the target here.
        }
        val dereferencedStat = if (OsConstants.S_ISLNK(sourceStat.st_mode)) {
            try {
                Syscall.stat(source)
            } catch (e: SyscallException) {
                null
            }
        } else {
            sourceStat
        }
        val isBlockDevice = (dereferencedStat != null && OsConstants.S_ISBLK(dereferencedStat.st_mode)) ||
            OsConstants.S_ISBLK(sourceStat.st_mode) ||
            (!OsConstants.S_ISDIR(sourceStat.st_mode) &&
                dereferencedStat?.let { OsConstants.S_ISDIR(it.st_mode) } != true &&
                source.toString().startsWith("/dev/block/"))
        if (isBlockDevice) {
            copyBlockDevice(source, target, copyOptions, targetStat)
        } else if (OsConstants.S_ISREG(sourceStat.st_mode)) {
            if (targetStat != null) {
                try {
                    Syscall.remove(target)
                } catch (e: SyscallException) {
                    if (e.errno != OsConstants.ENOENT) {
                        throw e.toFileSystemException(target.toString())
                    }
                }
            }
            val sourceFd = try {
                Syscall.open(source, OsConstants.O_RDONLY, 0)
            } catch (e: SyscallException) {
                throw e.toFileSystemException(source.toString())
            }
            try {
                var targetFlags = (OsConstants.O_WRONLY or OsConstants.O_TRUNC
                    or OsConstants.O_CREAT)
                if (!copyOptions.replaceExisting) {
                    targetFlags = targetFlags or OsConstants.O_EXCL
                }
                val targetFd = try {
                    Syscall.open(target, targetFlags, sourceStat.st_mode)
                } catch (e: SyscallException) {
                    e.maybeThrowInvalidFileNameException(target.toString())
                    throw e.toFileSystemException(target.toString())
                }
                var successful = false
                try {
                    val progressIntervalMillis = copyOptions.progressIntervalMillis
                    val progressListener = copyOptions.progressListener
                    var lastProgressMillis = System.currentTimeMillis()
                    var copiedSize = 0L
                    while (true) {
                        val sentSize = try {
                            Syscall.sendfile(targetFd, sourceFd, null, SEND_FILE_COUNT.toLong())
                        } catch (e: SyscallException) {
                            throw e.toFileSystemException(source.toString(), target.toString())
                        }
                        if (sentSize == 0L) {
                            break
                        }
                        copiedSize += sentSize
                        throwIfInterrupted()
                        val currentTimeMillis = System.currentTimeMillis()
                        if (progressListener != null
                            && currentTimeMillis >= lastProgressMillis + progressIntervalMillis) {
                            progressListener(copiedSize)
                            lastProgressMillis = currentTimeMillis
                            copiedSize = 0
                        }
                    }
                    progressListener?.invoke(copiedSize)
                    successful = true
                } finally {
                    try {
                        Syscall.close(targetFd)
                    } catch (e: SyscallException) {
                        throw e.toFileSystemException(target.toString())
                    } finally {
                        if (!successful) {
                            try {
                                Syscall.remove(target)
                            } catch (e: SyscallException) {
                                e.printStackTrace()
                            }
                        }
                    }
                }
            } finally {
                try {
                    Syscall.close(sourceFd)
                } catch (e: SyscallException) {
                    throw e.toFileSystemException(source.toString())
                }
            }
        } else if (OsConstants.S_ISDIR(sourceStat.st_mode)) {
            if (targetStat != null) {
                try {
                    Syscall.remove(target)
                } catch (e: SyscallException) {
                    if (e.errno != OsConstants.ENOENT) {
                        throw e.toFileSystemException(target.toString())
                    }
                }
            }
            try {
                Syscall.mkdir(target, sourceStat.st_mode)
            } catch (e: SyscallException) {
                e.maybeThrowInvalidFileNameException(target.toString())
                throw e.toFileSystemException(target.toString())
            }
            copyOptions.progressListener?.invoke(sourceStat.st_size)
        } else if (OsConstants.S_ISLNK(sourceStat.st_mode)) {
            val sourceTarget = try {
                Syscall.readlink(source)
            } catch (e: SyscallException) {
                throw e.toFileSystemException(source.toString())
            }
            var symlinkSuccess = false
            try {
                Syscall.symlink(sourceTarget, target)
                symlinkSuccess = true
            } catch (e: SyscallException) {
                if (e.errno == OsConstants.EEXIST && copyOptions.replaceExisting) {
                    try {
                        Syscall.remove(target)
                    } catch (e2: SyscallException) {
                        if (e2.errno != OsConstants.ENOENT) {
                            e2.addSuppressed(e.toFileSystemException(target.toString()))
                            throw e2.toFileSystemException(target.toString())
                        }
                    }
                    try {
                        Syscall.symlink(sourceTarget, target)
                        symlinkSuccess = true
                    } catch (e2: SyscallException) {
                        if (e2.errno != OsConstants.EPERM && e2.errno != OsConstants.ENOTSUP) {
                            e2.addSuppressed(e.toFileSystemException(target.toString()))
                            throw e2.toFileSystemException(target.toString())
                        }
                    }
                } else if (e.errno != OsConstants.EPERM && e.errno != OsConstants.ENOTSUP) {
                    e.maybeThrowInvalidFileNameException(target.toString())
                    throw e.toFileSystemException(target.toString())
                }
            }
            if (!symlinkSuccess) {
                // Target filesystem does not support symbolic links (e.g. FUSE/sdcardfs on Android).
                // Fallback: dereference and copy target content directly.
                val derefOptions = CopyOptions(
                    copyOptions.replaceExisting, false, false,
                    copyOptions.copyAttributes, copyOptions.progressIntervalMillis,
                    copyOptions.progressListener
                )
                copy(source, target, derefOptions)
                return
            }
            copyOptions.progressListener?.invoke(sourceStat.st_size)
        } else {
            throw FileSystemException(source.toString(), null, "st_mode ${sourceStat.st_mode}")
        }
        // Attributes copying (skipped for block devices which are written as regular files)
        if (!isBlockDevice) {
            try {
                if (copyOptions.copyAttributes) {
                    Syscall.lchown(target, sourceStat.st_uid, sourceStat.st_gid)
                }
            } catch (e: SyscallException) {
                e.printStackTrace()
            }
            try {
                if (!OsConstants.S_ISLNK(sourceStat.st_mode)) {
                    Syscall.chmod(target, sourceStat.st_mode)
                }
            } catch (e: SyscallException) {
                e.printStackTrace()
            }
            try {
                val times = arrayOf(
                    if (copyOptions.copyAttributes) {
                        sourceStat.st_atim
                    } else {
                        StructTimespec(0, Constants.UTIME_OMIT)
                    }, sourceStat.st_mtim
                )
                Syscall.lutimens(target, times)
            } catch (e: SyscallException) {
                e.printStackTrace()
            }
            try {
                val xattrNames = Syscall.llistxattr(source)
                for (xattrName in xattrNames) {
                    if (!(copyOptions.copyAttributes || xattrName.startsWith(XATTR_NAME_PREFIX_USER))) {
                        continue
                    }
                    val xattrValue = Syscall.lgetxattr(target, xattrName)
                    Syscall.lsetxattr(target, xattrName, xattrValue, 0)
                }
            } catch (e: SyscallException) {
                e.printStackTrace()
            }
        }
    }

    @Throws(IOException::class)
    private fun copyBlockDevice(
        source: ByteString,
        target: ByteString,
        copyOptions: CopyOptions,
        targetStat: StructStat?
    ) {
        if (targetStat != null) {
            if (!copyOptions.replaceExisting) {
                throw FileAlreadyExistsException(source.toString(), target.toString(), null)
            }
            try {
                Syscall.remove(target)
            } catch (e: SyscallException) {
                if (e.errno != OsConstants.ENOENT) {
                    throw e.toFileSystemException(target.toString())
                }
            }
        }
        val sourceFd = try {
            Syscall.open(source, OsConstants.O_RDONLY, 0)
        } catch (e: SyscallException) {
            throw e.toFileSystemException(source.toString())
        }
        try {
            var targetFlags = (OsConstants.O_WRONLY or OsConstants.O_TRUNC or OsConstants.O_CREAT)
            if (!copyOptions.replaceExisting) {
                targetFlags = targetFlags or OsConstants.O_EXCL
            }
            val targetMode = OsConstants.S_IRUSR or OsConstants.S_IWUSR or OsConstants.S_IRGRP or OsConstants.S_IWGRP or OsConstants.S_IROTH
            val targetFd = try {
                Syscall.open(target, targetFlags, targetMode)
            } catch (e: SyscallException) {
                e.maybeThrowInvalidFileNameException(target.toString())
                throw e.toFileSystemException(target.toString())
            }
            var successful = false
            try {
                val partitionSize = try {
                    val endOffset = Os.lseek(sourceFd, 0L, OsConstants.SEEK_END)
                    Os.lseek(sourceFd, 0L, OsConstants.SEEK_SET)
                    if (endOffset > 0) endOffset else 0L
                } catch (_: Throwable) {
                    0L
                }

                val progressIntervalMillis = copyOptions.progressIntervalMillis
                val progressListener = copyOptions.progressListener
                var lastProgressMillis = System.currentTimeMillis()
                var intervalCopiedSize = 0L
                var totalCopiedSize = 0L

                var useSendfile = true
                val bufferSize = 256 * 1024
                var buffer: ByteArray? = null

                while (true) {
                    var sentSize = 0L
                    if (useSendfile) {
                        val count = if (partitionSize > 0) {
                            val remaining = partitionSize - totalCopiedSize
                            if (remaining <= 0) break
                            minOf(remaining, 1024 * 1024L)
                        } else {
                            1024 * 1024L
                        }
                        try {
                            sentSize = Syscall.sendfile(targetFd, sourceFd, null, count)
                        } catch (e: SyscallException) {
                            if (e.errno == OsConstants.EINVAL || e.errno == OsConstants.ENOSYS || e.errno == OsConstants.EXDEV) {
                                useSendfile = false
                            } else {
                                throw e.toFileSystemException(source.toString(), target.toString())
                            }
                        }
                    }
                    if (!useSendfile) {
                        if (buffer == null) {
                            buffer = ByteArray(bufferSize)
                        }
                        val toRead = if (partitionSize > 0) {
                            val remaining = partitionSize - totalCopiedSize
                            if (remaining <= 0) break
                            minOf(remaining, bufferSize.toLong()).toInt()
                        } else {
                            bufferSize
                        }
                        val readBytes = try {
                            Syscall.read(sourceFd, buffer, 0, toRead)
                        } catch (e: SyscallException) {
                            throw e.toFileSystemException(source.toString())
                        }
                        if (readBytes <= 0) {
                            break
                        }
                        try {
                            Syscall.write(targetFd, buffer, 0, readBytes)
                        } catch (e: SyscallException) {
                            throw e.toFileSystemException(target.toString())
                        }
                        sentSize = readBytes.toLong()
                    } else if (sentSize == 0L) {
                        break
                    }

                    intervalCopiedSize += sentSize
                    totalCopiedSize += sentSize
                    throwIfInterrupted()
                    val currentTimeMillis = System.currentTimeMillis()
                    if (progressListener != null
                        && currentTimeMillis >= lastProgressMillis + progressIntervalMillis) {
                        progressListener(intervalCopiedSize)
                        lastProgressMillis = currentTimeMillis
                        intervalCopiedSize = 0
                    }
                    if (partitionSize > 0 && totalCopiedSize >= partitionSize) {
                        break
                    }
                }
                progressListener?.invoke(intervalCopiedSize)
                successful = true
            } finally {
                try {
                    Syscall.close(targetFd)
                } catch (e: SyscallException) {
                    throw e.toFileSystemException(target.toString())
                } finally {
                    if (!successful) {
                        try {
                            Syscall.remove(target)
                        } catch (e: SyscallException) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        } finally {
            try {
                Syscall.close(sourceFd)
            } catch (e: SyscallException) {
                throw e.toFileSystemException(source.toString())
            }
        }
    }

    @Throws(InterruptedIOException::class)
    private fun throwIfInterrupted() {
        if (Thread.interrupted()) {
            throw InterruptedIOException()
        }
    }

    @Throws(IOException::class)
    fun move(source: ByteString, target: ByteString, copyOptions: CopyOptions) {
        val sourceStat = try {
            Syscall.lstat(source)
        } catch (e: SyscallException) {
            throw e.toFileSystemException(source.toString())
        }
        val targetStat = try {
            Syscall.lstat(target)
        } catch (e: SyscallException) {
            if (e.errno != OsConstants.ENOENT) {
                throw e.toFileSystemException(target.toString())
            }
            // Ignored.
            null
        }
        if (targetStat != null) {
            if (sourceStat.st_dev == targetStat.st_dev && sourceStat.st_ino == targetStat.st_ino) {
                copyOptions.progressListener?.invoke(sourceStat.st_size)
                return
            }
            if (!copyOptions.replaceExisting) {
                throw FileAlreadyExistsException(source.toString(), target.toString(), null)
            }
            try {
                Syscall.remove(target)
            } catch (e: SyscallException) {
                throw e.toFileSystemException(target.toString())
            }
        }
        var renameSuccessful = false
        try {
            Syscall.rename(source, target)
            renameSuccessful = true
        } catch (e: SyscallException) {
            if (copyOptions.atomicMove) {
                e.maybeThrowAtomicMoveNotSupportedException(source.toString(), target.toString())
                e.maybeThrowInvalidFileNameException(target.toString())
                throw e.toFileSystemException(source.toString(), target.toString())
            }
            // Ignored.
        }
        if (renameSuccessful) {
            copyOptions.progressListener?.invoke(sourceStat.st_size)
            return
        }
        if (copyOptions.atomicMove) {
            throw AssertionError()
        }
        var copyOptions = copyOptions
        if (!copyOptions.copyAttributes || !copyOptions.noFollowLinks) {
            copyOptions = CopyOptions(
                copyOptions.replaceExisting, true, false, true, copyOptions.progressIntervalMillis,
                copyOptions.progressListener
            )
        }
        copy(source, target, copyOptions)
        try {
            Syscall.remove(source)
        } catch (e: SyscallException) {
            if (e.errno != OsConstants.ENOENT) {
                try {
                    Syscall.remove(target)
                } catch (e2: SyscallException) {
                    e.addSuppressed(e2.toFileSystemException(target.toString()))
                }
            }
            throw e.toFileSystemException(source.toString())
        }
    }
}
