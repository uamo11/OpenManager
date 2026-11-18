/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import android.content.Context
import android.util.AttributeSet
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes
import rikka.preference.SimpleMenuPreference

/**
 * List preference extending [SimpleMenuPreference] that opens a rich,
 * themed, icon-backed choice dialog via [SettingsIconHelper].
 */
class IconMenuPreference : SimpleMenuPreference {

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

    override fun onClick() {
        val entries = entries
        if (entries == null || entries.isEmpty()) {
            super.onClick()
            return
        }
        val specs = SettingsIconHelper.buildSpecsForPreference(context, key, entries.size)
        SettingsIconHelper.showIconChoiceDialog(context, this, specs)
    }
}
