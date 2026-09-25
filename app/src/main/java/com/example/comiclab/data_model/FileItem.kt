package com.example.comiclab

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.comiclab.ebook.ReaderFileDetector
import com.example.comiclab.MangaCollectionCoverAdapter
import com.example.comiclab.MangaCollectionCoverPlanner
import com.example.comiclab.AbsListViewScrollStateTracker
import com.example.comiclab.BookcaseRowViewTypes
import com.example.comiclab.R
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors

data class FileItem(
    val file: File? = null,
    val isParent: Boolean = false,
    val childCount: Int? = null
)

class FileListAdapter(
    context: Context,
    private val items: List<FileItem>,
    private val onCollectionCoverClick: ((File) -> Unit)? = null,
    private val onDirectoryLongClick: ((File) -> Unit)? = null
) : ArrayAdapter<FileItem>(context, 0, items) {

    private val archiveCoverCache = object : LruCache<String, Bitmap>(ARCHIVE_COVER_CACHE_SIZE) {}
    private val collectionSourceCache = object : LruCache<String, List<MangaCollectionCoverSource>>(ARCHIVE_COVER_CACHE_SIZE) {}
    private val collectionScrollState = MangaCollectionCoverScrollState()
    private val collectionCoverLoadTracker = MangaCollectionCoverLoadTracker<ImageView>()
    private val listScrollState = AbsListViewScrollStateTracker()
    private val loadingArchiveCovers = Collections.synchronizedSet(mutableSetOf<String>())
    private val failedArchiveCovers = Collections.synchronizedSet(mutableSetOf<String>())
    private val loadingCollectionSources = Collections.synchronizedSet(mutableSetOf<String>())
    private val failedCollectionSources = Collections.synchronizedSet(mutableSetOf<String>())
    private val collectionRefreshLock = Any()
    @Volatile
    private var collectionRefreshScheduled = false
    private var pendingFullListRefresh = false
    private val pendingCollectionRefreshKeys = mutableSetOf<String>()
    private val archiveCoverExecutor = Executors.newFixedThreadPool(ARCHIVE_COVER_THREAD_COUNT)
    private var favoriteComicPaths = FavoriteComicStore.favoriteFilePaths(context)
    private var favoriteDirectoryPaths = FavoritePathStore.favoriteDirectoryPaths(context)
    @Volatile
    private var closed = false

    override fun getViewTypeCount(): Int = BookcaseRowViewTypes.COUNT

    override fun getItemViewType(position: Int): Int {
        return BookcaseRowViewTypes.forBookcase(
            isBookcase = getItemLayoutResId(items[position].file) == R.layout.file_item_manga_collection
        )
    }

    override fun notifyDataSetChanged() {
        refreshFavoriteMarkerCache()
        super.notifyDataSetChanged()
    }

    fun notifyDataSetChangedWhenIdle(listView: AbsListView) {
        synchronized(collectionRefreshLock) {
            if (closed) {
                return
            }
            pendingFullListRefresh = true
        }
        schedulePendingCollectionRefresh(listView)
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        (parent as? AbsListView)?.let(listScrollState::attach)
        val item = items[position]
        val file = item.file
        val layoutResId = getItemLayoutResId(file)
        val view = convertView
            ?.takeIf { it.tag == layoutResId }
            ?: LayoutInflater.from(context).inflate(layoutResId, parent, false).also {
                it.tag = layoutResId
            }

        view.isPressed = false
        view.isSelected = false
        view.isActivated = false
        view.setOnClickListener(null)
        view.isClickable = false
        view.setOnLongClickListener(null)
        view.isLongClickable = false

        val imgIcon = view.findViewById<ImageView>(R.id.imgIcon)
        val iconContainer = view.findViewById<ViewGroup>(R.id.iconContainer)
        val collectionCoverList = view.findViewById<RecyclerView>(R.id.collectionCoverList)
        val infoContainer = view.findViewById<ViewGroup>(R.id.itemInfoContainer)
        val imgFavoriteMarker = view.findViewById<ImageView>(R.id.imgFavoriteMarker)
        val tvName = view.findViewById<TextView>(R.id.tvName)
        val tvInfo = view.findViewById<TextView>(R.id.tvInfo)
        val tvTypeMarker = view.findViewById<TextView>(R.id.tvTypeMarker)
        val tvDate = view.findViewById<TextView>(R.id.tvDate)

        hideFavoriteMarker(imgFavoriteMarker)

        if (item.isParent) {
            imgIcon.tag = null
            resetCollectionCoverContainer(iconContainer, infoContainer, imgIcon, collectionCoverList)
            setDefaultIconLayout(imgIcon)
            imgIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
            imgIcon.setImageResource(R.drawable.ic_lucide_corner_up_left)
            tvName.text = ".."
            tvInfo.text = context.getString(R.string.parent_directory)
            tvTypeMarker.text = "(D)"
            tvDate.text = ""
            return view
        }

        if (file == null) {
            imgIcon.tag = null
            resetCollectionCoverContainer(iconContainer, infoContainer, imgIcon, collectionCoverList)
            setDefaultIconLayout(imgIcon)
            imgIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
            imgIcon.setImageResource(R.drawable.ic_lucide_file)
            tvName.text = context.getString(R.string.unknown_item)
            tvInfo.text = ""
            tvTypeMarker.text = ""
            tvDate.text = ""
            return view
        }

        tvName.text = file.name
        tvTypeMarker.text = if (file.isDirectory) "(D)" else "(F)"
        tvDate.text = CommonFunc.formatDate(file.lastModified())
        bindFavoriteMarker(file, imgFavoriteMarker)

        if (file.isDirectory) {
            bindDirectoryIcon(file, view, iconContainer, infoContainer, imgIcon, collectionCoverList)
            tvInfo.text = context.getString(R.string.item_count, item.childCount ?: 0)
            return view
        }

        if (ComicArchive.isArchive(file) || ReaderFileDetector.isEbook(file)) {
            bindArchiveIcon(file, iconContainer, infoContainer, imgIcon, collectionCoverList)
        } else {
            imgIcon.tag = null
            resetCollectionCoverContainer(iconContainer, infoContainer, imgIcon, collectionCoverList)
            setDefaultIconLayout(imgIcon)
            imgIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
            imgIcon.setImageResource(R.drawable.ic_lucide_file)
        }
        tvInfo.text = CommonFunc.formatFileSize(file.length())

        return view
    }

    private fun refreshFavoriteMarkerCache() {
        favoriteComicPaths = FavoriteComicStore.favoriteFilePaths(context)
        favoriteDirectoryPaths = FavoritePathStore.favoriteDirectoryPaths(context)
    }

    private fun getItemLayoutResId(file: File?): Int {
        return if (file?.isDirectory == true && AppSettings.isBookcaseDirectory(context, file)) {
            R.layout.file_item_manga_collection
        } else {
            R.layout.file_item
        }
    }

    private fun bindFavoriteMarker(file: File, marker: ImageView) {
        val markerIconResId = when {
            file.isDirectory && favoriteDirectoryPaths.contains(file.absolutePath) ->
                R.drawable.png_mark_direct_icon

            file.isFile &&
                ReaderFileDetector.isSupported(file) &&
                favoriteComicPaths.contains(file.absolutePath) ->
                R.drawable.png_mark_file_icon

            else -> null
        }

        if (markerIconResId == null) {
            hideFavoriteMarker(marker)
            return
        }

        marker.setImageResource(markerIconResId)
        marker.visibility = View.VISIBLE
    }

    private fun hideFavoriteMarker(marker: ImageView) {
        marker.setImageDrawable(null)
        marker.visibility = View.GONE
    }

    private fun bindDirectoryClick(
        directory: File,
        row: View,
        collectionCoverList: RecyclerView
    ): (() -> Boolean)? {
        val openDirectory: (() -> Unit)? = onCollectionCoverClick?.let { callback ->
            {
                MangaCollectionCoverClickHandler.dispatch(directory, callback)
                Unit
            }
        }
        val dispatchDirectoryLongClick: (() -> Boolean)? = onDirectoryLongClick?.let { callback ->
            {
                (collectionCoverList as? MangaCollectionCoverRecyclerView)
                    ?.onLongPressConsumed()
                MangaCollectionCoverClickHandler.dispatchLongClick(directory, callback)
            }
        }
        val directoryLongClickListener = dispatchDirectoryLongClick?.let { dispatch ->
            View.OnLongClickListener { dispatch() }
        }
        row.isClickable = openDirectory != null
        row.setOnClickListener(openDirectory?.let { action ->
            View.OnClickListener { action() }
        })
        row.setOnLongClickListener(directoryLongClickListener)
        row.isLongClickable = directoryLongClickListener != null
        val routedCoverList = collectionCoverList as? MangaCollectionCoverRecyclerView
        if (routedCoverList != null) {
            collectionCoverList.setOnLongClickListener(null)
            collectionCoverList.isLongClickable = false
            routedCoverList.apply {
                setOnRowPressStateChangedListener { pressed -> row.isPressed = pressed }
                setOnBlankClickListener(openDirectory)
                setOnBlankLongClickListener(dispatchDirectoryLongClick)
            }
        } else {
            collectionCoverList.setOnLongClickListener(directoryLongClickListener)
            collectionCoverList.isLongClickable = directoryLongClickListener != null
        }
        return dispatchDirectoryLongClick
    }

    private fun bindDirectoryIcon(
        directory: File,
        row: View,
        iconContainer: ViewGroup,
        infoContainer: ViewGroup,
        imgIcon: ImageView,
        collectionCoverList: RecyclerView
    ) {
        if (!AppSettings.isBookcaseDirectory(context, directory)) {
            resetCollectionCoverContainer(iconContainer, infoContainer, imgIcon, collectionCoverList)
            setDefaultIconLayout(imgIcon)
            imgIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
            imgIcon.setImageResource(R.drawable.ic_lucide_folder)
            return
        }

        val cacheKey = mangaCollectionCacheKey(directory)
        val cachedSources = collectionSourceCache.get(cacheKey)
        if (cachedSources.isNullOrEmpty()) {
            resetCollectionCoverContainer(iconContainer, infoContainer, imgIcon, collectionCoverList)
            bindDirectoryClick(directory, row, collectionCoverList)
            setDefaultIconLayout(
                imgIcon,
                preserveBackground = true,
                iconSizeDp = BOOKCASE_ICON_SIZE_DP
            )
            imgIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
            imgIcon.setImageResource(R.drawable.png_bookcase_icon)
            iconContainer.tag = cacheKey

            if (cachedSources != null || cacheKey in failedCollectionSources ||
                !loadingCollectionSources.add(cacheKey)
            ) {
                return
            }

            executeCoverTask {
                val sources = MangaCollectionCoverPlanner.findCoverSourcesWithEntries(directory)
                loadingCollectionSources.remove(cacheKey)
                if (closed) {
                    return@executeCoverTask
                }

                collectionSourceCache.put(cacheKey, sources)
                if (sources.isEmpty()) {
                    failedCollectionSources.add(cacheKey)
                    return@executeCoverTask
                }

                scheduleCollectionRefresh(collectionCoverList, cacheKey)
            }
            return
        }

        bindCollectionCovers(
            cacheKey,
            directory,
            cachedSources,
            row,
            iconContainer,
            imgIcon,
            collectionCoverList
        )
    }

    private fun bindCollectionCovers(
        cacheKey: String,
        directory: File,
        sources: List<MangaCollectionCoverSource>,
        row: View,
        iconContainer: ViewGroup,
        imgIcon: ImageView,
        collectionCoverList: RecyclerView
    ) {
        if (sources.isEmpty()) {
            return
        }

        val previousCacheKey = collectionCoverList.tag as? String
        val needsNewAdapter = previousCacheKey != cacheKey ||
            collectionCoverList.adapter !is MangaCollectionCoverAdapter
        if (needsNewAdapter) {
            collectionScrollState.save(previousCacheKey, collectionCoverList)
        }
        setCollectionCoverContainer(imgIcon, collectionCoverList)
        val dispatchDirectoryLongClick =
            bindDirectoryClick(directory, row, collectionCoverList)
        if (needsNewAdapter) {
            collectionScrollState.attach(collectionCoverList, cacheKey)
            collectionCoverList.adapter = MangaCollectionCoverAdapter(
                sources = sources,
                bindCover = { source, imageView ->
                    bindCollectionCover(source, imageView)
                },
                onCoverClick = {
                    MangaCollectionCoverClickHandler.dispatch(directory, onCollectionCoverClick)
                },
                onCoverLongClick = dispatchDirectoryLongClick
            )
            collectionScrollState.restore(cacheKey, collectionCoverList, sources.size)
            collectionCoverList.tag = cacheKey
        }
        iconContainer.tag = cacheKey
    }

    private fun bindCollectionCover(source: MangaCollectionCoverSource, imageView: ImageView) {
        val file = source.file
        val cacheKey = "${archiveCoverCacheKey(file)}:collection:${source.imageEntry}"
        imageView.tag = cacheKey

        val cachedCover = archiveCoverCache.get(cacheKey)
        if (cachedCover != null) {
            bindCollectionCoverBitmap(imageView, cachedCover)
            return
        }

        if (closed || cacheKey in failedArchiveCovers ||
            !collectionCoverLoadTracker.register(cacheKey, imageView)
        ) {
            return
        }

        executeCoverTask {
            if (closed) {
                collectionCoverLoadTracker.complete(cacheKey)
                return@executeCoverTask
            }

            val cover = runCatching {
                ComicArchive.openReaderSession(file, context.cacheDir).use { session ->
                    session.decodePreviewForWidth(
                        source.imageEntry,
                        context.resources.getDimensionPixelSize(R.dimen.file_item_collection_cover_width)
                    )
                }
            }.getOrNull()

            handleCollectionCoverResult(cacheKey, cover)
        }
    }

    private fun handleCollectionCoverResult(
        cacheKey: String,
        cover: Bitmap?
    ) {
        if (cover == null) {
            failedArchiveCovers.add(cacheKey)
            collectionCoverLoadTracker.complete(cacheKey)
            return
        }

        archiveCoverCache.put(cacheKey, cover)
        val waitingViews = collectionCoverLoadTracker.complete(cacheKey)
        if (closed) {
            return
        }

        waitingViews.forEach { imageView ->
            imageView.post {
                if (!closed && imageView.tag == cacheKey) {
                    bindCollectionCoverBitmap(imageView, cover)
                }
            }
        }
    }

    private fun bindArchiveIcon(
        file: File,
        iconContainer: ViewGroup,
        infoContainer: ViewGroup,
        imgIcon: ImageView,
        collectionCoverList: RecyclerView
    ) {
        resetCollectionCoverContainer(iconContainer, infoContainer, imgIcon, collectionCoverList)
        setArchivePlaceholderLayout(imgIcon)
        imgIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
        imgIcon.setImageResource(R.drawable.ic_lucide_image)

        if (ReaderFileDetector.isEbook(file)) {
            imgIcon.tag = null
            return
        }

        if (!ComicArchive.isSupportedArchive(file)) {
            imgIcon.tag = null
            return
        }

        val cacheKey = archiveCoverCacheKey(file)
        imgIcon.tag = cacheKey

        val cachedCover = archiveCoverCache.get(cacheKey)
        if (cachedCover != null) {
            bindArchiveCoverBitmap(imgIcon, cachedCover)
            return
        }

        if (cacheKey in failedArchiveCovers || !loadingArchiveCovers.add(cacheKey)) {
            return
        }

        executeCoverTask {
            val cover = runCatching {
                val firstImageEntry = ComicArchive.imageEntries(file).firstOrNull()
                firstImageEntry?.let {
                    ComicArchive.decodeImage(file, it, ARCHIVE_COVER_MAX_SIZE)
                }
            }.getOrNull()

            loadingArchiveCovers.remove(cacheKey)
            if (closed) {
                return@executeCoverTask
            }

            if (cover == null) {
                failedArchiveCovers.add(cacheKey)
                return@executeCoverTask
            }

            archiveCoverCache.put(cacheKey, cover)
            imgIcon.post {
                if (!closed && imgIcon.tag == cacheKey) {
                    bindArchiveCoverBitmap(imgIcon, cover)
                }
            }
        }
    }

    fun close() {
        closed = true
        archiveCoverExecutor.shutdownNow()
        loadingArchiveCovers.clear()
        collectionCoverLoadTracker.clear()
        failedArchiveCovers.clear()
        loadingCollectionSources.clear()
        failedCollectionSources.clear()
        synchronized(collectionRefreshLock) {
            pendingFullListRefresh = false
            pendingCollectionRefreshKeys.clear()
        }
    }

    private fun executeCoverTask(block: () -> Unit) {
        if (closed) {
            return
        }

        runCatching {
            archiveCoverExecutor.execute {
                if (!closed) {
                    block()
                }
            }
        }
    }

    private fun setDefaultIconLayout(
        imgIcon: ImageView,
        preserveBackground: Boolean = false,
        iconSizeDp: Int = DEFAULT_ICON_SIZE_DP
    ) {
        if (!preserveBackground) {
            imgIcon.background = null
        }
        imgIcon.setPadding(dpToPx(DEFAULT_ICON_PADDING_DP), dpToPx(DEFAULT_ICON_PADDING_DP), dpToPx(DEFAULT_ICON_PADDING_DP), dpToPx(DEFAULT_ICON_PADDING_DP))
        updateIconSize(
            imgIcon,
            dpToPx(iconSizeDp),
            dpToPx(iconSizeDp)
        )
    }

    private fun scheduleCollectionRefresh(anchor: View, cacheKey: String) {
        synchronized(collectionRefreshLock) {
            if (closed) {
                return
            }
            pendingCollectionRefreshKeys.add(cacheKey)
        }

        anchor.post {
            val listView = anchor.findAncestorAbsListView()
            if (listView == null) {
                synchronized(collectionRefreshLock) {
                    pendingCollectionRefreshKeys.remove(cacheKey)
                }
                return@post
            }
            schedulePendingCollectionRefresh(listView)
        }
    }

    private fun schedulePendingCollectionRefresh(listView: AbsListView) {
        synchronized(collectionRefreshLock) {
            if (closed || collectionRefreshScheduled) {
                return
            }
            collectionRefreshScheduled = true
        }
        listView.post { refreshPendingCollectionChangesWhenIdle(listView) }
    }

    private fun refreshPendingCollectionChangesWhenIdle(listView: AbsListView) {
        if (closed) {
            synchronized(collectionRefreshLock) {
                collectionRefreshScheduled = false
                pendingFullListRefresh = false
                pendingCollectionRefreshKeys.clear()
            }
            return
        }

        if (listView.hasActiveCollectionCoverInteraction() ||
            listScrollState.currentState != AbsListView.OnScrollListener.SCROLL_STATE_IDLE
        ) {
            listView.postDelayed(
                { refreshPendingCollectionChangesWhenIdle(listView) },
                COLLECTION_REFRESH_RETRY_DELAY_MS
            )
            return
        }

        val refreshWholeList: Boolean
        val collectionKeys: Set<String>
        synchronized(collectionRefreshLock) {
            refreshWholeList = pendingFullListRefresh
            pendingFullListRefresh = false
            collectionKeys = pendingCollectionRefreshKeys.toSet()
            pendingCollectionRefreshKeys.clear()
            collectionRefreshScheduled = false
        }

        if (refreshWholeList) {
            notifyDataSetChanged()
            return
        }

        val list = listView as? ListView ?: return
        val firstVisiblePosition = list.firstVisiblePosition
        for (rowIndex in 0 until list.childCount) {
            val row = list.getChildAt(rowIndex)
            val iconContainer = row.findViewById<ViewGroup>(R.id.iconContainer) ?: continue
            val cacheKey = iconContainer.tag as? String ?: continue
            if (cacheKey !in collectionKeys || row.tag != R.layout.file_item_manga_collection) {
                continue
            }

            val position = firstVisiblePosition + rowIndex
            if (position in items.indices) {
                getView(position, row, list)
            }
        }
    }

    private fun View.findAncestorAbsListView(): AbsListView? {
        var current = parent
        while (current is View) {
            if (current is AbsListView) {
                return current
            }
            current = current.parent
        }
        return null
    }


    private fun bindCollectionCoverBitmap(imgIcon: ImageView, bitmap: Bitmap) {
        imgIcon.imageTintList = null
        imgIcon.setBackgroundColor(android.graphics.Color.BLACK)
        imgIcon.setPadding(0, 0, 0, 0)
        imgIcon.scaleType = ImageView.ScaleType.CENTER_CROP
        imgIcon.setImageBitmap(bitmap)
    }

    private fun bindArchiveCoverBitmap(
        imgIcon: ImageView,
        bitmap: Bitmap
    ) {
        setArchiveCoverLayout(imgIcon)
        imgIcon.scaleType = ImageView.ScaleType.CENTER_CROP
        imgIcon.setImageBitmap(bitmap)
    }

    private fun setArchiveCoverLayout(imgIcon: ImageView) {
        imgIcon.setBackgroundColor(android.graphics.Color.BLACK)
        imgIcon.setPadding(0, 0, 0, 0)
        updateIconSize(
            imgIcon,
            dpToPx(ARCHIVE_COVER_WIDTH_DP),
            dpToPx(ARCHIVE_COVER_HEIGHT_DP)
        )
    }

    private fun setArchivePlaceholderLayout(imgIcon: ImageView) {
        imgIcon.setBackgroundResource(R.drawable.bg_file_cover_placeholder)
        imgIcon.setPadding(0, 0, 0, 0)
        updateIconSize(
            imgIcon,
            dpToPx(ARCHIVE_COVER_WIDTH_DP),
            dpToPx(ARCHIVE_COVER_HEIGHT_DP)
        )
    }

    private fun resetCollectionCoverContainer(
        iconContainer: ViewGroup,
        infoContainer: ViewGroup,
        imgIcon: ImageView,
        collectionCoverList: RecyclerView
    ) {
        collectionScrollState.save(iconContainer.tag as? String, collectionCoverList)
        collectionCoverList.clearOnScrollListeners()
        collectionCoverList.tag = null
        infoContainer.setOnClickListener(null)
        infoContainer.setOnLongClickListener(null)
        infoContainer.isClickable = false
        infoContainer.isLongClickable = false
        imgIcon.tag = null
        imgIcon.visibility = View.VISIBLE
        collectionCoverList.adapter = null
        (collectionCoverList as? MangaCollectionCoverRecyclerView)?.apply {
            setOnBlankClickListener(null)
            setOnBlankLongClickListener(null)
            setOnRowPressStateChangedListener(null)
        }
        collectionCoverList.setOnClickListener(null)
        collectionCoverList.isClickable = false
        collectionCoverList.setOnLongClickListener(null)
        collectionCoverList.isLongClickable = false
        collectionCoverList.visibility = View.GONE
        iconContainer.tag = null
    }

    private fun setCollectionCoverContainer(
        imgIcon: ImageView,
        collectionCoverList: RecyclerView
    ) {
        imgIcon.visibility = View.GONE
        collectionCoverList.visibility = View.VISIBLE
    }

    private fun updateIconSize(imgIcon: ImageView, width: Int, height: Int) {
        val layoutParams = imgIcon.layoutParams
        if (layoutParams.width == width && layoutParams.height == height) {
            return
        }

        layoutParams.width = width
        layoutParams.height = height
        imgIcon.layoutParams = layoutParams
    }

    private fun dpToPx(value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }

    private fun archiveCoverCacheKey(file: File): String {
        return "${file.absolutePath}:${file.lastModified()}:${file.length()}"
    }

    private fun mangaCollectionCacheKey(directory: File): String {
        return "collection:${directory.absolutePath}:${directory.lastModified()}"
    }

    companion object {
        private const val ARCHIVE_COVER_CACHE_SIZE = 80
        private const val ARCHIVE_COVER_MAX_SIZE = 128
        private const val ARCHIVE_COVER_THREAD_COUNT = 2
        private const val COLLECTION_REFRESH_RETRY_DELAY_MS = 80L
        private const val ARCHIVE_COVER_WIDTH_DP = 50
        private const val ARCHIVE_COVER_HEIGHT_DP = 68
        private const val DEFAULT_ICON_SIZE_DP = 36
        private const val BOOKCASE_ICON_SIZE_DP = 34
        private const val DEFAULT_ICON_PADDING_DP = 4
    }
}
