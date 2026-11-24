/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java8.nio.file.Path
import me.zhanghai.android.files.R
import me.zhanghai.android.files.navigation.BookmarkDirectories
import me.zhanghai.android.files.navigation.BookmarkDirectory
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.theme.custom.OpenManagerPalette

/**
 * The bookmarks, MT Manager style: swipe up on the bottom bar (or ⋮ > Bookmarks, or long press
 * on home). Tap opens the folder in the panel the sheet was opened for, drag reorders, ⋮ edits,
 * removes or opens in the other panel; the top row bookmarks the folder that panel is in.
 */
class BookmarksSheetFragment : BottomSheetDialogFragment() {

    private val panelIndex: Int
        get() = arguments?.getInt(ARG_PANEL) ?: 0

    private val host: FileListActivity?
        get() = activity as? FileListActivity

    private lateinit var palette: OpenManagerPalette
    private lateinit var adapter: Adapter
    private var dragging = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.dialog_bookmarks, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        palette = OpenManagerPalette.from(requireContext())
        applyColors(view)
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val base = (12 * v.resources.displayMetrics.density).toInt()
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, base + navBars.bottom)
            insets
        }

        adapter = Adapter()
        val list = view.findViewById<RecyclerView>(R.id.rvBookmarks)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        ItemTouchHelper(DragCallback()).attachToRecyclerView(list)

        view.findViewById<View>(R.id.btnBookmarkCurrent).setOnClickListener { toggleCurrent() }

        Settings.BOOKMARK_DIRECTORIES.observe(viewLifecycleOwner) { bookmarks ->
            if (!dragging) adapter.submit(bookmarks)
            view.findViewById<View>(R.id.tvBookmarksEmpty).isVisible = bookmarks.isEmpty()
            updateCurrentRow(view)
        }
    }

    override fun onStart() {
        super.onStart()
        // Show the whole list at once instead of a half-open sheet.
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun currentPath(): Path? =
        runCatching { host?.panelFragment(panelIndex)?.currentPath }.getOrNull()

    private fun updateCurrentRow(view: View) {
        val path = currentPath()
        val row = view.findViewById<View>(R.id.btnBookmarkCurrent)
        row.isVisible = path != null
        path ?: return
        val saved = BookmarkDirectories.contains(path)
        view.findViewById<TextView>(R.id.tvBookmarkCurrent).setText(
            if (saved) R.string.om_bookmarks_remove_current else R.string.om_bookmarks_add_current
        )
        view.findViewById<TextView>(R.id.tvBookmarkCurrentPath).text = path.toUserFriendlyString()
        view.findViewById<ImageView>(R.id.ivBookmarkCurrent).setImageResource(
            if (saved) R.drawable.close_icon_white_24dp else R.drawable.add_icon_white_24dp
        )
    }

    private fun toggleCurrent() {
        val path = currentPath() ?: return
        if (BookmarkDirectories.contains(path)) {
            BookmarkDirectories.removeAt(path)
        } else {
            BookmarkDirectories.add(BookmarkDirectory(null, path))
        }
    }

    private fun open(bookmark: BookmarkDirectory, panel: Int) {
        host?.openBookmark(bookmark.path, panel)
        dismissAllowingStateLoss()
    }

    private fun showOptions(anchor: View, bookmark: BookmarkDirectory) {
        val activity = host ?: return
        val root = view ?: return
        BookmarkMenu.show(activity, anchor, bookmark, panelIndex, root) { dismissAllowingStateLoss() }
    }

    private fun applyColors(root: View) {
        // The layout is drawn for the default dark look; light and AMOLED take the palette.
        if (!palette.isDark || palette.isAmoled) root.setBackgroundColor(palette.dialogBg)
        root.findViewById<TextView>(R.id.tvBookmarksTitle).setTextColor(palette.textPrimary)
        root.findViewById<TextView>(R.id.tvBookmarksHint).setTextColor(palette.textSecondary)
        root.findViewById<TextView>(R.id.tvBookmarkCurrent).setTextColor(palette.accentColor)
        root.findViewById<TextView>(R.id.tvBookmarkCurrentPath).setTextColor(palette.textSecondary)
        root.findViewById<TextView>(R.id.tvBookmarksEmpty).setTextColor(palette.textSecondary)
        root.findViewById<View>(R.id.bookmarksDivider).setBackgroundColor(palette.dividerLineColor)
        ImageViewCompat.setImageTintList(
            root.findViewById(R.id.ivBookmarkCurrent), ColorStateList.valueOf(palette.accentColor)
        )
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        val items = mutableListOf<BookmarkDirectory>()

        init {
            setHasStableIds(true)
        }

        fun submit(bookmarks: List<BookmarkDirectory>) {
            items.clear()
            items += bookmarks
            notifyDataSetChanged()
        }

        fun move(from: Int, to: Int) {
            items.add(to, items.removeAt(from))
            notifyItemMoved(from, to)
        }

        override fun getItemId(position: Int): Long = items[position].id

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_bookmark, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val bookmark = items[position]
            holder.name.text = bookmark.name
            holder.path.text = bookmark.path.toUserFriendlyString()
            holder.itemView.setOnClickListener { open(bookmark, panelIndex) }
            holder.more.setOnClickListener { showOptions(it, bookmark) }
        }
    }

    private inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tvBookmarkName)
        val path: TextView = view.findViewById(R.id.tvBookmarkPath)
        val more: ImageButton = view.findViewById(R.id.btnBookmarkMore)

        init {
            name.setTextColor(palette.textPrimary)
            path.setTextColor(palette.textSecondary)
            ImageViewCompat.setImageTintList(
                view.findViewById(R.id.ivBookmarkIcon), ColorStateList.valueOf(palette.accentColor)
            )
            ImageViewCompat.setImageTintList(more, ColorStateList.valueOf(palette.iconTint))
        }
    }

    /**
     * Long press and drag reorders (saved when the finger lifts); a long press without moving
     * opens the bookmark's menu.
     */
    private inner class DragCallback :
        ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
        private var moved = false

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            dragging = true
            moved = true
            adapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            dragging = false
            if (moved) {
                moved = false
                BookmarkDirectories.setAll(adapter.items.toList())
            } else {
                // Held without moving: the bookmark's menu, like a long press in MT Manager.
                val position = viewHolder.bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    showOptions((viewHolder as Holder).more, adapter.items[position])
                }
            }
        }
    }

    companion object {
        const val TAG = "BookmarksSheet"
        private const val ARG_PANEL = "panel"

        fun newInstance(panelIndex: Int): BookmarksSheetFragment =
            BookmarksSheetFragment().apply {
                arguments = Bundle().apply { putInt(ARG_PANEL, panelIndex) }
            }
    }
}
