/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicPlaylistTest {

    @Test
    fun theFolderInNaturalOrderWithOnlyAudio() {
        val names = listOf("track10.mp3", "cover.jpg", "Track2.flac", "track1.ogg", "notes.txt", "b.WAV")
        val (list, index) = MusicPlaylist.order(names, "Track2.flac")
        assertEquals(listOf("b.WAV", "track1.ogg", "Track2.flac", "track10.mp3"), list)
        assertEquals(2, index)
    }

    @Test
    fun theOpenedFileIsAlwaysIn() {
        // An extension the list does not know (opened with "open as audio") still plays.
        val (list, index) = MusicPlaylist.order(listOf("a.mp3", "voice.xyz"), "voice.xyz")
        assertEquals(listOf("a.mp3", "voice.xyz"), list)
        assertEquals(1, index)
    }

    @Test
    fun aHugeFolderKeepsTheSongsAroundTheOpenedOne() {
        val names = (1..5000).map { "s$it.mp3" }
        val (list, index) = MusicPlaylist.order(names, "s4000.mp3")
        assertEquals(MusicPlaylist.MAX_ITEMS, list.size)
        assertEquals("s4000.mp3", list[index])
        val (first, firstIndex) = MusicPlaylist.order(names, "s1.mp3")
        assertEquals(0, firstIndex)
        assertEquals("s1.mp3", first[0])
    }

    @Test
    fun naturalOrder() {
        val sorted = listOf("10 b", "9 a", "009 c", "a", "A2", "a10", "a1").sortedWith(MusicPlaylist.NATURAL)
        assertEquals(listOf("9 a", "009 c", "10 b", "a", "a1", "A2", "a10"), sorted)
    }

    @Test
    fun audioExtensions() {
        assertTrue(MusicPlaylist.isAudio("x.MP3"))
        assertTrue(MusicPlaylist.isAudio("voice.opus"))
        assertFalse(MusicPlaylist.isAudio("video.mp4"))
        assertFalse(MusicPlaylist.isAudio("noext"))
    }
}
