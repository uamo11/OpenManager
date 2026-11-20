/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ui

import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback

class ListDiffer<T>(
    private val updateCallback: ListUpdateCallback,
    private val diffCallback: DiffUtil.ItemCallback<T>
) {
    private var _list: List<T> = emptyList()
    var list: List<T>
        get() = _list
        set(newList) {
            if (newList === _list || newList.isEmpty() && _list.isEmpty()) {
                return
            }
            if (newList.isEmpty()) {
                val oldListSize = _list.size
                _list = emptyList()
                updateCallback.onRemoved(0, oldListSize)
                return
            }
            if (_list.isEmpty()) {
                _list = newList
                updateCallback.onInserted(0, newList.size)
                return
            }
            val oldList = _list
            if (isUnchanged(oldList, newList)) {
                _list = newList
                return
            }
            if (oldList.size + newList.size > SYNC_DIFF_MAX_COMBINED) {
                _list = newList
                dispatchRangeFallback(oldList.size, newList.size)
                return
            }
            val result = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int {
                    return oldList.size
                }

                override fun getNewListSize(): Int {
                    return newList.size
                }

                override fun areItemsTheSame(
                    oldItemPosition: Int,
                    newItemPosition: Int
                ): Boolean {
                    val oldItem: T? = oldList[oldItemPosition]
                    val newItem: T? = newList[newItemPosition]
                    return if (oldItem != null && newItem != null) {
                        diffCallback.areItemsTheSame(oldItem, newItem)
                    } else {
                        oldItem == null && newItem == null
                    }
                }

                override fun areContentsTheSame(
                    oldItemPosition: Int,
                    newItemPosition: Int
                ): Boolean {
                    val oldItem: T? = oldList[oldItemPosition]
                    val newItem: T? = newList[newItemPosition]
                    return if (oldItem != null && newItem != null) {
                        diffCallback.areContentsTheSame(oldItem, newItem)
                    } else if (oldItem == null && newItem == null) {
                        true
                    } else {
                        throw AssertionError()
                    }
                }

                override fun getChangePayload(
                    oldItemPosition: Int,
                    newItemPosition: Int
                ): Any? {
                    val oldItem: T? = oldList[oldItemPosition]
                    val newItem: T? = newList[newItemPosition]
                    return if (oldItem != null && newItem != null) {
                        diffCallback.getChangePayload(oldItem, newItem)
                    } else {
                        throw AssertionError()
                    }
                }
            })
            _list = newList
            result.dispatchUpdatesTo(updateCallback)
        }

    fun replacePrecomputed(newList: List<T>, precomputedDiff: DiffUtil.DiffResult?) {
        if (newList === _list || (newList.isEmpty() && _list.isEmpty())) {
            return
        }
        if (newList.isEmpty()) {
            val oldListSize = _list.size
            _list = emptyList()
            updateCallback.onRemoved(0, oldListSize)
            return
        }
        if (_list.isEmpty()) {
            _list = newList
            updateCallback.onInserted(0, newList.size)
            return
        }
        val oldList = _list
        if (isUnchanged(oldList, newList)) {
            _list = newList
            return
        }
        _list = newList
        if (precomputedDiff != null) {
            precomputedDiff.dispatchUpdatesTo(updateCallback)
        } else {
            dispatchRangeFallback(oldList.size, newList.size)
        }
    }

    private fun isUnchanged(oldList: List<T>, newList: List<T>): Boolean {
        val size = oldList.size
        if (size != newList.size) return false
        for (i in 0 until size) {
            val oldItem = oldList[i]
            val newItem = newList[i]
            if (oldItem !== newItem) {
                if (oldItem == null || newItem == null ||
                    !diffCallback.areItemsTheSame(oldItem, newItem) ||
                    !diffCallback.areContentsTheSame(oldItem, newItem)
                ) {
                    return false
                }
            }
        }
        return true
    }

    private fun dispatchRangeFallback(oldSize: Int, newSize: Int) {
        if (oldSize == newSize) {
            updateCallback.onChanged(0, newSize, null)
        } else {
            val common = minOf(oldSize, newSize)
            if (common > 0) {
                updateCallback.onChanged(0, common, null)
            }
            if (newSize > oldSize) {
                updateCallback.onInserted(oldSize, newSize - oldSize)
            } else if (oldSize > newSize) {
                updateCallback.onRemoved(newSize, oldSize - newSize)
            }
        }
    }

    companion object {
        private const val SYNC_DIFF_MAX_COMBINED = 400
    }
}
