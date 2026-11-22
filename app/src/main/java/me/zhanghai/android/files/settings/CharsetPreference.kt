/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import android.content.Context
import android.util.AttributeSet
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes
import androidx.preference.ListPreference
import java.nio.charset.Charset

class CharsetPreference : ListPreference {
    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?, @AttrRes defStyleAttr: Int) : super(
        context, attrs, defStyleAttr
    )

    constructor(
        context: Context,
        attrs: AttributeSet?,
        @AttrRes defStyleAttr: Int,
        @StyleRes defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes)

    // Charset.availableCharsets() instantiates every ICU converter (~2.5 MiB of native heap), so
    // the list is only built when the dialog or the settings search actually needs it.
    private var charsetsLoaded = false

    init {
        summaryProvider = SummaryProvider<CharsetPreference> { preference ->
            val value = preference.value
            if (value.isNullOrEmpty()) {
                null
            } else {
                try {
                    Charset.forName(value).displayName()
                } catch (_: Exception) {
                    value
                }
            }
        }
    }

    private fun ensureCharsetsLoaded() {
        if (charsetsLoaded) {
            return
        }
        charsetsLoaded = true
        val charsets = Charset.availableCharsets()
        entries = charsets.values.map { it.displayName() }.toTypedArray<CharSequence>()
        entryValues = charsets.keys.toTypedArray<CharSequence>()
    }

    override fun getEntries(): Array<CharSequence>? {
        ensureCharsetsLoaded()
        return super.getEntries()
    }

    override fun getEntryValues(): Array<CharSequence>? {
        ensureCharsetsLoaded()
        return super.getEntryValues()
    }

    override fun findIndexOfValue(value: String?): Int {
        ensureCharsetsLoaded()
        return super.findIndexOfValue(value)
    }
}
