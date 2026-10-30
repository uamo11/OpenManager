/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

/**
 * Defensive utility to escape shell arguments according to POSIX specifications.
 * Encloses the argument in single quotes and replaces internal single quotes with '\''
 * preventing any shell injection, parameter injection, or command substitution.
 */
object ShellEscapeUtils {
    @JvmStatic
    fun escape(arg: String): String {
        return "'" + arg.replace("'", "'\\''") + "'"
    }
}

fun String.escapeShellArg(): String = ShellEscapeUtils.escape(this)
