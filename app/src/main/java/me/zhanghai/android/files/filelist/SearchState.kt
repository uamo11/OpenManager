/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

class SearchState(
    val isSearching: Boolean,
    val query: String,
    /** Show the results kept from the last search instead of searching again. */
    val reuseResults: Boolean = false
)
