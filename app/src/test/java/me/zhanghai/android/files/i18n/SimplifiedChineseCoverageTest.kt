/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.i18n

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Issue #2: with the app in Simplified Chinese most screens stayed in English because
 * OpenManager's own strings only existed in English and Spanish. Every user-visible string must
 * have a Simplified Chinese version, with the same placeholders.
 */
class SimplifiedChineseCoverageTest {

    private val res: File = (File("src/main/res").takeIf { it.isDirectory } ?: File("app/src/main/res"))

    private val placeholder = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[sdfxXc%]""")

    private data class Entry(val type: String, val texts: List<String>)

    private fun load(dir: String): Map<String, Entry> {
        val result = mutableMapOf<String, Entry>()
        val folder = File(res, dir)
        folder.listFiles { f -> f.name.endsWith(".xml") }?.forEach { file ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = doc.documentElement.childNodes
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as? Element ?: continue
                if (e.tagName !in setOf("string", "plurals", "string-array")) continue
                if (e.getAttribute("translatable") == "false") continue
                val texts = if (e.tagName == "string") {
                    listOf(e.textContent)
                } else {
                    val items = e.getElementsByTagName("item")
                    (0 until items.length).map { items.item(it).textContent }
                }
                result[e.getAttribute("name")] = Entry(e.tagName, texts)
            }
        }
        return result
    }

    /** Preference keys, numbers, paths and format-only strings are not text to translate. */
    private fun needsTranslation(entry: Entry): Boolean = entry.texts.any { raw ->
        val v = raw.trim()
        v.any { it in 'A'..'Z' || it in 'a'..'z' } &&
            !Regex("""(pref_)?key_\w+""").matches(v) &&
            !v.startsWith("/") && v != "UTF-8" && v != "admin" &&
            !Regex("""%\d\$[sd]""").matches(v)
    }

    private fun placeholders(text: String) = placeholder.findAll(text).map { it.value }.sorted().toList()

    @Test
    fun everyVisibleStringHasAChineseVersion() {
        val base = load("values")
        val zh = load("values-zh-rCN")
        val missing = base.filter { (name, entry) -> name !in zh && needsTranslation(entry) }.keys.sorted()
        assertTrue("Missing Simplified Chinese strings (${missing.size}): ${missing.take(30)}", missing.isEmpty())
    }

    @Test
    fun chinesePlaceholdersMatchTheOriginal() {
        val base = load("values")
        val zh = load("values-zh-rCN")
        val wrong = zh.mapNotNull { (name, entry) ->
            val original = base[name] ?: return@mapNotNull null
            val expected = placeholders(original.texts.last())
            val bad = when (entry.type) {
                "string-array" -> entry.texts.size != original.texts.size
                else -> entry.texts.any { placeholders(it) != expected }
            }
            name.takeIf { bad }
        }
        assertTrue("Placeholders differ in: $wrong", wrong.isEmpty())
    }
}
