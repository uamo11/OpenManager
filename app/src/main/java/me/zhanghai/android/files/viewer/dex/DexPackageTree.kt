/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.dex

/**
 * The classes of a DEX as folders by package, browsed like files (user request, as MT does,
 * instead of one flat list of thousands of classes). Built once per load.
 *
 * A package with no classes of its own and a single sub-package is shown joined to it
 * ("com.google.android"), so reaching the code takes fewer taps.
 */
class DexPackageTree<T>(items: List<T>, packageOf: (T) -> String) {

    /** One sub-package shown in a folder: [fullName] to open it, [label] relative to the parent. */
    data class Folder(val fullName: String, val label: String, val classCount: Int)

    private val classesByPackage: Map<String, List<T>> = items.groupBy(packageOf)
    private val children = HashMap<String, MutableSet<String>>()
    private val countUnder = HashMap<String, Int>()

    init {
        for ((pkg, classes) in classesByPackage) {
            var current = pkg
            while (true) {
                countUnder[current] = (countUnder[current] ?: 0) + classes.size
                if (current.isEmpty()) break
                val parent = parentOf(current)
                children.getOrPut(parent) { HashSet() }.add(current)
                current = parent
            }
        }
    }

    /** Sub-packages of [pkg] ("" is the root), by name. */
    fun folders(pkg: String): List<Folder> =
        (children[pkg] ?: emptySet()).map { child ->
            var shown = child
            while (classesByPackage[shown].isNullOrEmpty() && children[shown]?.size == 1) {
                shown = children.getValue(shown).first()
            }
            Folder(shown, if (pkg.isEmpty()) shown else shown.removePrefix("$pkg."), countUnder[shown] ?: 0)
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })

    /** Classes directly in [pkg]. */
    fun classes(pkg: String): List<T> = classesByPackage[pkg] ?: emptyList()

    /** Where Back goes from [pkg]: up past packages that were joined into their child. */
    fun parentShown(pkg: String): String {
        var parent = parentOf(pkg)
        while (parent.isNotEmpty() && classesByPackage[parent].isNullOrEmpty() && children[parent]?.size == 1) {
            parent = parentOf(parent)
        }
        return parent
    }

    companion object {
        fun parentOf(pkg: String): String = pkg.substringBeforeLast('.', "")
    }
}
