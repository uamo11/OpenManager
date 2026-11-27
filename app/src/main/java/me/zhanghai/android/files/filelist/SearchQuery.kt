/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import org.json.JSONObject

/**
 * A file search as MT's dialog describes it: a name (plain text, `*`/`?` wildcards or a regular
 * expression), whether to go into subfolders, case, a size range and text the file must contain.
 *
 * It travels through the search state as a string: a plain search stays the bare text (as before,
 * so the old fast path and saved state keep working); anything more is a marked JSON string.
 */
data class SearchQuery(
    val text: String,
    val recursive: Boolean = true,
    val caseSensitive: Boolean = false,
    val regex: Boolean = false,
    val minSize: Long? = null,
    val maxSize: Long? = null,
    val content: String? = null
) {
    val hasWildcards: Boolean get() = !regex && (text.contains('*') || text.contains('?'))

    /** Only the name, matched the old way (case-insensitive "contains"): providers do it fast. */
    val isPlain: Boolean
        get() = recursive && !caseSensitive && !regex && !hasWildcards && minSize == null &&
            maxSize == null && content.isNullOrEmpty()

    /** What the provider's own name search may filter on (empty: let everything through). */
    val providerText: String get() = if (isPlain) text else ""

    val needsContent: Boolean get() = !content.isNullOrEmpty()

    private val nameRegex: Regex? by lazy {
        val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        when {
            regex -> runCatching { Regex(text, options) }.getOrNull()
            hasWildcards -> Regex(globToRegex(text), options)
            else -> null
        }
    }

    /** A regular expression that does not compile; the dialog shows it instead of searching. */
    val isInvalid: Boolean get() = regex && runCatching { Regex(text) }.isFailure

    fun matchesName(name: String): Boolean = when {
        text.isEmpty() -> true
        regex -> nameRegex?.containsMatchIn(name) == true
        hasWildcards -> nameRegex?.matches(name) == true
        else -> name.contains(text, ignoreCase = !caseSensitive)
    }

    fun matchesSize(size: Long): Boolean =
        (minSize == null || size >= minSize) && (maxSize == null || size <= maxSize)

    fun matchesContent(fileText: CharSequence): Boolean {
        val needle = content ?: return true
        if (needle.isEmpty()) return true
        return if (regex) {
            val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            runCatching { Regex(needle, options).containsMatchIn(fileText) }.getOrDefault(false)
        } else {
            fileText.contains(needle, ignoreCase = !caseSensitive)
        }
    }

    fun encode(): String {
        if (isPlain) return text
        return MARKER + JSONObject().apply {
            put("text", text)
            put("recursive", recursive)
            put("caseSensitive", caseSensitive)
            put("regex", regex)
            minSize?.let { put("minSize", it) }
            maxSize?.let { put("maxSize", it) }
            content?.let { put("content", it) }
        }.toString()
    }

    companion object {
        private const val MARKER = "\u0000omq:"

        /** A plain string from before (or from a plain search) is a name search. */
        fun decode(query: String): SearchQuery {
            if (!query.startsWith(MARKER)) return SearchQuery(query)
            return runCatching {
                val json = JSONObject(query.substring(MARKER.length))
                SearchQuery(
                    text = json.optString("text"),
                    recursive = json.optBoolean("recursive", true),
                    caseSensitive = json.optBoolean("caseSensitive", false),
                    regex = json.optBoolean("regex", false),
                    minSize = if (json.has("minSize")) json.getLong("minSize") else null,
                    maxSize = if (json.has("maxSize")) json.getLong("maxSize") else null,
                    content = if (json.has("content")) json.getString("content") else null
                )
            }.getOrElse { SearchQuery(query.substring(MARKER.length)) }
        }

        /** `*.png` matches a whole name; `*` is any run of characters and `?` one character. */
        fun globToRegex(glob: String): String = buildString {
            for (c in glob) {
                when (c) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    else -> append(Regex.escape(c.toString()))
                }
            }
        }

        /** "500K", "2 MB", "1.5g", "300" (bytes): null when empty or not a size. */
        fun parseSize(input: String): Long? {
            val s = input.trim().lowercase().replace(" ", "")
            if (s.isEmpty()) return null
            val match = Regex("""^(\d+(?:[.,]\d+)?)([kmgt]?)b?$""").matchEntire(s) ?: return null
            val number = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            val multiplier = when (match.groupValues[2]) {
                "k" -> 1L shl 10
                "m" -> 1L shl 20
                "g" -> 1L shl 30
                "t" -> 1L shl 40
                else -> 1L
            }
            return (number * multiplier).toLong()
        }
    }
}
