/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.res.ColorStateList
import android.text.TextUtils
import android.util.LruCache
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import java8.nio.file.Path
import kotlin.math.abs
import me.zhanghai.android.fastscroll.PopupTextProvider
import me.zhanghai.android.files.R
import me.zhanghai.android.files.coil.AppIconPackageName
import me.zhanghai.android.files.compat.foregroundCompat
import me.zhanghai.android.files.compat.getDrawableCompat
import me.zhanghai.android.files.databinding.FileItemGridBinding
import me.zhanghai.android.files.databinding.FileItemListBinding
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.formatMtDateWithOptions
import me.zhanghai.android.files.file.isApk
import me.zhanghai.android.files.provider.archive.isArchivePath
import me.zhanghai.android.files.provider.common.PosixFileAttributes
import me.zhanghai.android.files.provider.common.isEncrypted
import me.zhanghai.android.files.provider.common.toModeString
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.settings.SettingsUiLogic
import me.zhanghai.android.files.theme.custom.OpenManagerPalette
import me.zhanghai.android.files.ui.AnimatedListAdapter
import me.zhanghai.android.files.ui.CheckableForegroundLinearLayout
import me.zhanghai.android.files.ui.CheckableItemBackground
import me.zhanghai.android.files.util.isMaterial3Theme
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.valueCompat
import java.time.Instant
import java.util.Locale

class FileListAdapter(
    private val listener: Listener
) : AnimatedListAdapter<FileItem, FileListAdapter.ViewHolder>(CALLBACK), PopupTextProvider {
    private var isSearching = false

    private lateinit var _viewType: FileViewType
    var viewType: FileViewType
        get() = _viewType
        set(value) {
            _viewType = value
            if (!isSearching) {
                super.replace(list, true)
            }
        }

    private lateinit var _sortOptions: FileSortOptions
    var sortOptions: FileSortOptions
        get() = _sortOptions
        set(value) {
            _sortOptions = value
            if (!isSearching) {
                val parentItem = list.firstOrNull { it.isParentDirectoryItem }
                val prepared = FileListEngine.prepareDisplayList(
                    rawFiles = list,
                    showHidden = true,
                    sortOptions = value,
                    isSearching = false,
                    parentItem = parentItem,
                    previousList = list,
                    computeDiff = true
                )
                applyPreparedList(
                    prepared = prepared,
                    isSearching = false,
                    isSharedStoragePath = currentPathIsSharedStorage
                )
            }
        }

    fun setSortOptionsWithoutResort(value: FileSortOptions) {
        _sortOptions = value
    }

    var pickOptions: PickOptions? = null
        set(value) {
            field = value
            notifyItemRangeChanged(0, itemCount, PAYLOAD_STATE_CHANGED)
        }

    private val selectedFiles = fileItemSetOf()

    private val filePositionMap = HashMap<Path, Int>(64)

    private var currentPathIsSharedStorage: Boolean = false

    private val dateFormatCache = LruCache<Long, String>(512)
    private val descriptionCache = LruCache<FileItem, String>(1024)

    var timePreference: String = "1"
        set(value) {
            if (field != value) {
                field = value
                dateFormatCache.evictAll()
                descriptionCache.evictAll()
                if (itemCount > 0) {
                    refreshVisibleItems()
                }
            }
        }

    var dateTimeFormat: String = "0"
        set(value) {
            if (field != value) {
                field = value
                dateFormatCache.evictAll()
                descriptionCache.evictAll()
                if (itemCount > 0) {
                    refreshVisibleItems()
                }
            }
        }

    fun initDateTimeOptions(timePref: String, dateFormat: String) {
        timePreference = timePref
        dateTimeFormat = dateFormat
    }

    private var styleVersion: Int = 0
    private var cachedSecondaryTintList: ColorStateList? = null
    private var cachedDensity: Float = -1f
    private var cachedSizeForDims: Int = -1
    private var cachedRowHeightPx: Int = 0
    private var cachedIconContainerPx: Int = 0
    private var cachedIconPx: Int = 0
    private var cachedNamePx: Float = 0f
    private var cachedDescPx: Float = 0f
    private var cachedNameSp: Float = 13f
    private var cachedDescSp: Float = 11f
    private var cachedGridThumbPx: Int = 0
    private var cachedLargeIconPx: Int = 0
    private var cachedBadgePx: Int = 0

    private lateinit var _nameEllipsize: TextUtils.TruncateAt
    var nameEllipsize: TextUtils.TruncateAt
        get() = _nameEllipsize
        set(value) {
            if (!this::_nameEllipsize.isInitialized || _nameEllipsize != value) {
                _nameEllipsize = value
                styleVersion++
                if (itemCount > 0) {
                    notifyItemRangeChanged(0, itemCount, PAYLOAD_STATE_CHANGED)
                }
            }
        }

    var maxLines: Int = 2
        set(value) {
            if (field != value) {
                field = value
                styleVersion++
                if (itemCount > 0) {
                    notifyItemRangeChanged(0, itemCount, PAYLOAD_STATE_CHANGED)
                }
            }
        }

    var fileListSize: Int = 0
        set(value) {
            if (field != value) {
                field = value
                styleVersion++
                cachedSizeForDims = -1
                if (itemCount > 0) {
                    notifyItemRangeChanged(0, itemCount, PAYLOAD_STATE_CHANGED)
                }
            }
        }

    var palette: OpenManagerPalette? = null
        set(value) {
            if (field != value) {
                field = value
                cachedSecondaryTintList = value?.let { ColorStateList.valueOf(it.textSecondary) }
                styleVersion++
                if (itemCount > 0) {
                    notifyItemRangeChanged(0, itemCount, PAYLOAD_STATE_CHANGED)
                }
            }
        }

    var disablePermissionInFileList: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                descriptionCache.evictAll()
                if (itemCount > 0) {
                    notifyItemRangeChanged(0, itemCount, PAYLOAD_CONTENT_REFRESH)
                }
            }
        }

    var thumbnailLoadMode: Int = 0
        set(value) {
            if (field != value) {
                field = value
                if (itemCount > 0) {
                    notifyItemRangeChanged(0, itemCount, PAYLOAD_CONTENT_REFRESH)
                }
            }
        }

    private var _isFlinging: Boolean = false
    var isFlinging: Boolean
        get() = _isFlinging
        set(value) {
            updateFlingState(value)
        }

    fun updateFlingState(flinging: Boolean, firstVisible: Int = -1, lastVisible: Int = -1) {
        if (_isFlinging == flinging) return
        val stoppedFlinging = _isFlinging && !flinging
        _isFlinging = flinging
        if (stoppedFlinging && FileListEngine.shouldDeferThumbnailsDuringFling(thumbnailLoadMode, itemCount)) {
            val range = SettingsUiLogic.computeVisibleRefreshRange(firstVisible, lastVisible, itemCount)
            if (range != null) {
                notifyItemRangeChanged(
                    range.first, range.last - range.first + 1, PAYLOAD_CONTENT_REFRESH
                )
            } else {
                notifyItemRangeChanged(0, itemCount, PAYLOAD_CONTENT_REFRESH)
            }
        }
    }

    var visualPerformanceMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                styleVersion++
                if (itemCount > 0) {
                    notifyItemRangeChanged(0, itemCount, PAYLOAD_STATE_CHANGED)
                }
            }
        }

    var highlightedPath: Path? = null

    fun refreshVisibleItems() {
        notifyItemRangeChanged(0, itemCount, PAYLOAD_CONTENT_REFRESH)
    }

    fun getPositionForPath(path: Path): Int = filePositionMap[path] ?: -1

    fun replaceSelectedFiles(files: FileItemSet) {
        val changedFiles = fileItemSetOf()
        val iterator = selectedFiles.iterator()
        while (iterator.hasNext()) {
            val file = iterator.next()
            if (file !in files) {
                iterator.remove()
                changedFiles.add(file)
            }
        }
        for (file in files) {
            if (file !in selectedFiles) {
                selectedFiles.add(file)
                changedFiles.add(file)
            }
        }
        for (file in changedFiles) {
            val position = filePositionMap[file.path]
            position?.let { notifyItemChanged(it, PAYLOAD_STATE_CHANGED) }
        }
    }

    private fun selectFile(file: FileItem) {
        if (file.isParentDirectoryItem || !isFileSelectable(file)) {
            return
        }
        val selected = file in selectedFiles
        val pickOptions = pickOptions
        if (!selected && pickOptions != null && !pickOptions.allowMultiple) {
            listener.clearSelectedFiles()
        }
        listener.selectFile(file, !selected)
    }

    fun selectAllFiles() {
        val files = fileItemSetOf()
        for (index in 0..<itemCount) {
            val file = getItem(index)
            if (!file.isParentDirectoryItem && isFileSelectable(file)) {
                files.add(file)
            }
        }
        listener.selectFiles(files, true)
    }

    fun invertSelection() {
        val filesToSelect = fileItemSetOf()
        val filesToDeselect = fileItemSetOf()
        for (index in 0..<itemCount) {
            val file = getItem(index)
            if (!file.isParentDirectoryItem && isFileSelectable(file)) {
                if (file in selectedFiles) {
                    filesToDeselect.add(file)
                } else {
                    filesToSelect.add(file)
                }
            }
        }
        if (filesToDeselect.isNotEmpty()) {
            listener.selectFiles(filesToDeselect, false)
        }
        if (filesToSelect.isNotEmpty()) {
            listener.selectFiles(filesToSelect, true)
        }
    }

    fun getPositionForFile(file: FileItem): Int = filePositionMap[file.path] ?: -1

    fun getItemOrNull(position: Int): FileItem? =
        if (position in 0 until itemCount) getItem(position) else null

    fun getSelectableFilesInRange(fromPosition: Int, toPosition: Int): FileItemSet {
        val start = minOf(fromPosition, toPosition).coerceAtLeast(0)
        val end = maxOf(fromPosition, toPosition).coerceAtMost(itemCount - 1)
        val result = fileItemSetOf()
        for (i in start..end) {
            val file = getItem(i)
            if (!file.isParentDirectoryItem && isFileSelectable(file)) {
                result.add(file)
            }
        }
        return result
    }

    val selectableItemCount: Int
        get() {
            var count = 0
            for (i in 0 until itemCount) {
                val file = getItem(i)
                if (!file.isParentDirectoryItem && isFileSelectable(file)) {
                    count++
                }
            }
            return count
        }

    fun isFileSelectable(file: FileItem): Boolean {
        if (file.isParentDirectoryItem) {
            return false
        }
        val pickOptions = pickOptions ?: return true
        return when (pickOptions.mode) {
            PickOptions.Mode.OPEN_FILE, PickOptions.Mode.CREATE_FILE ->
                !file.attributes.isDirectory &&
                    pickOptions.mimeTypes.any { it.match(file.mimeType) }
            PickOptions.Mode.OPEN_DIRECTORY -> file.attributes.isDirectory
        }
    }

    override fun clear() {
        super.clear()

        rebuildFilePositionMap()
    }

    @Deprecated("", ReplaceWith("replaceListAndSearching(list, searching)"))
    override fun replace(list: List<FileItem>, clear: Boolean) {
        throw UnsupportedOperationException()
    }

    fun replaceListAndIsSearching(list: List<FileItem>, isSearching: Boolean) {
        val parentItem = if (!isSearching) list.firstOrNull { it.isParentDirectoryItem } else null
        val prepared = FileListEngine.prepareDisplayList(
            rawFiles = list,
            showHidden = true,
            sortOptions = sortOptions,
            isSearching = isSearching,
            parentItem = parentItem,
            previousList = this.list,
            computeDiff = true
        )
        applyPreparedList(prepared, isSearching, isSharedStoragePath = false)
    }

    fun applyPreparedList(
        prepared: FileListEngine.PreparedFileList,
        isSearching: Boolean,
        isSharedStoragePath: Boolean
    ) {
        val clear = this.isSearching != isSearching
        if (this.isSearching != isSearching || this.currentPathIsSharedStorage != isSharedStoragePath) {
            descriptionCache.evictAll()
        }
        this.isSearching = isSearching
        this.currentPathIsSharedStorage = isSharedStoragePath
        filePositionMap.clear()
        filePositionMap.putAll(prepared.positionMap)
        super.replacePrecomputed(prepared.displayList, prepared.diffResult, clear)
    }

    private fun rebuildFilePositionMap() {
        filePositionMap.clear()
        for (index in 0..<itemCount) {
            val file = getItem(index)
            filePositionMap[file.path] = index
        }
    }

    override fun getItemViewType(position: Int): Int = viewType.ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val viewType = FileViewType.entries[viewType]
        val inflater = parent.context.layoutInflater
        val holder = when (viewType) {
            FileViewType.LIST -> ViewHolder(FileItemListBinding.inflate(inflater, parent, false))
            FileViewType.GRID -> ViewHolder(FileItemGridBinding.inflate(inflater, parent, false))
        }
        return holder.apply {
            itemLayout.apply {
                val context = context
                val isMaterial3Theme = context.isMaterial3Theme
                if (viewType == FileViewType.GRID && isMaterial3Theme) {
                    foregroundCompat =
                        context.getDrawableCompat(R.drawable.file_item_grid_foreground_material3)
                }
                background = if (viewType == FileViewType.GRID && isMaterial3Theme) {
                    CheckableItemBackground.create(4f, 12f, context)
                } else {
                    CheckableItemBackground.create(0f, 0f, context)
                }
            }
            thumbnailOutlineView?.apply {
                val context = context
                if (context.isMaterial3Theme) {
                    background = context.getDrawableCompat(
                        R.drawable.file_item_grid_thumbnail_outline_material3
                    )
                }
            }
            itemLayout.setOnClickListener {
                if (isParentItem) {
                    listener.navigateUp()
                } else {
                    val bound = boundFile ?: return@setOnClickListener
                    if (selectedFiles.isEmpty()) {
                        listener.openFile(bound)
                    } else {
                        selectFile(bound)
                    }
                }
            }
            itemLayout.setOnLongClickListener {
                if (isParentItem) {
                    false
                } else {
                    val bound = boundFile ?: return@setOnLongClickListener false
                    listener.showMtMenu(bound)
                    true
                }
            }
            iconLayout.setOnClickListener {
                if (isParentItem) {
                    listener.navigateUp()
                } else {
                    val bound = boundFile ?: return@setOnClickListener
                    selectFile(bound)
                }
            }
            menuButton.setOnClickListener {
                val bound = boundFile ?: return@setOnClickListener
                showItemPopupMenu(this, bound)
            }
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        throw UnsupportedOperationException()
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: List<Any>) {
        val file = getItem(position)
        val isParent = file.isParentDirectoryItem
        val isDirectory = file.attributes.isDirectory
        val isEnabled = isFileSelectable(file) || isDirectory
        if (holder.itemLayout.isEnabled != isEnabled) {
            holder.itemLayout.isEnabled = isEnabled
        }
        if (holder.menuButton.isVisible) {
            holder.menuButton.isVisible = false
        }
        val checked = file in selectedFiles
        if (holder.itemLayout.isChecked != checked) {
            holder.itemLayout.isChecked = checked
        }
        applySizeAndTheme(holder, isParent)
        if (isParent) {
            holder.isParentItem = true
            holder.boundFile = null
            if (!holder.itemLayout.isEnabled) {
                holder.itemLayout.isEnabled = true
            }
            if (holder.itemLayout.isChecked) {
                holder.itemLayout.isChecked = false
            }
            holder.nameText.text = ".."
            holder.descriptionText?.isVisible = false
            holder.iconImage.apply {
                isVisible = true
                if (holder.boundIconRes != R.drawable.file_parent_directory_icon) {
                    holder.boundIconRes = R.drawable.file_parent_directory_icon
                    setImageResource(R.drawable.file_parent_directory_icon)
                }
            }
            holder.directoryThumbnailImage?.isVisible = false
            holder.thumbnailOutlineView?.isVisible = false
            holder.thumbnailIconImage?.apply {
                if (holder.hasActiveThumbnailIcon) {
                    dispose()
                    holder.hasActiveThumbnailIcon = false
                    setImageDrawable(null)
                }
                isVisible = false
            }
            holder.thumbnailImage.apply {
                if (holder.hasActiveThumbnail) {
                    dispose()
                    holder.hasActiveThumbnail = false
                    setImageDrawable(null)
                }
                isVisible = false
            }
            holder.appIconBadgeImage.apply {
                if (holder.hasActiveAppBadge) {
                    dispose()
                    holder.hasActiveAppBadge = false
                    setImageDrawable(null)
                }
                isVisible = false
            }
            if (holder.boundBadgeRes != 0) {
                holder.boundBadgeRes = 0
                holder.badgeImage.setImageDrawable(null)
            }
            holder.badgeImage.isVisible = false
            return
        }
        holder.isParentItem = false
        holder.boundFile = file
        if (payloads.isNotEmpty() && payloads.none { it === PAYLOAD_CONTENT_REFRESH }) {
            return
        }
        if (payloads.isEmpty()) {
            bindViewHolderAnimation(holder)
        }
        val attributes = file.attributes
        val isSymlink = file.attributesNoFollowLinks.isSymbolicLink
        if (isSymlink) {
            holder.nameText.text = "${file.name} ->"
        } else {
            holder.nameText.text = file.name
        }

        val appDirectoryPackageName = file.appDirectoryPackageName
        val appLabel = if (isDirectory && appDirectoryPackageName != null) {
            val cached = AppMetadataCache.getCachedAppLabel(appDirectoryPackageName)
            if (cached == null && !AppMetadataCache.hasLabelEntry(appDirectoryPackageName)) {
                AppMetadataCache.prefetchAppLabelAsync(holder.itemView.context, appDirectoryPackageName) {
                    descriptionCache.remove(file)
                    filePositionMap[file.path]?.let { pos ->
                        if (pos in 0 until itemCount) {
                            notifyItemChanged(pos, PAYLOAD_CONTENT_REFRESH)
                        }
                    }
                }
            }
            cached
        } else {
            null
        }

        val path = file.path
        holder.descriptionText?.let { descView ->
            if (!descView.isVisible) {
                descView.isVisible = true
            }
            val cachedDesc = descriptionCache[file]
            if (cachedDesc != null && (appDirectoryPackageName == null || appLabel != null)) {
                descView.text = cachedDesc
            } else {
                val timeMillis = attributes.lastModifiedTime().toMillis()
                val timeBucket = if (timePreference == "0") timeMillis / 1000L else timeMillis / 60_000L
                val lastModificationTime = dateFormatCache[timeBucket]
                    ?: Instant.ofEpochMilli(timeMillis)
                        .formatMtDateWithOptions(timePreference, dateTimeFormat)
                        .also { dateFormatCache.put(timeBucket, it) }
                val isSharedStorage = if (isSearching) {
                    val pathStr = path.toString()
                    pathStr.startsWith("/storage/") || pathStr.startsWith("/sdcard")
                } else {
                    currentPathIsSharedStorage
                }
                val permStr = if (!disablePermissionInFileList && !isSharedStorage) {
                    (attributes as? PosixFileAttributes)?.mode()?.toModeString()
                } else {
                    null
                }
                val baseDesc = if (isDirectory) {
                    if (appLabel != null) "$lastModificationTime  $appLabel" else lastModificationTime
                } else {
                    val sizeStr = FileListEngine.formatCompactFileSize(attributes.size())
                    "$lastModificationTime  $sizeStr"
                }
                val formattedDesc = if (permStr != null) "$baseDesc  $permStr" else baseDesc
                if (appDirectoryPackageName == null || AppMetadataCache.hasLabelEntry(appDirectoryPackageName)) {
                    descriptionCache.put(file, formattedDesc)
                }
                descView.text = formattedDesc
            }
        }

        val iconRes = FileIconHelper.getIconRes(file)
        holder.iconImage.apply {
            isVisible = true
            if (holder.boundIconRes != iconRes) {
                holder.boundIconRes = iconRes
                setImageResource(iconRes)
            }
        }
        holder.directoryThumbnailImage?.isVisible = isDirectory
        holder.thumbnailOutlineView?.isVisible = !isDirectory
        val deferForFling = isFlinging &&
            FileListEngine.shouldDeferThumbnailsDuringFling(thumbnailLoadMode, itemCount)
        val canDecodeBitmaps = thumbnailLoadMode != 2 && !deferForFling
        val supportsThumbnail = file.supportsThumbnail && canDecodeBitmaps
        val shouldLoadThumbnailIcon = supportsThumbnail && holder.thumbnailIconImage != null &&
            file.mimeType.isApk
        val allowCrossfade = !visualPerformanceMode
        ensureDimensionsCached(holder.itemView.resources.displayMetrics)
        val iconTargetPx = if (holder.descriptionText != null) cachedIconPx else cachedLargeIconPx
        val thumbTargetPx = if (holder.descriptionText != null) cachedIconPx else cachedGridThumbPx

        holder.thumbnailIconImage?.apply {
            if (holder.hasActiveThumbnailIcon) {
                dispose()
                holder.hasActiveThumbnailIcon = false
            }
            isVisible = !isDirectory
            setImageResource(iconRes)
            if (shouldLoadThumbnailIcon) {
                holder.hasActiveThumbnailIcon = true
                load(path to attributes) {
                    size(iconTargetPx, iconTargetPx)
                    crossfade(allowCrossfade)
                }
            }
        }
        holder.thumbnailImage.apply {
            if (holder.hasActiveThumbnail) {
                dispose()
                holder.hasActiveThumbnail = false
                setImageDrawable(null)
            }
            if (isDirectory && appLabel != null && appDirectoryPackageName != null && canDecodeBitmaps) {
                isVisible = true
                holder.hasActiveThumbnail = true
                load(AppIconPackageName(appDirectoryPackageName)) {
                    size(iconTargetPx, iconTargetPx)
                    crossfade(allowCrossfade)
                    listener(
                        onSuccess = { _, _ -> holder.iconImage.isVisible = false },
                        onError = { _, _ ->
                            holder.iconImage.isVisible = true
                            if (holder.boundIconRes != R.drawable.file_directory_icon) {
                                holder.boundIconRes = R.drawable.file_directory_icon
                                holder.iconImage.setImageResource(R.drawable.file_directory_icon)
                            }
                        }
                    )
                }
            } else {
                val shouldLoadThumbnail = supportsThumbnail && !shouldLoadThumbnailIcon
                isVisible = shouldLoadThumbnail
                if (shouldLoadThumbnail) {
                    holder.hasActiveThumbnail = true
                    load(path to attributes) {
                        size(thumbTargetPx, thumbTargetPx)
                        crossfade(allowCrossfade)
                        listener { _, _ ->
                            val targetIcon = holder.thumbnailIconImage ?: holder.iconImage
                            targetIcon.isVisible = false
                        }
                    }
                }
            }
        }
        holder.appIconBadgeImage.apply {
            if (holder.hasActiveAppBadge) {
                dispose()
                holder.hasActiveAppBadge = false
                setImageDrawable(null)
            }
            val hasAppIconBadge = canDecodeBitmaps && !isDirectory && appDirectoryPackageName != null
            isVisible = hasAppIconBadge
            if (hasAppIconBadge) {
                holder.hasActiveAppBadge = true
                load(AppIconPackageName(appDirectoryPackageName!!)) {
                    size(cachedBadgePx, cachedBadgePx)
                    crossfade(allowCrossfade)
                }
            }
        }
        holder.badgeImage.apply {
            val badgeIconRes = if (file.attributesNoFollowLinks.isSymbolicLink) {
                if (file.isSymbolicLinkBroken) {
                    R.drawable.error_badge_icon_18dp
                } else {
                    R.drawable.symbolic_link_badge_icon_18dp
                }
            } else if (file.attributesNoFollowLinks.isEncrypted()) {
                R.drawable.encrypted_badge_icon_18dp
            } else {
                0
            }
            val hasBadge = badgeIconRes != 0
            isVisible = hasBadge
            if (holder.boundBadgeRes != badgeIconRes) {
                holder.boundBadgeRes = badgeIconRes
                if (hasBadge) {
                    setImageResource(badgeIconRes)
                } else {
                    setImageDrawable(null)
                }
            }
        }
    }

    private fun showItemPopupMenu(holder: ViewHolder, file: FileItem) {
        val popup = holder.popupMenu ?: PopupMenu(holder.itemLayout.context, holder.itemLayout).apply {
            inflate(R.menu.file_item)
            holder.popupMenu = this
        }
        val menu = popup.menu
        val path = file.path
        val isDirectory = file.attributes.isDirectory
        val hasPickOptions = pickOptions != null
        val isReadOnly = path.fileSystem.isReadOnly
        val isArchivePath = path.isArchivePath
        menu.findItem(R.id.action_cut).isVisible = !hasPickOptions && !isReadOnly
        menu.findItem(R.id.action_copy).apply {
            isVisible = !hasPickOptions
            setTitle(if (isArchivePath) R.string.file_item_action_extract else R.string.copy)
        }
        menu.findItem(R.id.action_delete).isVisible = !isReadOnly
        menu.findItem(R.id.action_rename).isVisible = !isReadOnly
        menu.findItem(R.id.action_extract).isVisible = file.isArchiveFile
        menu.findItem(R.id.action_archive).isVisible = !isArchivePath
        menu.findItem(R.id.action_add_bookmark).isVisible = isDirectory
        menu.findItem(R.id.action_copy_opposite)?.isVisible = !hasPickOptions
        menu.findItem(R.id.action_move_opposite)?.isVisible = !hasPickOptions && !isReadOnly
        menu.findItem(R.id.action_swap_file_names)?.isVisible = !isReadOnly
        menu.findItem(R.id.action_split_file)?.isVisible = !isDirectory && !isArchivePath
        menu.findItem(R.id.action_merge_files)?.isVisible = FileSplitMergeHelper.isSplitPart(file)
        menu.findItem(R.id.action_create_symlink)?.isVisible = !isArchivePath
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_open_with -> {
                    listener.openFileWith(file)
                    true
                }
                R.id.action_copy_opposite -> {
                    listener.copyFileToOppositePanel(file)
                    true
                }
                R.id.action_move_opposite -> {
                    listener.moveFileToOppositePanel(file)
                    true
                }
                R.id.action_cut -> {
                    listener.cutFile(file)
                    true
                }
                R.id.action_copy -> {
                    listener.copyFile(file)
                    true
                }
                R.id.action_delete -> {
                    listener.confirmDeleteFile(file)
                    true
                }
                R.id.action_rename -> {
                    listener.showRenameFileDialog(file)
                    true
                }
                R.id.action_extract -> {
                    listener.extractFile(file)
                    true
                }
                R.id.action_archive -> {
                    listener.showCreateArchiveDialog(file)
                    true
                }
                R.id.action_share -> {
                    listener.shareFile(file)
                    true
                }
                R.id.action_copy_path -> {
                    listener.copyPath(file)
                    true
                }
                R.id.action_add_bookmark -> {
                    listener.addBookmark(file)
                    true
                }
                R.id.action_create_shortcut -> {
                    listener.createShortcut(file)
                    true
                }
                R.id.action_swap_file_names -> {
                    listener.swapFileNames(file)
                    true
                }
                R.id.action_split_file -> {
                    listener.splitFile(file)
                    true
                }
                R.id.action_merge_files -> {
                    listener.mergeFiles(file)
                    true
                }
                R.id.action_create_symlink -> {
                    listener.createSymlink(file)
                    true
                }
                R.id.action_permissions -> {
                    listener.showPermissionsDialog(file)
                    true
                }
                R.id.action_properties -> {
                    listener.showPropertiesDialog(file)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.boundFile = null
        holder.isParentItem = false
        if (holder.hasActiveThumbnail) {
            holder.thumbnailImage.dispose()
            holder.thumbnailImage.setImageDrawable(null)
            holder.hasActiveThumbnail = false
        }
        if (holder.hasActiveThumbnailIcon) {
            holder.thumbnailIconImage?.dispose()
            holder.thumbnailIconImage?.setImageDrawable(null)
            holder.hasActiveThumbnailIcon = false
        }
        if (holder.hasActiveAppBadge) {
            holder.appIconBadgeImage.dispose()
            holder.appIconBadgeImage.setImageDrawable(null)
            holder.hasActiveAppBadge = false
        }
    }

    override fun getPopupText(view: View, position: Int): CharSequence {
        val file = getItem(position)
        if (file.isParentDirectoryItem) {
            return ".."
        }
        return when (sortOptions.by) {
            FileSortOptions.By.NAME -> file.name.take(1).uppercase(Locale.getDefault())
            FileSortOptions.By.TYPE -> file.extension.uppercase(Locale.getDefault())
            FileSortOptions.By.SIZE -> FileListEngine.formatCompactFileSize(file.attributes.size())
            FileSortOptions.By.LAST_MODIFIED ->
                file.attributes.lastModifiedTime().toInstant()
                    .formatMtDateWithOptions(timePreference, dateTimeFormat)
        }
    }

    private fun ensureDimensionsCached(dm: android.util.DisplayMetrics) {
        val density = dm.density
        if (cachedDensity == density && cachedSizeForDims == fileListSize) {
            return
        }
        cachedDensity = density
        cachedSizeForDims = fileListSize
        when (fileListSize) {
            1 -> {
                cachedRowHeightPx = (52 * density).toInt()
                cachedIconContainerPx = (42 * density).toInt()
                cachedIconPx = (38 * density).toInt()
                cachedNameSp = 14f
                cachedDescSp = 11.5f
            }
            2 -> {
                cachedRowHeightPx = (62 * density).toInt()
                cachedIconContainerPx = (48 * density).toInt()
                cachedIconPx = (44 * density).toInt()
                cachedNameSp = 15.5f
                cachedDescSp = 12.5f
            }
            else -> {
                cachedRowHeightPx = (44 * density).toInt()
                cachedIconContainerPx = (36 * density).toInt()
                cachedIconPx = (32 * density).toInt()
                cachedNameSp = 13f
                cachedDescSp = 11f
            }
        }
        cachedNamePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, cachedNameSp, dm)
        cachedDescPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, cachedDescSp, dm)
        cachedGridThumbPx = (180 * density).toInt().coerceAtLeast(96)
        cachedLargeIconPx = (48 * density).toInt().coerceAtLeast(48)
        cachedBadgePx = (20 * density).toInt().coerceAtLeast(24)
    }

    private fun applySizeAndTheme(holder: ViewHolder, isParentDir: Boolean) {
        if (holder.appliedStyleVersion == styleVersion && holder.appliedIsParentStyle == isParentDir) {
            return
        }
        val currentPalette = palette
        if (currentPalette != null) {
            holder.nameText.setTextColor(
                if (isParentDir) currentPalette.textSecondary else currentPalette.filePrimaryText
            )
            holder.descriptionText?.setTextColor(currentPalette.textSecondary)
            ImageViewCompat.setImageTintList(
                holder.menuButton,
                cachedSecondaryTintList ?: ColorStateList.valueOf(currentPalette.textSecondary)
            )
        }
        holder.nameText.apply {
            val targetMaxLines = this@FileListAdapter.maxLines
            if (maxLines != targetMaxLines) {
                maxLines = targetMaxLines
            }
            if (targetMaxLines == 1) {
                val targetEllipsize = if (
                    visualPerformanceMode && nameEllipsize == TextUtils.TruncateAt.MARQUEE
                ) {
                    TextUtils.TruncateAt.END
                } else {
                    nameEllipsize
                }
                if (ellipsize != targetEllipsize) {
                    ellipsize = targetEllipsize
                }
                val targetSelected =
                    !visualPerformanceMode && targetEllipsize == TextUtils.TruncateAt.MARQUEE
                if (isSelected != targetSelected) {
                    isSelected = targetSelected
                }
            } else {
                if (ellipsize != TextUtils.TruncateAt.END) {
                    ellipsize = TextUtils.TruncateAt.END
                }
                if (isSelected) {
                    isSelected = false
                }
            }
        }
        if (holder.descriptionText != null) {
            ensureDimensionsCached(holder.itemView.resources.displayMetrics)
            updateViewSizeIfChanged(holder.itemLayout, height = cachedRowHeightPx)
            updateViewSizeIfChanged(
                holder.iconLayout,
                width = cachedIconContainerPx,
                height = cachedIconContainerPx
            )
            updateViewSizeIfChanged(holder.iconImage, width = cachedIconPx, height = cachedIconPx)
            updateViewSizeIfChanged(
                holder.thumbnailImage,
                width = cachedIconPx,
                height = cachedIconPx
            )
            if (abs(holder.nameText.textSize - cachedNamePx) > 0.5f) {
                holder.nameText.setTextSize(TypedValue.COMPLEX_UNIT_SP, cachedNameSp)
            }
            if (abs(holder.descriptionText.textSize - cachedDescPx) > 0.5f) {
                holder.descriptionText.setTextSize(TypedValue.COMPLEX_UNIT_SP, cachedDescSp)
            }
        }
        holder.appliedStyleVersion = styleVersion
        holder.appliedIsParentStyle = isParentDir
    }

    private fun updateViewSizeIfChanged(view: View, width: Int? = null, height: Int? = null) {
        val lp = view.layoutParams ?: return
        var changed = false
        if (width != null && lp.width != width) {
            lp.width = width
            changed = true
        }
        if (height != null && lp.height != height) {
            lp.height = height
            changed = true
        }
        if (changed) {
            view.layoutParams = lp
        }
    }

    override val isAnimationEnabled: Boolean
        get() = Settings.FILE_LIST_ANIMATION.valueCompat && !visualPerformanceMode && itemCount <= 150

    companion object {
        private val PAYLOAD_STATE_CHANGED = Any()
        private val PAYLOAD_CONTENT_REFRESH = Any()

        private val CALLBACK = object : DiffUtil.ItemCallback<FileItem>() {
            override fun areItemsTheSame(oldItem: FileItem, newItem: FileItem): Boolean =
                oldItem.path == newItem.path

            override fun areContentsTheSame(oldItem: FileItem, newItem: FileItem): Boolean =
                oldItem == newItem
        }
    }

    class ViewHolder private constructor(
        root: View,
        val itemLayout: CheckableForegroundLinearLayout,
        val iconLayout: View,
        val iconImage: ImageView,
        val directoryThumbnailImage: ImageView?,
        val thumbnailOutlineView: View?,
        val thumbnailIconImage: ImageView?,
        val thumbnailImage: ImageView,
        val appIconBadgeImage: ImageView,
        val badgeImage: ImageView,
        val nameText: TextView,
        val descriptionText: TextView?,
        val menuButton: ImageButton
    ) : RecyclerView.ViewHolder(root) {
        constructor(binding: FileItemListBinding) : this(
            binding.root,
            binding.itemLayout,
            binding.iconLayout,
            binding.iconImage,
            null,
            null,
            null,
            binding.thumbnailImage,
            binding.appIconBadgeImage,
            binding.badgeImage,
            binding.nameText,
            binding.descriptionText,
            binding.menuButton
        )

        constructor(binding: FileItemGridBinding) : this(
            binding.root,
            binding.itemLayout,
            binding.iconLayout,
            binding.iconImage,
            binding.directoryThumbnailImage,
            binding.thumbnailOutlineView,
            binding.thumbnailIconImage,
            binding.thumbnailImage,
            binding.appIconBadgeImage,
            binding.badgeImage,
            binding.nameText,
            null,
            binding.menuButton
        )

        var popupMenu: PopupMenu? = null
        var boundFile: FileItem? = null
        var isParentItem: Boolean = false
        var appliedStyleVersion: Int = -1
        var appliedIsParentStyle: Boolean = false
        var boundIconRes: Int = 0
        var boundBadgeRes: Int = 0
        var hasActiveThumbnail: Boolean = false
        var hasActiveThumbnailIcon: Boolean = false
        var hasActiveAppBadge: Boolean = false
    }

    interface Listener {
        fun navigateUp()
        fun clearSelectedFiles()
        fun selectFile(file: FileItem, selected: Boolean)
        fun selectFiles(files: FileItemSet, selected: Boolean)
        fun openFile(file: FileItem)
        fun openFileWith(file: FileItem)
        fun cutFile(file: FileItem)
        fun copyFile(file: FileItem)
        fun copyFileToOppositePanel(file: FileItem)
        fun moveFileToOppositePanel(file: FileItem)
        fun confirmDeleteFile(file: FileItem)
        fun showRenameFileDialog(file: FileItem)
        fun extractFile(file: FileItem)
        fun showCreateArchiveDialog(file: FileItem)
        fun shareFile(file: FileItem)
        fun copyPath(file: FileItem)
        fun addBookmark(file: FileItem)
        fun createShortcut(file: FileItem)
        fun swapFileNames(file: FileItem)
        fun splitFile(file: FileItem)
        fun mergeFiles(file: FileItem)
        fun createSymlink(file: FileItem)
        fun showPermissionsDialog(file: FileItem)
        fun showPropertiesDialog(file: FileItem)
        fun showMtMenu(file: FileItem)
    }
}
