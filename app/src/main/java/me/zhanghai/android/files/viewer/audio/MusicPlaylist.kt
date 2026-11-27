/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.audio

import java.io.File
import java.util.Locale

/**
 * The songs the player goes through: the audio files in the folder of the one opened, in natural
 * name order ("2 x" before "10 x"), as MT's player does (user request).
 */
object MusicPlaylist {

    /** What ExoPlayer plays without extra decoders. */
    val AUDIO_EXTENSIONS = setOf(
        "mp3", "m4a", "m4b", "aac", "flac", "ogg", "oga", "opus", "wav", "amr", "awb", "mka", "weba", "3ga"
    )

    /** A folder with more songs than this is cut to the ones around the opened file. */
    const val MAX_ITEMS = 2000

    fun isAudio(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT) in AUDIO_EXTENSIONS

    /** The audio files among [names] in natural order, and the index of [current] (always included). */
    fun order(names: Collection<String>, current: String): Pair<List<String>, Int> {
        val sorted = (names.filter { isAudio(it) } + current).distinct().sortedWith(NATURAL)
        var index = sorted.indexOf(current)
        if (sorted.size <= MAX_ITEMS) return sorted to index
        val from = (index - MAX_ITEMS / 2).coerceIn(0, sorted.size - MAX_ITEMS)
        val window = sorted.subList(from, from + MAX_ITEMS)
        index -= from
        return window to index
    }

    /** The songs next to [file] on the local disk. */
    fun around(file: File): Pair<List<File>, Int> {
        val dir = file.parentFile ?: return listOf(file) to 0
        val names = runCatching { dir.list()?.toList() }.getOrNull() ?: emptyList()
        val (ordered, index) = order(names, file.name)
        return ordered.map { File(dir, it) } to index
    }

    /** Case-insensitive, with runs of digits compared as numbers. */
    val NATURAL: Comparator<String> = Comparator { a, b ->
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ei = i
                while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j
                while (ej < b.length && b[ej].isDigit()) ej++
                val na = a.substring(i, ei).trimStart('0')
                val nb = b.substring(j, ej).trimStart('0')
                if (na.length != nb.length) return@Comparator na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return@Comparator c
                i = ei
                j = ej
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return@Comparator c
                i++
                j++
            }
        }
        val rest = (a.length - i) - (b.length - j)
        if (rest != 0) rest else a.compareTo(b)
    }
}
