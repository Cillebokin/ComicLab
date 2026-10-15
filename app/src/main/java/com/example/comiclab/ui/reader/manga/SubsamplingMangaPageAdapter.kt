package com.example.comiclab.ui.reader.manga

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.davemorrissey.labs.subscaleview.ImageSource
import com.example.comiclab.AppSettings
import com.example.comiclab.ComicArchive
import com.example.comiclab.ReaderSubsamplingImageView
import com.example.comiclab.core.archive.cache.PreparedReaderCacheLeaseRegistry
import com.example.comiclab.core.reader.decode.preparedReaderNearbyPositions
import com.example.comiclab.core.reader.layout.ReaderPagePositionMapper
import com.example.comiclab.readerPageContentGravity
import java.io.File
import java.util.Collections
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

internal class SubsamplingMangaPageAdapter(
    private val context: Context,
    private val recyclerView: RecyclerView,
    private val archiveFile: File,
    private val entryNames: List<String>,
    private val pageFiles: List<File>,
    boundsByPosition: Map<Int, ComicArchive.ImageBounds>,
    private val preparedCacheDir: File,
    private val cacheUseLease: PreparedReaderCacheLeaseRegistry.Lease,
    private val baseDisplayWidth: Int,
    private val baseDisplayHeight: Int,
    private val readingDirection: String,
    private val doublePageReading: Boolean,
    private val coverSinglePage: Boolean,
    private val onPageReady: (position: Int) -> Unit,
    private val onPageTap: () -> Unit,
    private val closeWaitTimeoutMs: Long = PREPARED_READER_CLOSE_WAIT_TIMEOUT_MS,
    private val spreadAnchorSourcePosition: Int? = null
) : RecyclerView.Adapter<SubsamplingMangaPageAdapter.PageViewHolder>(),
    ReaderPageAdapterController {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val extractExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        PriorityBlockingQueue<Runnable>()
    )
    private val taskSequence = AtomicLong(0L)
    private val readyPositions = Collections.synchronizedSet(mutableSetOf<Int>())
    private val loadingPositions = Collections.synchronizedSet(mutableSetOf<Int>())
    private val failedPositions = Collections.synchronizedSet(mutableSetOf<Int>())
    private val pageBounds = Collections.synchronizedMap(boundsByPosition.toMutableMap())
    private val activeSubsamplingViews = mutableSetOf<ReaderSubsamplingImageView>()
    private val pageHolders = mutableSetOf<PageViewHolder>()
    private var imageEnhancementEnabled = false
    private val positionMapper = ReaderPagePositionMapper(
        sourceItemCount = entryNames.size,
        doublePageReading = doublePageReading,
        coverSinglePage = coverSinglePage,
        spreadAnchorSourcePosition = spreadAnchorSourcePosition
    )

    @Volatile
    private var closed = false

    @Volatile
    private var extractor: ComicArchive.PreparedImageExtractor? = null

    init {
        setHasStableIds(true)
        pageFiles.forEachIndexed { index, file ->
            if (file.isFile && file.length() > 0L) {
                readyPositions.add(index)
            }
        }
    }

    private val adapterItemCount = positionMapper.adapterItemCount

    override fun getItemCount(): Int = adapterItemCount

    override fun readerItemCount(): Int = entryNames.size

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val container = ReaderTapFrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            layoutParams = initialPageLayoutParams()
            onReaderTap = onPageTap
        }
        val imageView = ReaderSubsamplingImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val statusText = TextView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            setTextColor(Color.WHITE)
            textSize = 14f
            visibility = View.GONE
        }
        container.addView(imageView)
        container.addView(statusText)
        return PageViewHolder(container, imageView, statusText).also(pageHolders::add)
    }

    override fun onBindViewHolder(holder: PageViewHolder, adapterPosition: Int) {
        pageHolders += holder
        val sourcePosition = positionMapper.sourcePositionForAdapterPosition(adapterPosition)
        if (closed || sourcePosition == null || sourcePosition !in entryNames.indices ||
            sourcePosition !in pageFiles.indices
        ) {
            bindEmptyPage(holder)
            return
        }

        val file = pageFiles[sourcePosition]
        val bounds = pageBounds[sourcePosition]
        holder.container.onReaderTap = onPageTap
        applyPageLayout(holder.container, bounds)
        applyImageLayout(holder.imageView, bounds, adapterPosition)

        if (!file.isFile || file.length() <= 0L) {
            bindEmptyPage(holder)
            submitExtraction(sourcePosition, PRIORITY_VISIBLE)
            onPageReady(sourcePosition)
            return
        }

        readyPositions.add(sourcePosition)
        holder.statusText.visibility = View.GONE
        holder.imageView.visibility = View.VISIBLE
        ReaderImageEnhancement.apply(
            holder.imageView,
            enabled = imageEnhancementEnabled && holder.itemView.isAttachedToWindow
        )
        if (holder.boundFilePath != file.absolutePath) {
            holder.boundFilePath = file.absolutePath
            activeSubsamplingViews += holder.imageView
            runCatching {
                holder.imageView.setReaderImage(ImageSource.uri(Uri.fromFile(file)))
            }.onFailure {
                holder.boundFilePath = null
                holder.imageView.clearReaderImage()
            }
        }

        onPageReady(sourcePosition)
    }

    private fun bindEmptyPage(holder: PageViewHolder) {
        holder.statusText.visibility = View.GONE
        holder.boundFilePath = null
        holder.imageView.visibility = View.INVISIBLE
        ReaderImageEnhancement.apply(holder.imageView, enabled = false)
        holder.imageView.clearReaderImage { result ->
            if (result.isFailure) {
                Log.w(
                    READER_ADAPTER_LOG_TAG,
                    "prepared reader image clear failed",
                    result.exceptionOrNull()
                )
            } else if (!closed && holder.boundFilePath == null) {
                activeSubsamplingViews.remove(holder.imageView)
            }
        }
    }

    override fun onViewRecycled(holder: PageViewHolder) {
        holder.boundFilePath = null
        ReaderImageEnhancement.clear(holder.imageView)
        pageHolders.remove(holder)
        if (!closed) {
            holder.imageView.clearReaderImage { result ->
                if (result.isFailure) {
                    Log.w(
                        READER_ADAPTER_LOG_TAG,
                        "recycled prepared reader image clear failed",
                        result.exceptionOrNull()
                    )
                } else if (!closed && holder.boundFilePath == null) {
                    activeSubsamplingViews.remove(holder.imageView)
                }
            }
        }
        super.onViewRecycled(holder)
    }

    override fun onViewAttachedToWindow(holder: PageViewHolder) {
        super.onViewAttachedToWindow(holder)
        ReaderImageEnhancement.apply(
            holder.imageView,
            enabled = imageEnhancementEnabled && holder.boundFilePath != null
        )
    }

    override fun onViewDetachedFromWindow(holder: PageViewHolder) {
        ReaderImageEnhancement.apply(holder.imageView, enabled = false)
        super.onViewDetachedFromWindow(holder)
    }

    override fun preloadAround(
        firstVisiblePosition: Int,
        visibleItemCount: Int,
        scrollDirection: Int,
        isFastScroll: Boolean,
        isIdle: Boolean,
        isDiscreteJump: Boolean
    ) {
        if (closed || entryNames.isEmpty()) {
            return
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
        val movingForward = scrollDirection >= 0

        (firstVisible..lastVisible).forEach { position ->
            submitExtraction(position, PRIORITY_VISIBLE)
        }

        orderedNearPositions(
            firstVisible = firstVisible,
            lastVisible = lastVisible,
            movingForward = movingForward,
            isFastScroll = isFastScroll,
            isIdle = isIdle
        ).forEach { position ->
            submitExtraction(position, PRIORITY_NEAR)
        }

    }

    override fun applyDebugZoomToVisiblePages(
        recyclerView: RecyclerView,
        scale: Float,
        panX: Float,
        panY: Float
    ) {
        // The subsampling view owns scale state internally; stress tests still cover page jumps.
    }

    override fun trimMemory(level: Int) {
        if (level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            return
        }

        for (index in 0 until recyclerView.childCount) {
            val holder = recyclerView.getChildViewHolder(recyclerView.getChildAt(index))
                as? PageViewHolder
                ?: continue
            holder.boundFilePath = null
            holder.imageView.visibility = View.INVISIBLE
            holder.imageView.clearReaderImage { result ->
                if (result.isFailure) {
                    Log.w(
                        READER_ADAPTER_LOG_TAG,
                        "trimmed prepared reader image clear failed",
                        result.exceptionOrNull()
                    )
                } else if (!closed && holder.boundFilePath == null) {
                    activeSubsamplingViews.remove(holder.imageView)
                }
            }
        }
        notifyDataSetChangedSafely()
    }

    override fun flushPendingRefreshesIfIdle() = Unit

    fun setImageEnhancementEnabled(enabled: Boolean) {
        imageEnhancementEnabled = enabled
        pageHolders.forEach { holder ->
            ReaderImageEnhancement.apply(
                holder.imageView,
                enabled = imageEnhancementEnabled &&
                    holder.itemView.isAttachedToWindow &&
                    holder.boundFilePath != null
            )
        }
    }

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        extractExecutor.shutdownNow()
        loadingPositions.clear()
        failedPositions.clear()
        pageHolders.forEach { holder -> ReaderImageEnhancement.clear(holder.imageView) }
        pageHolders.clear()
        val viewsToClose = activeSubsamplingViews.toList()
        val closeLock = Any()
        var pendingCloseDependencies = viewsToClose.size + 1
        var allCloseDependenciesSucceeded = true
        var cacheLeaseCloseStarted = false

        fun recordCloseDependency(result: Result<Unit>) {
            val releaseCacheLease = synchronized(closeLock) {
                if (result.isFailure) {
                    allCloseDependenciesSucceeded = false
                }
                pendingCloseDependencies -= 1
                if (pendingCloseDependencies == 0 &&
                    allCloseDependenciesSucceeded &&
                    !cacheLeaseCloseStarted
                ) {
                    cacheLeaseCloseStarted = true
                    true
                } else {
                    false
                }
            }
            if (releaseCacheLease) {
                Thread(
                    {
                        runCatching { cacheUseLease.close() }
                            .onFailure {
                                Log.w(
                                    READER_ADAPTER_LOG_TAG,
                                    "prepared reader cache lease close failed",
                                    it
                                )
                            }
                    },
                    "ComicLabPreparedReaderLeaseClose"
                ).apply { isDaemon = true }.start()
            }
        }

        viewsToClose.forEach { imageView ->
            imageView.closeReaderImage { result ->
                activeSubsamplingViews.remove(imageView)
                if (result.isFailure) {
                    Log.w(
                        READER_ADAPTER_LOG_TAG,
                        "prepared reader Subsampling decoder close failed; cache lease retained",
                        result.exceptionOrNull()
                    )
                }
                recordCloseDependency(result)
            }
        }
        closeExtractorAfterExtractionStops(::recordCloseDependency)
    }

    private fun closeExtractorAfterExtractionStops(onClosed: (Result<Unit>) -> Unit) {
        Thread(
            {
                if (!awaitExtractorTermination()) {
                    awaitExtractorTerminationAfterTimeout()
                }
                val closeResult = runCatching {
                    extractor?.close()
                    Unit
                }
                if (closeResult.isFailure) {
                    Log.w(READER_ADAPTER_LOG_TAG, "prepared extractor close failed", closeResult.exceptionOrNull())
                }
                onClosed(closeResult)
            },
            "ComicLabPreparedReaderClose"
        ).apply { isDaemon = true }.start()
    }

    private fun awaitExtractorTerminationAfterTimeout() {
        var interrupted = false
        while (!extractExecutor.isTerminated) {
            try {
                if (extractExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) {
                    break
                }
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt()
        }
    }

    private fun awaitExtractorTermination(): Boolean {
        return try {
            if (extractExecutor.awaitTermination(
                    closeWaitTimeoutMs,
                    TimeUnit.MILLISECONDS
                )
            ) {
                true
            } else {
                Log.w(
                    READER_ADAPTER_LOG_TAG,
                    "prepared reader close timed out; cache cleanup is deferred"
                )
                false
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(READER_ADAPTER_LOG_TAG, "prepared extractor close interrupted", interrupted)
            false
        }
    }

    private fun notifyDataSetChangedSafely() {
        mainHandler.post {
            if (closed) {
                return@post
            }

            if (!recyclerView.isComputingLayout) {
                notifyDataSetChanged()
            } else {
                mainHandler.postDelayed({ notifyDataSetChangedSafely() }, UI_REFRESH_RETRY_MS)
            }
        }
    }

    private fun initialPageLayoutParams(): RecyclerView.LayoutParams {
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

    private fun applyPageLayout(
        container: FrameLayout,
        bounds: ComicArchive.ImageBounds?
    ) {
        val layoutParams = container.layoutParams as? RecyclerView.LayoutParams
            ?: initialPageLayoutParams()

        val targetWidth: Int
        val targetHeight: Int
        if (isHorizontalReading()) {
            targetWidth = baseDisplayWidth
            targetHeight = baseDisplayHeight
        } else {
            targetWidth = RecyclerView.LayoutParams.MATCH_PARENT
            targetHeight = if (bounds != null) {
                calculateDisplayHeight(bounds.width, bounds.height, baseDisplayWidth)
            } else {
                (baseDisplayWidth * ESTIMATED_READER_PAGE_HEIGHT_RATIO)
                    .roundToInt()
                    .coerceAtLeast(MIN_READER_PAGE_HEIGHT)
            }
        }

        if (layoutParams.width != targetWidth || layoutParams.height != targetHeight) {
            layoutParams.width = targetWidth
            layoutParams.height = targetHeight
            container.layoutParams = layoutParams
        }
    }

    private fun applyImageLayout(
        imageView: ReaderSubsamplingImageView,
        bounds: ComicArchive.ImageBounds?,
        position: Int
    ) {
        val layoutParams = imageView.layoutParams as? FrameLayout.LayoutParams
            ?: FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        val targetWidth: Int
        val targetHeight: Int
        val targetGravity: Int
        if (doublePageReading &&
            bounds != null &&
            bounds.width > 0 &&
            bounds.height > 0 &&
            baseDisplayWidth > 0 &&
            baseDisplayHeight > 0
        ) {
            val widthScale = baseDisplayWidth.toFloat() / bounds.width.toFloat()
            val heightScale = baseDisplayHeight.toFloat() / bounds.height.toFloat()
            val scale = minOf(widthScale, heightScale)
            targetWidth = (bounds.width * scale).roundToInt().coerceAtLeast(1)
            targetHeight = (bounds.height * scale).roundToInt().coerceAtLeast(1)
            targetGravity = readerPageContentGravity(
                readingDirection,
                position,
                doublePageReading = true
            )
        } else {
            targetWidth = FrameLayout.LayoutParams.MATCH_PARENT
            targetHeight = FrameLayout.LayoutParams.MATCH_PARENT
            targetGravity = Gravity.CENTER
        }

        if (layoutParams.width != targetWidth ||
            layoutParams.height != targetHeight ||
            layoutParams.gravity != targetGravity
        ) {
            layoutParams.width = targetWidth
            layoutParams.height = targetHeight
            layoutParams.gravity = targetGravity
            imageView.layoutParams = layoutParams
        }
    }

    private fun submitExtraction(position: Int, priority: Int) {
        if (closed ||
            position !in entryNames.indices ||
            position !in pageFiles.indices ||
            position in readyPositions
        ) {
            return
        }
        if (position in failedPositions && priority < PRIORITY_VISIBLE) {
            return
        }
        if (priority >= PRIORITY_VISIBLE) {
            failedPositions.remove(position)
        }

        val wasAlreadyLoading = !loadingPositions.add(position)
        if (wasAlreadyLoading && !promoteQueuedExtraction(position, priority)) {
            return
        }

        val task = ExtractTask(
            priority = priority,
            position = position,
            sequence = taskSequence.getAndIncrement()
        ) {
            extractPage(position)
        }
        runCatching {
            extractExecutor.execute(task)
        }.onFailure {
            loadingPositions.remove(position)
        }
    }

    private fun promoteQueuedExtraction(position: Int, priority: Int): Boolean {
        val queue = extractExecutor.queue
        val queuedTask = queue.toList()
            .filterIsInstance<ExtractTask>()
            .firstOrNull { it.position == position }
            ?: return false

        if (queuedTask.priority >= priority) {
            return false
        }

        return if (queue.remove(queuedTask)) {
            queuedTask.cancelled = true
            true
        } else {
            false
        }
    }

    private fun extractPage(position: Int) {
        try {
            if (closed || position !in entryNames.indices || position !in pageFiles.indices) {
                return
            }

            val targetFile = pageFiles[position]
            val extractedFile = if (targetFile.isFile && targetFile.length() > 0L) {
                targetFile
            } else {
                preparedExtractor()?.extract(position, entryNames[position])
            }

            if (closed) {
                return
            }

            if (extractedFile != null && extractedFile.isFile && extractedFile.length() > 0L) {
                readyPositions.add(position)
                failedPositions.remove(position)
                ComicArchive.imageFileBounds(extractedFile)?.let { bounds ->
                    pageBounds[position] = bounds
                }
                notifyPageFileReady(position)
            } else {
                failedPositions.add(position)
            }
        } catch (throwable: Throwable) {
            failedPositions.add(position)
            if (throwable is OutOfMemoryError) {
                Log.w(READER_ADAPTER_LOG_TAG, "prepared page extraction OOM position=$position")
                System.gc()
            } else {
                Log.w(
                    READER_ADAPTER_LOG_TAG,
                    "prepared page extraction failed position=$position",
                    throwable
                )
            }
        } finally {
            loadingPositions.remove(position)
        }
    }

    private fun notifyPageFileReady(position: Int) {
        mainHandler.post {
            if (closed || position !in entryNames.indices) {
                return@post
            }

            if (recyclerView.isComputingLayout) {
                mainHandler.postDelayed({ notifyPageFileReady(position) }, UI_REFRESH_RETRY_MS)
                return@post
            }

            val adapterPosition = positionMapper.adapterPositionForSourcePosition(position)
            if (adapterPosition >= 0) {
                notifyItemChanged(adapterPosition)
            }
            onPageReady(position)
        }
    }

    private fun preparedExtractor(): ComicArchive.PreparedImageExtractor? {
        extractor?.let { return it }
        return synchronized(this) {
            extractor ?: runCatching {
                ComicArchive.openPreparedImageExtractor(archiveFile, preparedCacheDir)
            }.getOrNull().also { createdExtractor ->
                extractor = createdExtractor
            }
        }
    }

    private fun orderedNearPositions(
        firstVisible: Int,
        lastVisible: Int,
        movingForward: Boolean,
        isFastScroll: Boolean,
        isIdle: Boolean
    ): List<Int> = preparedReaderNearbyPositions(
        firstVisible = firstVisible,
        lastVisible = lastVisible,
        entryCount = entryNames.size,
        movingForward = movingForward,
        beforeCount = if (isIdle) IDLE_PREPARE_BEFORE_COUNT else NORMAL_PREPARE_BEFORE_COUNT,
        afterCount = when {
            isFastScroll -> FAST_PREPARE_FORWARD_COUNT
            isIdle -> IDLE_PREPARE_FORWARD_COUNT
            else -> NORMAL_PREPARE_FORWARD_COUNT
        }
    )

    private fun calculateDisplayHeight(width: Int, height: Int, imageWidth: Int): Int {
        if (width <= 0 || height <= 0 || imageWidth <= 0) {
            return MIN_READER_PAGE_HEIGHT
        }

        return (imageWidth.toFloat() / width.toFloat() * height.toFloat())
            .roundToInt()
            .coerceAtLeast(1)
    }

    private fun isHorizontalReading(): Boolean {
        return readingDirection == AppSettings.READING_DIRECTION_RIGHT_TO_LEFT ||
            readingDirection == AppSettings.READING_DIRECTION_LEFT_TO_RIGHT
    }

    private class ExtractTask(
        val priority: Int,
        val position: Int,
        private val sequence: Long,
        private val block: () -> Unit
    ) : Runnable, Comparable<ExtractTask> {

        @Volatile
        var cancelled = false

        override fun run() {
            if (!cancelled) {
                block()
            }
        }

        override fun compareTo(other: ExtractTask): Int {
            val priorityComparison = other.priority.compareTo(priority)
            if (priorityComparison != 0) {
                return priorityComparison
            }
            return sequence.compareTo(other.sequence)
        }
    }

    internal class ReaderTapFrameLayout(context: Context) : FrameLayout(context) {
        var onReaderTap: (() -> Unit)? = null

        private val tapDetector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean {
                    return true
                }

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    performClick()
                    return true
                }
            }
        )

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            tapDetector.onTouchEvent(event)
            return super.dispatchTouchEvent(event)
        }

        override fun performClick(): Boolean {
            super.performClick()
            onReaderTap?.invoke()
            return true
        }
    }

    internal class PageViewHolder(
        val container: ReaderTapFrameLayout,
        val imageView: ReaderSubsamplingImageView,
        val statusText: TextView
    ) : RecyclerView.ViewHolder(container) {
        var boundFilePath: String? = null
    }

    companion object {
        private const val ESTIMATED_READER_PAGE_HEIGHT_RATIO = 1.45f
        private const val MIN_READER_PAGE_HEIGHT = 320
        private const val PRIORITY_VISIBLE = 100
        private const val PRIORITY_NEAR = 60
        private const val NORMAL_PREPARE_BEFORE_COUNT = 1
        private const val NORMAL_PREPARE_FORWARD_COUNT = 8
        private const val FAST_PREPARE_FORWARD_COUNT = 14
        private const val IDLE_PREPARE_BEFORE_COUNT = 3
        private const val IDLE_PREPARE_FORWARD_COUNT = 18
        private const val UI_REFRESH_RETRY_MS = 48L
        private const val PREPARED_READER_CLOSE_WAIT_TIMEOUT_MS = 8_000L
        private const val READER_ADAPTER_LOG_TAG = "ComicLabReader"
    }
}
