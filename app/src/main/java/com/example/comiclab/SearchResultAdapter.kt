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
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors

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
    private val loadingCollectionSources = Collections.synchronizedSet(mutableSetOf<String>())
    private val failedCollectionSources = Collections.synchronizedSet(mutableSetOf<String>())
    private val collectionRefreshLock = Any()
    @Volatile
    private var collectionRefreshScheduled = false
    private val pendingCollectionRefreshKeys = mutableSetOf<String>()
    private val coverExecutor = Executors.newFixedThreadPool(SEARCH_COVER_THREAD_COUNT)
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
        coverExecutor.shutdownNow()
        loadingCovers.clear()
        collectionCoverLoadTracker.clear()
        failedCovers.clear()
        loadingCollectionSources.clear()
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
                }
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

        val cachedCover = coverCache.get(cacheKey)
        if (cachedCover != null) {
            bindArchiveCoverBitmap(imgIcon, cachedCover)
            return
        }

        if (cacheKey in failedCovers || !loadingCovers.add(cacheKey)) {
            return
        }

        executeCoverTask {
            val cover = runCatching {
                val firstImageEntry = ComicArchive.imageEntries(file).firstOrNull()
                firstImageEntry?.let {
                    ComicArchive.decodeImage(file, it, SEARCH_COVER_MAX_SIZE)
                }
            }.getOrNull()
            handleCoverResult(cacheKey, cover, imgIcon)
        }
    }

    private fun executeCoverTask(block: () -> Unit) {
        if (closed) {
            return
        }

        runCatching {
            coverExecutor.execute {
                if (!closed) {
                    block()
                }
            }
        }
    }

    private fun handleCollectionCoverResult(
        cacheKey: String,
        cover: Bitmap?
    ) {
        if (cover == null) {
            failedCovers.add(cacheKey)
            collectionCoverLoadTracker.complete(cacheKey)
            return
        }

        coverCache.put(cacheKey, cover)
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
            dpToPx(DEFAULT_ICON_PADDING_DP),
            dpToPx(DEFAULT_ICON_PADDING_DP),
            dpToPx(DEFAULT_ICON_PADDING_DP),
            dpToPx(DEFAULT_ICON_PADDING_DP)
        )
        updateIconSize(
            imgIcon,
            dpToPx(iconSizeDp),
            dpToPx(iconSizeDp)
        )
    }

    private fun setCoverIconLayout(imgIcon: ImageView) {
        imgIcon.setBackgroundColor(android.graphics.Color.BLACK)
        imgIcon.setPadding(0, 0, 0, 0)
        updateIconSize(
            imgIcon,
            dpToPx(SEARCH_COVER_WIDTH_DP),
            dpToPx(SEARCH_COVER_HEIGHT_DP)
        )
    }

    private fun setArchivePlaceholderLayout(imgIcon: ImageView) {
        imgIcon.setBackgroundResource(R.drawable.bg_file_cover_placeholder)
        imgIcon.setPadding(0, 0, 0, 0)
        updateIconSize(
            imgIcon,
            dpToPx(SEARCH_COVER_WIDTH_DP),
            dpToPx(SEARCH_COVER_HEIGHT_DP)
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
