/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.docx

import android.util.Base64
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

data class DocxMetadata(
    val fileName: String,
    val fileSize: Long,
    val filePath: String,
    val isLegacyDoc: Boolean = false,
    val wordCount: Int = 0,
    val charCount: Int = 0,
    val paragraphCount: Int = 0,
    val tableCount: Int = 0,
    val imageCount: Int = 0,
    val readingTimeMinutes: Int = 1
)

data class DocxDocument(
    val metadata: DocxMetadata,
    val htmlContent: String,
    val plainText: String
)

private data class Relationship(
    val id: String,
    val type: String,
    val target: String,
    val isExternal: Boolean
)

object DocxDocumentEngine {

    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val OLE_MAGIC = byteArrayOf(
        0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte(),
        0xA1.toByte(), 0xB1.toByte(), 0x1A.toByte(), 0xE1.toByte()
    )

    fun parse(file: File, defaultTheme: DocxReadingTheme = DocxReadingTheme.DAY): Result<DocxDocument> = runCatching {
        val magic = ByteArray(8)
        FileInputStream(file).use { it.read(magic) }

        if (isZipArchive(magic)) {
            parseDocxFile(file, defaultTheme)
        } else if (isOleCompoundFile(magic)) {
            parseLegacyDocFile(file, defaultTheme)
        } else {
            // Attempt DOCX parser as fallback in case magic was partially displaced
            parseDocxFile(file, defaultTheme)
        }
    }

    private fun isZipArchive(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        return bytes[0] == ZIP_MAGIC[0] && bytes[1] == ZIP_MAGIC[1] &&
                bytes[2] == ZIP_MAGIC[2] && bytes[3] == ZIP_MAGIC[3]
    }

    private fun isOleCompoundFile(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        for (i in 0..7) {
            if (bytes[i] != OLE_MAGIC[i]) return false
        }
        return true
    }

    private fun parseDocxFile(file: File, defaultTheme: DocxReadingTheme): DocxDocument {
        val zip = ZipFile(file)
        try {
            val relationships = parseRelationships(zip)
            val imagesMap = extractImages(zip, relationships)

            val docEntry = zip.getEntry("word/document.xml")
                ?: throw IllegalArgumentException("Not a valid DOCX file (missing word/document.xml)")

            val docInput = zip.getInputStream(docEntry)
            val dbFactory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
            }
            val doc = dbFactory.newDocumentBuilder().parse(docInput)
            val root = doc.documentElement

            val body = root.findChild("body") ?: root

            val htmlBuilder = StringBuilder()
            val textBuilder = StringBuilder()
            var paragraphCount = 0
            var tableCount = 0

            val bodyChildren = body.childNodes
            for (i in 0 until bodyChildren.length) {
                val node = bodyChildren.item(i)
                if (node !is Element) continue
                when (node.localOrNodeName) {
                    "p" -> {
                        paragraphCount++
                        val (pText, pHtml) = parseParagraph(node, relationships, imagesMap)
                        if (pText.isNotEmpty()) {
                            textBuilder.append(pText).append("\n\n")
                        }
                        htmlBuilder.append(pHtml)
                    }
                    "tbl" -> {
                        tableCount++
                        val (tText, tHtml) = parseTable(node, relationships, imagesMap)
                        if (tText.isNotEmpty()) {
                            textBuilder.append(tText).append("\n\n")
                        }
                        htmlBuilder.append(tHtml)
                    }
                    "sdt" -> {
                        // Structured Document Tag - unpack sdtContent
                        val content = node.findChild("sdtContent") ?: node
                        val sdtChildren = content.childNodes
                        for (j in 0 until sdtChildren.length) {
                            val sNode = sdtChildren.item(j)
                            if (sNode !is Element) continue
                            if (sNode.localOrNodeName == "p") {
                                paragraphCount++
                                val (pText, pHtml) = parseParagraph(sNode, relationships, imagesMap)
                                if (pText.isNotEmpty()) textBuilder.append(pText).append("\n\n")
                                htmlBuilder.append(pHtml)
                            } else if (sNode.localOrNodeName == "tbl") {
                                tableCount++
                                val (tText, tHtml) = parseTable(sNode, relationships, imagesMap)
                                if (tText.isNotEmpty()) textBuilder.append(tText).append("\n\n")
                                htmlBuilder.append(tHtml)
                            }
                        }
                    }
                }
            }

            val plainText = textBuilder.toString().trim()
            val words = if (plainText.isEmpty()) 0 else plainText.split(Regex("\\s+")).count { it.isNotEmpty() }
            val chars = plainText.length
            val readingTime = maxOf(1, Math.ceil(words / 200.0).toInt())

            val metadata = DocxMetadata(
                fileName = file.name,
                fileSize = file.length(),
                filePath = file.absolutePath,
                isLegacyDoc = false,
                wordCount = words,
                charCount = chars,
                paragraphCount = paragraphCount,
                tableCount = tableCount,
                imageCount = imagesMap.size,
                readingTimeMinutes = readingTime
            )

            val fullHtml = buildFullHtml(
                title = file.name,
                bodyContent = htmlBuilder.toString(),
                theme = defaultTheme
            )

            return DocxDocument(
                metadata = metadata,
                htmlContent = fullHtml,
                plainText = plainText
            )
        } finally {
            try { zip.close() } catch (_: Exception) {}
        }
    }

    private fun parseParagraph(
        pElem: Element,
        relationships: Map<String, Relationship>,
        imagesMap: Map<String, String>
    ): Pair<String, String> {
        val pPr = pElem.findChild("pPr")
        val pStyle = pPr?.findChild("pStyle")?.getAttr("val") ?: ""
        val jc = pPr?.findChild("jc")?.getAttr("val") ?: ""
        val isList = pPr?.findChild("numPr") != null

        val pText = StringBuilder()
        val pHtml = StringBuilder()

        val styleTag = when {
            pStyle.equals("Title", true) -> "h1"
            pStyle.equals("Subtitle", true) -> "p"
            pStyle.matches(Regex("(?i)Heading\\s*1|H1")) -> "h1"
            pStyle.matches(Regex("(?i)Heading\\s*2|H2")) -> "h2"
            pStyle.matches(Regex("(?i)Heading\\s*3|H3")) -> "h3"
            pStyle.matches(Regex("(?i)Heading\\s*4|H4")) -> "h4"
            pStyle.matches(Regex("(?i)Heading\\s*5|H5")) -> "h5"
            pStyle.matches(Regex("(?i)Heading\\s*6|H6")) -> "h6"
            pStyle.contains("Quote", true) -> "blockquote"
            isList -> "li"
            else -> "p"
        }

        val cssClasses = mutableListOf<String>()
        if (pStyle.equals("Title", true)) cssClasses.add("doc-title")
        if (pStyle.equals("Subtitle", true)) cssClasses.add("doc-subtitle")

        val inlineStyles = mutableListOf<String>()
        when (jc.lowercase()) {
            "center" -> inlineStyles.add("text-align: center;")
            "right" -> inlineStyles.add("text-align: right;")
            "both", "justify" -> inlineStyles.add("text-align: justify;")
            "left" -> inlineStyles.add("text-align: left;")
        }

        val childNodes = pElem.childNodes
        for (i in 0 until childNodes.length) {
            val child = childNodes.item(i)
            if (child !is Element) continue
            when (child.localOrNodeName) {
                "r" -> {
                    val (rText, rHtml) = parseRun(child, imagesMap)
                    pText.append(rText)
                    pHtml.append(rHtml)
                }
                "hyperlink" -> {
                    val rId = child.getAttr("id")
                    val targetUrl = relationships[rId]?.target ?: "#"
                    val (hText, hContent) = parseHyperlink(child, imagesMap)
                    pText.append(hText)
                    pHtml.append("<a href=\"").append(escapeHtmlAttr(targetUrl)).append("\" target=\"_blank\">")
                        .append(hContent).append("</a>")
                }
                "drawing" -> {
                    val (dText, dHtml) = parseDrawing(child, imagesMap)
                    pText.append(dText)
                    pHtml.append(dHtml)
                }
            }
        }

        if (pHtml.isEmpty() && pText.isEmpty()) {
            return "" to ""
        }

        val classAttr = if (cssClasses.isNotEmpty()) " class=\"${cssClasses.joinToString(" ")}\"" else ""
        val styleAttr = if (inlineStyles.isNotEmpty()) " style=\"${inlineStyles.joinToString(" ")}\"" else ""

        val finalHtml = if (isList) {
            "<ul class=\"doc-list\"><$styleTag$classAttr$styleAttr>$pHtml</$styleTag></ul>\n"
        } else {
            "<$styleTag$classAttr$styleAttr>$pHtml</$styleTag>\n"
        }

        return pText.toString() to finalHtml
    }

    private fun parseRun(
        rElem: Element,
        imagesMap: Map<String, String>
    ): Pair<String, String> {
        val rPr = rElem.findChild("rPr")
        val isBold = rPr?.hasChildWithTruth("b") == true
        val isItalic = rPr?.hasChildWithTruth("i") == true
        val isUnderline = rPr?.hasChild("u") == true
        val isStrike = rPr?.hasChildWithTruth("strike") == true
        val vertAlign = rPr?.findChild("vertAlign")?.getAttr("val") ?: ""
        val colorVal = rPr?.findChild("color")?.getAttr("val") ?: ""
        val highlightVal = rPr?.findChild("highlight")?.getAttr("val") ?: ""

        val rText = StringBuilder()
        val rHtml = StringBuilder()

        val children = rElem.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child !is Element) continue
            when (child.localOrNodeName) {
                "t" -> {
                    val text = child.textContent ?: ""
                    rText.append(text)
                    rHtml.append(escapeHtml(text))
                }
                "br" -> {
                    val type = child.getAttr("type")
                    if (type.equals("page", true)) {
                        rHtml.append("<div class=\"doc-page-break\"><hr/><span class=\"page-break-tag\">Salto de página</span></div>")
                        rText.append("\n--- [Salto de página] ---\n")
                    } else {
                        rHtml.append("<br/>")
                        rText.append("\n")
                    }
                }
                "tab" -> {
                    rHtml.append("&emsp;")
                    rText.append("\t")
                }
                "drawing", "pict" -> {
                    val (dText, dHtml) = parseDrawing(child, imagesMap)
                    rText.append(dText)
                    rHtml.append(dHtml)
                }
            }
        }

        if (rHtml.isEmpty() && rText.isEmpty()) {
            return "" to ""
        }

        var styledHtml = rHtml.toString()
        if (isBold) styledHtml = "<b>$styledHtml</b>"
        if (isItalic) styledHtml = "<i>$styledHtml</i>"
        if (isUnderline) styledHtml = "<u>$styledHtml</u>"
        if (isStrike) styledHtml = "<s>$styledHtml</s>"
        if (vertAlign.equals("superscript", true)) styledHtml = "<sup>$styledHtml</sup>"
        if (vertAlign.equals("subscript", true)) styledHtml = "<sub>$styledHtml</sub>"

        val spanStyles = mutableListOf<String>()
        if (colorVal.isNotEmpty() && !colorVal.equals("auto", true)) {
            val hex = if (colorVal.startsWith("#")) colorVal else "#$colorVal"
            spanStyles.add("color: $hex;")
        }
        if (highlightVal.isNotEmpty() && !highlightVal.equals("none", true)) {
            val bg = when (highlightVal.lowercase()) {
                "yellow" -> "#FFF59D"
                "green" -> "#A5D6A7"
                "cyan" -> "#80DEEA"
                "magenta" -> "#F48FB1"
                "blue" -> "#90CAF9"
                "red" -> "#EF9A9A"
                else -> "#FFF59D"
            }
            spanStyles.add("background-color: $bg;")
        }

        if (spanStyles.isNotEmpty()) {
            styledHtml = "<span style=\"${spanStyles.joinToString(" ")}\">$styledHtml</span>"
        }

        return rText.toString() to styledHtml
    }

    private fun parseHyperlink(
        hElem: Element,
        imagesMap: Map<String, String>
    ): Pair<String, String> {
        val hText = StringBuilder()
        val hHtml = StringBuilder()
        val children = hElem.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child is Element && child.localOrNodeName == "r") {
                val (rText, rHtml) = parseRun(child, imagesMap)
                hText.append(rText)
                hHtml.append(rHtml)
            }
        }
        return hText.toString() to hHtml.toString()
    }

    private fun parseDrawing(
        drawingElem: Element,
        imagesMap: Map<String, String>
    ): Pair<String, String> {
        val blip = findDescendant(drawingElem) {
            it.localOrNodeName == "blip" || it.localOrNodeName == "imagedata"
        } ?: return "" to ""

        val embedId = blip.getAttr("embed").ifEmpty { blip.getAttr("id") }
        val dataUri = imagesMap[embedId] ?: return "" to ""

        val html = "<div class=\"doc-image-box\"><img src=\"$dataUri\" alt=\"Imagen\" loading=\"lazy\" /></div>"
        return "[Imagen]" to html
    }

    private fun parseTable(
        tblElem: Element,
        relationships: Map<String, Relationship>,
        imagesMap: Map<String, String>
    ): Pair<String, String> {
        val tText = StringBuilder()
        val tHtml = StringBuilder()

        tHtml.append("<div class=\"table-container\"><table class=\"doc-table\">\n")

        val children = tblElem.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child !is Element || child.localOrNodeName != "tr") continue

            val trPr = child.findChild("trPr")
            val isHeader = trPr?.findChild("tblHeader") != null

            tHtml.append("<tr>\n")
            val cells = child.childNodes
            for (j in 0 until cells.length) {
                val cell = cells.item(j)
                if (cell !is Element || cell.localOrNodeName != "tc") continue

                val tcPr = cell.findChild("tcPr")
                val gridSpan = tcPr?.findChild("gridSpan")?.getAttr("val")?.toIntOrNull()
                val shdFill = tcPr?.findChild("shd")?.getAttr("fill")

                val attrs = mutableListOf<String>()
                if (gridSpan != null && gridSpan > 1) {
                    attrs.add("colspan=\"$gridSpan\"")
                }
                val cellStyles = mutableListOf<String>()
                if (!shdFill.isNullOrEmpty() && !shdFill.equals("auto", true) && !shdFill.equals("none", true)) {
                    val hex = if (shdFill.startsWith("#")) shdFill else "#$shdFill"
                    cellStyles.add("background-color: $hex;")
                }
                if (cellStyles.isNotEmpty()) {
                    attrs.add("style=\"${cellStyles.joinToString(" ")}\"")
                }

                val tag = if (isHeader) "th" else "td"
                val attrStr = if (attrs.isNotEmpty()) " " + attrs.joinToString(" ") else ""
                tHtml.append("<$tag$attrStr>")

                val pChildren = cell.childNodes
                for (k in 0 until pChildren.length) {
                    val pChild = pChildren.item(k)
                    if (pChild is Element && pChild.localOrNodeName == "p") {
                        val (pT, pH) = parseParagraph(pChild, relationships, imagesMap)
                        tText.append(pT).append(" ")
                        tHtml.append(pH)
                    }
                }
                tHtml.append("</$tag>\n")
                tText.append(" | ")
            }
            tHtml.append("</tr>\n")
            tText.append("\n")
        }

        tHtml.append("</table></div>\n")
        return tText.toString() to tHtml.toString()
    }

    private fun parseRelationships(zip: ZipFile): Map<String, Relationship> {
        val relsMap = mutableMapOf<String, Relationship>()
        val relsEntry = zip.getEntry("word/_rels/document.xml.rels") ?: return relsMap

        try {
            zip.getInputStream(relsEntry).use { input ->
                val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                val doc = factory.newDocumentBuilder().parse(input)
                val relNodes = doc.getElementsByTagName("Relationship")
                for (i in 0 until relNodes.length) {
                    val node = relNodes.item(i) as? Element ?: continue
                    val id = node.getAttr("Id")
                    val type = node.getAttr("Type")
                    val target = node.getAttr("Target")
                    val targetMode = node.getAttr("TargetMode")
                    if (id.isNotEmpty()) {
                        relsMap[id] = Relationship(
                            id = id,
                            type = type,
                            target = target,
                            isExternal = targetMode.equals("External", true)
                        )
                    }
                }
            }
        } catch (_: Exception) {}
        return relsMap
    }

    private fun extractImages(zip: ZipFile, relationships: Map<String, Relationship>): Map<String, String> {
        val imagesMap = mutableMapOf<String, String>()
        for ((id, rel) in relationships) {
            if (rel.type.contains("/image", ignoreCase = true) || rel.type.contains("image", ignoreCase = true)) {
                var target = rel.target
                if (target.startsWith("/")) target = target.substring(1)
                if (!target.startsWith("word/")) {
                    target = "word/$target"
                }

                val entry = zip.getEntry(target) ?: continue
                try {
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    val mime = when {
                        target.endsWith(".png", true) -> "image/png"
                        target.endsWith(".jpg", true) || target.endsWith(".jpeg", true) -> "image/jpeg"
                        target.endsWith(".gif", true) -> "image/gif"
                        target.endsWith(".webp", true) -> "image/webp"
                        target.endsWith(".svg", true) -> "image/svg+xml"
                        target.endsWith(".bmp", true) -> "image/bmp"
                        else -> "image/png"
                    }
                    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    imagesMap[id] = "data:$mime;base64,$base64"
                } catch (_: Exception) {}
            }
        }
        return imagesMap
    }

    private fun parseLegacyDocFile(file: File, defaultTheme: DocxReadingTheme): DocxDocument {
        val data = file.readBytes()
        val paragraphs = mutableListOf<String>()

        // Scan for 16-bit UTF-16LE characters and CP1252 sequences
        val current = StringBuilder()
        var i = 0
        val n = data.size
        while (i < n - 1) {
            val c = (data[i].toInt() and 0xFF) or ((data[i + 1].toInt() and 0xFF) shl 8)
            if ((c in 32..126) || (c in 160..550) || c == 10 || c == 13 || c == 9) {
                if (c == 13 || c == 10) {
                    if (current.length >= 3) {
                        paragraphs.add(current.toString().trim())
                    }
                    current.clear()
                } else if (c == 9) {
                    current.append(" ")
                } else {
                    current.append(c.toChar())
                }
                i += 2
            } else {
                if (current.length >= 4) {
                    paragraphs.add(current.toString().trim())
                }
                current.clear()
                i += 2
            }
        }
        if (current.length >= 4) {
            paragraphs.add(current.toString().trim())
        }

        val filteredParagraphs = paragraphs.filter { it.length > 2 && it.any { ch -> ch.isLetter() } }
        val plainText = filteredParagraphs.joinToString("\n\n")

        val htmlBody = StringBuilder().apply {
            append("<div class=\"legacy-doc-banner\">")
            append("<strong>Documento de Word heredado (.doc)</strong><br/>")
            append("Este archivo utiliza el formato binario anterior a Word 2007. Se muestra a continuación el contenido textual extraído.")
            append("</div>\n")
            for (p in filteredParagraphs) {
                append("<p>").append(escapeHtml(p)).append("</p>\n")
            }
        }.toString()

        val words = if (plainText.isEmpty()) 0 else plainText.split(Regex("\\s+")).count { it.isNotEmpty() }
        val chars = plainText.length
        val readingTime = maxOf(1, Math.ceil(words / 200.0).toInt())

        val metadata = DocxMetadata(
            fileName = file.name,
            fileSize = file.length(),
            filePath = file.absolutePath,
            isLegacyDoc = true,
            wordCount = words,
            charCount = chars,
            paragraphCount = filteredParagraphs.size,
            tableCount = 0,
            imageCount = 0,
            readingTimeMinutes = readingTime
        )

        val fullHtml = buildFullHtml(file.name, htmlBody, defaultTheme)
        return DocxDocument(metadata, fullHtml, plainText)
    }

    private fun buildFullHtml(title: String, bodyContent: String, theme: DocxReadingTheme): String {
        return """
<!DOCTYPE html>
<html class="${theme.cssClass}">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=3.0, user-scalable=yes">
    <title>${escapeHtml(title)}</title>
    <style>
        :root {
            --font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
        }
        :root.theme-day {
            --bg-body: #ECEFF1;
            --bg-card: #FFFFFF;
            --text-main: #212121;
            --text-muted: #757575;
            --border-subtle: #E0E0E0;
            --accent-color: #1976D2;
            --table-border: #BDBDBD;
            --table-header: #F5F5F5;
            --table-alt: #FAFAFA;
            --shadow: 0 4px 16px rgba(0,0,0,0.08);
            --code-bg: #F0F2F5;
        }
        :root.theme-night {
            --bg-body: #121212;
            --bg-card: #1E1E1E;
            --text-main: #E0E0E0;
            --text-muted: #9E9E9E;
            --border-subtle: #333333;
            --accent-color: #64B5F6;
            --table-border: #424242;
            --table-header: #2A2A2A;
            --table-alt: #222222;
            --shadow: 0 4px 16px rgba(0,0,0,0.5);
            --code-bg: #262626;
        }
        :root.theme-sepia {
            --bg-body: #EFE4D0;
            --bg-card: #FBF0D9;
            --text-main: #4F3824;
            --text-muted: #8D735C;
            --border-subtle: #DFD3BD;
            --accent-color: #A0522D;
            --table-border: #C8BA9E;
            --table-header: #F3E5C8;
            --table-alt: #F7EBD2;
            --shadow: 0 4px 16px rgba(79, 56, 36, 0.12);
            --code-bg: #EFE0C2;
        }

        body {
            margin: 0;
            padding: 16px 12px 64px 12px;
            background-color: var(--bg-body);
            color: var(--text-main);
            font-family: var(--font-family);
            line-height: 1.65;
            font-size: 16px;
            word-wrap: break-word;
            transition: background-color 0.2s ease, color 0.2s ease;
        }

        .doc-paper {
            max-width: 820px;
            margin: 0 auto;
            background-color: var(--bg-card);
            padding: 24px 20px;
            border-radius: 8px;
            box-shadow: var(--shadow);
            box-sizing: border-box;
        }

        h1, h2, h3, h4, h5, h6 {
            color: var(--text-main);
            margin-top: 1.4em;
            margin-bottom: 0.6em;
            font-weight: 700;
            line-height: 1.3;
        }
        h1 { font-size: 1.65em; border-bottom: 2px solid var(--accent-color); padding-bottom: 6px; }
        h2 { font-size: 1.38em; border-bottom: 1px solid var(--border-subtle); padding-bottom: 4px; }
        h3 { font-size: 1.18em; }
        h4 { font-size: 1.05em; }

        .doc-title {
            font-size: 2em;
            font-weight: 800;
            text-align: center;
            margin-top: 0.5em;
            margin-bottom: 0.4em;
            border-bottom: none;
        }
        .doc-subtitle {
            font-size: 1.15em;
            color: var(--text-muted);
            text-align: center;
            margin-bottom: 1.8em;
        }

        p {
            margin-top: 0;
            margin-bottom: 1em;
            text-rendering: optimizeLegibility;
        }

        a {
            color: var(--accent-color);
            text-decoration: underline;
        }

        .table-container {
            width: 100%;
            overflow-x: auto;
            margin: 18px 0;
            -webkit-overflow-scrolling: touch;
        }

        table.doc-table {
            border-collapse: collapse;
            width: 100%;
            font-size: 14.5px;
            border: 1px solid var(--table-border);
        }

        table.doc-table th, table.doc-table td {
            border: 1px solid var(--table-border);
            padding: 8px 12px;
            text-align: left;
            vertical-align: top;
        }

        table.doc-table th {
            background-color: var(--table-header);
            font-weight: 600;
        }

        table.doc-table tr:nth-child(even) td {
            background-color: var(--table-alt);
        }

        .doc-image-box {
            text-align: center;
            margin: 18px 0;
        }

        .doc-image-box img {
            max-width: 100%;
            height: auto;
            border-radius: 4px;
        }

        .doc-page-break {
            display: flex;
            align-items: center;
            margin: 32px 0;
        }

        .doc-page-break hr {
            flex-grow: 1;
            border: none;
            border-top: 1px dashed var(--border-subtle);
        }

        .doc-page-break .page-break-tag {
            font-size: 11px;
            color: var(--text-muted);
            text-transform: uppercase;
            padding: 0 10px;
            letter-spacing: 1px;
        }

        blockquote {
            margin: 16px 0;
            padding: 8px 16px;
            border-left: 4px solid var(--accent-color);
            background-color: var(--code-bg);
            border-radius: 0 4px 4px 0;
            font-style: italic;
        }

        ul, ol {
            padding-left: 24px;
            margin: 8px 0 16px 0;
        }

        li {
            margin-bottom: 6px;
        }

        .legacy-doc-banner {
            background-color: #FFF3E0;
            border: 1px solid #FFE082;
            color: #E65100;
            padding: 12px 16px;
            border-radius: 6px;
            margin-bottom: 20px;
            font-size: 13.5px;
            line-height: 1.4;
        }
        :root.theme-night .legacy-doc-banner {
            background-color: #2E2010;
            border-color: #5D4037;
            color: #FFB74D;
        }
        :root.theme-sepia .legacy-doc-banner {
            background-color: #F3E5C8;
            border-color: #D7CCC8;
            color: #795548;
        }
    </style>
    <script>
        function setThemeClass(themeClass) {
            document.documentElement.className = themeClass;
        }
    </script>
</head>
<body>
    <div class="doc-paper">
        $bodyContent
    </div>
</body>
</html>
        """.trimIndent()
    }

    private val Element.localOrNodeName: String
        get() = localName ?: nodeName.substringAfter(':')

    private fun Element.getAttr(name: String): String {
        if (hasAttribute(name)) return getAttribute(name)
        if (hasAttribute("w:$name")) return getAttribute("w:$name")
        if (hasAttribute("r:$name")) return getAttribute("r:$name")
        val attrs = attributes ?: return ""
        for (i in 0 until attrs.length) {
            val item = attrs.item(i)
            if (item.localName == name || item.nodeName.substringAfter(':') == name) {
                return item.nodeValue
            }
        }
        return ""
    }

    private fun Element.findChild(localName: String): Element? {
        val children = childNodes ?: return null
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child is Element && child.localOrNodeName == localName) {
                return child
            }
        }
        return null
    }

    private fun Element.hasChild(localName: String): Boolean {
        return findChild(localName) != null
    }

    private fun Element.hasChildWithTruth(localName: String): Boolean {
        val child = findChild(localName) ?: return false
        val v = child.getAttr("val")
        return v.isEmpty() || v.equals("1", true) || v.equals("true", true)
    }

    private fun findDescendant(root: Element, predicate: (Element) -> Boolean): Element? {
        val children = root.childNodes ?: return null
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child is Element) {
                if (predicate(child)) return child
                val found = findDescendant(child, predicate)
                if (found != null) return found
            }
        }
        return null
    }

    private fun escapeHtml(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun escapeHtmlAttr(text: String): String {
        return escapeHtml(text).replace("\n", "").replace("\r", "")
    }
}
