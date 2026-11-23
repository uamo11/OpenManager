/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.webaccess

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLConnection
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Small HTTP server to manage the phone's files from a browser on the same network: browse,
 * download (with ranges, so videos seek), upload, create folders, rename and delete.
 *
 * Safety:
 * - every request needs the access code shown on the phone (header `X-OM-Key` or `?k=`), and the
 *   server locks itself after [MAX_FAILED_CODES] wrong codes, so it cannot be guessed;
 * - paths are resolved inside [root] only (no `..` or symlink escapes);
 * - uploaded pages are never served inline, so a file cannot run script with the code's rights.
 */
class WebAccessServer(
    private val root: File,
    private val code: String,
    private val indexHtml: String,
    private val deviceName: String = "Android"
) {
    private var serverSocket: ServerSocket? = null
    private var pool: ExecutorService? = null
    private val failedCodes = AtomicInteger(0)
    private val rootCanonical: File = root.canonicalFile

    @Volatile
    var isLocked = false
        private set

    /** Called (on a server thread) after each file change, e.g. to refresh the app's lists. */
    var onChange: ((File) -> Unit)? = null

    /** Called once when too many wrong codes locked the server. */
    var onLocked: (() -> Unit)? = null

    val port: Int
        get() = serverSocket?.localPort ?: -1

    /** Binds the first free port from [ports]; 0 picks any free port. */
    @Throws(IOException::class)
    fun start(ports: IntRange = DEFAULT_PORTS) {
        var lastError: IOException? = null
        for (candidate in ports) {
            try {
                val socket = ServerSocket()
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(candidate))
                serverSocket = socket
                break
            } catch (e: IOException) {
                lastError = e
            }
        }
        val socket = serverSocket ?: throw lastError ?: IOException("No free port")
        val workers = Executors.newFixedThreadPool(MAX_CONNECTIONS)
        pool = workers
        Thread({
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                try {
                    workers.execute { serve(client) }
                } catch (_: Exception) {
                    client.close()
                }
            }
        }, "OMWebAccess").apply { isDaemon = true }.start()
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        pool?.shutdownNow()
        serverSocket = null
        pool = null
    }

    private class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: InputStream
    )

    private fun serve(socket: Socket) {
        socket.use {
            try {
                it.soTimeout = SOCKET_TIMEOUT_MS
                val input = BufferedInputStream(it.getInputStream())
                val output = it.getOutputStream()
                val request = readRequest(input) ?: return
                handle(request, output)
                output.flush()
            } catch (_: SocketException) {
            } catch (_: IOException) {
            }
        }
    }

    private fun readRequest(input: InputStream): Request? {
        val head = ByteArrayOutputStream()
        var matched = 0
        while (matched < 4) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            if (head.size() > MAX_HEADER_BYTES) return null
            matched = when {
                (matched == 0 || matched == 2) && b == '\r'.code -> matched + 1
                (matched == 1 || matched == 3) && b == '\n'.code -> matched + 1
                b == '\r'.code -> 1
                else -> 0
            }
        }
        val lines = head.toString(Charsets.ISO_8859_1.name()).split("\r\n")
        val parts = lines.first().split(' ')
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase(Locale.ROOT)] = line.substring(colon + 1).trim()
        }
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = parseQuery(target.substringAfter('?', ""))
        return Request(parts[0].uppercase(Locale.ROOT), path, query, headers, input)
    }

    private fun handle(request: Request, out: OutputStream) {
        if (isLocked) {
            return send(out, 423, "text/plain; charset=utf-8", "Locked: too many wrong codes. Restart web access on the phone.")
        }
        val given = request.headers["x-om-key"] ?: request.query["k"]
        if (given == null || !codeMatches(given)) {
            if (given != null && failedCodes.incrementAndGet() >= MAX_FAILED_CODES && !isLocked) {
                isLocked = true
                onLocked?.invoke()
            }
            return if (request.path == "/" && request.method == "GET") {
                send(out, 200, "text/html; charset=utf-8", loginPage(given != null), extraHeaders = PAGE_HEADERS)
            } else {
                send(out, 401, "application/json", """{"error":"code"}""")
            }
        }
        try {
            when (request.method to request.path) {
                "GET" to "/" -> send(out, 200, "text/html; charset=utf-8", indexHtml, extraHeaders = PAGE_HEADERS)
                "GET" to "/api/info" -> sendJson(out, infoJson())
                "GET" to "/api/ls" -> sendJson(out, listJson(resolve(request.query["p"])))
                "GET" to "/api/dl" -> download(request, out)
                "PUT" to "/api/up" -> upload(request, out)
                "POST" to "/api/mkdir" -> {
                    val dir = resolve(request.query["p"])
                    val created = File(dir, validName(request.query["n"]))
                    if (created.exists()) throw ApiError(409, "exists")
                    if (!created.mkdir()) throw ApiError(500, "mkdir failed")
                    onChange?.invoke(dir)
                    sendJson(out, """{"ok":true}""")
                }
                "POST" to "/api/mv" -> {
                    val source = resolve(request.query["p"])
                    if (source == rootCanonical) throw ApiError(403, "root")
                    val target = File(source.parentFile, validName(request.query["n"]))
                    if (target.exists() && !target.path.equals(source.path, ignoreCase = true)) throw ApiError(409, "exists")
                    if (!source.renameTo(target)) throw ApiError(500, "rename failed")
                    onChange?.invoke(source.parentFile!!)
                    sendJson(out, """{"ok":true}""")
                }
                "POST" to "/api/rm" -> {
                    val target = resolve(request.query["p"])
                    if (target == rootCanonical) throw ApiError(403, "root")
                    if (!target.exists()) throw ApiError(404, "missing")
                    // Does not follow folder symlinks: deletes the link, not what it points to.
                    if (!deleteTree(target)) throw ApiError(500, "delete failed")
                    onChange?.invoke(target.parentFile!!)
                    sendJson(out, """{"ok":true}""")
                }
                else -> throw ApiError(404, "not found")
            }
        } catch (e: ApiError) {
            send(out, e.status, "application/json", """{"error":${json(e.message ?: "")}}""")
        }
    }

    private class ApiError(val status: Int, message: String) : Exception(message)

    private fun codeMatches(given: String): Boolean =
        MessageDigest.isEqual(given.trim().uppercase(Locale.ROOT).toByteArray(), code.uppercase(Locale.ROOT).toByteArray())

    /** A path from the browser ("/DCIM/a.jpg"), resolved inside the root or rejected. */
    fun resolve(relative: String?): File {
        val clean = (relative ?: "/").replace('\\', '/')
        if (clean.contains('\u0000')) throw ApiError(400, "bad path")
        val file = File(rootCanonical, clean.trimStart('/')).canonicalFile
        val inside = file == rootCanonical || file.path.startsWith(rootCanonical.path + File.separator)
        if (!inside) throw ApiError(403, "outside")
        return file
    }

    private fun relativeOf(file: File): String =
        "/" + file.path.removePrefix(rootCanonical.path).trimStart(File.separatorChar)

    private fun validName(name: String?): String {
        val n = name?.trim().orEmpty()
        if (n.isEmpty() || n == "." || n == ".." || n.contains('/') || n.contains('\\') || n.contains('\u0000')) {
            throw ApiError(400, "bad name")
        }
        return n
    }

    private fun infoJson(): String {
        val total = rootCanonical.totalSpace
        val free = rootCanonical.usableSpace
        return """{"device":${json(deviceName)},"free":$free,"total":$total}"""
    }

    private fun listJson(dir: File): String {
        if (!dir.isDirectory) throw ApiError(404, "not a folder")
        val entries = (dir.listFiles() ?: throw ApiError(403, "unreadable"))
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))
        val sb = StringBuilder()
        sb.append("""{"path":""").append(json(relativeOf(dir))).append(""","entries":[""")
        entries.forEachIndexed { i, f ->
            if (i > 0) sb.append(',')
            sb.append("""{"n":""").append(json(f.name))
                .append(""","d":""").append(f.isDirectory)
                .append(""","s":""").append(if (f.isDirectory) 0 else f.length())
                .append(""","m":""").append(f.lastModified())
                .append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun download(request: Request, out: OutputStream) {
        val file = resolve(request.query["p"])
        if (!file.isFile) throw ApiError(404, "not a file")
        val length = file.length()
        val guessed = URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
        val inline = request.query["inline"] == "1" && isSafeInline(guessed)
        val type = if (inline) guessed else "application/octet-stream"
        var start = 0L
        var end = length - 1
        var status = 200
        request.headers["range"]?.let { range ->
            val m = Regex("""bytes=(\d*)-(\d*)""").matchEntire(range.trim()) ?: return@let
            val (a, b) = m.destructured
            when {
                a.isEmpty() && b.isNotEmpty() -> start = maxOf(0L, length - b.toLong())
                a.isNotEmpty() -> {
                    start = a.toLong()
                    if (b.isNotEmpty()) end = minOf(b.toLong(), length - 1)
                }
            }
            if (start >= length || start > end) {
                return send(out, 416, "text/plain", "", extraHeaders = mapOf("Content-Range" to "bytes */$length"))
            }
            status = 206
        }
        val count = if (length == 0L) 0L else end - start + 1
        val disposition = (if (inline) "inline" else "attachment") +
            "; filename*=UTF-8''" + URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")
        val headers = mutableMapOf(
            "Content-Disposition" to disposition,
            "Accept-Ranges" to "bytes"
        )
        if (status == 206) headers["Content-Range"] = "bytes $start-$end/$length"
        writeHead(out, status, type, count, headers)
        if (request.method == "HEAD" || count == 0L) return
        FileInputStream(file).use { input ->
            input.channel.position(start)
            copy(input, out, count)
        }
    }

    private fun upload(request: Request, out: OutputStream) {
        val length = request.headers["content-length"]?.toLongOrNull() ?: throw ApiError(411, "length required")
        val (dir, target) = try {
            val folder = resolve(request.query["p"])
            if (!folder.isDirectory) throw ApiError(404, "not a folder")
            val file = File(folder, validName(request.query["n"]))
            if (file.exists() && (request.query["o"] != "1" || file.isDirectory)) throw ApiError(409, "exists")
            folder to file
        } catch (e: ApiError) {
            // Read the unwanted body first: closing with unread data resets the connection and the
            // browser would show a network error instead of the reason.
            if (length <= MAX_DRAIN_BYTES) copy(request.body, NullOutputStream, length)
            throw e
        }
        val name = target.name
        val overwrite = request.query["o"] == "1"
        // Written aside and moved at the end: an interrupted upload never leaves a half file.
        val temp = File(dir, ".$name.omupload")
        try {
            temp.outputStream().use { copy(request.body, it, length) }
            if (temp.length() != length) throw ApiError(400, "incomplete")
            if (overwrite) target.delete()
            if (!temp.renameTo(target)) throw ApiError(500, "save failed")
        } finally {
            temp.delete()
        }
        onChange?.invoke(dir)
        sendJson(out, """{"ok":true}""")
    }

    /** A symlink's real path differs from its own place in the folder. */
    private fun isSymlink(file: File): Boolean = runCatching {
        val parent = file.absoluteFile.parentFile?.canonicalFile ?: return false
        File(parent, file.name).canonicalPath != File(parent, file.name).absolutePath
    }.getOrDefault(false)

    private fun deleteTree(file: File): Boolean {
        if (file.isDirectory && !isSymlink(file)) {
            file.listFiles()?.forEach { if (!deleteTree(it)) return false }
        }
        return file.delete()
    }

    private fun copy(input: InputStream, output: OutputStream, count: Long) {
        val buffer = ByteArray(1 shl 16)
        var left = count
        while (left > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (read < 0) break
            output.write(buffer, 0, read)
            left -= read
        }
    }

    private fun sendJson(out: OutputStream, body: String) = send(out, 200, "application/json; charset=utf-8", body)

    private fun send(
        out: OutputStream,
        status: Int,
        type: String,
        body: String,
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        writeHead(out, status, type, bytes.size.toLong(), extraHeaders)
        out.write(bytes)
    }

    private fun writeHead(out: OutputStream, status: Int, type: String, length: Long, extra: Map<String, String>) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
        sb.append("Content-Type: ").append(type).append("\r\n")
        sb.append("Content-Length: ").append(length).append("\r\n")
        sb.append("Connection: close\r\n")
        sb.append("Cache-Control: no-store\r\n")
        sb.append("X-Content-Type-Options: nosniff\r\n")
        sb.append("Referrer-Policy: no-referrer\r\n")
        for ((k, v) in extra) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
    }

    private fun loginPage(wrong: Boolean): String = LOGIN_PAGE
        .replace("{{device}}", escapeHtml(deviceName))
        .replace("{{wrong}}", if (wrong) "block" else "none")

    companion object {
        val DEFAULT_PORTS = 8088..8098
        const val MAX_FAILED_CODES = 10
        private const val MAX_CONNECTIONS = 8
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val SOCKET_TIMEOUT_MS = 60_000
        private const val MAX_DRAIN_BYTES = 64L * 1024 * 1024

        private object NullOutputStream : OutputStream() {
            override fun write(b: Int) = Unit
            override fun write(b: ByteArray, off: Int, len: Int) = Unit
        }

        private val PAGE_HEADERS = mapOf(
            "Content-Security-Policy" to
                "default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src 'self' blob: data:; media-src 'self'; frame-ancestors 'none'",
            "X-Frame-Options" to "DENY"
        )

        /** Types a browser can show without running anything: never HTML, SVG or XML. */
        fun isSafeInline(type: String): Boolean {
            val t = type.lowercase(Locale.ROOT)
            return (t.startsWith("image/") && !t.contains("svg")) || t.startsWith("video/") ||
                t.startsWith("audio/") || t == "text/plain" || t == "application/pdf"
        }

        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        /** 8 characters without look-alikes (0/O, 1/I): about 40 bits, with lock-out after 10 tries. */
        fun newCode(random: java.security.SecureRandom = java.security.SecureRandom()): String =
            String(CharArray(8) { ALPHABET[random.nextInt(ALPHABET.length)] })

        fun parseQuery(query: String): Map<String, String> {
            if (query.isEmpty()) return emptyMap()
            return query.split('&').filter { it.isNotEmpty() }.associate { pair ->
                val key = pair.substringBefore('=')
                val value = pair.substringAfter('=', "")
                URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
            }
        }

        fun json(text: String): String {
            val sb = StringBuilder("\"")
            for (c in text) {
                when {
                    c == '"' -> sb.append("\\\"")
                    c == '\\' -> sb.append("\\\\")
                    c == '\n' -> sb.append("\\n")
                    c == '\r' -> sb.append("\\r")
                    c == '\t' -> sb.append("\\t")
                    c < ' ' || c == ' ' || c == ' ' -> sb.append(String.format("\\u%04x", c.code))
                    else -> sb.append(c)
                }
            }
            return sb.append('"').toString()
        }

        private fun escapeHtml(text: String) =
            text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        private fun reason(status: Int) = when (status) {
            200 -> "OK"
            206 -> "Partial Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            409 -> "Conflict"
            411 -> "Length Required"
            416 -> "Range Not Satisfiable"
            423 -> "Locked"
            else -> "Error"
        }

        private const val LOGIN_PAGE = """<!DOCTYPE html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>OpenManager</title>
<style>
body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#121417;color:#e8eaed;font-family:system-ui,sans-serif}
form{background:#1d2025;padding:32px;border-radius:16px;width:300px;box-shadow:0 8px 30px #0008}
h1{margin:0 0 4px;font-size:20px}p{color:#9aa0a6;margin:0 0 20px;font-size:14px}
input{width:100%;box-sizing:border-box;padding:12px;border-radius:10px;border:1px solid #3c4043;background:#121417;color:#fff;font-size:20px;letter-spacing:4px;text-transform:uppercase;text-align:center}
button{margin-top:16px;width:100%;padding:12px;border:0;border-radius:10px;background:#4f8cff;color:#fff;font-size:16px;cursor:pointer}
.err{display:{{wrong}};color:#ff6b6b;margin-top:12px;font-size:14px}
</style>
<script>var k=sessionStorage.getItem("omk");if(k&&!location.search)location.replace("/?k="+encodeURIComponent(k)+location.hash);</script>
</head><body>
<form method="get" action="/">
<h1>OpenManager</h1><p>{{device}}</p>
<input name="k" autocomplete="off" autofocus placeholder="CÓDIGO / CODE" maxlength="16">
<button type="submit">Entrar / Enter</button>
<div class="err">Código incorrecto / Wrong code</div>
</form></body></html>"""
    }
}
