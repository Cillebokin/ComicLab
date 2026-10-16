package com.example.comiclab.ui.browser

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
import com.example.comiclab.ui.browser.bookcase.AbsListViewScrollStateTracker
import com.example.comiclab.AppSettings
import com.example.comiclab.ui.browser.bookcase.BookcaseRowViewTypes
import com.example.comiclab.BoundedKeyedTaskQueue
import com.example.comiclab.ComicArchive
import com.example.comiclab.CommonFunc
import com.example.comiclab.ui.browser.bookcase.MangaCollectionCoverAdapter
import com.example.comiclab.ui.browser.bookcase.MangaCollectionCoverClickHandler
import com.example.comiclab.ui.browser.bookcase.MangaCollectionCoverLoadTracker
import com.example.comiclab.ui.browser.bookcase.MangaCollectionCoverPlanner
import com.example.comiclab.ui.browser.bookcase.MangaCollectionCoverRecyclerView
import com.example.comiclab.ui.browser.bookcase.MangaCollectionCoverScrollState
import com.example.comiclab.ui.browser.bookcase.MangaCollectionCoverSource
import com.example.comiclab.R
import com.example.comiclab.ebook.ReaderFileDetector
import com.example.comiclab.ui.browser.bookcase.hasActiveCollectionCoverInteraction
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class SearchResultAdapter(
    context: Context,
    private val items: List<File>,
    private val onCollectionCoverClick: ((File) -> Unit)? = null
) : ArrayAdapter<File>(context, 0, items) {

    private val coverCache = object : LruCache<String, Bitmap>(SEARCH_COVER_CACHE_SIZE) {}
    private val collectionSourceCache = object : LruCache<String, List<MangaCollectionCoverSource>>(SEARCH_COVER_CACHE_SIZE) {}
    private val collectionScrollState = MangaCollectionCoverScrollState()
    private val collectionCoverLoadTracker = MangaCollectionCoverLoadTracker<ImageView>()
    private val listScrollState = AbsListViewScrollStateTracker()
    private val loadingCovers = Collections.synchronizedSet(mutableSetOf<String>())
    private val failedCovers = Collections.synchronizedSet(mutableSetOf<String>())
    private val collectionSourceRequestIds = ConcurrentHashMap<String, Long>()
    private val nextCollectionSourceRequestId = AtomicLong()
    private val failedCollectionSources = Collections.synchronizedSet(mutableSetOf<String>())
    private val collectionRefreshLock = Any()
    @Volatile
    private var collectionRefreshScheduled = false
    private val pendingCollectionRefreshKeys = mutableSetOf<String>()
    private val coverTasks = BoundedKeyedTaskQueue(SEARCH_COVER_THREAD_COUNT)
    @Volatile
    private var closed = false

    override fun getViewTypeCount(): Int = BookcaseRowViewTypes.COUNT

    override fun getItemViewType(position: Int): Int {
        return BookcaseRowViewTypes.forBookcase(
            isBookcase = getItemLayoutResId(items[position]) == R.layout.file_item_manga_collection
        )
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        (parent as? AbsListView)?.let(listScrollState::attach)
        val file = items[position]
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

        val imgIcon = view.findViewById<ImageView>(R.id.imgIcon)
        val iconContainer = view.findViewById<ViewGroup>(R.id.iconContainer)
        val collectionCoverList = view.findViewById<RecyclerView>(R.id.collectionCoverList)
        val infoContainer = view.findViewById<ViewGroup>(R.id.itemInfoContainer)
        val imgFavoriteMarker = view.findViewById<ImageView>(R.id.imgFavoriteMarker)
        val tvName = view.findViewById<TextView>(R.id.tvName)
        val tvInfo = view.findViewById<TextView>(R.id.tvInfo)
        val tvTypeMarker = view.findViewById<TextView>(R.id.tvTypeMarker)
        val tvDate = view.findViewById<TextView>(R.id.tvDate)

        imgFavoriteMarker.setImageDrawable(null)
        imgFavoriteMarker.visibility = View.GONE

        bindIcon(file, view, iconContainer, infoContainer, imgIcon, collectionCoverList)

        tvName.text = file.name
        tvInfo.text = file.parent.orEmpty()
        tvTypeMarker.text = if (file.isDirectory) "(D)" else "(F)"
        tvDate.text = CommonFunc.formatDate(file.lastModified())

        return view
    }

    fun close() {
        closed = true
        coverTasks.close()
        loadingCovers.clear()
        collectionCoverLoadTracker.clear()
        failedCovers.clear()
        collectionSourceRequestIds.clear()
        failedCollectionSources.clear()
        synchronized(collectionRefreshLock) {
            pendingCollectionRefreshKeys.clear()
        }
    }

    private fun getItemLayoutResId(file: File): Int {
        return if (file.isDirectory && AppSettings.isBookcaseDirectory(context, file)) {
            R.layout.file_item_manga_collection
        } else {
            R.layout.file_item
        }
    }

    private fun bindDirectoryClick(
        directory: File,
        row: View,
        collectionCoverList: RecyclerView
    ) {
        val openDirectory: (() -> Unit)? = onCollectionCoverClick?.let { callback ->
            {
                MangaCollectionCoverClickHandler.dispatch(directory, callback)
                Unit
            }
        }
        row.isClickable = openDirectory != null
        row.setOnClickListener(openDirectory?.let { action ->
            View.OnClickListener { action() }
        })
        (collectionCoverList as? MangaCollectionCoverRecyclerView)?.apply {
            setOnBlankLongClickListener(null)
            setOnRowPressStateChangedListener { pressed -> row.isPressed = pressed }
            setOnBlankClickListener(openDirectory)
        }
        collectionCoverList.setOnLongClickListener(null)
        collectionCoverList.isLongClickable = false
    }

    private fun bindIcon(
        file: File,
        row: View,
        iconContainer: ViewGroup,
        infoContainer: ViewGroup,
        imgIcon: ImageView,
        collectionCoverList: RecyclerView
    ) {
        when {
            file.isDirectory ->
                bindDirectoryIcon(file, row, iconContainer, infoContainer, imgIcon, collectionCoverList)
            ComicArchive.isArchive(file) || ReaderFileDetector.isEbook(file) ->
                bindArchiveIcon(file, iconContainer, infoContainer, imgIcon, collectionCoverList)
            else -> {
                imgIcon.tag = null
                resetCollectionCoverContainer(iconContainer, infoContainer, imgIcon, collectionCoverList)
                setDefaultIconLayout(imgIcon)
                imgIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
                imgIcon.setImageResource(R.drawable.ic_lucide_file)
            }
        }
    }

    private fun bindDirectoryIcon(
        directory: File,
        row: View,
        iconContainer: ViewGroup,
        infoContainer: ViewGroup,
        imgIcon: ImageView,
        collectionCoverList: RecyclerView
    ) {
        val cacheKey = mangaCollectionCacheKey(directory)
        val previousCacheKey = iconContainer.tag as? String
        if (previousCacheKey != cacheKey) {
            cancelCollectionSourceLoad(previousCacheKey)
        }

        if (!AppSettings.isBookcaseDirectory(context, directory)) {
            resetCollectionCoverContainer(iconContainer, infoContainer, imgIcon, collectionCoverList)
            setDefaultIconLayout(imgIcon)
            imgIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
            imgIcon.setImageResource(R.drawable.ic_lucide_folder)
            return
        }

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

            if (cachedSources != null || cacheKey in failedCollectionSources || closed) {
                return
            }

            val requestId = nextCollectionSourceRequestId.incrementAndGet()
            if (collectionSourceRequestIds.putIfAbsent(cacheKey, requestId) != null) return

            executeCoverTask(
                taskKey = "collection-sources:$cacheKey",
                onRejected = { collectionSourceRequestIds.remove(cacheKey, requestId) }
            ) {
                val sources = runCatching {
                    MangaCollectionCoverPlanner.findCoverSourcesWithEntries(directory)
                }.getOrDefault(emptyList())
                if (closed || !collectionSourceRequestIds.remove(cacheKey, requestId)) {
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
                onCoverRecycled = ::cancelCollectionCoverLoad
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

        val cachedCover = coverCache.get(cacheKey)
        if (cachedCover != null) {
            bindCollectionCoverBitmap(imageView, cachedCover)
            return
        }

        if (closed || cacheKey in failedCovers ||
            !collectionCoverLoadTracker.register(cacheKey, imageView)
        ) {
            return
        }

        val requestId = collectionCoverLoadTracker.requestId(cacheKey) ?: return
        executeCoverTask(
            taskKey = "collection-cover:$cacheKey",
            onRejected = { collectionCoverLoadTracker.complete(cacheKey, requestId) }
        ) {
            if (closed) {
                collectionCoverLoadTracker.complete(cacheKey, requestId)
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
            handleCollectionCoverResult(cacheKey, requestId, cover)
        }
    }

    private fun cancelCollectionCoverLoad(cacheKey: String, imageView: ImageView) {
        if (collectionCoverLoadTracker.unregister(cacheKey, imageView) == null) return
        coverTasks.cancel("collection-cover:$cacheKey")
    }

    private fun cancelCollectionSourceLoad(cacheKey: String?) {
        if (cacheKey?.startsWith("collection:") != true) return
        if (collectionSourceRequestIds.remove(cacheKey) == null) return
        coverTasks.cancel("collection-sources:$cacheKey")
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
        val isTxt = ReaderFileDetector.isTxt(file)
        imgIcon.imageTintList = if (isTxt) {
            imgIcon.context.getColorStateList(R.color.comiclab_icon)
        } else {
            null
        }
        imgIcon.setImageResource(
            if (isTxt) R.drawable.file_txt_streamline_core else R.drawable.ic_lucide_image
        )
        if (isTxt) {
            applyHalfSizeIconPadding(imgIcon)
        }

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

        val cachedCover = coverCache.get(cacheKey)
        if (cachedCover != null) {
            bindArchiveCoverBitmap(imgIcon, cachedCover)
            return
        }

        if (cacheKey in failedCovers || !loadingCovers.add(cacheKey)) {
            return
        }

        executeCoverTask(
            taskKey = "archive-cover:$cacheKey",
            onRejected = { loadingCovers.remove(cacheKey) }
        ) {
            val cover = runCatching {
                val firstImageEntry = ComicArchive.imageEntries(file).firstOrNull()
                firstImageEntry?.let {
                    ComicArchive.decodeImage(file, it, SEARCH_COVER_MAX_SIZE, context.cacheDir)
                }
            }.getOrNull()
            handleCoverResult(cacheKey, cover, imgIcon)
        }
    }

    private fun executeCoverTask(
        taskKey: String,
        onRejected: () -> Unit = {},
        block: () -> Unit
    ) {
        coverTasks.execute(taskKey, onRejected) {
            if (!closed) block()
        }
    }

    private fun handleCollectionCoverResult(
        cacheKey: String,
        requestId: Long,
        cover: Bitmap?
    ) {
        val waitingViews = collectionCoverLoadTracker.complete(cacheKey, requestId) ?: return
        if (cover == null) {
            failedCovers.add(cacheKey)
            return
        }

        coverCache.put(cacheKey, cover)
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
        listView.post { refreshCollectionRowsWhenIdle(listView) }
    }

    private fun refreshCollectionRowsWhenIdle(listView: AbsListView) {
        if (closed) {
            synchronized(collectionRefreshLock) {
                collectionRefreshScheduled = false
                pendingCollectionRefreshKeys.clear()
            }
            return
        }

        if (listView.hasActiveCollectionCoverInteraction() ||
            listScrollState.currentState != AbsListView.OnScrollListener.SCROLL_STATE_IDLE
        ) {
            listView.postDelayed(
                { refreshCollectionRowsWhenIdle(listView) },
                COLLECTION_REFRESH_RETRY_DELAY_MS
            )
            return
        }

        val cacheKeys: Set<String>
        synchronized(collectionRefreshLock) {
            cacheKeys = pendingCollectionRefreshKeys.toSet()
            pendingCollectionRefreshKeys.clear()
            collectionRefreshScheduled = false
        }

        val list = listView as? ListView ?: return
        val firstVisiblePosition = list.firstVisiblePosition
        for (rowIndex in 0 until list.childCount) {
            val row = list.getChildAt(rowIndex)
            val iconContainer = row.findViewById<ViewGroup>(R.id.iconContainer) ?: continue
            val cacheKey = iconContainer.tag as? String ?: continue
            if (cacheKey !in cacheKeys || row.tag != R.layout.file_item_manga_collection) {
                continue
            }

            val position = firstVisiblePosition + rowIndex
            if (position in items.indices) {
                getView(position, row, list)
            }
        }
    }

    private fun handleCoverResult(
        cacheKey: String,
        cover: Bitmap?,
        imgIcon: ImageView
    ) {
        loadingCovers.remove(cacheKey)
        if (closed) {
            return
        }

        if (cover == null) {
            failedCovers.add(cacheKey)
            return
        }

        coverCache.put(cacheKey, cover)
        imgIcon.post {
            if (!closed && imgIcon.tag == cacheKey) {
                bindArchiveCoverBitmap(imgIcon, cover)
            }
        }
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
        setCoverIconLayout(imgIcon)
        imgIcon.scaleType = ImageView.ScaleType.CENTER_CROP
        imgIcon.setImageBitmap(bitmap)
    }

    private fun setDefaultIconLayout(
        imgIcon: ImageView,
        preserveBackground: Boolean = false,
        iconSizeDp: Int = DEFAULT_ICON_SIZE_DP
    ) {
        if (!preserveBackground) {
            imgIcon.background = null
        }
        imgIcon.setPadding(
            context.dpToPx(DEFAULT_ICON_PADDING_DP),
            context.dpToPx(DEFAULT_ICON_PADDING_DP),
            context.dpToPx(DEFAULT_ICON_PADDING_DP),
            context.dpToPx(DEFAULT_ICON_PADDING_DP)
        )
        updateIconSize(
            imgIcon,
            context.dpToPx(iconSizeDp),
            context.dpToPx(iconSizeDp)
        )
    }

    private fun setCoverIconLayout(imgIcon: ImageView) {
        imgIcon.setBackgroundColor(android.graphics.Color.BLACK)
        imgIcon.setPadding(0, 0, 0, 0)
        updateIconSize(
            imgIcon,
            context.dpToPx(SEARCH_COVER_WIDTH_DP),
            context.dpToPx(SEARCH_COVER_HEIGHT_DP)
        )
    }

    private fun setArchivePlaceholderLayout(imgIcon: ImageView) {
        imgIcon.setBackgroundResource(R.drawable.bg_file_cover_placeholder)
        imgIcon.setPadding(0, 0, 0, 0)
        updateIconSize(
            imgIcon,
            context.dpToPx(SEARCH_COVER_WIDTH_DP),
            context.dpToPx(SEARCH_COVER_HEIGHT_DP)
        )
    }

    private fun resetCollectionCoverContainer(
        iconContainer: ViewGroup,
        infoContainer: ViewGroup,
        imgIcon: ImageView,
        collectionCoverList: RecyclerView
    ) {
        cancelCollectionSourceLoad(iconContainer.tag as? String)
        collectionScrollState.save(iconContainer.tag as? String, collectionCoverList)
        collectionCoverList.clearOnScrollListeners()
        collectionCoverList.tag = null
        infoContainer.setOnClickListener(null)
        infoContainer.isClickable = false
        infoContainer.setOnLongClickListener(null)
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

    private fun mangaCollectionCacheKey(directory: File): String {
        return "collection:${directory.absolutePath}:${directory.lastModified()}"
    }

    companion object {
        private const val SEARCH_COVER_CACHE_SIZE = 80
        private const val SEARCH_COVER_MAX_SIZE = 128
        private const val SEARCH_COVER_THREAD_COUNT = 2
        private const val COLLECTION_REFRESH_RETRY_DELAY_MS = 80L
        private const val SEARCH_COVER_WIDTH_DP = 50
        private const val SEARCH_COVER_HEIGHT_DP = 68
        private const val DEFAULT_ICON_SIZE_DP = 36
        private const val BOOKCASE_ICON_SIZE_DP = 34
        private const val DEFAULT_ICON_PADDING_DP = 4
    }
}
