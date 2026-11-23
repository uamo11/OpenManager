/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import java8.nio.file.Path
import me.zhanghai.android.files.provider.common.exists
import me.zhanghai.android.files.provider.common.moveTo
import java.io.IOException
import java.util.Locale

/**
 * Renames many files at once from a pattern: a template with tokens, an optional find/replace
 * (plain or regex), a letter case and a counter. Pure planning plus a two-step apply, so names can
 * be swapped or shifted ("1.jpg" -> "2.jpg" -> "3.jpg") without overwriting anything.
 */
object BatchRenameEngine {

    enum class LetterCase { KEEP, LOWER, UPPER, TITLE }

    data class Rule(
        /** {name} = old name without extension, {ext} = extension, {n} = counter. */
        val template: String = "{name}",
        val find: String = "",
        val replace: String = "",
        val regex: Boolean = false,
        val ignoreCase: Boolean = false,
        val letterCase: LetterCase = LetterCase.KEEP,
        val start: Int = 1,
        val step: Int = 1,
        val padding: Int = 0,
        /** Keep the old extension after the template (the template then only shapes the base name). */
        val keepExtension: Boolean = true
    )

    enum class Problem { EMPTY, INVALID_CHARACTER, DUPLICATE, EXISTS, BAD_REGEX }

    data class Item(val oldName: String, val newName: String, val problem: Problem?) {
        val changed: Boolean get() = oldName != newName
    }

    private const val TOKEN_NAME = "{name}"
    private const val TOKEN_EXT = "{ext}"
    private const val TOKEN_N = "{n}"

    fun splitExtension(name: String, isDirectory: Boolean = false): Pair<String, String> {
        if (isDirectory) return name to ""
        val dot = name.lastIndexOf('.')
        // ".bashrc" is a name, not an extension.
        return if (dot <= 0 || dot == name.length - 1) name to "" else name.substring(0, dot) to name.substring(dot + 1)
    }

    /**
     * New names for [oldNames] in order. [directories] marks entries without extension handling;
     * [otherNamesInFolder] are the files that are not being renamed (a new name must not hit them).
     */
    fun plan(
        oldNames: List<String>,
        rule: Rule,
        directories: Set<String> = emptySet(),
        otherNamesInFolder: Set<String> = emptySet()
    ): List<Item> {
        val pattern = if (rule.find.isNotEmpty() && rule.regex) {
            try {
                Regex(rule.find, if (rule.ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
            } catch (_: Exception) {
                return oldNames.map { Item(it, it, Problem.BAD_REGEX) }
            }
        } else null

        val newNames = oldNames.mapIndexed { index, old ->
            val (base, ext) = splitExtension(old, old in directories)
            var replaced = when {
                rule.find.isEmpty() -> base
                pattern != null -> try {
                    base.replace(pattern, rule.replace)
                } catch (_: Exception) {
                    // "$3" with fewer groups, for example.
                    base
                }
                else -> base.replace(rule.find, rule.replace, ignoreCase = rule.ignoreCase)
            }
            val counter = (rule.start + index * rule.step).let { n ->
                if (rule.padding > 0) n.toString().padStart(rule.padding, '0') else n.toString()
            }
            val template = rule.template.ifEmpty { TOKEN_NAME }
            replaced = template
                .replace(TOKEN_NAME, replaced)
                .replace(TOKEN_EXT, ext)
                .replace(TOKEN_N, counter)
            replaced = applyCase(replaced, rule.letterCase)
            val withExt = if (rule.keepExtension && ext.isNotEmpty() && !template.contains(TOKEN_EXT)) {
                "$replaced.$ext"
            } else {
                replaced
            }
            withExt.trim()
        }

        // Android's shared storage ignores letter case: "Photo.jpg" and "photo.jpg" are one file.
        val renamedSet = oldNames.map { it.lowercase(Locale.ROOT) }.toSet()
        val others = otherNamesInFolder.map { it.lowercase(Locale.ROOT) }.toSet()
        val counts = newNames.groupingBy { it.lowercase(Locale.ROOT) }.eachCount()
        return oldNames.indices.map { i ->
            val newName = newNames[i]
            val problem = when {
                newName.isEmpty() || newName == "." || newName == ".." -> Problem.EMPTY
                newName.contains('/') || newName.contains('\u0000') -> Problem.INVALID_CHARACTER
                (counts[newName.lowercase(Locale.ROOT)] ?: 0) > 1 -> Problem.DUPLICATE
                newName.lowercase(Locale.ROOT).let { it in others && it !in renamedSet } -> Problem.EXISTS
                else -> null
            }
            Item(oldNames[i], newName, problem)
        }
    }

    private fun applyCase(text: String, case: LetterCase): String = when (case) {
        LetterCase.KEEP -> text
        LetterCase.LOWER -> text.lowercase(Locale.ROOT)
        LetterCase.UPPER -> text.uppercase(Locale.ROOT)
        LetterCase.TITLE -> text.split(' ').joinToString(" ") { word ->
            word.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
        }
    }

    /**
     * Renames in two steps (old -> temporary -> new) so a chain or a swap never overwrites a file.
     * On failure, what was already moved is put back. Returns how many files changed name.
     */
    @Throws(IOException::class)
    fun apply(folder: Path, items: List<Item>): Int {
        val todo = items.filter { it.changed }
        if (todo.any { it.problem != null }) throw IOException("The plan has conflicts")
        val stamp = System.currentTimeMillis()
        val staged = mutableListOf<Pair<Path, Item>>()
        try {
            todo.forEachIndexed { i, item ->
                val temp = folder.resolve(".om_rename_${stamp}_$i")
                folder.resolve(item.oldName).moveTo(temp)
                staged += temp to item
            }
        } catch (e: Exception) {
            for ((temp, item) in staged.asReversed()) runCatching { temp.moveTo(folder.resolve(item.oldName)) }
            throw IOException(e.message ?: e.toString(), e)
        }
        val done = mutableListOf<Pair<Path, Item>>()
        try {
            for ((temp, item) in staged) {
                val target = folder.resolve(item.newName)
                if (target.exists()) throw IOException("${item.newName} already exists")
                temp.moveTo(target)
                done += temp to item
            }
        } catch (e: Exception) {
            for ((_, item) in done.asReversed()) {
                runCatching { folder.resolve(item.newName).moveTo(folder.resolve(item.oldName)) }
            }
            for ((temp, item) in staged) {
                if (done.none { it.second === item }) runCatching { temp.moveTo(folder.resolve(item.oldName)) }
            }
            throw IOException(e.message ?: e.toString(), e)
        }
        return done.size
    }
}
