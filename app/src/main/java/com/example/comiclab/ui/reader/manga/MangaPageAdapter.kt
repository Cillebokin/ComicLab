package com.example.comiclab.ui.reader.manga

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.example.comiclab.AppSettings
import com.example.comiclab.ComicArchive
import com.example.comiclab.core.diagnostics.CrashLogManager
import com.example.comiclab.core.diagnostics.formatReaderDecodeQueueTiming
import com.example.comiclab.MAX_READER_DECODE_HEIGHT
import com.example.comiclab.MAX_READER_DECODE_WIDTH
import com.example.comiclab.MAX_READER_TILE_DECODE_WIDTH
import com.example.comiclab.R
import com.example.comiclab.readerPageContentGravity
import com.example.comiclab.core.reader.decode.calculatePdfPreviewWidth
import com.example.comiclab.core.reader.decode.calculatePdfRenderWidthByPixelBudget
import com.example.comiclab.core.reader.decode.pdfDecodeThreadCountForScroll
import com.example.comiclab.core.reader.decode.fastPdfPreviewPreloadPriorityForReader
import com.example.comiclab.core.reader.decode.isReaderTileVisible
import com.example.comiclab.core.reader.decode.pdfFullBitmapCacheSizeKb
import com.example.comiclab.core.reader.decode.pdfTileBitmapCacheSizeKb
import com.example.comiclab.core.reader.decode.reducedDecodeDimensionForRetry
import com.example.comiclab.core.reader.decode.shouldCancelPreloadAfterOutOfMemory
import com.example.comiclab.core.reader.decode.shouldPreloadBackgroundFullPagesForReader
import com.example.comiclab.core.reader.decode.shouldRefreshBoundReaderPageImmediately
import com.example.comiclab.core.reader.decode.shouldReleasePreviewAfterFullDecode
import com.example.comiclab.core.reader.decode.shouldRetryFullPageAfterFailure
import com.example.comiclab.core.reader.decode.shouldRetryPdfPageAfterFailure
import com.example.comiclab.core.reader.decode.shouldScheduleImmediateFullDecodeForReader
import com.example.comiclab.core.reader.decode.shouldSkipPdfPreloadAfterMemoryTrim
import com.example.comiclab.core.reader.decode.shouldSkipTiledPdfPreviewDecodeAfterMemoryTrim
import com.example.comiclab.core.reader.decode.shouldUsePdfTiledRendering
import com.example.comiclab.core.reader.decode.visiblePreviewPriorityForReader
import com.example.comiclab.core.reader.layout.ReaderPagePositionMapper
import com.example.comiclab.ui.reader.manga.page.calculateDisplayHeight
import com.example.comiclab.ui.reader.manga.page.calculateFitInsideSize
import com.example.comiclab.ui.reader.manga.page.calculateSampleSizeForWidth
import com.example.comiclab.ui.reader.manga.page.buildPageTiles
import com.example.comiclab.ui.reader.manga.page.PageTile
import com.example.comiclab.ui.reader.manga.page.PreloadWindow
import com.example.comiclab.ui.reader.manga.page.sourceToDisplayY
import java.io.File
import java.util.Collections
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
internal class MangaPageAdapter(
    private val context: Context,
    private val recyclerView: RecyclerView,
    private val archiveFile: File,
    private val session: ComicArchive.ImageReaderSession,
    private val entries: List<String>,
    private val baseDisplayWidth: Int,
    private val baseDisplayHeight: Int,
    private val decodeWidth: Int,
    private val decodeHeight: Int,
    private val readingDirection: String,
    private val doublePageReading: Boolean,
    private val coverSinglePage: Boolean,
    private val onPageReady: (position: Int) -> Unit,
    private val onPageTap: () -> Unit,
    private val spreadAnchorSourcePosition: Int? = null
) : RecyclerView.Adapter<MangaPageAdapter.PageViewHolder>(), ReaderPageAdapterController {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val isPdfSource = session.isPdfSource()
    private val hasIndependentPdfSessions = isPdfSource &&
        archiveFile.extension.equals("pdf", ignoreCase = true)
    private val decoderSessions = Collections.synchronizedSet(mutableSetOf(session))
    private val nextPdfWorkerSession = AtomicInteger(0)
    private val additionalPdfSessionLock = Any()
    @Volatile
    private var additionalPdfSession: ComicArchive.ImageReaderSession? = null
    private val decodeSessionForWorker: ThreadLocal<ComicArchive.ImageReaderSession> =
        ThreadLocal.withInitial<ComicArchive.ImageReaderSession> {
            if (!hasIndependentPdfSessions || nextPdfWorkerSession.getAndIncrement() == 0) {
                session
            } else {
                additionalPdfDecodeSession()
            }
        }
    private var decodeExecutor = ThreadPoolExecutor(
        READER_DECODE_THREAD_COUNT,
        if (hasIndependentPdfSessions) PDF_FAST_SCROLL_DECODE_THREAD_COUNT else READER_DECODE_THREAD_COUNT,
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
    private val loadingZoomFullPages = Collections.synchronizedSet(mutableSetOf<Int>())
    private val failedZoomFullPages = Collections.synchronizedSet(mutableSetOf<Int>())
    private val highQualityFullPages = Collections.synchronizedSet(mutableSetOf<Int>())
    private val zoomQualityRequestedPages = Collections.synchronizedSet(mutableSetOf<Int>())
    private val failedTileKeys = Collections.synchronizedSet(mutableSetOf<TileKey>())
    private val pdfPreviewRetryUsedPages = Collections.synchronizedSet(mutableSetOf<Int>())
    private val pdfFullFailureRetryUsedPages = Collections.synchronizedSet(mutableSetOf<Int>())
    private val fullDecodeRetriedPages = Collections.synchronizedSet(mutableSetOf<Int>())
    private val tiledPages = Collections.synchronizedSet(mutableSetOf<Int>())
    private val boundPositions = Collections.synchronizedSet(mutableSetOf<Int>())
    private val pendingRefreshPositions = Collections.synchronizedSet(mutableSetOf<Int>())
    private val pendingPdfIdleFullDecodeCallbacks = mutableMapOf<Int, Runnable>()
    private val pageBounds = Collections.synchronizedMap(mutableMapOf<Int, ComicArchive.ImageBounds>())
    @Volatile
    private var pdfFastScrollMode = false
    @Volatile
    private var pdfIdlePreviewPreloadMode = false
    private val positionMapper = ReaderPagePositionMapper(
        sourceItemCount = entries.size,
        doublePageReading = doublePageReading,
        coverSinglePage = coverSinglePage,
        spreadAnchorSourcePosition = spreadAnchorSourcePosition
    )
    private val cacheLock = Any()
    private val fullBitmapDecodeWidths = Collections.synchronizedMap(mutableMapOf<Int, Int>())
    private val previewBitmapCache = object : LruCache<Int, Bitmap>(previewBitmapCacheSizeKb(isPdfSource)) {
        override fun sizeOf(key: Int, value: Bitmap): Int {
            return (value.byteCount / 1024).coerceAtLeast(1)
        }
    }
    private val fullBitmapCache = object : LruCache<Int, Bitmap>(
        fullBitmapCacheSizeKb(isPdfSource, doublePageReading)
    ) {
        override fun sizeOf(key: Int, value: Bitmap): Int {
            return (value.byteCount / 1024).coerceAtLeast(1)
        }
    }
    private val tileBitmapCache = object : LruCache<TileKey, Bitmap>(tileBitmapCacheSizeKb(isPdfSource)) {
        override fun sizeOf(key: TileKey, value: Bitmap): Int {
            return (value.byteCount / 1024).coerceAtLeast(1)
        }
    }

    @Volatile
    private var closed = false

    @Volatile
    private var pdfLowMemoryMode = false

    @Volatile
    private var preloadWindowStart = 0

    @Volatile
    private var preloadWindowEnd = -1

    @Volatile
    private var visibleWindowStart = 0

    @Volatile
    private var visibleWindowEnd = -1

    @Volatile
    private var protectedWindowStart = 0

    @Volatile
    private var protectedWindowEnd = -1

    @Volatile
    private var refreshScheduled = false

    private val flushRefreshRunnable = Runnable {
        flushPendingRefreshes()
    }

    init {
        setHasStableIds(true)
    }

    private val adapterItemCount = positionMapper.adapterItemCount

    override fun getItemCount(): Int = adapterItemCount

    override fun readerItemCount(): Int = entries.size

    override fun allVisiblePagesHaveRenderableContent(recyclerView: RecyclerView): Boolean {
        val recyclerBounds = Rect()
        if (!recyclerView.getGlobalVisibleRect(recyclerBounds)) {
            return false
        }
        val visibleHolders = mutableListOf<PageViewHolder>()
        for (index in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(index)
            val childBounds = Rect()
            if (!child.getGlobalVisibleRect(childBounds) ||
                !Rect.intersects(recyclerBounds, childBounds)
            ) {
                continue
            }
            (recyclerView.getChildViewHolder(child) as? PageViewHolder)?.let(visibleHolders::add)
        }

        val visiblePositions = visibleHolders
            .mapNotNull { holder -> holder.boundPosition.takeIf { it in entries.indices } }
            .distinct()
        return areVisibleReaderPagesRenderable(visiblePositions) { position ->
            val holder = visibleHolders.firstOrNull { it.boundPosition == position }
                ?: return@areVisibleReaderPagesRenderable false
            holder.imageView.drawable != null || (0 until holder.tileContainer.childCount).any { index ->
                (holder.tileContainer.getChildAt(index) as? ImageView)?.drawable != null
            }
        }
    }

    override fun getItemId(position: Int): Long = position.toLong()

    private fun initialContainerLayoutParams(): RecyclerView.LayoutParams {
        return if (isHorizontalReading()) {
            RecyclerView.LayoutParams(
                baseDisplayWidth,
                baseDisplayHeight
            )
        } else {
            RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                MIN_READER_PAGE_HEIGHT
            )
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val container = ReaderPageZoomLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            clipChildren = true
            isClickable = true
            layoutParams = initialContainerLayoutParams()
        }
        val imageView = ImageView(context).apply {
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_XY
            adjustViewBounds = false
            contentDescription = context.getString(R.string.reader_image)
        }
        val tileContainer = FrameLayout(context).apply {
            visibility = View.GONE
            clipChildren = true
        }
        container.addView(imageView)
        container.addView(tileContainer)
        return PageViewHolder(container, imageView, tileContainer).also { holder ->
            holder.container.onTap = onPageTap
            holder.container.onZoomThresholdChanged = { isActive ->
                if (isActive) {
                    requestZoomQuality(holder)
                } else {
                    cancelZoomQuality(holder)
                }
            }
            holder.container.onTransformChanged = {
                scheduleVisibleTileRefresh(holder)
            }
        }
    }

    override fun onBindViewHolder(holder: PageViewHolder, adapterPosition: Int) {
        val previousPosition = holder.boundPosition
        if (previousPosition != RecyclerView.NO_POSITION) {
            boundPositions.remove(previousPosition)
        }
        val sourcePosition = positionMapper.sourcePositionForAdapterPosition(adapterPosition)
        holder.boundPosition = sourcePosition ?: RecyclerView.NO_POSITION
        if (previousPosition != holder.boundPosition) {
            if (previousPosition != RecyclerView.NO_POSITION) {
                zoomQualityRequestedPages.remove(previousPosition)
                cancelPendingPdfIdleFullDecode(previousPosition)
                cancelQueuedPreloadForPosition(previousPosition, DecodeTaskKind.PREVIEW)
                cancelQueuedPreloadForPosition(previousPosition, DecodeTaskKind.ZOOM_FULL)
            }
            holder.container.resetZoom()
        }
        holder.container.onZoomThresholdChanged = { isActive ->
            if (isActive) {
                requestZoomQuality(holder)
            } else {
                cancelZoomQuality(holder)
            }
        }
        holder.container.onTransformChanged = {
            scheduleVisibleTileRefresh(holder)
        }
        if (sourcePosition == null || sourcePosition !in entries.indices) {
            bindBlankPage(holder)
            return
        }
        boundPositions.add(sourcePosition)
        bindBestAvailable(holder, sourcePosition, adapterPosition)
        ensureVisiblePage(sourcePosition)
    }

    override fun onViewRecycled(holder: PageViewHolder) {
        cancelPendingTileRefresh(holder)
        if (holder.boundPosition != RecyclerView.NO_POSITION) {
            boundPositions.remove(holder.boundPosition)
            zoomQualityRequestedPages.remove(holder.boundPosition)
            cancelPendingPdfIdleFullDecode(holder.boundPosition)
            cancelQueuedPreloadForPosition(holder.boundPosition, DecodeTaskKind.PREVIEW)
            cancelQueuedPreloadForPosition(holder.boundPosition, DecodeTaskKind.ZOOM_FULL)
        }
        holder.boundPosition = RecyclerView.NO_POSITION
        holder.container.onZoomThresholdChanged = null
        holder.container.onTransformChanged = null
        holder.container.resetZoom()
        holder.imageView.setImageDrawable(null)
        holder.tileContainer.removeAllViews()
        super.onViewRecycled(holder)
    }

    override fun preloadAround(
        firstVisiblePosition: Int,
        visibleItemCount: Int,
        scrollDirection: Int,
        isFastScroll: Boolean,
        isIdle: Boolean,
        isDiscreteJump: Boolean
    ) {
        if (entries.isEmpty() || closed) {
            return
        }
        pdfFastScrollMode = isPdfSource && isFastScroll && !isIdle && !isDiscreteJump
        pdfIdlePreviewPreloadMode = isPdfSource && isIdle && !isDiscreteJump
        val desiredDecodeThreadCount = pdfDecodeThreadCountForScroll(
            isPdfSource = isPdfSource,
            isFastScroll = pdfFastScrollMode,
            hasIndependentSessions = hasIndependentPdfSessions
        ).coerceAtMost(decodeExecutor.maximumPoolSize)
        if (decodeExecutor.corePoolSize != desiredDecodeThreadCount) {
            decodeExecutor.corePoolSize = desiredDecodeThreadCount
        }
        if (isPdfSource && (!isIdle || isDiscreteJump)) {
            cancelPendingPdfIdleFullDecodes()
        }

        val firstVisibleAdapterPosition = positionMapper
            .spreadStartAdapterPosition(firstVisiblePosition)
        val visiblePositions = positionMapper.sourcePositionsInAdapterRange(
            firstAdapterPosition = firstVisibleAdapterPosition,
            visibleItemCount = visibleItemCount.coerceAtLeast(1)
        )
        if (visiblePositions.isEmpty()) {
            return
        }
        val firstVisible = visiblePositions.first()
        val lastVisible = visiblePositions.last()
        val generation = preloadGeneration.incrementAndGet()
        val window = PreloadWindow.from(
            firstVisible = firstVisible,
            lastVisible = lastVisible,
            lastIndex = entries.lastIndex,
            scrollDirection = scrollDirection,
            isFastScroll = isFastScroll,
            isIdle = isIdle,
            isPdfSource = isPdfSource
        )

        visibleWindowStart = firstVisible
        visibleWindowEnd = lastVisible
        protectedWindowStart = window.protectedStart
        protectedWindowEnd = window.protectedEnd
        preloadWindowStart = window.previewStart
        preloadWindowEnd = window.previewEnd

        cancelStaleZoomQualityRequests()
        cancelStalePreloadTasks(generation)
        trimDistantCaches()

        if (!shouldSkipPdfPreloadAfterMemoryTrim(
                isPdfSource = isPdfSource,
                lowMemoryMode = pdfLowMemoryMode,
                isPreload = true
            )
        ) {
            window.previewPositions.forEachIndexed { index, position ->
                val isAheadOfViewport = if (scrollDirection >= 0) {
                    position > lastVisible
                } else {
                    position < firstVisible
                }
                val priority = if (isPdfSource && isFastScroll && isAheadOfViewport) {
                    fastPdfPreviewPreloadPriorityForReader(
                        distanceFromViewport = index,
                        visiblePriority = PRIORITY_VISIBLE_PREVIEW,
                        backgroundPriority = PRIORITY_PRELOAD_PREVIEW
                    )
                } else {
                    PRIORITY_PRELOAD_PREVIEW
                }
                if (isPdfSource) {
                    updateQueuedPdfPreviewPriority(position, priority)
                }
                ensurePreview(position, priority, generation, isPreload = true)
            }
        }

        if (isPdfSource && isIdle && !isDiscreteJump) {
            (firstVisible..lastVisible).forEach(::schedulePdfIdleFullDecode)
        } else if (isPdfSource && shouldScheduleImmediateFullDecodeForReader(
                isPdfSource = true,
                isDiscreteJump = isDiscreteJump
            )
        ) {
            (firstVisible..lastVisible).forEach { position ->
                ensureFullOrTiledPage(
                    position,
                    PRIORITY_VISIBLE,
                    generation = 0,
                    isPreload = false
                )
            }
        }

        if (shouldPreloadFullPages(isFastScroll, isIdle)) {
            window.fullPositions.forEach { position ->
                ensureFullOrTiledPage(position, PRIORITY_PRELOAD, generation, isPreload = true)
            }
        }

        if (isPdfSource) {
            mainHandler.post {
                if (!closed) {
                    ensureVisibleTilesForBoundPages()
                }
            }
        }
    }

    override fun applyDebugZoomToVisiblePages(
        recyclerView: RecyclerView,
        scale: Float,
        panX: Float,
        panY: Float
    ) {
        for (index in 0 until recyclerView.childCount) {
            val holder = recyclerView.getChildViewHolder(recyclerView.getChildAt(index))
                as? PageViewHolder
                ?: continue
            holder.container.setZoomForDebug(scale, panX, panY)
        }
    }

    override fun trimMemory(level: Int) {
        if (isPdfSource && level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            pdfLowMemoryMode = true
            cancelQueuedPreloadTasks()
            clearNonProtectedCachesAfterOom()
            if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
                synchronized(cacheLock) {
                    fullBitmapCache.evictAll()
                    clearFullBitmapMetadata()
                }
            }
            clearVisibleBitmapReferences(
                clearFullBitmaps = level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE
            )
            notifyDataSetChangedSafely()
            return
        }

        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            synchronized(cacheLock) {
                previewBitmapCache.evictAll()
                tileBitmapCache.evictAll()
            }
            clearVisibleBitmapReferences(clearFullBitmaps = false)
            notifyDataSetChangedSafely()
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            synchronized(cacheLock) {
                fullBitmapCache.evictAll()
                clearFullBitmapMetadata()
            }
            clearVisibleBitmapReferences(clearFullBitmaps = true)
            notifyDataSetChangedSafely()
        }
    }

    override fun flushPendingRefreshesIfIdle() {
        mainHandler.post {
            flushPendingRefreshes()
        }
    }

    // 测试接口：仅供单元测试读取预览加载状态、触发预览请求与读取预览缓存，不参与运行时逻辑。
    internal val loadingPreviewPagesForTest: Set<Int>
        get() = loadingPreviewPages

    internal val loadingFullPagesForTest: Set<Int>
        get() = loadingFullPages

    internal val failedPreviewPagesForTest: Set<Int>
        get() = failedPreviewPages

    internal fun requestPreviewForTest(position: Int) {
        ensurePreview(position, 100, 0, false)
    }

    internal fun cachedPreviewForTest(position: Int): Bitmap? = previewBitmap(position)

    internal fun shouldUseTiledPageForTest(bounds: ComicArchive.ImageBounds): Boolean =
        shouldUseTiledPage(bounds)

    internal fun ensureFullOrTiledPageForTest(position: Int, priority: Int, generation: Int, isPreload: Boolean) {
        ensureFullOrTiledPage(position, priority, generation, isPreload)
    }

    // 测试接口：仅供单元测试注入解码执行器并直接触发预览解码，不参与运行时逻辑。
    internal var decodeExecutorForTest: ThreadPoolExecutor
        get() = decodeExecutor
        set(value) {
            decodeExecutor = value
        }

    internal fun ensurePreviewForTest(position: Int, priority: Int, generation: Int, isPreload: Boolean) {
        ensurePreview(position, priority, generation, isPreload)
    }

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        mainHandler.removeCallbacks(flushRefreshRunnable)
        cancelPendingPdfIdleFullDecodes()
        clearHolderCallbacks()
        clearVisibleBitmapReferences(clearFullBitmaps = true)
        decodeExecutor.shutdownNow()
        synchronized(cacheLock) {
            previewBitmapCache.evictAll()
            fullBitmapCache.evictAll()
            tileBitmapCache.evictAll()
            clearFullBitmapMetadata()
        }
        loadingPreviewPages.clear()
        loadingFullPages.clear()
        loadingZoomFullPages.clear()
        loadingTiles.clear()
        failedPreviewPages.clear()
        failedFullPages.clear()
        failedZoomFullPages.clear()
        zoomQualityRequestedPages.clear()
        failedTileKeys.clear()
        pdfPreviewRetryUsedPages.clear()
        pdfFullFailureRetryUsedPages.clear()
        fullDecodeRetriedPages.clear()
        tiledPages.clear()
        boundPositions.clear()
        pendingRefreshPositions.clear()
        pageBounds.clear()
        closeSessionAfterDecoderStops()
    }

    private fun clearHolderCallbacks() {
        for (index in 0 until recyclerView.childCount) {
            val holder = recyclerView.getChildViewHolder(recyclerView.getChildAt(index))
                as? PageViewHolder
                ?: continue
            cancelPendingTileRefresh(holder)
            holder.container.onZoomThresholdChanged = null
            holder.container.onTransformChanged = null
        }
    }

    private fun additionalPdfDecodeSession(): ComicArchive.ImageReaderSession {
        additionalPdfSession?.let { return it }
        synchronized(additionalPdfSessionLock) {
            additionalPdfSession?.let { return it }
            val opened = runCatching {
                ComicArchive.openReaderSession(archiveFile, context.cacheDir)
            }.onFailure {
                Log.w(READER_ADAPTER_LOG_TAG, "could not open an independent PDF decode session", it)
            }.getOrNull() ?: return session
            additionalPdfSession = opened
            decoderSessions.add(opened)
            return opened
        }
    }

    private fun decodeSessionForCurrentThread(): ComicArchive.ImageReaderSession =
        decodeSessionForWorker.get() ?: session

    private fun clearVisibleBitmapReferences(clearFullBitmaps: Boolean) {
        for (index in 0 until recyclerView.childCount) {
            val holder = recyclerView.getChildViewHolder(recyclerView.getChildAt(index))
                as? PageViewHolder
                ?: continue

            cancelPendingTileRefresh(holder)
            holder.tileContainer.removeAllViews()
            val position = holder.boundPosition
            if (clearFullBitmaps ||
                position in tiledPages ||
                fullBitmap(position) == null
            ) {
                holder.imageView.setImageDrawable(null)
            }
        }
    }

    private fun closeSessionAfterDecoderStops() {
        Thread(
            {
                if (!awaitDecodeExecutorTermination()) {
                    return@Thread
                }
                synchronized(cacheLock) {
                    previewBitmapCache.evictAll()
                    fullBitmapCache.evictAll()
                    tileBitmapCache.evictAll()
                    clearFullBitmapMetadata()
                }
                val sessionsToClose = synchronized(decoderSessions) { decoderSessions.toList() }
                sessionsToClose.forEach { decodeSession ->
                    runCatching { decodeSession.close() }
                        .onFailure {
                            Log.w(READER_ADAPTER_LOG_TAG, "reader session close failed", it)
                        }
                }
            },
            "ComicLabReaderSessionClose"
        ).apply { isDaemon = true }.start()
    }

    private fun awaitDecodeExecutorTermination(): Boolean {
        var interrupted = false
        try {
            if (!decodeExecutor.awaitTermination(
                    READER_CLOSE_WAIT_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS
                )
            ) {
                Log.w(
                    READER_ADAPTER_LOG_TAG,
                    "reader session close timed out; waiting for active decoder tasks to finish"
                )
            }
        } catch (failure: InterruptedException) {
            interrupted = true
            Log.w(
                READER_ADAPTER_LOG_TAG,
                "reader session close wait interrupted; continuing to protect the session",
                failure
            )
        }

        while (!decodeExecutor.isTerminated) {
            try {
                decodeExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt()
        }
        return true
    }

    private fun bindBestAvailable(
        holder: PageViewHolder,
        sourcePosition: Int,
        adapterPosition: Int
    ) {
        val bounds = pageBounds[sourcePosition]
        val isTiled = sourcePosition in tiledPages && bounds != null
        if (isTiled) {
            bindTiledPage(holder, sourcePosition, bounds)
            return
        }

        val fullBitmap = fullBitmap(sourcePosition)
        if (fullBitmap != null) {
            bindBitmap(holder, adapterPosition, fullBitmap)
            return
        }

        val previewBitmap = previewBitmap(sourcePosition)
        if (previewBitmap != null) {
            bindBitmap(holder, adapterPosition, previewBitmap)
            return
        }

        bindPlaceholder(holder, adapterPosition, bounds)
    }

    private fun bindBlankPage(holder: PageViewHolder) {
        holder.tileContainer.visibility = View.GONE
        holder.tileContainer.removeAllViews()
        holder.imageView.visibility = View.VISIBLE
        holder.imageView.setImageDrawable(null)
        holder.imageView.setBackgroundColor(Color.BLACK)
        setContainerSize(holder.container, baseDisplayWidth, baseDisplayHeight)
        setFrameChildSize(
            holder.imageView,
            baseDisplayWidth,
            baseDisplayHeight,
            Gravity.CENTER
        )
        holder.container.refreshContentLayout()
    }

    private fun bindPlaceholder(
        holder: PageViewHolder,
        position: Int,
        bounds: ComicArchive.ImageBounds?
    ) {
        holder.tileContainer.visibility = View.GONE
        holder.tileContainer.removeAllViews()
        holder.imageView.visibility = View.VISIBLE
        holder.imageView.setImageDrawable(null)
        holder.imageView.setBackgroundColor(PLACEHOLDER_COLOR)

        if (isHorizontalReading()) {
            setContainerSize(holder.container, baseDisplayWidth, baseDisplayHeight)
            val imageWidth = baseDisplayWidth
            val imageHeight = if (bounds != null) {
                val size = calculateFitInsideSize(
                    bounds.width,
                    bounds.height,
                    baseDisplayWidth,
                    baseDisplayHeight
                )
                size.height
            } else {
                baseDisplayHeight
            }
            val centeredWidth = if (bounds != null) {
                calculateFitInsideSize(
                    bounds.width,
                    bounds.height,
                    baseDisplayWidth,
                    baseDisplayHeight
                ).width
            } else {
                imageWidth
            }
            setFrameChildSize(
                holder.imageView,
                centeredWidth,
                imageHeight,
                readerPageContentGravity(readingDirection, position, doublePageReading)
            )
            holder.container.refreshContentLayout()
            return
        }

        val imageWidth = zoomedDisplayWidth()
        val imageHeight = if (bounds != null) {
            calculateDisplayHeight(
                bounds.width,
                bounds.height,
                imageWidth,
                MIN_READER_PAGE_HEIGHT
            )
        } else {
            (imageWidth * ESTIMATED_READER_PAGE_HEIGHT_RATIO)
                .roundToInt()
                .coerceAtLeast(MIN_READER_PAGE_HEIGHT)
        }
        setContainerHeight(holder.container, imageHeight)
        setFrameChildSize(holder.imageView, imageWidth, imageHeight)
        holder.container.refreshContentLayout()
    }

    private fun bindBitmap(holder: PageViewHolder, position: Int, bitmap: Bitmap) {
        holder.tileContainer.visibility = View.GONE
        holder.tileContainer.removeAllViews()
        holder.imageView.visibility = View.VISIBLE
        holder.imageView.setBackgroundColor(Color.BLACK)

        if (isHorizontalReading()) {
            val imageSize = calculateFitInsideSize(
                bitmap.width,
                bitmap.height,
                baseDisplayWidth,
                baseDisplayHeight
            )
            setContainerSize(holder.container, baseDisplayWidth, baseDisplayHeight)
            setFrameChildSize(
                holder.imageView,
                imageSize.width,
                imageSize.height,
                readerPageContentGravity(readingDirection, position, doublePageReading)
            )
            holder.imageView.setImageBitmap(bitmap)
            holder.container.refreshContentLayout()
            return
        }

        val imageWidth = zoomedDisplayWidth()
        val imageHeight = calculateDisplayHeight(
            bitmap.width,
            bitmap.height,
            imageWidth,
            MIN_READER_PAGE_HEIGHT
        )
        setContainerHeight(holder.container, imageHeight)
        setFrameChildSize(holder.imageView, imageWidth, imageHeight)
        holder.imageView.setImageBitmap(bitmap)
        holder.container.refreshContentLayout()
    }

    private fun bindTiledPage(
        holder: PageViewHolder,
        sourcePosition: Int,
        bounds: ComicArchive.ImageBounds
    ) {
        val imageWidth = zoomedDisplayWidth()
        val imageHeight = calculateDisplayHeight(
            bounds.width,
            bounds.height,
            imageWidth,
            MIN_READER_PAGE_HEIGHT
        )
        setContainerHeight(holder.container, imageHeight)
        setFrameChildSize(holder.imageView, imageWidth, imageHeight)
        setFrameChildSize(holder.tileContainer, imageWidth, imageHeight)
        holder.imageView.visibility = View.VISIBLE
        holder.tileContainer.visibility = View.VISIBLE
        holder.imageView.setBackgroundColor(Color.BLACK)
        holder.imageView.setImageBitmap(previewBitmap(sourcePosition))

        val tileDecodeWidth = tileDecodeWidth(imageWidth, bounds.width)
        val tiles = buildPageTiles(
            sourceWidth = bounds.width,
            sourceHeight = bounds.height,
            imageWidth = imageWidth,
            tileDecodeWidth = tileDecodeWidth,
            isPdfSource = isPdfSource
        )
        holder.tileContainer.removeAllViews()
        holder.container.refreshContentLayout()
        mainHandler.post {
            if (!closed && holder.boundPosition == sourcePosition) {
                ensureVisibleTiles(holder, sourcePosition, bounds, imageWidth, tileDecodeWidth, tiles)
            }
        }
    }

    private fun ensureVisibleTilesForHolder(holder: PageViewHolder) {
        val position = holder.boundPosition
        if (position !in tiledPages) {
            return
        }

        val bounds = pageBounds[position] ?: return
        val imageWidth = zoomedDisplayWidth()
        val tileDecodeWidth = tileDecodeWidth(imageWidth, bounds.width)
        val tiles = buildPageTiles(
            sourceWidth = bounds.width,
            sourceHeight = bounds.height,
            imageWidth = imageWidth,
            tileDecodeWidth = tileDecodeWidth,
            isPdfSource = isPdfSource
        )
        ensureVisibleTiles(holder, position, bounds, imageWidth, tileDecodeWidth, tiles)
    }

    private fun scheduleVisibleTileRefresh(holder: PageViewHolder) {
        if (closed || holder.boundPosition !in tiledPages || holder.tileRefreshRunnable != null) {
            return
        }

        val runnable = Runnable {
            holder.tileRefreshRunnable = null
            if (!closed) {
                ensureVisibleTilesForHolder(holder)
            }
        }
        holder.tileRefreshRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun cancelPendingTileRefresh(holder: PageViewHolder) {
        holder.tileRefreshRunnable?.let { mainHandler.removeCallbacks(it) }
        holder.tileRefreshRunnable = null
    }

    private fun ensureVisibleTilesForBoundPages() {
        for (index in 0 until recyclerView.childCount) {
            val holder = recyclerView.getChildViewHolder(recyclerView.getChildAt(index))
                as? PageViewHolder
                ?: continue
            ensureVisibleTilesForHolder(holder)
        }
    }

    private fun ensureVisibleTiles(
        holder: PageViewHolder,
        position: Int,
        bounds: ComicArchive.ImageBounds,
        imageWidth: Int,
        tileDecodeWidth: Int,
        tiles: List<PageTile>
    ) {
        if (closed || holder.boundPosition != position) {
            return
        }

        val imageHeight = calculateDisplayHeight(
            bounds.width,
            bounds.height,
            imageWidth,
            MIN_READER_PAGE_HEIGHT
        )
        val containerTop = holder.container.top
        val pageViewportTop = (-containerTop).coerceIn(0, imageHeight).toFloat()
        val pageViewportBottom = (recyclerView.height - containerTop)
            .coerceIn(0, imageHeight)
            .toFloat()
        val transformedViewport = holder.container.contentViewportForContainerRect(
            left = 0f,
            top = pageViewportTop,
            right = holder.container.width.toFloat().coerceAtLeast(imageWidth.toFloat()),
            bottom = pageViewportBottom
        ) ?: return
        val viewportTop = transformedViewport.top.coerceIn(0f, imageHeight.toFloat())
        val viewportBottom = transformedViewport.bottom.coerceIn(0f, imageHeight.toFloat())
        if (viewportBottom <= viewportTop) {
            holder.tileContainer.removeAllViews()
            return
        }

        val visibleKeys = mutableSetOf<TileKey>()
        tiles.forEach { tile ->
            val tileTop = sourceToDisplayY(tile.sourceRect.top, bounds.height, imageHeight)
            val tileBottom = sourceToDisplayY(tile.sourceRect.bottom, bounds.height, imageHeight)
                .coerceAtLeast(tileTop + 1)
            if (!isReaderTileVisible(tileTop, tileBottom, viewportTop, viewportBottom)) {
                return@forEach
            }

            val key = TileKey(position, tile.index, imageWidth, tileDecodeWidth)
            visibleKeys.add(key)
            val tileView = (0 until holder.tileContainer.childCount)
                .asSequence()
                .mapNotNull { childIndex -> holder.tileContainer.getChildAt(childIndex) as? ImageView }
                .firstOrNull { it.tag == key }
                ?: ImageView(context).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    scaleType = ImageView.ScaleType.FIT_XY
                    adjustViewBounds = false
                    tag = key
                    setTileChildSize(this, bounds, tile.sourceRect, imageWidth, imageHeight)
                    holder.tileContainer.addView(this)
                }
            val cachedTile = tileBitmap(key)
            if (cachedTile != null) {
                tileView.setImageBitmap(cachedTile)
            } else {
                ensureTile(position, tile, tileDecodeWidth, tileView, holder)
            }
        }

        for (childIndex in holder.tileContainer.childCount - 1 downTo 0) {
            val tileView = holder.tileContainer.getChildAt(childIndex)
            if (tileView.tag !in visibleKeys) {
                holder.tileContainer.removeViewAt(childIndex)
            }
        }
    }

    private fun ensureVisiblePage(position: Int) {
        if (isPdfSource) {
            ensurePreview(
                position,
                visiblePreviewPriorityForReader(
                    isPdfSource = true,
                    previewPriority = PRIORITY_VISIBLE_PREVIEW,
                    fullPriority = PRIORITY_VISIBLE
                ),
                generation = 0,
                isPreload = false
            )
            if (recyclerView.scrollState == RecyclerView.SCROLL_STATE_IDLE) {
                schedulePdfIdleFullDecode(position)
            }
            return
        }

        ensurePreview(position, PRIORITY_VISIBLE_PREVIEW, generation = 0, isPreload = false)
        ensureFullOrTiledPage(position, PRIORITY_VISIBLE, generation = 0, isPreload = false)
    }

    private fun requestZoomQuality(holder: PageViewHolder) {
        if (closed || !isPdfSource || !isHorizontalReading() ||
            !holder.itemView.isAttachedToWindow
        ) {
            return
        }

        val position = holder.boundPosition
        if (position !in entries.indices) {
            return
        }

        zoomQualityRequestedPages.add(position)
        ensureZoomQualityPage(position)
    }

    private fun cancelZoomQuality(holder: PageViewHolder) {
        val position = holder.boundPosition
        if (position !in entries.indices) {
            return
        }

        zoomQualityRequestedPages.remove(position)
        cancelQueuedPreloadForPosition(position, DecodeTaskKind.ZOOM_FULL)
    }

    private fun ensurePreview(
        position: Int,
        priority: Int,
        generation: Int,
        isPreload: Boolean
    ) {
        if (!isPreload) {
            cancelQueuedPreloadForPosition(position, DecodeTaskKind.PREVIEW)
        }

        if (position !in entries.indices ||
            closed ||
            previewBitmap(position) != null
        ) {
            return
        }
        if (position in failedPreviewPages) {
            if (isPreload) {
                return
            }
            if (isPdfSource && !shouldRetryPdfPageAfterFailure(
                    isPdfSource = true,
                    isPreload = isPreload,
                    retryAlreadyUsed = position in pdfPreviewRetryUsedPages
                )
            ) {
                return
            }
            failedPreviewPages.remove(position)
            if (isPdfSource) {
                pdfPreviewRetryUsedPages.add(position)
            }
        }

        val registeredLoading = loadingPreviewPages.add(position)
        if (!registeredLoading) {
            return
        }
        submitTask(
            priority = priority,
            position = position,
            generation = generation,
            kind = DecodeTaskKind.PREVIEW,
            cancelable = isPreload
        ) {
            try {
                if (isPreload && !isUsefulPreviewPreload(position, generation)) {
                    return@submitTask
                }
                if (previewBitmap(position) != null) {
                    return@submitTask
                }
                val targetWidth = previewDecodeWidth(isPreload)

                val bitmap = decodeWithRenderDiagnostics(
                    position = position,
                    kind = "PREVIEW",
                    targetWidth = targetWidth,
                    targetHeight = null
                ) {
                    runCatching {
                        decodeSessionForCurrentThread()
                            .decodePreviewForWidth(entries[position], targetWidth)
                    }.onFailure {
                        handleDecodeFailure(
                            position = position,
                            kind = DecodeTaskKind.PREVIEW,
                            throwable = it,
                            targetWidth = targetWidth
                        )
                    }.getOrNull()
                }

                if (bitmap == null) {
                    if (isPdfSource || !isPreload) {
                        failedPreviewPages.add(position)
                    }
                    if (isPdfSource && !isPreload) {
                        pdfPreviewRetryUsedPages.add(position)
                    }
                    return@submitTask
                }

                CrashLogManager.recordReaderRender(
                    context = context,
                    file = archiveFile,
                    position = position,
                    kind = "PREVIEW",
                    targetWidth = targetWidth,
                    targetHeight = null,
                    bitmapBytes = bitmap.byteCount
                )

                if (isPreload && !isUsefulPreviewPreload(position, generation)) {
                    bitmap.recycle()
                    return@submitTask
                }
                if (shouldSkipPdfPreloadAfterMemoryTrim(
                        isPdfSource = isPdfSource,
                        lowMemoryMode = pdfLowMemoryMode,
                        isPreload = isPreload
                    )
                ) {
                    bitmap.recycle()
                    return@submitTask
                }

                putPreviewBitmap(position, bitmap)
                requestItemRefresh(position, immediate = !isPreload)
            } finally {
                if (registeredLoading) {
                    loadingPreviewPages.remove(position)
                }
            }
        }
    }

    private fun ensureFullOrTiledPage(
        position: Int,
        priority: Int,
        generation: Int,
        isPreload: Boolean
    ) {
        if (!isPreload) {
            cancelPendingPdfIdleFullDecode(position)
            cancelQueuedPreloadForPosition(position, DecodeTaskKind.FULL)
        }

        if (position !in entries.indices ||
            closed ||
            position in tiledPages ||
            fullBitmap(position) != null
        ) {
            return
        }
        if (position in failedFullPages) {
            if (isPdfSource) {
                if (!shouldRetryPdfPageAfterFailure(
                        isPdfSource = true,
                        isPreload = isPreload,
                        retryAlreadyUsed = position in pdfFullFailureRetryUsedPages
                    )
                ) {
                    return
                }
                failedFullPages.remove(position)
                pdfFullFailureRetryUsedPages.add(position)
            }
            if (!isPdfSource && isPreload) {
                return
            }
            if (!isPdfSource) {
                failedFullPages.remove(position)
            }
        }

        val registeredLoading = loadingFullPages.add(position)
        if (!registeredLoading && isPreload) {
            return
        }

        submitTask(
            priority = priority,
            position = position,
            generation = generation,
            kind = DecodeTaskKind.FULL,
            cancelable = isPreload
        ) {
            try {
                if (isPreload && !isUsefulPreload(position, generation)) {
                    return@submitTask
                }
                if (position in tiledPages || fullBitmap(position) != null) {
                    return@submitTask
                }

                val bounds = pageBounds[position]
                    ?: runCatching { decodeSessionForCurrentThread().readBounds(entries[position]) }
                        .onFailure { handleDecodeFailure(position, DecodeTaskKind.FULL, it) }
                        .getOrNull()
                        ?.also { pageBounds[position] = it }

                if (bounds == null) {
                    if (isPdfSource || !isPreload) {
                        failedFullPages.add(position)
                    }
                    if (isPdfSource && !isPreload) {
                        pdfFullFailureRetryUsedPages.add(position)
                    }
                    return@submitTask
                }

                if (isPreload && !isUsefulPreload(position, generation)) {
                    return@submitTask
                }

                val shouldUseTiles = shouldUseTiledPage(bounds)
                if (shouldUseTiles) {
                    if (shouldSkipTiledPdfPreviewDecodeAfterMemoryTrim(
                            isPdfSource = isPdfSource,
                            lowMemoryMode = pdfLowMemoryMode,
                            isPreload = isPreload
                        )
                    ) {
                        return@submitTask
                    }

                    if (previewBitmap(position) == null) {
                        val previewWidth = previewDecodeWidth()
                        val previewBitmap = decodeWithRenderDiagnostics(
                            position = position,
                            kind = "PREVIEW",
                            targetWidth = previewWidth,
                            targetHeight = null
                        ) {
                            runCatching {
                                decodeSessionForCurrentThread()
                                    .decodePreviewForWidth(entries[position], previewWidth)
                            }.onFailure {
                                handleDecodeFailure(
                                    position = position,
                                    kind = DecodeTaskKind.PREVIEW,
                                    throwable = it,
                                    targetWidth = previewWidth
                                )
                            }.getOrNull()
                        }
                        if (previewBitmap != null) {
                            if (shouldSkipTiledPdfPreviewDecodeAfterMemoryTrim(
                                    isPdfSource = isPdfSource,
                                    lowMemoryMode = pdfLowMemoryMode,
                                    isPreload = isPreload
                                )
                            ) {
                                previewBitmap.recycle()
                                return@submitTask
                            }
                            putPreviewBitmap(position, previewBitmap)
                        }
                    }
                    if (previewBitmap(position) != null) {
                        if (isPreload && !isUsefulPreload(position, generation)) {
                            return@submitTask
                        }
                        tiledPages.add(position)
                        notifyPageReady(position, immediate = !isPreload)
                        return@submitTask
                    }

                    // 背景预加载不因预览失败而解码整张大图，等页面真正可见时再重试。
                    if (isPreload) {
                        return@submitTask
                    }
                }

                // 分块预览失败时退回整页低分辨率解码，避免页面没有任何可显示内容。
                val targetWidth = if (shouldUseTiles) {
                    previewDecodeWidth()
                } else {
                    fullPageDecodeWidth(bounds)
                }
                val targetHeight = fullPageDecodeHeight(bounds)
                var bitmap = decodeFullPage(
                    position = position,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight,
                    fitWithinBounds = shouldUseTiles
                )

                if (bitmap == null && isPdfSource && shouldRetryFullPageAfterFailure(
                        isPreload = isPreload,
                        alreadyRetried = position in fullDecodeRetriedPages
                    )
                ) {
                    fullDecodeRetriedPages.add(position)
                    val retryWidth = reducedDecodeDimensionForRetry(
                        currentDimension = targetWidth,
                        minimumDimension = previewDecodeWidth()
                    )
                    val retryHeight = reducedDecodeDimensionForRetry(
                        currentDimension = targetHeight,
                        minimumDimension = baseDisplayHeight
                    )
                    bitmap = decodeFullPage(
                        position = position,
                        targetWidth = retryWidth,
                        targetHeight = retryHeight,
                        fitWithinBounds = shouldUseTiles
                    )
                }

                if (bitmap == null) {
                    if (isPdfSource || !isPreload) {
                        failedFullPages.add(position)
                    }
                    if (isPdfSource && !isPreload) {
                        pdfFullFailureRetryUsedPages.add(position)
                    }
                    return@submitTask
                }

                if (isPreload && !isUsefulPreload(position, generation)) {
                    bitmap.recycle()
                    return@submitTask
                }
                if (shouldSkipPdfPreloadAfterMemoryTrim(
                        isPdfSource = isPdfSource,
                        lowMemoryMode = pdfLowMemoryMode,
                        isPreload = isPreload
                    )
                ) {
                    bitmap.recycle()
                    return@submitTask
                }

                if (shouldReleasePreviewAfterFullDecode(position in tiledPages)) {
                    removePreviewBitmap(position)
                }
                if (putFullBitmap(position, bitmap)) {
                    notifyPageReady(position, immediate = !isPreload)
                }
            } finally {
                if (registeredLoading) {
                    loadingFullPages.remove(position)
                }
            }
        }
    }

    private fun ensureZoomQualityPage(position: Int) {
        // 普通图片已经按最大缩放级别解码；PDF 横屏双页受像素预算限制，进入高倍缩放时再升级。
        if (closed || !isPdfSource || !isHorizontalReading() || pdfLowMemoryMode ||
            position !in entries.indices || position in tiledPages
        ) {
            return
        }

        if (position in highQualityFullPages && fullBitmap(position) != null) {
            return
        }
        highQualityFullPages.remove(position)
        if (position in failedZoomFullPages || !loadingZoomFullPages.add(position)) {
            return
        }

        submitTask(
            priority = PRIORITY_ZOOM_FULL,
            position = position,
            generation = preloadGeneration.get(),
            kind = DecodeTaskKind.ZOOM_FULL,
            cancelable = true
        ) {
            try {
                if (closed || pdfLowMemoryMode || position !in zoomQualityRequestedPages) {
                    return@submitTask
                }

                val bounds = pageBounds[position]
                    ?: runCatching { decodeSessionForCurrentThread().readBounds(entries[position]) }
                        .onFailure {
                            handleDecodeFailure(position, DecodeTaskKind.ZOOM_FULL, it)
                        }
                        .getOrNull()
                        ?.also { pageBounds[position] = it }
                    ?: run {
                        failedZoomFullPages.add(position)
                        return@submitTask
                    }

                val targetWidth = zoomFullPageDecodeWidth(bounds)
                val currentBitmap = fullBitmap(position)
                if (currentBitmap != null && fullBitmapDecodeWidth(position) >= targetWidth) {
                    highQualityFullPages.add(position)
                    return@submitTask
                }

                val targetHeight = zoomFullPageDecodeHeight(bounds, targetWidth)
                if (!canFitFullBitmapInCache(targetWidth, targetHeight)) {
                    return@submitTask
                }

                val bitmap = decodeFullPage(
                    position = position,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight,
                    kind = DecodeTaskKind.ZOOM_FULL
                ) ?: run {
                    failedZoomFullPages.add(position)
                    return@submitTask
                }

                if (closed || pdfLowMemoryMode || position !in zoomQualityRequestedPages) {
                    bitmap.recycle()
                    return@submitTask
                }

                failedZoomFullPages.remove(position)
                if (putFullBitmap(position, bitmap, bitmap.width)) {
                    highQualityFullPages.add(position)
                    notifyPageReady(position, immediate = true)
                }
            } finally {
                loadingZoomFullPages.remove(position)
            }
        }
    }

    private fun <T> decodeWithRenderDiagnostics(
        position: Int,
        kind: String,
        targetWidth: Int?,
        targetHeight: Int?,
        block: () -> T
    ): T {
        runCatching {
            if (isPdfSource) {
                CrashLogManager.recordReaderRenderStarted(
                    context = context,
                    file = archiveFile,
                    position = position,
                    kind = kind,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight
                )
            } else {
                CrashLogManager.recordReaderRender(
                    context = context,
                    file = archiveFile,
                    position = position,
                    kind = kind,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight
                )
            }
        }

        return try {
            block()
        } finally {
            if (isPdfSource) {
                runCatching {
                    CrashLogManager.recordReaderRenderCompleted(context)
                }
            }
        }
    }

    private fun decodeFullPage(
        position: Int,
        targetWidth: Int,
        targetHeight: Int,
        kind: DecodeTaskKind = DecodeTaskKind.FULL,
        fitWithinBounds: Boolean = false
    ): Bitmap? {
        val renderKind = if (kind == DecodeTaskKind.ZOOM_FULL) "ZOOM_FULL" else "FULL"
        return decodeWithRenderDiagnostics(
            position = position,
            kind = renderKind,
            targetWidth = targetWidth,
            targetHeight = targetHeight
        ) {
            runCatching {
                if (isHorizontalReading() || fitWithinBounds) {
                    decodeSessionForCurrentThread().decodeImageForPage(
                        entryName = entries[position],
                        targetWidth = targetWidth,
                        targetHeight = targetHeight
                    )
                } else {
                    decodeSessionForCurrentThread().decodeImageForWidth(
                        entries[position],
                        targetWidth
                    )
                }
            }.onFailure {
                handleDecodeFailure(
                    position = position,
                    kind = kind,
                    throwable = it,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight
                )
            }.getOrNull()
        }?.also { bitmap ->
            CrashLogManager.recordReaderRender(
                context = context,
                file = archiveFile,
                position = position,
                kind = renderKind,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                bitmapBytes = bitmap.byteCount
            )
        }
    }

    private fun ensureTile(
        position: Int,
        tile: PageTile,
        tileDecodeWidth: Int,
        tileView: ImageView,
        holder: PageViewHolder
    ) {
        if (closed || position !in entries.indices) {
            return
        }

        val displayImageWidth = (tileView.layoutParams?.width ?: zoomedDisplayWidth())
            .coerceAtLeast(baseDisplayWidth)
        val key = TileKey(position, tile.index, displayImageWidth, tileDecodeWidth)
        if (tileBitmap(key) != null || key in failedTileKeys || !loadingTiles.add(key)) {
            return
        }

        submitTask(
            priority = PRIORITY_TILE,
            position = position,
            generation = preloadGeneration.get(),
            kind = DecodeTaskKind.TILE,
            cancelable = true,
            tileKey = key
        ) {
            try {
                val targetHeight = (
                    tile.sourceRect.height().toLong() * tileDecodeWidth /
                        tile.sourceRect.width().coerceAtLeast(1)
                    ).toInt().coerceAtLeast(1)
                val bitmap = decodeWithRenderDiagnostics(
                    position = position,
                    kind = "TILE",
                    targetWidth = tileDecodeWidth,
                    targetHeight = targetHeight
                ) {
                    runCatching {
                            decodeSessionForCurrentThread().decodeRegionForWidth(
                            entries[position],
                            tile.sourceRect,
                            tileDecodeWidth
                        )
                    }.onFailure {
                        handleDecodeFailure(
                            position = position,
                            kind = DecodeTaskKind.TILE,
                            throwable = it,
                            targetWidth = tileDecodeWidth,
                            targetHeight = targetHeight
                        )
                    }.getOrNull()
                } ?: run {
                    failedTileKeys.add(key)
                    return@submitTask
                }

                CrashLogManager.recordReaderRender(
                    context = context,
                    file = archiveFile,
                    position = position,
                    kind = "TILE",
                    targetWidth = tileDecodeWidth,
                    targetHeight = targetHeight,
                    bitmapBytes = bitmap.byteCount
                )

                mainHandler.post {
                    if (!closed &&
                        holder.itemView.isAttachedToWindow &&
                        holder.boundPosition == position &&
                        tileView.parent === holder.tileContainer &&
                        tileView.tag == key &&
                        putTileBitmap(key, bitmap)
                    ) {
                        tileView.setImageBitmap(bitmap)
                    } else {
                        bitmap.recycle()
                    }
                }
            } finally {
                loadingTiles.remove(key)
            }
        }
    }

    private fun notifyPageReady(position: Int, immediate: Boolean) {
        mainHandler.post {
            if (closed || position !in entries.indices) {
                return@post
            }

            requestItemRefresh(position, immediate || position in boundPositions)
            onPageReady(position)
        }
    }

    private fun requestItemRefresh(position: Int, immediate: Boolean) {
        if (closed || position !in entries.indices || !shouldRefreshPosition(position)) {
            return
        }

        if (immediate) {
            mainHandler.post {
                if (!closed && position in entries.indices && shouldRefreshPosition(position)) {
                    if (refreshBoundPositionDirectly(position)) {
                        return@post
                    }
                    refreshPositionOrDefer(position)
                }
            }
            return
        }

        mainHandler.post {
            if (closed || position !in entries.indices || !shouldRefreshPosition(position)) {
                return@post
            }

            pendingRefreshPositions.add(position)
            if (!refreshScheduled) {
                refreshScheduled = true
                mainHandler.postDelayed(flushRefreshRunnable, UI_REFRESH_THROTTLE_MS)
            }
        }
    }

    private fun flushPendingRefreshes() {
        refreshScheduled = false
        if (closed) {
            return
        }
        if (recyclerView.isComputingLayout) {
            scheduleRefreshFlush()
            return
        }

        val positions = synchronized(pendingRefreshPositions) {
            pendingRefreshPositions.toList().also {
                pendingRefreshPositions.clear()
            }
        }

        positions.forEach { position ->
            if (closed || position !in entries.indices || !shouldRefreshPosition(position)) {
                return@forEach
            }

            if (refreshBoundPositionDirectly(position)) {
                return@forEach
            }

            if (canNotifyRecyclerView()) {
                val adapterPosition = positionMapper.adapterPositionForSourcePosition(position)
                if (adapterPosition >= 0) {
                    notifyItemChanged(adapterPosition)
                }
            } else {
                pendingRefreshPositions.add(position)
            }
        }

        if (pendingRefreshPositions.isNotEmpty()) {
            scheduleRefreshFlush()
        }
    }

    private fun refreshPositionOrDefer(position: Int) {
        if (refreshBoundPositionDirectly(position)) {
            return
        }

        if (canNotifyRecyclerView()) {
            val adapterPosition = positionMapper.adapterPositionForSourcePosition(position)
            if (adapterPosition >= 0) {
                notifyItemChanged(adapterPosition)
            }
            return
        }

        pendingRefreshPositions.add(position)
        scheduleRefreshFlush()
    }

    private fun scheduleRefreshFlush() {
        if (refreshScheduled || closed) {
            return
        }

        refreshScheduled = true
        mainHandler.postDelayed(flushRefreshRunnable, UI_REFRESH_RETRY_MS)
    }

    private fun refreshBoundPositionDirectly(position: Int): Boolean {
        if (recyclerView.isComputingLayout) {
            return false
        }

        val adapterPosition = positionMapper.adapterPositionForSourcePosition(position)
        if (adapterPosition < 0) {
            return false
        }

        val holder = recyclerView.findViewHolderForAdapterPosition(adapterPosition) as? PageViewHolder
            ?: return false
        if (!shouldRefreshBoundReaderPageImmediately(
                isBoundToPosition = holder.boundPosition == position,
                isComputingLayout = recyclerView.isComputingLayout
            )
        ) {
            return false
        }

        bindBestAvailable(holder, position, adapterPosition)
        return true
    }

    private fun canNotifyRecyclerView(): Boolean {
        return !recyclerView.isComputingLayout &&
            recyclerView.scrollState == RecyclerView.SCROLL_STATE_IDLE
    }

    private fun notifyDataSetChangedSafely() {
        mainHandler.post {
            if (!closed && canNotifyRecyclerView()) {
                notifyDataSetChanged()
            } else if (!closed) {
                mainHandler.postDelayed(
                    {
                        if (!closed) {
                            notifyDataSetChangedSafely()
                        }
                    },
                    UI_REFRESH_RETRY_MS
                )
            }
        }
    }

    private fun submitTask(
        priority: Int,
        position: Int,
        generation: Int,
        kind: DecodeTaskKind,
        cancelable: Boolean,
        tileKey: TileKey? = null,
        block: () -> Unit
    ) {
        if (closed) {
            return
        }

        val task = DecodeTask(
            priority = priority,
            position = position,
            generation = generation,
            kind = kind,
            cancelable = cancelable,
            tileKey = tileKey,
            sequence = taskSequence.getAndIncrement(),
            enqueuedAtNanos = SystemClock.elapsedRealtimeNanos(),
            pendingBefore = decodeExecutor.queue.size + decodeExecutor.activeCount,
            logPdfTiming = isPdfSource,
            block = block
        )
        runCatching {
            decodeExecutor.execute(task)
        }.onFailure {
            clearLoadingForCanceledTask(task)
            handleDecodeFailure(position, kind, it)
        }
    }

    private fun handleDecodeFailure(
        position: Int,
        kind: DecodeTaskKind,
        throwable: Throwable,
        targetWidth: Int? = null,
        targetHeight: Int? = null
    ) {
        if (closed) {
            return
        }

        val runtime = Runtime.getRuntime()
        val usedHeapKb = (runtime.totalMemory() - runtime.freeMemory()) / 1024L
        val maxHeapKb = runtime.maxMemory() / 1024L
        val target = "${targetWidth ?: "?"}x${targetHeight ?: "?"}"
        runCatching {
            CrashLogManager.recordReaderRender(
                context = context,
                file = archiveFile,
                position = position,
                kind = kind.name,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                usedHeapKb = usedHeapKb,
                maxHeapKb = maxHeapKb
            )
        }
        if (throwable is OutOfMemoryError) {
            Log.w(
                READER_ADAPTER_LOG_TAG,
                "decode OOM kind=$kind position=$position target=$target " +
                    "usedHeapKb=$usedHeapKb maxHeapKb=$maxHeapKb",
                throwable
            )
            cancelNonVisiblePreloadTasksAfterOom()
            clearNonProtectedCachesAfterOom()
            System.gc()
        } else {
            Log.w(
                READER_ADAPTER_LOG_TAG,
                "decode failed kind=$kind position=$position target=$target " +
                    "usedHeapKb=$usedHeapKb maxHeapKb=$maxHeapKb",
                throwable
            )
        }
    }

    private fun cancelNonVisiblePreloadTasksAfterOom() {
        val queue = decodeExecutor.queue
        queue.toList().forEach { runnable ->
            val task = runnable as? DecodeTask ?: return@forEach
            if (!shouldCancelPreloadAfterOutOfMemory(
                    isCancelable = task.cancelable,
                    isVisible = task.position in visibleWindowStart..visibleWindowEnd
                )
            ) {
                return@forEach
            }

            if (queue.remove(task)) {
                task.cancelled = true
                clearLoadingForCanceledTask(task)
            }
        }
    }

    private fun cancelQueuedPreloadTasks() {
        val queue = decodeExecutor.queue
        queue.toList().forEach { runnable ->
            val task = runnable as? DecodeTask ?: return@forEach
            if (!task.cancelable) {
                return@forEach
            }

            if (queue.remove(task)) {
                task.cancelled = true
                clearLoadingForCanceledTask(task)
            }
        }
    }

    private fun clearNonProtectedCachesAfterOom() {
        val fullPositionsToRemove = synchronized(cacheLock) {
            previewBitmapCache.evictAll()
            tileBitmapCache.evictAll()
            fullBitmapCache.snapshot().keys
                .filter { it !in protectedWindowStart..protectedWindowEnd }
        }
        fullPositionsToRemove.forEach(::removeFullBitmap)
    }

    private fun removeFullBitmap(position: Int) {
        synchronized(cacheLock) {
            fullBitmapCache.remove(position)
            fullBitmapDecodeWidths.remove(position)
        }
        highQualityFullPages.remove(position)
    }

    private fun isUsefulPreload(position: Int, generation: Int): Boolean {
        return !closed &&
            generation == preloadGeneration.get() &&
            position in preloadWindowStart..preloadWindowEnd
    }

    private fun isUsefulPreviewPreload(position: Int, generation: Int): Boolean {
        return !closed &&
            position in preloadWindowStart..preloadWindowEnd &&
            (isPdfSource || generation == preloadGeneration.get())
    }

    private fun schedulePdfIdleFullDecode(position: Int) {
        if (!isPdfSource || closed || position !in entries.indices ||
            position !in boundPositions || position in loadingFullPages ||
            position in pendingPdfIdleFullDecodeCallbacks
        ) {
            return
        }

        val callback = Runnable {
            pendingPdfIdleFullDecodeCallbacks.remove(position)
            if (!closed && recyclerView.scrollState == RecyclerView.SCROLL_STATE_IDLE &&
                position in boundPositions
            ) {
                ensureFullOrTiledPage(position, PRIORITY_VISIBLE, generation = 0, isPreload = false)
            }
        }
        pendingPdfIdleFullDecodeCallbacks[position] = callback
        mainHandler.postDelayed(callback, PDF_IDLE_FULL_DECODE_DELAY_MS)
    }

    private fun cancelPendingPdfIdleFullDecode(position: Int) {
        pendingPdfIdleFullDecodeCallbacks.remove(position)?.let { callback ->
            mainHandler.removeCallbacks(callback)
        }
    }

    private fun cancelPendingPdfIdleFullDecodes() {
        pendingPdfIdleFullDecodeCallbacks.values.toList().forEach { callback ->
            mainHandler.removeCallbacks(callback)
        }
        pendingPdfIdleFullDecodeCallbacks.clear()
    }

    private fun cancelStaleZoomQualityRequests() {
        zoomQualityRequestedPages.toList().forEach { position ->
            if (position !in boundPositions ||
                position !in protectedWindowStart..protectedWindowEnd
            ) {
                zoomQualityRequestedPages.remove(position)
                cancelQueuedPreloadForPosition(position, DecodeTaskKind.ZOOM_FULL)
            }
        }
    }

    private fun shouldRefreshPosition(position: Int): Boolean {
        return position in visibleWindowStart..visibleWindowEnd || position in boundPositions
    }

    private fun cancelStalePreloadTasks(currentGeneration: Int) {
        val queue = decodeExecutor.queue
        queue.toList().forEach { runnable ->
            val task = runnable as? DecodeTask ?: return@forEach
            val isVisiblePdfPreview = isPdfSource &&
                task.kind == DecodeTaskKind.PREVIEW &&
                task.generation == 0
            if (!task.cancelable && !isVisiblePdfPreview) {
                return@forEach
            }

            val isStale = when {
                task.kind == DecodeTaskKind.ZOOM_FULL -> {
                    task.position !in zoomQualityRequestedPages ||
                        task.position !in boundPositions ||
                        task.position !in protectedWindowStart..protectedWindowEnd
                }

                isVisiblePdfPreview -> {
                    task.position !in visibleWindowStart..visibleWindowEnd
                }

                isPdfSource && task.kind == DecodeTaskKind.PREVIEW -> {
                    task.position !in preloadWindowStart..preloadWindowEnd
                }

                else -> {
                    task.generation != currentGeneration ||
                        task.position !in preloadWindowStart..preloadWindowEnd
                }
            }
            if (isStale) {
                if (queue.remove(task)) {
                    task.cancelled = true
                    clearLoadingForCanceledTask(task)
                }
            }
        }
    }

    private fun cancelQueuedPreloadForPosition(position: Int, kind: DecodeTaskKind) {
        val queue = decodeExecutor.queue
        queue.toList().forEach { runnable ->
            val task = runnable as? DecodeTask ?: return@forEach
            val isPdfVisiblePreview = isPdfSource && kind == DecodeTaskKind.PREVIEW
            if ((task.cancelable || isPdfVisiblePreview) &&
                task.position == position &&
                task.kind == kind
            ) {
                if (queue.remove(task)) {
                    task.cancelled = true
                    clearLoadingForCanceledTask(task)
                }
            }
        }
    }

    private fun updateQueuedPdfPreviewPriority(position: Int, priority: Int) {
        val queue = decodeExecutor.queue
        queue.toList().forEach { runnable ->
            val task = runnable as? DecodeTask ?: return@forEach
            if (task.kind != DecodeTaskKind.PREVIEW ||
                !task.cancelable ||
                task.position != position ||
                task.priority == priority
            ) {
                return@forEach
            }
            if (queue.remove(task)) {
                task.priority = priority
                queue.add(task)
            }
        }
    }

    private fun clearLoadingForCanceledTask(task: DecodeTask) {
        when (task.kind) {
            DecodeTaskKind.PREVIEW -> loadingPreviewPages.remove(task.position)
            DecodeTaskKind.FULL -> loadingFullPages.remove(task.position)
            DecodeTaskKind.ZOOM_FULL -> loadingZoomFullPages.remove(task.position)
            DecodeTaskKind.TILE -> task.tileKey?.let { loadingTiles.remove(it) }
        }
    }

    private fun trimDistantCaches() {
        if (protectedWindowEnd < protectedWindowStart) {
            return
        }

        val fullPositionsToRemove = synchronized(cacheLock) {
            tileBitmapCache.snapshot().keys
                .filter { it.position !in protectedWindowStart..protectedWindowEnd }
                .forEach { tileBitmapCache.remove(it) }

            if (isPdfSource ||
                fullBitmapCache.size() >= fullBitmapCache.maxSize() * FULL_CACHE_TRIM_THRESHOLD_PERCENT / 100
            ) {
                fullBitmapCache.snapshot().keys
                    .filter { it !in protectedWindowStart..protectedWindowEnd }
            } else {
                emptyList()
            }
        }
        fullPositionsToRemove.forEach(::removeFullBitmap)
    }

    private fun shouldUseTiledPage(bounds: ComicArchive.ImageBounds): Boolean {
        if (isHorizontalReading()) {
            return false
        }

        if (isPdfSource) {
            return shouldUsePdfTiledRendering(
                pageWidth = bounds.width,
                pageHeight = bounds.height,
                displayWidth = zoomedDisplayWidth(),
                renderPixelThreshold = PDF_TILED_RENDER_PIXEL_THRESHOLD
            )
        }

        // 按整页解码实际采用的宽度估算，避免横屏宽度大于原图时误判为超长页。
        val targetDecodeWidth = fullPageDecodeWidth(bounds)
        val sourcePixels = bounds.width.toLong() * bounds.height.toLong()
        val decodedHeight = calculateDisplayHeight(
            bounds.width,
            bounds.height,
            targetDecodeWidth,
            MIN_READER_PAGE_HEIGHT
        )
        val sampleSize = calculateSampleSizeForWidth(bounds.width, targetDecodeWidth)
        val decodedPixels = (bounds.width / sampleSize).toLong() *
            (bounds.height / sampleSize).toLong()
        return bounds.width > MAX_READER_DECODE_WIDTH ||
            bounds.height >= TILED_SOURCE_HEIGHT_THRESHOLD ||
            decodedHeight >= TILED_DECODED_HEIGHT_THRESHOLD ||
            sourcePixels >= TILED_SOURCE_PIXEL_THRESHOLD ||
            decodedPixels >= TILED_DECODED_PIXEL_THRESHOLD
    }

    private fun setContainerHeight(container: FrameLayout, height: Int) {
        setContainerSize(container, RecyclerView.LayoutParams.MATCH_PARENT, height)
    }

    private fun setContainerSize(container: FrameLayout, width: Int, height: Int) {
        val layoutParams = container.layoutParams as? RecyclerView.LayoutParams
            ?: RecyclerView.LayoutParams(
                width,
                height
            )
        if (layoutParams.width != width || layoutParams.height != height) {
            layoutParams.width = width
            layoutParams.height = height
            container.layoutParams = layoutParams
        }
    }

    private fun setFrameChildSize(
        view: View,
        width: Int,
        height: Int,
        gravity: Int = Gravity.CENTER_HORIZONTAL
    ) {
        val layoutParams = view.layoutParams as? FrameLayout.LayoutParams
            ?: FrameLayout.LayoutParams(width, height, gravity)
        if (layoutParams.width != width ||
            layoutParams.height != height ||
            layoutParams.gravity != gravity
        ) {
            layoutParams.width = width
            layoutParams.height = height
            layoutParams.gravity = gravity
            view.layoutParams = layoutParams
        }
    }

    private fun setTileChildSize(
        view: View,
        bounds: ComicArchive.ImageBounds,
        sourceRect: Rect,
        imageWidth: Int,
        imageHeight: Int
    ) {
        val top = sourceToDisplayY(sourceRect.top, bounds.height, imageHeight)
        val bottom = sourceToDisplayY(sourceRect.bottom, bounds.height, imageHeight)
            .coerceAtLeast(top + 1)
        val height = (bottom - top).coerceAtLeast(1)
        val layoutParams = view.layoutParams as? FrameLayout.LayoutParams
            ?: FrameLayout.LayoutParams(imageWidth, height)
        if (layoutParams.width != imageWidth ||
            layoutParams.height != height ||
            layoutParams.topMargin != top
        ) {
            layoutParams.width = imageWidth
            layoutParams.height = height
            layoutParams.topMargin = top
            view.layoutParams = layoutParams
        }
    }

    private fun zoomedDisplayWidth(): Int {
        return baseDisplayWidth
    }

    private fun previewDecodeWidth(isPreload: Boolean = false): Int {
        if (isPdfSource) {
            val regularWidth = calculatePdfPreviewWidth(
                baseDisplayWidth = baseDisplayWidth,
                minimumWidth = PDF_MIN_PREVIEW_DECODE_WIDTH,
                maximumWidth = PDF_MAX_PREVIEW_DECODE_WIDTH
            )
            return if (pdfFastScrollMode || (isPreload && pdfIdlePreviewPreloadMode)) {
                minOf(regularWidth, PDF_FAST_PREVIEW_DECODE_WIDTH)
            } else {
                regularWidth
            }
        }
        return (baseDisplayWidth / 2).coerceIn(MIN_PREVIEW_DECODE_WIDTH, MAX_PREVIEW_DECODE_WIDTH)
    }

    private fun fullPageDecodeWidth(bounds: ComicArchive.ImageBounds): Int {
        if (isPdfSource) {
            return calculatePdfRenderWidthByPixelBudget(
                pageWidth = bounds.width,
                pageHeight = bounds.height,
                baseDisplayWidth = baseDisplayWidth,
                renderScale = PDF_FULL_RENDER_SCALE,
                maxRenderWidth = PDF_MAX_FULL_RENDER_WIDTH,
                maxRenderPixels = if (doublePageReading) {
                    PDF_DOUBLE_PAGE_FULL_RENDER_PIXELS
                } else {
                    PDF_MAX_FULL_RENDER_PIXELS
                }
            )
        }

        val sourceWidth = bounds.width.coerceAtMost(MAX_READER_DECODE_WIDTH).coerceAtLeast(1)
        val minimumWidth = baseDisplayWidth.coerceAtMost(sourceWidth).coerceAtLeast(1)
        return decodeWidth
            .coerceAtMost(sourceWidth)
            .coerceAtLeast(minimumWidth)
    }

    private fun fullPageDecodeHeight(bounds: ComicArchive.ImageBounds): Int {
        if (isPdfSource) {
            return (baseDisplayHeight * PDF_FULL_RENDER_SCALE)
                .coerceAtLeast(baseDisplayHeight)
                .coerceAtMost(PDF_MAX_FULL_RENDER_HEIGHT)
        }

        if (!isHorizontalReading()) {
            return MAX_READER_DECODE_HEIGHT
        }

        val sourceHeight = bounds.height.coerceAtMost(MAX_READER_DECODE_HEIGHT).coerceAtLeast(1)
        val minimumHeight = baseDisplayHeight.coerceAtMost(sourceHeight).coerceAtLeast(1)
        return decodeHeight
            .coerceAtMost(sourceHeight)
            .coerceAtLeast(minimumHeight)
    }

    private fun zoomFullPageDecodeWidth(bounds: ComicArchive.ImageBounds): Int {
        return calculatePdfRenderWidthByPixelBudget(
            pageWidth = bounds.width,
            pageHeight = bounds.height,
            baseDisplayWidth = baseDisplayWidth,
            renderScale = PDF_ZOOM_RENDER_SCALE,
            maxRenderWidth = PDF_MAX_FULL_RENDER_WIDTH,
            maxRenderPixels = if (doublePageReading) {
                PDF_DOUBLE_PAGE_ZOOM_RENDER_PIXELS
            } else {
                PDF_ZOOM_RENDER_PIXELS
            }
        )
    }

    private fun zoomFullPageDecodeHeight(
        bounds: ComicArchive.ImageBounds,
        targetWidth: Int
    ): Int {
        if (bounds.width <= 0 || bounds.height <= 0 || targetWidth <= 0) {
            return baseDisplayHeight.coerceAtLeast(1)
        }

        return (bounds.height.toLong() * targetWidth / bounds.width)
            .coerceAtLeast(1L)
            .coerceAtMost(PDF_MAX_FULL_RENDER_HEIGHT.toLong())
            .toInt()
    }

    private fun tileDecodeWidth(displayImageWidth: Int, sourceWidth: Int): Int {
        if (isPdfSource) {
            return (displayImageWidth * PDF_TILE_RENDER_SCALE)
                .coerceAtLeast(displayImageWidth)
                .coerceAtMost(PDF_MAX_TILE_RENDER_WIDTH)
        }

        return maxOf(
            displayImageWidth,
            decodeWidth,
            sourceWidth.coerceAtMost(MAX_READER_TILE_DECODE_WIDTH)
        )
            .coerceAtMost(MAX_READER_TILE_DECODE_WIDTH)
    }

    private fun shouldPreloadFullPages(isFastScroll: Boolean, isIdle: Boolean): Boolean {
        return shouldPreloadBackgroundFullPagesForReader(isPdfSource, isFastScroll, isIdle)
    }

    private fun isHorizontalReading(): Boolean {
        return readingDirection == AppSettings.READING_DIRECTION_RIGHT_TO_LEFT ||
            readingDirection == AppSettings.READING_DIRECTION_LEFT_TO_RIGHT
    }

    private fun previewBitmap(position: Int): Bitmap? {
        return synchronized(cacheLock) {
            previewBitmapCache.get(position)
        }
    }

    private fun removePreviewBitmap(position: Int) {
        synchronized(cacheLock) {
            previewBitmapCache.remove(position)
        }
    }

    private fun fullBitmap(position: Int): Bitmap? {
        return synchronized(cacheLock) {
            fullBitmapCache.get(position)
        }
    }

    private fun clearFullBitmapMetadata() {
        highQualityFullPages.clear()
        fullBitmapDecodeWidths.clear()
    }

    private fun fullBitmapDecodeWidth(position: Int): Int {
        return synchronized(cacheLock) {
            val bitmap = fullBitmapCache.get(position)
            if (bitmap == null) {
                fullBitmapDecodeWidths.remove(position)
                return@synchronized 0
            }
            fullBitmapDecodeWidths[position] ?: bitmap.width
        }
    }

    private fun tileBitmap(key: TileKey): Bitmap? {
        return synchronized(cacheLock) {
            tileBitmapCache.get(key)
        }
    }

    private fun putPreviewBitmap(position: Int, bitmap: Bitmap) {
        if (closed) {
            bitmap.recycle()
            return
        }
        synchronized(cacheLock) {
            previewBitmapCache.put(position, bitmap)
        }
    }

    private fun canFitFullBitmapInCache(
        targetWidth: Int,
        targetHeight: Int
    ): Boolean {
        val safeWidth = targetWidth.coerceAtLeast(1).toLong()
        val safeHeight = targetHeight.coerceAtLeast(1).toLong()
        val estimatedBytes = (safeWidth * safeHeight * 4L)
            .coerceAtLeast(1L)

        // 解码期间旧 Bitmap 仍可能被缓存或 ImageView 持有，因此按峰值容量判断。
        return synchronized(cacheLock) {
            val residentBytes = fullBitmapCache.size().toLong() * 1024L
            val maxBytes = fullBitmapCache.maxSize().toLong() * 1024L
            estimatedBytes <= maxBytes &&
                residentBytes + estimatedBytes <= maxBytes
        }
    }

    private fun putFullBitmap(
        position: Int,
        bitmap: Bitmap,
        decodeWidth: Int = bitmap.width
    ): Boolean {
        if (closed) {
            bitmap.recycle()
            return false
        }

        val accepted = synchronized(cacheLock) {
            fullBitmapCache.put(position, bitmap)
            val isAccepted = fullBitmapCache.get(position) === bitmap
            if (isAccepted) {
                fullBitmapDecodeWidths[position] = decodeWidth.coerceAtLeast(1)
            } else {
                fullBitmapDecodeWidths.remove(position)
            }
            isAccepted
        }
        if (!accepted) {
            bitmap.recycle()
        }
        return accepted
    }

    private fun putTileBitmap(key: TileKey, bitmap: Bitmap): Boolean {
        if (closed) {
            return false
        }
        return synchronized(cacheLock) {
            tileBitmapCache.put(key, bitmap)
            tileBitmapCache.get(key) === bitmap
        }
    }

    internal class PageViewHolder(
        val container: ReaderPageZoomLayout,
        val imageView: ImageView,
        val tileContainer: FrameLayout
    ) : RecyclerView.ViewHolder(container) {
        var boundPosition: Int = RecyclerView.NO_POSITION
        var tileRefreshRunnable: Runnable? = null
    }

    private data class TileKey(
        val position: Int,
        val tileIndex: Int,
        val imageWidth: Int,
        val decodeWidth: Int
    )

    private enum class DecodeTaskKind {
        PREVIEW,
        FULL,
        ZOOM_FULL,
        TILE
    }

    private class DecodeTask(
        var priority: Int,
        val position: Int,
        val generation: Int,
        val kind: DecodeTaskKind,
        val cancelable: Boolean,
        val tileKey: TileKey?,
        private val sequence: Long,
        private val enqueuedAtNanos: Long,
        private val pendingBefore: Int,
        private val logPdfTiming: Boolean,
        private val block: () -> Unit
    ) : Runnable, Comparable<DecodeTask> {

        @Volatile
        var cancelled = false

        override fun run() {
            if (cancelled) {
                return
            }
            val startedAtNanos = SystemClock.elapsedRealtimeNanos()
            if (logPdfTiming) {
                Log.i(
                    PDF_TIMING_LOG_TAG,
                    formatReaderDecodeQueueTiming(
                        position = position,
                        kind = kind.name,
                        queueWaitNanos = startedAtNanos - enqueuedAtNanos,
                        pendingBefore = pendingBefore
                    )
                )
            }
            try {
                block()
            } catch (throwable: Throwable) {
                if (throwable is OutOfMemoryError) {
                    Log.w(READER_ADAPTER_LOG_TAG, "decode task OOM kind=$kind position=$position")
                    System.gc()
                } else {
                    Log.w(
                        READER_ADAPTER_LOG_TAG,
                        "decode task failed kind=$kind position=$position",
                        throwable
                    )
                }
            } finally {
                if (logPdfTiming) {
                    val elapsedNanos = SystemClock.elapsedRealtimeNanos() - startedAtNanos
                    Log.i(
                        PDF_TIMING_LOG_TAG,
                        "event=decode_task_complete page=${position + 1} position=$position " +
                            "kind=${kind.name} elapsedMs=" +
                            String.format(java.util.Locale.ROOT, "%.1f", elapsedNanos / 1_000_000.0)
                    )
                }
            }
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
        private const val READER_DECODE_THREAD_COUNT = 1
        private const val READER_CLOSE_WAIT_TIMEOUT_MS = 8_000L
        private const val ESTIMATED_READER_PAGE_HEIGHT_RATIO = 1.45f
        private const val MIN_READER_PAGE_HEIGHT = 320
        private const val PLACEHOLDER_COLOR = 0xFF101010.toInt()
        private const val MIN_PREVIEW_DECODE_WIDTH = 240
        private const val MAX_PREVIEW_DECODE_WIDTH = 720
        private const val PDF_MIN_PREVIEW_DECODE_WIDTH = 360
        private const val PDF_FAST_PREVIEW_DECODE_WIDTH = 180
        private const val PDF_FAST_SCROLL_DECODE_THREAD_COUNT = 2
        private const val PDF_IDLE_FULL_DECODE_DELAY_MS = 250L
        private const val PDF_MAX_PREVIEW_DECODE_WIDTH = 1080
        private const val FULL_CACHE_TRIM_THRESHOLD_PERCENT = 88
        private const val UI_REFRESH_THROTTLE_MS = 24L
        private const val UI_REFRESH_RETRY_MS = 48L
        private const val PRIORITY_VISIBLE = 100
        private const val PRIORITY_VISIBLE_PREVIEW = 90
        private const val PRIORITY_TILE = 80
        private const val PRIORITY_ZOOM_FULL = 95
        private const val PRIORITY_PRELOAD = 30
        private const val PRIORITY_PRELOAD_PREVIEW = 20
        private const val TILED_SOURCE_HEIGHT_THRESHOLD = 5200
        private const val TILED_DECODED_HEIGHT_THRESHOLD = 5200
        private const val TILED_SOURCE_PIXEL_THRESHOLD = 12_000_000L
        private const val TILED_DECODED_PIXEL_THRESHOLD = 8_000_000L
        private const val PDF_FULL_RENDER_SCALE = 2
        private const val PDF_ZOOM_RENDER_SCALE = 3
        private const val PDF_TILE_RENDER_SCALE = 2
        private const val PDF_MAX_FULL_RENDER_WIDTH = 3072
        private const val PDF_MAX_FULL_RENDER_HEIGHT = 4096
        private const val PDF_MAX_TILE_RENDER_WIDTH = 3072
        private const val PDF_MAX_FULL_RENDER_PIXELS = 4_000_000L
        private const val PDF_DOUBLE_PAGE_FULL_RENDER_PIXELS = 2_000_000L
        private const val PDF_ZOOM_RENDER_PIXELS = 4_000_000L
        private const val PDF_DOUBLE_PAGE_ZOOM_RENDER_PIXELS = 3_000_000L
        private const val PDF_TILED_RENDER_PIXEL_THRESHOLD = 8_000_000L
        private const val READER_ADAPTER_LOG_TAG = "ComicLabReader"
        private const val PDF_TIMING_LOG_TAG = "ComicLabPdfTiming"

        private fun previewBitmapCacheSizeKb(isPdfSource: Boolean): Int {
            val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
            if (isPdfSource) {
                return (maxMemoryKb / 32)
                    .coerceAtLeast(4 * 1024)
                    .coerceAtMost(12 * 1024)
            }
            return (maxMemoryKb / 48)
                .coerceAtLeast(4 * 1024)
                .coerceAtMost(12 * 1024)
        }

        private fun fullBitmapCacheSizeKb(
            isPdfSource: Boolean,
            doublePageReading: Boolean
        ): Int {
            val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
            if (isPdfSource) {
                return pdfFullBitmapCacheSizeKb(maxMemoryKb, doublePageReading)
            }
            return (maxMemoryKb / 10)
                .coerceAtLeast(12 * 1024)
                .coerceAtMost(64 * 1024)
        }

        private fun tileBitmapCacheSizeKb(isPdfSource: Boolean): Int {
            val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
            if (isPdfSource) {
                return pdfTileBitmapCacheSizeKb(maxMemoryKb)
            }
            return (maxMemoryKb / 10)
                .coerceAtLeast(8 * 1024)
                .coerceAtMost(48 * 1024)
        }
    }
}
