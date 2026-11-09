/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.linux

import android.os.Parcelable
import android.system.Os
import android.system.OsConstants
import java.time.Instant
import java8.nio.file.attribute.FileTime
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.provider.common.AbstractPosixFileAttributes
import me.zhanghai.android.files.provider.common.ByteString
import me.zhanghai.android.files.provider.common.FileTimeParceler
import me.zhanghai.android.files.provider.common.PosixFileMode
import me.zhanghai.android.files.provider.common.PosixFileModeBit
import me.zhanghai.android.files.provider.common.PosixFileType
import me.zhanghai.android.files.provider.common.PosixGroup
import me.zhanghai.android.files.provider.common.PosixUser
import me.zhanghai.android.files.provider.linux.syscall.StructStat
import me.zhanghai.android.files.provider.linux.syscall.Syscall

@Parcelize
internal class LinuxFileAttributes(
    override val lastModifiedTime: @WriteWith<FileTimeParceler> FileTime,
    override val lastAccessTime: @WriteWith<FileTimeParceler> FileTime,
    override val creationTime: @WriteWith<FileTimeParceler> FileTime,
    override val type: PosixFileType,
    override val size: Long,
    override val fileKey: Parcelable,
    override val owner: PosixUser?,
    override val group: PosixGroup?,
    override val mode: Set<PosixFileModeBit>?,
    override val seLinuxContext: ByteString?
) : AbstractPosixFileAttributes() {
    companion object {
        fun from(
            path: ByteString?,
            stat: StructStat,
            owner: PosixUser,
            group: PosixGroup,
            seLinuxContext: ByteString?
        ): LinuxFileAttributes {
            val lastModifiedTime =
                FileTime.from(Instant.ofEpochSecond(stat.st_mtim.tv_sec, stat.st_mtim.tv_nsec))
            val lastAccessTime =
                FileTime.from(Instant.ofEpochSecond(stat.st_atim.tv_sec, stat.st_atim.tv_nsec))
            val creationTime = lastModifiedTime
            val type = PosixFileType.fromMode(stat.st_mode)
            val size = if (path != null && (OsConstants.S_ISBLK(stat.st_mode) ||
                (OsConstants.S_ISLNK(stat.st_mode) && path.toString().startsWith("/dev/block")))
            ) {
                try {
                    val fd = Syscall.open(path, OsConstants.O_RDONLY, 0)
                    try {
                        val s = Os.lseek(fd, 0L, OsConstants.SEEK_END)
                        if (s > 0) s else stat.st_size
                    } finally {
                        Syscall.close(fd)
                    }
                } catch (e: Throwable) {
                    stat.st_size
                }
            } else {
                stat.st_size
            }
            val fileKey = LinuxFileKey(stat.st_dev, stat.st_ino)
            val mode = PosixFileMode.fromInt(stat.st_mode)
            return LinuxFileAttributes(
                lastModifiedTime, lastAccessTime, creationTime, type, size, fileKey, owner, group,
                mode, seLinuxContext
            )
        }

        fun from(
            stat: StructStat,
            owner: PosixUser,
            group: PosixGroup,
            seLinuxContext: ByteString?
        ): LinuxFileAttributes = from(null, stat, owner, group, seLinuxContext)
    }
}
