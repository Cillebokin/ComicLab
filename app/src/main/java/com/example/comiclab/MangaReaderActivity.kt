package com.example.comiclab

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.Collections
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

class MangaReaderActivity : AppCompatActivity() {

    private lateinit var rootView: View
    private lateinit var listReaderPages: ZoomableReaderRecyclerView
    private lateinit var readerLayoutManager: LinearLayoutManager
    private lateinit var layoutReaderToolbar: View
    private lateinit var layoutReaderProgress: View
    private lateinit var sliderReaderProgress: SeekBar
    private lateinit var tvReaderTitle: TextView
    private lateinit var tvReaderProgress: TextView
    private lateinit var tvReaderStatus: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val readerPrefs by lazy {
        getSharedPreferences(READER_PREFS_NAME, MODE_PRIVATE)
    }
    private val autoHideControlsRunnable = Runnable {
        setReaderControlsVisible(false)
    }
    private val clearSuppressReaderTapRunnable = Runnable {
        suppressReaderTap = false
    }

    private var archiveFile: File? = null
    private var imageEntries: List<String> = emptyList()
    private var pageAdapter: MangaPageAdapter? = null
    private var readerControlsVisible = true
    private var suppressReaderTap = false
    private var isDraggingReaderSlider = false
    private var shouldStartFromBeginning = false
    private var pendingRestorePosition: Int? = null
    private var pendingRestoreOffset = 0
    private var restoreAttemptCount = 0
    private var restoreRetryScheduled = false
    private var currentReaderPosition = 0
    private var currentReaderOffset = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manga_reader)

        bindViews()
        configureImmersiveSystemBars()
        configureBackHandling()
        configureReaderActions()

        val path = intent.getStringExtra(EXTRA_ARCHIVE_PATH)
        val file = path?.let(::File)
        if (file == null || !file.isFile) {
            showError(getString(R.string.message_invalid_file))
            return
        }

        archiveFile = file
        tvReaderTitle.text = file.nameWithoutExtension
        shouldStartFromBeginning = intent.getBooleanExtra(EXTRA_START_FROM_BEGINNING, false)
        if (shouldStartFromBeginning) {
            clearSavedReadingProgress(this, file)
        }

        loadArchive(file)
        showReaderControlsTemporarily()
    }

    override fun onPause() {
        saveReaderPosition()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        pageAdapter?.close()
        pageAdapter = null
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        pageAdapter?.trimMemory(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        pageAdapter?.trimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !readerControlsVisible) {
            setSystemBarsVisible(false)
        }
    }

    private fun bindViews() {
        rootView = findViewById(R.id.main)
        listReaderPages = findViewById(R.id.listReaderPages)
        layoutReaderToolbar = findViewById(R.id.layoutReaderToolbar)
        layoutReaderProgress = findViewById(R.id.layoutReaderProgress)
        sliderReaderProgress = findViewById(R.id.sliderReaderProgress)
        tvReaderTitle = findViewById(R.id.tvReaderTitle)
        tvReaderProgress = findViewById(R.id.tvReaderProgress)
        tvReaderStatus = findViewById(R.id.tvReaderStatus)

        readerLayoutManager = LinearLayoutManager(this)
        listReaderPages.layoutManager = readerLayoutManager
        listReaderPages.itemAnimator = null
        listReaderPages.setHasFixedSize(false)
        listReaderPages.setItemViewCacheSize(READER_VIEW_CACHE_SIZE)
    }

    private fun configureImmersiveSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowInsetsControllerCompat(window, rootView).systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        val toolbarInitialPaddingTop = layoutReaderToolbar.paddingTop
        val progressInitialMarginBottom =
            (layoutReaderProgress.layoutParams as FrameLayout.LayoutParams).bottomMargin
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            layoutReaderToolbar.setPadding(
                layoutReaderToolbar.paddingLeft,
                toolbarInitialPaddingTop + systemBars.top,
                layoutReaderToolbar.paddingRight,
                layoutReaderToolbar.paddingBottom
            )
            (layoutReaderProgress.layoutParams as FrameLayout.LayoutParams).apply {
                bottomMargin = progressInitialMarginBottom + systemBars.bottom
                layoutReaderProgress.layoutParams = this
            }
            insets
        }
        ViewCompat.requestApplyInsets(rootView)
    }

    private fun configureBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                saveReaderPosition()
                finish()
            }
        })
    }

    private fun configureReaderActions() {
        listReaderPages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING ||
                    newState == RecyclerView.SCROLL_STATE_SETTLING
                ) {
                    clearPendingReaderPosition()
                }
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val firstVisibleItem = readerLayoutManager.findFirstVisibleItemPosition()
                    .takeIf { it != RecyclerView.NO_POSITION }
                    ?: 0
                val visibleItemCount = visibleReaderItemCount()
                val totalItemCount = pageAdapter?.itemCount ?: 0
                updateCurrentReaderPosition(firstVisibleItem)
                updateReaderProgress(firstVisibleItem, totalItemCount)
                pageAdapter?.preloadAround(firstVisibleItem, visibleItemCount)
            }
        })
        listReaderPages.onTransformChanged = { scale, horizontalPanX ->
            updateReaderTransform(scale, horizontalPanX)
        }
        sliderReaderProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    updateReaderProgressText(progress, imageEntries.size)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                isDraggingReaderSlider = true
                clearPendingReaderPosition()
                handler.removeCallbacks(autoHideControlsRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                isDraggingReaderSlider = false
                jumpReaderToPage(seekBar.progress)
                showReaderControlsTemporarily()
            }
        })
    }

    private fun loadArchive(file: File) {
        tvReaderStatus.text = getString(R.string.loading_reading)
        tvReaderStatus.visibility = View.VISIBLE

        Thread {
            val entries = runCatching {
                ComicArchive.imageEntries(file)
            }.getOrElse {
                runOnUiThread {
                    showError(getString(R.string.unsupported_archive_format))
                }
                return@Thread
            }

            runOnUiThread {
                if (entries.isEmpty()) {
                    showError(getString(R.string.no_reading_images))
                    return@runOnUiThread
                }

                imageEntries = entries
                tvReaderStatus.visibility = View.GONE
                bindReaderAdapter(file, entries)
            }
        }.start()
    }

    private fun bindReaderAdapter(file: File, entries: List<String>) {
        val session = runCatching {
            ComicArchive.openReaderSession(file, cacheDir)
        }.getOrElse {
            showError(getString(R.string.unsupported_archive_format))
            return
        }
        val displayWidth = listReaderPages.width
            .takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels.coerceAtLeast(MIN_READER_IMAGE_WIDTH)
        val decodeWidth = displayWidth * READER_PAGE_DECODE_SCALE

        pageAdapter?.close()
        pageAdapter = MangaPageAdapter(
            context = this,
            session = session,
            entries = entries,
            baseDisplayWidth = displayWidth,
            decodeWidth = decodeWidth,
            onPageReady = { position ->
                if (position == pendingRestorePosition) {
                    applyPendingReaderPosition()
                }
            },
            onPageTap = {
                handleReaderTap()
            }
        )
        listReaderPages.adapter = pageAdapter
        sliderReaderProgress.max = (entries.size - 1).coerceAtLeast(0)
        sliderReaderProgress.progress = 0
        if (shouldStartFromBeginning) {
            updateReaderProgress(0, entries.size)
        } else {
            restoreReaderPosition()
            updateReaderProgress(readerLayoutManager.findFirstVisibleItemPosition().coerceAtLeast(0), entries.size)
        }
        listReaderPages.post {
            pageAdapter?.preloadAround(
                firstVisiblePosition = readerLayoutManager.findFirstVisibleItemPosition().coerceAtLeast(0),
                visibleItemCount = visibleReaderItemCount().coerceAtLeast(1)
            )
        }
    }

    private fun handleReaderTap() {
        if (suppressReaderTap) {
            return
        }

        if (readerControlsVisible) {
            setReaderControlsVisible(false)
        } else {
            showReaderControlsTemporarily()
        }
    }

    private fun updateReaderTransform(scale: Float, horizontalPanX: Float) {
        suppressReaderTap = true
        handler.removeCallbacks(clearSuppressReaderTapRunnable)
        handler.postDelayed(clearSuppressReaderTapRunnable, SUPPRESS_TAP_AFTER_ZOOM_MS)

        val firstVisiblePosition = readerLayoutManager.findFirstVisibleItemPosition()
            .takeIf { it != RecyclerView.NO_POSITION }
            ?: 0
        val firstChildTop = readerLayoutManager.findViewByPosition(firstVisiblePosition)?.top
            ?: 0

        val scaleChanged = pageAdapter?.setReaderTransform(scale, horizontalPanX) ?: false
        pageAdapter?.applyTransformToVisiblePages(
            recyclerView = listReaderPages,
            resizePages = scaleChanged
        )
        if (scaleChanged) {
            readerLayoutManager.scrollToPositionWithOffset(firstVisiblePosition, firstChildTop)
        }
    }

    private fun updateReaderProgress(firstVisibleItem: Int, totalItemCount: Int) {
        if (totalItemCount <= 0) {
            tvReaderProgress.text = ""
            sliderReaderProgress.max = 0
            sliderReaderProgress.progress = 0
            return
        }

        val currentPosition = firstVisibleItem.coerceIn(0, totalItemCount - 1)
        updateReaderProgressText(currentPosition, totalItemCount)
        if (!isDraggingReaderSlider) {
            sliderReaderProgress.max = (totalItemCount - 1).coerceAtLeast(0)
            sliderReaderProgress.progress = currentPosition
        }
    }

    private fun updateReaderProgressText(position: Int, totalItemCount: Int) {
        if (totalItemCount <= 0) {
            tvReaderProgress.text = ""
            return
        }

        val currentPage = position.coerceIn(0, totalItemCount - 1) + 1
        tvReaderProgress.text = getString(R.string.reader_page_progress, currentPage, totalItemCount)
    }

    private fun jumpReaderToPage(position: Int) {
        if (imageEntries.isEmpty()) {
            return
        }

        clearPendingReaderPosition()
        val targetPosition = position.coerceIn(0, imageEntries.lastIndex)
        readerLayoutManager.scrollToPositionWithOffset(targetPosition, 0)
        currentReaderPosition = targetPosition
        currentReaderOffset = 0
        updateReaderProgress(targetPosition, imageEntries.size)
        pageAdapter?.preloadAround(targetPosition, visibleItemCount = 1)
    }

    private fun saveReaderPosition() {
        val file = archiveFile ?: return
        if (imageEntries.isEmpty()) {
            return
        }

        val firstVisible = readerLayoutManager.findFirstVisibleItemPosition()
            .takeIf { it != RecyclerView.NO_POSITION }
            ?: currentReaderPosition
        updateCurrentReaderPosition(firstVisible)

        readerPrefs.edit()
            .putInt(readerPositionKey(file), currentReaderPosition)
            .putInt(readerOffsetKey(file), currentReaderOffset)
            .apply()
    }

    private fun updateCurrentReaderPosition(firstVisiblePosition: Int) {
        currentReaderPosition = firstVisiblePosition.coerceAtLeast(0)
        currentReaderOffset = readerLayoutManager.findViewByPosition(currentReaderPosition)?.top
            ?: currentReaderOffset
    }

    private fun restoreReaderPosition() {
        val file = archiveFile ?: return
        val position = readerPrefs.getInt(readerPositionKey(file), 0)
            .coerceIn(0, imageEntries.lastIndex.coerceAtLeast(0))
        val offset = readerPrefs.getInt(readerOffsetKey(file), 0)

        currentReaderPosition = position
        currentReaderOffset = offset
        pendingRestorePosition = position
        pendingRestoreOffset = offset
        restoreAttemptCount = 0
        applyPendingReaderPosition()
    }

    private fun applyPendingReaderPosition() {
        val position = pendingRestorePosition ?: return
        if (imageEntries.isEmpty()) {
            clearPendingReaderPosition()
            return
        }

        listReaderPages.post {
            if (pendingRestorePosition != position) {
                return@post
            }

            readerLayoutManager.scrollToPositionWithOffset(position, pendingRestoreOffset)
            updateReaderProgress(position, imageEntries.size)

            if (restoreAttemptCount < RESTORE_READER_POSITION_MAX_ATTEMPTS) {
                restoreAttemptCount++
                schedulePendingReaderPositionRetry()
            } else {
                clearPendingReaderPosition()
            }
        }
    }

    private fun schedulePendingReaderPositionRetry() {
        if (restoreRetryScheduled) {
            return
        }

        restoreRetryScheduled = true
        handler.postDelayed(
            {
                restoreRetryScheduled = false
                applyPendingReaderPosition()
            },
            RESTORE_READER_POSITION_RETRY_MS
        )
    }

    private fun clearPendingReaderPosition() {
        pendingRestorePosition = null
        pendingRestoreOffset = 0
        restoreAttemptCount = 0
        restoreRetryScheduled = false
    }

    private fun visibleReaderItemCount(): Int {
        val first = readerLayoutManager.findFirstVisibleItemPosition()
        val last = readerLayoutManager.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) {
            return 1
        }
        return (last - first + 1).coerceAtLeast(1)
    }

    private fun showReaderControlsTemporarily() {
        setReaderControlsVisible(true)
        handler.removeCallbacks(autoHideControlsRunnable)
        handler.postDelayed(autoHideControlsRunnable, READER_CONTROLS_AUTO_HIDE_MS)
    }

    private fun setReaderControlsVisible(visible: Boolean) {
        readerControlsVisible = visible
        layoutReaderToolbar.visibility = if (visible) View.VISIBLE else View.GONE
        layoutReaderProgress.visibility = if (visible) View.VISIBLE else View.GONE
        setSystemBarsVisible(visible)
    }

    private fun setSystemBarsVisible(visible: Boolean) {
        val controller = WindowInsetsControllerCompat(window, rootView)
        if (visible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun showError(message: String) {
        tvReaderStatus.text = message
        tvReaderStatus.visibility = View.VISIBLE
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun readerPositionKey(file: File): String {
        return readerPositionKeyFor(file)
    }

    private fun readerOffsetKey(file: File): String {
        return readerOffsetKeyFor(file)
    }

    private class MangaPageAdapter(
        private val context: Context,
        private val session: ComicArchive.ReaderSession,
        private val entries: List<String>,
        private val baseDisplayWidth: Int,
        private val decodeWidth: Int,
        private val onPageReady: (position: Int) -> Unit,
        private val onPageTap: () -> Unit
    ) : RecyclerView.Adapter<MangaPageAdapter.PageViewHolder>() {

        private val mainHandler = Handler(Looper.getMainLooper())
        private val decodeExecutor = ThreadPoolExecutor(
            READER_DECODE_THREAD_COUNT,
            READER_DECODE_THREAD_COUNT,
            0L,
            TimeUnit.MILLISECONDS,
            PriorityBlockingQueue<Runnable>()
        )
        private val taskSequence = AtomicLong(0L)
        private val preloadGeneration = AtomicInteger(0)
        private val loadingPreviewPages = Collections.synchronizedSet(mutableSetOf<Int>())
        private val loadingFullPages = Collections.synchronizedSet(mutableSetOf<Int>())
        private val loadingTiles = Collections.synchronizedSet(mutableSetOf<TileKey>())
        private val failedPreviewPages = Collections.synchronizedSet(mutableSetOf<Int>())
        private val failedFullPages = Collections.synchronizedSet(mutableSetOf<Int>())
        private val tiledPages = Collections.synchronizedSet(mutableSetOf<Int>())
        private val pageBounds = Collections.synchronizedMap(mutableMapOf<Int, ComicArchive.ImageBounds>())
        private val cacheLock = Any()
        private val previewBitmapCache = object : LruCache<Int, Bitmap>(previewBitmapCacheSizeKb()) {
            override fun sizeOf(key: Int, value: Bitmap): Int {
                return (value.byteCount / 1024).coerceAtLeast(1)
            }
        }
        private val fullBitmapCache = object : LruCache<Int, Bitmap>(fullBitmapCacheSizeKb()) {
            override fun sizeOf(key: Int, value: Bitmap): Int {
                return (value.byteCount / 1024).coerceAtLeast(1)
            }
        }
        private val tileBitmapCache = object : LruCache<TileKey, Bitmap>(tileBitmapCacheSizeKb()) {
            override fun sizeOf(key: TileKey, value: Bitmap): Int {
                return (value.byteCount / 1024).coerceAtLeast(1)
            }
        }

        @Volatile
        private var closed = false

        @Volatile
        private var preloadWindowStart = 0

        @Volatile
        private var preloadWindowEnd = -1

        private var zoomScale = ZoomableReaderRecyclerView.MIN_ZOOM
        private var horizontalPanX = 0f

        init {
            setHasStableIds(true)
        }

        override fun getItemCount(): Int = entries.size

        override fun getItemId(position: Int): Long = position.toLong()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
            val container = FrameLayout(context).apply {
                setBackgroundColor(Color.BLACK)
                clipChildren = true
                isClickable = true
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    MIN_READER_PAGE_HEIGHT
                )
            }
            val imageView = ImageView(context).apply {
                setBackgroundColor(Color.BLACK)
                scaleType = ImageView.ScaleType.FIT_XY
                adjustViewBounds = false
                contentDescription = context.getString(R.string.reader_image)
            }
            val tileContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                clipChildren = true
            }
            container.addView(imageView)
            container.addView(tileContainer)
            return PageViewHolder(container, imageView, tileContainer).also {
                container.setOnClickListener {
                    onPageTap()
                }
            }
        }

        override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
            holder.boundPosition = position
            bindBestAvailable(holder, position)
            ensureVisiblePage(position)
        }

        override fun onViewRecycled(holder: PageViewHolder) {
            holder.boundPosition = RecyclerView.NO_POSITION
            holder.imageView.setImageDrawable(null)
            holder.tileContainer.removeAllViews()
            super.onViewRecycled(holder)
        }

        fun preloadAround(firstVisiblePosition: Int, visibleItemCount: Int) {
            if (entries.isEmpty() || closed) {
                return
            }

            val firstVisible = firstVisiblePosition.coerceIn(0, entries.lastIndex)
            val lastVisible = (firstVisible + visibleItemCount.coerceAtLeast(1) - 1)
                .coerceIn(firstVisible, entries.lastIndex)
            val generation = preloadGeneration.incrementAndGet()
            preloadWindowStart = (firstVisible - READER_PRELOAD_BEFORE_COUNT).coerceAtLeast(0)
            preloadWindowEnd = (lastVisible + READER_PRELOAD_AFTER_COUNT).coerceAtMost(entries.lastIndex)

            val preloadPositions = mutableListOf<Int>()
            for (position in (lastVisible + 1)..preloadWindowEnd) {
                preloadPositions.add(position)
            }
            for (position in (firstVisible - 1) downTo preloadWindowStart) {
                preloadPositions.add(position)
            }

            preloadPositions.forEach { position ->
                ensurePreview(position, PRIORITY_PRELOAD_PREVIEW, generation, isPreload = true)
                ensureFullOrTiledPage(position, PRIORITY_PRELOAD, generation, isPreload = true)
            }
        }

        fun setReaderTransform(scale: Float, panX: Float): Boolean {
            val newScale = scale.coerceIn(
                ZoomableReaderRecyclerView.MIN_ZOOM,
                ZoomableReaderRecyclerView.MAX_ZOOM
            )
            val scaleChanged = newScale != zoomScale
            val panChanged = panX != horizontalPanX
            if (!scaleChanged && !panChanged) {
                return false
            }

            zoomScale = newScale
            horizontalPanX = panX
            return scaleChanged
        }

        fun applyTransformToVisiblePages(recyclerView: RecyclerView, resizePages: Boolean) {
            for (index in 0 until recyclerView.childCount) {
                val holder = recyclerView.getChildViewHolder(recyclerView.getChildAt(index))
                    as? PageViewHolder
                    ?: continue
                val position = recyclerView.getChildAdapterPosition(holder.itemView)
                if (position == RecyclerView.NO_POSITION) {
                    continue
                }

                if (resizePages) {
                    holder.boundPosition = position
                    bindBestAvailable(holder, position)
                    ensureVisiblePage(position)
                } else {
                    holder.imageView.translationX = horizontalPanX
                    holder.tileContainer.translationX = horizontalPanX
                }
            }
        }

        fun trimMemory(level: Int) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                synchronized(cacheLock) {
                    previewBitmapCache.evictAll()
                    tileBitmapCache.evictAll()
                }
                notifyDataSetChangedSafely()
            }
            if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
                synchronized(cacheLock) {
                    fullBitmapCache.evictAll()
                }
                notifyDataSetChangedSafely()
            }
        }

        fun close() {
            closed = true
            decodeExecutor.shutdownNow()
            synchronized(cacheLock) {
                previewBitmapCache.evictAll()
                fullBitmapCache.evictAll()
                tileBitmapCache.evictAll()
            }
            loadingPreviewPages.clear()
            loadingFullPages.clear()
            loadingTiles.clear()
            failedPreviewPages.clear()
            failedFullPages.clear()
            tiledPages.clear()
            pageBounds.clear()
            session.close()
        }

        private fun bindBestAvailable(holder: PageViewHolder, position: Int) {
            val bounds = pageBounds[position]
            val isTiled = position in tiledPages && bounds != null
            if (isTiled) {
                bindTiledPage(holder, position, bounds)
                return
            }

            val fullBitmap = fullBitmap(position)
            if (fullBitmap != null) {
                bindBitmap(holder, fullBitmap)
                return
            }

            val previewBitmap = previewBitmap(position)
            if (previewBitmap != null) {
                bindBitmap(holder, previewBitmap)
                return
            }

            bindPlaceholder(holder, bounds)
        }

        private fun bindPlaceholder(
            holder: PageViewHolder,
            bounds: ComicArchive.ImageBounds?
        ) {
            holder.tileContainer.visibility = View.GONE
            holder.tileContainer.removeAllViews()
            holder.imageView.visibility = View.VISIBLE
            holder.imageView.setImageDrawable(null)
            holder.imageView.setBackgroundColor(PLACEHOLDER_COLOR)

            val imageWidth = zoomedDisplayWidth()
            val imageHeight = if (bounds != null) {
                calculateDisplayHeight(bounds.width, bounds.height, imageWidth)
            } else {
                (imageWidth * ESTIMATED_READER_PAGE_HEIGHT_RATIO)
                    .roundToInt()
                    .coerceAtLeast(MIN_READER_PAGE_HEIGHT)
            }
            setContainerHeight(holder.container, imageHeight)
            setFrameChildSize(holder.imageView, imageWidth, imageHeight)
            holder.imageView.translationX = horizontalPanX
        }

        private fun bindBitmap(holder: PageViewHolder, bitmap: Bitmap) {
            holder.tileContainer.visibility = View.GONE
            holder.tileContainer.removeAllViews()
            holder.imageView.visibility = View.VISIBLE
            holder.imageView.setBackgroundColor(Color.BLACK)

            val imageWidth = zoomedDisplayWidth()
            val imageHeight = calculateDisplayHeight(bitmap.width, bitmap.height, imageWidth)
            setContainerHeight(holder.container, imageHeight)
            setFrameChildSize(holder.imageView, imageWidth, imageHeight)
            holder.imageView.translationX = horizontalPanX
            holder.imageView.setImageBitmap(bitmap)
        }

        private fun bindTiledPage(
            holder: PageViewHolder,
            position: Int,
            bounds: ComicArchive.ImageBounds
        ) {
            val imageWidth = zoomedDisplayWidth()
            val imageHeight = calculateDisplayHeight(bounds.width, bounds.height, imageWidth)
            setContainerHeight(holder.container, imageHeight)
            setFrameChildSize(holder.imageView, imageWidth, imageHeight)
            setFrameChildSize(holder.tileContainer, imageWidth, imageHeight)
            holder.imageView.translationX = horizontalPanX
            holder.tileContainer.translationX = horizontalPanX
            holder.imageView.visibility = View.VISIBLE
            holder.tileContainer.visibility = View.VISIBLE
            holder.imageView.setBackgroundColor(Color.BLACK)
            holder.imageView.setImageBitmap(previewBitmap(position))

            val tiles = buildTiles(bounds, imageWidth)
            holder.tileContainer.removeAllViews()
            tiles.forEach { tile ->
                val tileView = ImageView(context).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    scaleType = ImageView.ScaleType.FIT_XY
                    adjustViewBounds = false
                }
                val tileHeight = calculateDisplayHeight(bounds.width, tile.sourceRect.height(), imageWidth)
                setLinearChildSize(tileView, imageWidth, tileHeight)
                val key = TileKey(position, tile.index, imageWidth)
                tileView.tag = key

                val cachedTile = tileBitmap(key)
                if (cachedTile != null) {
                    tileView.setImageBitmap(cachedTile)
                } else {
                    ensureTile(position, tile, imageWidth, tileView, holder)
                }

                holder.tileContainer.addView(tileView)
            }
        }

        private fun ensureVisiblePage(position: Int) {
            ensurePreview(position, PRIORITY_VISIBLE_PREVIEW, generation = 0, isPreload = false)
            ensureFullOrTiledPage(position, PRIORITY_VISIBLE, generation = 0, isPreload = false)
        }

        private fun ensurePreview(
            position: Int,
            priority: Int,
            generation: Int,
            isPreload: Boolean
        ) {
            if (position !in entries.indices ||
                closed ||
                position in failedPreviewPages ||
                previewBitmap(position) != null ||
                !loadingPreviewPages.add(position)
            ) {
                return
            }

            submitTask(priority) {
                try {
                    if (isPreload && !isUsefulPreload(position, generation)) {
                        return@submitTask
                    }

                    val bitmap = runCatching {
                        session.decodePreviewForWidth(entries[position], previewDecodeWidth())
                    }.getOrNull()

                    if (bitmap == null) {
                        failedPreviewPages.add(position)
                        return@submitTask
                    }

                    putPreviewBitmap(position, bitmap)
                    notifyItemChangedOnMain(position)
                } finally {
                    loadingPreviewPages.remove(position)
                }
            }
        }

        private fun ensureFullOrTiledPage(
            position: Int,
            priority: Int,
            generation: Int,
            isPreload: Boolean
        ) {
            if (position !in entries.indices ||
                closed ||
                position in failedFullPages ||
                position in tiledPages ||
                fullBitmap(position) != null ||
                !loadingFullPages.add(position)
            ) {
                return
            }

            submitTask(priority) {
                try {
                    if (isPreload && !isUsefulPreload(position, generation)) {
                        return@submitTask
                    }

                    val bounds = pageBounds[position]
                        ?: runCatching { session.readBounds(entries[position]) }
                            .getOrNull()
                            ?.also { pageBounds[position] = it }

                    if (bounds == null) {
                        failedFullPages.add(position)
                        return@submitTask
                    }

                    if (shouldUseTiledPage(bounds)) {
                        if (previewBitmap(position) == null) {
                            runCatching {
                                session.decodePreviewForWidth(entries[position], previewDecodeWidth())
                            }.getOrNull()?.let { putPreviewBitmap(position, it) }
                        }
                        tiledPages.add(position)
                        notifyPageReady(position)
                        return@submitTask
                    }

                    val bitmap = runCatching {
                        session.decodeImageForWidth(entries[position], decodeWidth)
                    }.getOrNull()

                    if (bitmap == null) {
                        failedFullPages.add(position)
                        return@submitTask
                    }

                    putFullBitmap(position, bitmap)
                    notifyPageReady(position)
                } finally {
                    loadingFullPages.remove(position)
                }
            }
        }

        private fun ensureTile(
            position: Int,
            tile: PageTile,
            imageWidth: Int,
            tileView: ImageView,
            holder: PageViewHolder
        ) {
            if (closed || position !in entries.indices) {
                return
            }

            val key = TileKey(position, tile.index, imageWidth)
            if (tileBitmap(key) != null || !loadingTiles.add(key)) {
                return
            }

            submitTask(PRIORITY_TILE) {
                try {
                    val bitmap = runCatching {
                        session.decodeRegionForWidth(entries[position], tile.sourceRect, imageWidth)
                    }.getOrNull() ?: return@submitTask

                    putTileBitmap(key, bitmap)
                    mainHandler.post {
                        if (!closed && holder.boundPosition == position && tileView.tag == key) {
                            tileView.setImageBitmap(bitmap)
                        }
                    }
                } finally {
                    loadingTiles.remove(key)
                }
            }
        }

        private fun notifyPageReady(position: Int) {
            mainHandler.post {
                if (closed || position !in entries.indices) {
                    return@post
                }

                notifyItemChanged(position)
                onPageReady(position)
            }
        }

        private fun notifyItemChangedOnMain(position: Int) {
            mainHandler.post {
                if (!closed && position in entries.indices) {
                    notifyItemChanged(position)
                }
            }
        }

        private fun notifyDataSetChangedSafely() {
            mainHandler.post {
                if (!closed) {
                    notifyDataSetChanged()
                }
            }
        }

        private fun submitTask(priority: Int, block: () -> Unit) {
            if (closed) {
                return
            }

            runCatching {
                decodeExecutor.execute(
                    DecodeTask(
                        priority = priority,
                        sequence = taskSequence.getAndIncrement(),
                        block = block
                    )
                )
            }
        }

        private fun isUsefulPreload(position: Int, generation: Int): Boolean {
            return !closed &&
                generation == preloadGeneration.get() &&
                position in preloadWindowStart..preloadWindowEnd
        }

        private fun buildTiles(
            bounds: ComicArchive.ImageBounds,
            imageWidth: Int
        ): List<PageTile> {
            val sourceTileHeightByDisplay = (TILE_MAX_DISPLAY_HEIGHT.toFloat() * bounds.width / imageWidth)
                .roundToInt()
            val sourceTileHeightByPixels = (TILE_MAX_SOURCE_PIXELS / bounds.width.coerceAtLeast(1))
                .coerceAtLeast(TILE_MIN_SOURCE_HEIGHT)
            val sourceTileHeight = minOf(sourceTileHeightByDisplay, sourceTileHeightByPixels)
                .coerceAtLeast(TILE_MIN_SOURCE_HEIGHT)
            val tiles = mutableListOf<PageTile>()
            var top = 0
            var index = 0
            while (top < bounds.height) {
                val bottom = (top + sourceTileHeight).coerceAtMost(bounds.height)
                tiles.add(PageTile(index, Rect(0, top, bounds.width, bottom)))
                top = bottom
                index++
            }
            return tiles
        }

        private fun shouldUseTiledPage(bounds: ComicArchive.ImageBounds): Boolean {
            val sourcePixels = bounds.width.toLong() * bounds.height.toLong()
            val decodedHeight = calculateDisplayHeight(bounds.width, bounds.height, decodeWidth)
            val sampleSize = calculateSampleSizeForWidth(bounds.width, decodeWidth)
            val decodedPixels = (bounds.width / sampleSize).toLong() *
                (bounds.height / sampleSize).toLong()
            return bounds.height >= TILED_SOURCE_HEIGHT_THRESHOLD ||
                decodedHeight >= TILED_DECODED_HEIGHT_THRESHOLD ||
                sourcePixels >= TILED_SOURCE_PIXEL_THRESHOLD ||
                decodedPixels >= TILED_DECODED_PIXEL_THRESHOLD
        }

        private fun calculateSampleSizeForWidth(width: Int, targetWidth: Int): Int {
            if (width <= 0 || targetWidth <= 0) {
                return 1
            }

            var sampleSize = 1
            while (width / (sampleSize * 2) >= targetWidth) {
                sampleSize *= 2
            }
            return sampleSize
        }

        private fun setContainerHeight(container: FrameLayout, height: Int) {
            val layoutParams = container.layoutParams as? RecyclerView.LayoutParams
                ?: RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    height
                )
            if (layoutParams.height != height) {
                layoutParams.height = height
                container.layoutParams = layoutParams
            }
        }

        private fun setFrameChildSize(view: View, width: Int, height: Int) {
            val layoutParams = view.layoutParams as? FrameLayout.LayoutParams
                ?: FrameLayout.LayoutParams(width, height, Gravity.CENTER_HORIZONTAL)
            if (layoutParams.width != width || layoutParams.height != height) {
                layoutParams.width = width
                layoutParams.height = height
                layoutParams.gravity = Gravity.CENTER_HORIZONTAL
                view.layoutParams = layoutParams
            }
        }

        private fun setLinearChildSize(view: View, width: Int, height: Int) {
            val layoutParams = view.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(width, height)
            if (layoutParams.width != width || layoutParams.height != height) {
                layoutParams.width = width
                layoutParams.height = height
                view.layoutParams = layoutParams
            }
        }

        private fun calculateDisplayHeight(width: Int, height: Int, imageWidth: Int): Int {
            if (width <= 0 || height <= 0 || imageWidth <= 0) {
                return MIN_READER_PAGE_HEIGHT
            }

            return (imageWidth.toFloat() / width.toFloat() * height.toFloat())
                .roundToInt()
                .coerceAtLeast(1)
        }

        private fun zoomedDisplayWidth(): Int {
            return (baseDisplayWidth * zoomScale).roundToInt().coerceAtLeast(baseDisplayWidth)
        }

        private fun previewDecodeWidth(): Int {
            return (baseDisplayWidth / 2).coerceIn(MIN_PREVIEW_DECODE_WIDTH, MAX_PREVIEW_DECODE_WIDTH)
        }

        private fun previewBitmap(position: Int): Bitmap? {
            return synchronized(cacheLock) {
                previewBitmapCache.get(position)
            }
        }

        private fun fullBitmap(position: Int): Bitmap? {
            return synchronized(cacheLock) {
                fullBitmapCache.get(position)
            }
        }

        private fun tileBitmap(key: TileKey): Bitmap? {
            return synchronized(cacheLock) {
                tileBitmapCache.get(key)
            }
        }

        private fun putPreviewBitmap(position: Int, bitmap: Bitmap) {
            synchronized(cacheLock) {
                previewBitmapCache.put(position, bitmap)
            }
        }

        private fun putFullBitmap(position: Int, bitmap: Bitmap) {
            synchronized(cacheLock) {
                fullBitmapCache.put(position, bitmap)
            }
        }

        private fun putTileBitmap(key: TileKey, bitmap: Bitmap) {
            synchronized(cacheLock) {
                tileBitmapCache.put(key, bitmap)
            }
        }

        private class PageViewHolder(
            val container: FrameLayout,
            val imageView: ImageView,
            val tileContainer: LinearLayout
        ) : RecyclerView.ViewHolder(container) {
            var boundPosition: Int = RecyclerView.NO_POSITION
        }

        private data class PageTile(
            val index: Int,
            val sourceRect: Rect
        )

        private data class TileKey(
            val position: Int,
            val tileIndex: Int,
            val imageWidth: Int
        )

        private class DecodeTask(
            private val priority: Int,
            private val sequence: Long,
            private val block: () -> Unit
        ) : Runnable, Comparable<DecodeTask> {

            override fun run() {
                block()
            }

            override fun compareTo(other: DecodeTask): Int {
                val priorityComparison = other.priority.compareTo(priority)
                if (priorityComparison != 0) {
                    return priorityComparison
                }
                return sequence.compareTo(other.sequence)
            }
        }

        companion object {
            private const val READER_DECODE_THREAD_COUNT = 2
            private const val READER_PRELOAD_BEFORE_COUNT = 1
            private const val READER_PRELOAD_AFTER_COUNT = 3
            private const val ESTIMATED_READER_PAGE_HEIGHT_RATIO = 1.45f
            private const val MIN_READER_PAGE_HEIGHT = 320
            private const val PLACEHOLDER_COLOR = 0xFF101010.toInt()
            private const val MIN_PREVIEW_DECODE_WIDTH = 240
            private const val MAX_PREVIEW_DECODE_WIDTH = 720
            private const val PRIORITY_VISIBLE = 100
            private const val PRIORITY_VISIBLE_PREVIEW = 90
            private const val PRIORITY_TILE = 80
            private const val PRIORITY_PRELOAD = 30
            private const val PRIORITY_PRELOAD_PREVIEW = 20
            private const val TILED_SOURCE_HEIGHT_THRESHOLD = 8192
            private const val TILED_DECODED_HEIGHT_THRESHOLD = 8192
            private const val TILED_SOURCE_PIXEL_THRESHOLD = 42_000_000L
            private const val TILED_DECODED_PIXEL_THRESHOLD = 24_000_000L
            private const val TILE_MAX_DISPLAY_HEIGHT = 3072
            private const val TILE_MIN_SOURCE_HEIGHT = 512
            private const val TILE_MAX_SOURCE_PIXELS = 4_000_000

            private fun previewBitmapCacheSizeKb(): Int {
                val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
                return (maxMemoryKb / 16).coerceAtLeast(8 * 1024)
            }

            private fun fullBitmapCacheSizeKb(): Int {
                val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
                return (maxMemoryKb / 4).coerceAtLeast(24 * 1024)
            }

            private fun tileBitmapCacheSizeKb(): Int {
                val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
                return (maxMemoryKb / 4).coerceAtLeast(24 * 1024)
            }
        }
    }

    companion object {
        const val EXTRA_ARCHIVE_PATH = "archive_path"
        const val EXTRA_START_FROM_BEGINNING = "start_from_beginning"

        private const val READER_PREFS_NAME = "reader_prefs"
        private const val MIN_READER_IMAGE_WIDTH = 320
        private const val READER_PAGE_DECODE_SCALE = 3
        private const val READER_CONTROLS_AUTO_HIDE_MS = 2600L
        private const val SUPPRESS_TAP_AFTER_ZOOM_MS = 250L
        private const val RESTORE_READER_POSITION_MAX_ATTEMPTS = 16
        private const val RESTORE_READER_POSITION_RETRY_MS = 250L
        private const val READER_VIEW_CACHE_SIZE = 6

        fun hasSavedReadingProgress(context: Context, file: File): Boolean {
            val prefs = context.getSharedPreferences(READER_PREFS_NAME, Context.MODE_PRIVATE)
            val position = prefs.getInt(readerPositionKeyFor(file), 0)
            val offset = prefs.getInt(readerOffsetKeyFor(file), 0)
            return position > 0 || offset != 0
        }

        private fun clearSavedReadingProgress(context: Context, file: File) {
            context.getSharedPreferences(READER_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(readerPositionKeyFor(file))
                .remove(readerOffsetKeyFor(file))
                .apply()
        }

        private fun readerPositionKeyFor(file: File): String {
            return "${readerArchiveKeyFor(file)}:position"
        }

        private fun readerOffsetKeyFor(file: File): String {
            return "${readerArchiveKeyFor(file)}:offset"
        }

        private fun readerArchiveKeyFor(file: File): String {
            return "reader:${file.absolutePath}:${file.lastModified()}:${file.length()}"
        }
    }
}
