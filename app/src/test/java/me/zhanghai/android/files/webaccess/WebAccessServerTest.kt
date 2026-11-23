/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.webaccess

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.file.Files

class WebAccessServerTest {

    private lateinit var root: File
    private lateinit var outside: File
    private lateinit var server: WebAccessServer
    private val code = "ABCD2345"

    @Before
    fun setUp() {
        val base = Files.createTempDirectory("omweb").toFile()
        root = File(base, "storage").apply { mkdirs() }
        outside = File(base, "secret.txt").apply { writeText("private") }
        File(root, "DCIM").mkdirs()
        File(root, "DCIM/photo.jpg").writeBytes(ByteArray(1000) { it.toByte() })
        File(root, "notes.txt").writeText("hola")
        File(root, "page.html").writeText("<script>alert(1)</script>")
        server = WebAccessServer(root, code, "<html>INDEX</html>", "Test phone")
        server.start(0..0)
    }

    @After
    fun tearDown() {
        server.stop()
        root.parentFile!!.deleteRecursively()
    }

    private class Reply(val status: Int, val body: String, val headers: Map<String, String>)

    private fun call(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        key: String? = code,
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap()
    ): Reply {
        val q = query.entries.joinToString("&") { "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val conn = URL("http://127.0.0.1:${server.port}$path${if (q.isEmpty()) "" else "?$q"}").openConnection() as HttpURLConnection
        conn.requestMethod = method
        key?.let { conn.setRequestProperty("X-OM-Key", it) }
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (body != null) {
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
        }
        val status = conn.responseCode
        val stream = if (status >= 400) conn.errorStream else conn.inputStream
        val text = stream?.use { it.readBytes().toString(Charsets.ISO_8859_1) }.orEmpty()
        val h = conn.headerFields.filterKeys { it != null }.mapValues { it.value.joinToString(",") }
        return Reply(status, text, h)
    }

    @Test
    fun everyRequestNeedsTheCode() {
        assertEquals(401, call("GET", "/api/ls", mapOf("p" to "/"), key = null).status)
        assertEquals(401, call("GET", "/api/ls", mapOf("p" to "/"), key = "WRONG").status)
        // Without the code the page is the login form, not the file manager.
        val login = call("GET", "/", key = null)
        assertEquals(200, login.status)
        assertFalse(login.body.contains("INDEX"))
        assertTrue(call("GET", "/", query = mapOf("k" to code.lowercase()), key = null).body.contains("INDEX"))
        assertEquals(200, call("GET", "/api/ls", mapOf("p" to "/")).status)
    }

    @Test
    fun tooManyWrongCodesLockTheServer() {
        var locked = false
        server.onLocked = { locked = true }
        repeat(WebAccessServer.MAX_FAILED_CODES) { call("GET", "/api/info", key = "BAD$it") }
        assertTrue(server.isLocked)
        assertTrue(locked)
        // Even the right code is refused now: guessing cannot continue.
        assertEquals(423, call("GET", "/api/info").status)
    }

    @Test
    fun listsFoldersFirst() {
        val reply = call("GET", "/api/ls", mapOf("p" to "/"))
        assertEquals(200, reply.status)
        assertTrue(reply.body.indexOf("\"DCIM\"") < reply.body.indexOf("\"notes.txt\""))
        assertTrue(reply.body.contains("\"path\":\"/\""))
        assertTrue(call("GET", "/api/ls", mapOf("p" to "/DCIM")).body.contains("\"photo.jpg\",\"d\":false,\"s\":1000"))
    }

    @Test
    fun pathsCannotLeaveTheStorage() {
        assertEquals(403, call("GET", "/api/dl", mapOf("p" to "/../secret.txt")).status)
        assertEquals(403, call("GET", "/api/ls", mapOf("p" to "../")).status)
        assertEquals(403, call("POST", "/api/rm", mapOf("p" to "/")).status)
        assertEquals(400, call("POST", "/api/mkdir", mapOf("p" to "/", "n" to "../evil")).status)
        assertTrue(outside.exists())
    }

    @Test
    fun downloadsWithRanges() {
        val full = call("GET", "/api/dl", mapOf("p" to "/DCIM/photo.jpg"))
        assertEquals(200, full.status)
        assertEquals(1000, full.body.length)
        assertTrue(full.headers["Content-Disposition"]!!.startsWith("attachment"))
        val part = call("GET", "/api/dl", mapOf("p" to "/DCIM/photo.jpg"), headers = mapOf("Range" to "bytes=10-19"))
        assertEquals(206, part.status)
        assertEquals("bytes 10-19/1000", part.headers["Content-Range"])
        assertEquals((10..19).map { it.toChar() }.joinToString(""), part.body)
        val tail = call("GET", "/api/dl", mapOf("p" to "/DCIM/photo.jpg"), headers = mapOf("Range" to "bytes=-5"))
        assertEquals("bytes 995-999/1000", tail.headers["Content-Range"])
        assertEquals(416, call("GET", "/api/dl", mapOf("p" to "/notes.txt"), headers = mapOf("Range" to "bytes=50-")).status)
    }

    @Test
    fun uploadedPagesAreNeverShownInline() {
        val html = call("GET", "/api/dl", mapOf("p" to "/page.html", "inline" to "1"))
        assertEquals("application/octet-stream", html.headers["Content-Type"])
        assertTrue(html.headers["Content-Disposition"]!!.startsWith("attachment"))
        val photo = call("GET", "/api/dl", mapOf("p" to "/DCIM/photo.jpg", "inline" to "1"))
        assertEquals("image/jpeg", photo.headers["Content-Type"])
        assertFalse(WebAccessServer.isSafeInline("image/svg+xml"))
        assertEquals("nosniff", photo.headers["X-Content-Type-Options"])
    }

    @Test
    fun uploadMkdirRenameDelete() {
        val data = "uploaded from a PC".toByteArray()
        assertEquals(200, call("PUT", "/api/up", mapOf("p" to "/DCIM", "n" to "new file.txt"), body = data).status)
        assertEquals("uploaded from a PC", File(root, "DCIM/new file.txt").readText())
        assertFalse(File(root, "DCIM/.new file.txt.omupload").exists())
        // No silent overwrite; explicit overwrite works.
        assertEquals(409, call("PUT", "/api/up", mapOf("p" to "/DCIM", "n" to "new file.txt"), body = data).status)
        assertEquals(200, call("PUT", "/api/up", mapOf("p" to "/DCIM", "n" to "new file.txt", "o" to "1"), body = "v2".toByteArray()).status)
        assertEquals("v2", File(root, "DCIM/new file.txt").readText())

        assertEquals(200, call("POST", "/api/mkdir", mapOf("p" to "/", "n" to "Música")).status)
        assertTrue(File(root, "Música").isDirectory)
        assertEquals(200, call("POST", "/api/mv", mapOf("p" to "/notes.txt", "n" to "renamed.txt")).status)
        assertTrue(File(root, "renamed.txt").exists())
        assertEquals(409, call("POST", "/api/mv", mapOf("p" to "/renamed.txt", "n" to "DCIM")).status)
        assertEquals(200, call("POST", "/api/rm", mapOf("p" to "/DCIM")).status)
        assertFalse(File(root, "DCIM").exists())
    }

    @Test
    fun deletingASymlinkKeepsItsTarget() {
        val target = File(root.parentFile, "elsewhere").apply { mkdirs() }
        File(target, "keep.txt").writeText("keep")
        val link = File(root, "link")
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (_: Exception) {
            return // File system without symlinks.
        }
        // The link points outside: listing through it is refused...
        assertEquals(403, call("GET", "/api/ls", mapOf("p" to "/link")).status)
        assertTrue(File(target, "keep.txt").exists())
    }

    @Test
    fun jsonEscaping() {
        assertEquals("\"a\\\"b\\\\c\\n\\u0001\"", WebAccessServer.json("a\"b\\c\n\u0001"))
        assertEquals(mapOf("p" to "/a b/ñ", "k" to "X"), WebAccessServer.parseQuery("p=%2Fa+b%2F%C3%B1&k=X"))
        val c = WebAccessServer.newCode()
        assertEquals(8, c.length)
        assertTrue(c.none { it in "01IO" })
    }
}
