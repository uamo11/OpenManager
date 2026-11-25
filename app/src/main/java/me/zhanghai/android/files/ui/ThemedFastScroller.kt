/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ui

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import me.zhanghai.android.fastscroll.FastScroller
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import me.zhanghai.android.fastscroll.ThumbOnlyViewHelper

object ThemedFastScroller {
    /** Lists scroll by the thumb only; a swipe elsewhere along the edge scrolls them normally. */
    fun create(view: ViewGroup): FastScroller = FastScrollerBuilder(view)
        .apply { if (view is RecyclerView) setViewHelper(ThumbOnlyViewHelper(view)) }
        .build()
}
