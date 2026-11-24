/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import androidx.annotation.StringRes
import me.zhanghai.android.files.app.application

/**
 * A translated text for code without a Context (file providers, engines, services). Some of it
 * also runs in the root/Shizuku helper process, where the app may not be set up: then the
 * English [fallback] is used instead of failing.
 */
fun localizedText(@StringRes res: Int, fallback: String, vararg args: Any?): String =
    try {
        application.getString(res, *args)
    } catch (_: Throwable) {
        if (args.isEmpty()) fallback else runCatching { String.format(fallback, *args) }.getOrDefault(fallback)
    }
