/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.iconRes
import java.util.Locale

object FileIconHelper {
    private val EXTENSION_ICON_MAP: Map<String, Int> = HashMap<String, Int>(96).apply {
        put("dex", R.drawable.file_dex_icon)
        put("arsc", R.drawable.file_arsc_icon)
        put("xml", R.drawable.file_xml_mt_icon)
        put("so", R.drawable.file_so_icon)
        put("elf", R.drawable.ic_exec_elf_generic)

        for (ext in arrayOf("sh", "bash", "zsh")) put(ext, R.drawable.file_sh_icon)
        for (ext in arrayOf("apk", "apks", "xapk", "apkm")) put(ext, R.drawable.file_apk_icon)
        for (ext in arrayOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz", "zst")) {
            put(ext, R.drawable.file_archive_icon)
        }
        for (ext in arrayOf("ttf", "otf", "woff", "woff2")) put(ext, R.drawable.file_font_icon)
        for (ext in arrayOf("mp3", "ogg", "flac", "wav", "m4a", "aac", "opus", "wma")) {
            put(ext, R.drawable.file_audio_icon)
        }
        for (ext in arrayOf("mp4", "mkv", "avi", "mov", "flv", "webm", "3gp", "wmv")) {
            put(ext, R.drawable.file_video_icon)
        }
        for (ext in arrayOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "svg", "ico")) {
            put(ext, R.drawable.file_image_icon)
        }
        for (ext in arrayOf("txt", "log", "ini", "conf", "properties")) {
            put(ext, R.drawable.file_text_icon)
        }
        for (ext in arrayOf(
            "java", "kt", "c", "cpp", "h", "hpp", "py", "js", "ts",
            "html", "css", "json", "yaml", "yml", "md", "smali"
        )) {
            put(ext, R.drawable.file_code_icon)
        }
        put("pdf", R.drawable.file_pdf_icon)
        for (ext in arrayOf("cer", "crt", "pem", "der", "p12", "bks", "jks")) {
            put(ext, R.drawable.file_certificate_icon)
        }
    }

    fun getIconRes(file: FileItem): Int {
        if (file.isParentDirectoryItem) {
            return R.drawable.file_parent_directory_icon
        }
        if (file.attributes.isDirectory) {
            return R.drawable.file_directory_icon
        }
        val name = file.name
        val dotIndex = name.lastIndexOf('.')
        if (dotIndex > 0 && dotIndex < name.length - 1) {
            val ext = name.substring(dotIndex + 1).lowercase(Locale.ROOT)
            EXTENSION_ICON_MAP[ext]?.let { return it }
        }
        return file.mimeType.iconRes
    }
}
