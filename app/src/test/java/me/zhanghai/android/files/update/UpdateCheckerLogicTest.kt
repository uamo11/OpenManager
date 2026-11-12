/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerLogicTest {

    @Test
    fun testVersionComparison() {
        // Newer versions
        assertTrue(UpdateChecker.isVersionNewer("1.9.51", "1.9.50"))
        assertTrue(UpdateChecker.isVersionNewer("v1.9.51", "1.9.50"))
        assertTrue(UpdateChecker.isVersionNewer("1.10.0", "1.9.50"))
        assertTrue(UpdateChecker.isVersionNewer("2.0.0", "1.9.50"))
        assertTrue(UpdateChecker.isVersionNewer("1.9.50.1", "1.9.50"))

        // Equal or older versions
        assertFalse(UpdateChecker.isVersionNewer("1.9.50", "1.9.50"))
        assertFalse(UpdateChecker.isVersionNewer("v1.9.50", "1.9.50"))
        assertFalse(UpdateChecker.isVersionNewer("1.9.49", "1.9.50"))
        assertFalse(UpdateChecker.isVersionNewer("1.8.99", "1.9.50"))
        assertFalse(UpdateChecker.isVersionNewer("1.9.50", "1.9.50.1"))
    }

    @Test
    fun testSelectAssetForDebugAndReleaseChannels() {
        val assets = listOf(
            ReleaseAsset("OpenManager-v1.9.51-release.apk", 36622438L, "https://github.com/uamo11/OpenManager/releases/download/v1.9.51/OpenManager-v1.9.51-release.apk"),
            ReleaseAsset("OpenManager-v1.9.51-debug.apk", 36879410L, "https://github.com/uamo11/OpenManager/releases/download/v1.9.51/OpenManager-v1.9.51-debug.apk")
        )

        // Debug build must pick debug APK
        val debugAsset = UpdateChecker.selectAssetForDevice(assets, isDebug = true)
        assertNotNull(debugAsset)
        assertEquals("OpenManager-v1.9.51-debug.apk", debugAsset!!.name)

        // Release build must pick release APK
        val releaseAsset = UpdateChecker.selectAssetForDevice(assets, isDebug = false)
        assertNotNull(releaseAsset)
        assertEquals("OpenManager-v1.9.51-release.apk", releaseAsset!!.name)
    }

    @Test
    fun testSelectAssetFallbackWhenOnlySingleApkPresent() {
        val assets = listOf(
            ReleaseAsset("OpenManager-v1.9.51-universal.apk", 35000000L, "https://github.com/uamo11/OpenManager/releases/download/v1.9.51/OpenManager-v1.9.51-universal.apk")
        )

        val debugAsset = UpdateChecker.selectAssetForDevice(assets, isDebug = true)
        assertNotNull(debugAsset)
        assertEquals("OpenManager-v1.9.51-universal.apk", debugAsset!!.name)

        val releaseAsset = UpdateChecker.selectAssetForDevice(assets, isDebug = false)
        assertNotNull(releaseAsset)
        assertEquals("OpenManager-v1.9.51-universal.apk", releaseAsset!!.name)
    }

    @Test
    fun testSelectAssetEmptyReturnsNull() {
        assertNull(UpdateChecker.selectAssetForDevice(emptyList(), isDebug = false))
    }

    @Test
    fun testParseReleaseJson() {
        val mockJson = """
            {
              "tag_name": "v1.9.51",
              "name": "OpenManager v1.9.51: Update System",
              "html_url": "https://github.com/uamo11/OpenManager/releases/tag/v1.9.51",
              "body": "- Added in-app updater\n- Added auto-check switch",
              "assets": [
                {
                  "name": "OpenManager-v1.9.51-debug.apk",
                  "size": 36879410,
                  "browser_download_url": "https://github.com/uamo11/OpenManager/releases/download/v1.9.51/OpenManager-v1.9.51-debug.apk"
                },
                {
                  "name": "OpenManager-v1.9.51-release.apk",
                  "size": 36622438,
                  "browser_download_url": "https://github.com/uamo11/OpenManager/releases/download/v1.9.51/OpenManager-v1.9.51-release.apk"
                }
              ]
            }
        """.trimIndent()

        // Test Release parsing
        val releaseInfo = UpdateChecker.parseReleaseJson(mockJson, isDebug = false)
        assertNotNull(releaseInfo)
        assertEquals("v1.9.51", releaseInfo!!.tagName)
        assertEquals("1.9.51", releaseInfo.version)
        assertEquals("OpenManager-v1.9.51-release.apk", releaseInfo.targetAsset.name)
        assertEquals(36622438L, releaseInfo.targetAsset.size)
        assertFalse(releaseInfo.isDebugChannel)

        // Test Debug parsing
        val debugInfo = UpdateChecker.parseReleaseJson(mockJson, isDebug = true)
        assertNotNull(debugInfo)
        assertEquals("OpenManager-v1.9.51-debug.apk", debugInfo!!.targetAsset.name)
        assertEquals(36879410L, debugInfo.targetAsset.size)
        assertTrue(debugInfo.isDebugChannel)
    }

    @Test
    fun testParseReleaseListJsonSelectsHighestVersion() {
        val mockListJson = """
            [
              {
                "tag_name": "v1.9.59",
                "name": "OpenManager v1.9.59",
                "body": "Fixed insets and latency",
                "assets": [
                  { "name": "OpenManager-v1.9.59-release.apk", "size": 30000000, "browser_download_url": "https://example.com/v1.9.59.apk" }
                ]
              },
              {
                "tag_name": "v1.9.65",
                "name": "OpenManager v1.9.65",
                "body": "Added DOCX reader",
                "assets": [
                  { "name": "OpenManager-v1.9.65-release.apk", "size": 31000000, "browser_download_url": "https://example.com/v1.9.65.apk" }
                ]
              },
              {
                "tag_name": "v1.9.60",
                "name": "OpenManager v1.9.60",
                "body": "Fixed update dialog buttons",
                "assets": [
                  { "name": "OpenManager-v1.9.60-release.apk", "size": 30500000, "browser_download_url": "https://example.com/v1.9.60.apk" }
                ]
              }
            ]
        """.trimIndent()

        val parsed = UpdateChecker.parseReleaseListJson(mockListJson, isDebug = false, currentVersion = "1.9.55")
        assertNotNull(parsed)
        // Must choose 1.9.65 as highest version regardless of order in list!
        assertEquals("1.9.65", parsed!!.version)
        assertEquals("v1.9.65", parsed.tagName)
        assertEquals("OpenManager-v1.9.65-release.apk", parsed.targetAsset.name)

        // Must show ONLY the notes of the latest release (1.9.65), never old releases!
        assertTrue(parsed.body.contains("Added DOCX reader"))
        assertFalse(parsed.body.contains("Fixed update dialog buttons"))
        assertFalse(parsed.body.contains("Fixed insets and latency"))
    }

    @Test
    fun testParseReleaseAtomFeed() {
        val mockAtom = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <id>tag:github.com,2008:Repository/1438677536/v1.9.65</id>
                <title>OpenManager v1.9.65: DOCX Reader</title>
                <link rel="alternate" type="text/html" href="https://github.com/uamo11/OpenManager/releases/tag/v1.9.65"/>
                <content type="html">&lt;h1&gt;Word Reader&lt;/h1&gt;&lt;p&gt;Added DOCX support&lt;/p&gt;</content>
              </entry>
              <entry>
                <id>tag:github.com,2008:Repository/1438677536/v1.9.64</id>
                <title>OpenManager v1.9.64: PDF Reader</title>
                <link rel="alternate" type="text/html" href="https://github.com/uamo11/OpenManager/releases/tag/v1.9.64"/>
                <content type="html">&lt;h1&gt;PDF Reader&lt;/h1&gt;&lt;p&gt;Added PDF viewer&lt;/p&gt;</content>
              </entry>
            </feed>
        """.trimIndent()

        val parsed = UpdateChecker.parseReleaseAtomFeed(mockAtom, isDebug = false, currentVersion = "1.9.60")
        assertNotNull(parsed)
        assertEquals("1.9.65", parsed!!.version)
        assertEquals("v1.9.65", parsed.tagName)
        assertEquals("OpenManager-v1.9.65-release.apk", parsed.targetAsset.name)
        assertTrue(parsed.targetAsset.downloadUrl.contains("v1.9.65/OpenManager-v1.9.65-release.apk"))
        assertTrue(parsed.body.contains("Word Reader") || parsed.body.contains("DOCX support"))
        assertFalse(parsed.body.contains("PDF Reader"))
        assertFalse(parsed.body.contains("PDF viewer"))
    }

    @Test
    fun testCleanReleaseNotesStripsOldReleasesAndMarkdownClutter() {
        val rawNotes = """
            # OpenManager v1.9.67: UI Scale and Inset Fixes

            ### 📐 Insets
            - **Navigation Bar**:
              - Automatically insets buttons above 3-button bar `navigationBars().bottom`
              - Preserves [<] [>] [+] [⇆] [↑]

            ---

            # OpenManager v1.9.66: Old Update
            - Old feature from previous release
        """.trimIndent()

        val clean = UpdateChecker.cleanReleaseNotes(rawNotes)
        // Redundant top title stripped
        assertFalse(clean.contains("# OpenManager v1.9.67"))
        // Markdown headings converted
        assertTrue(clean.contains("📐 Insets:"))
        // Bold stripped
        assertTrue(clean.contains("• Navigation Bar:"))
        // Backticks stripped
        assertTrue(clean.contains("navigationBars().bottom"))
        // [<] [>] preserved!
        assertTrue(clean.contains("[<] [>] [+] [⇆] [↑]"))
        // Sub-bullets formatted
        assertTrue(clean.contains("   ▫ Automatically insets"))
        // Old release (v1.9.66) completely excluded!
        assertFalse(clean.contains("OpenManager v1.9.66"))
        assertFalse(clean.contains("Old feature"))
    }

    @Test
    fun testCleanHtmlToMarkdown() {
        val html = "<h1>Title</h1><p>Paragraph with <strong>bold</strong> and <code>code</code></p><ul><li>Item 1</li><li>Item 2</li></ul>"
        val md = UpdateChecker.cleanHtmlToMarkdown(html)
        assertTrue(md.contains("# Title"))
        assertTrue(md.contains("**bold**"))
        assertTrue(md.contains("`code`"))
        assertTrue(md.contains("- Item 1"))
        assertTrue(md.contains("- Item 2"))
    }

    @Test
    fun testFormatSize() {
        assertEquals("0 B", UpdateChecker.formatSize(0L))
        assertEquals("1.0 KB", UpdateChecker.formatSize(1024L))
        assertEquals("1.0 MB", UpdateChecker.formatSize(1024L * 1024L))
        val formatted = UpdateChecker.formatSize(36879410L)
        assertTrue("Formatted size should be around 35.2 MB, was $formatted", formatted.contains("MB"))
    }

    @Test
    fun testAutoUpdateDefaultIsOff() {
        val defaultAutoCheckUpdates = false
        assertFalse("Auto check updates must default to false (disabled)", defaultAutoCheckUpdates)
    }
}
