package com.example.comiclab.ui.reader.manga

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.comiclab.ComicArchive
import com.example.comiclab.R
import java.io.File
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ReaderPreviewAdapter(
    private val context: Context,
    private val archiveFile: File,
    private val entries: List<String>,
    private val sharedSession: ComicArchive.ImageReaderSession? = null,
    private val onPageClick: (position: Int) -> Unit
) : RecyclerView.Adapter<ReaderPreviewAdapter.PreviewViewHolder>() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var decodeExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val loadingPositions = Collections.synchronizedSet(mutableSetOf<Int>())
    private val failedPositions = Collections.synchronizedSet(mutableSetOf<Int>())
    private val decodeAttempts = Collections.synchronizedMap(mutableMapOf<Int, Int>())
    private val thumbnailWidth = context.resources.getDimensionPixelSize(
        R.dimen.reader_preview_thumbnail_width
    )
    private val cache = object : LruCache<Int, Bitmap>(thumbnailCacheSizeKb()) {
        override fun sizeOf(key: Int, value: Bitmap): Int {
            return (value.byteCount / 1024).coerceAtLeast(1)
        }
    }

    private val sessionLock = Any()

    private var attachedRecyclerView: RecyclerView? = null

    @Volatile
    private var readerSession: ComicArchive.ImageReaderSession? = null

    @Volatile
    private var closed = false

    private var selectedPosition = RecyclerView.NO_POSITION

    init {
        setHasStableIds(true)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        if (attachedRecyclerView === recyclerView) {
            attachedRecyclerView = null
        }
        super.onDetachedFromRecyclerView(recyclerView)
    }

    override fun getItemCount(): Int = entries.size

    override fun getItemId(position: Int): Long = position.toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PreviewViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_reader_preview, parent, false)
        return PreviewViewHolder(view)
    }

    override fun onBindViewHolder(holder: PreviewViewHolder, position: Int) {
        holder.itemView.isSelected = position == selectedPosition
        holder.tvPage.text = (position + 1).toString()
        holder.itemView.setOnClickListener {
            if (position in entries.indices) {
                onPageClick(position)
            }
        }

        val cachedBitmap = synchronized(cache) {
            cache.get(position)
        }
        if (cachedBitmap != null) {
            holder.imgPreview.setImageBitmap(cachedBitmap)
            return
        }

        holder.imgPreview.setImageDrawable(null)
        loadThumbnail(position)
    }

    fun setSelectedPosition(position: Int) {
        val boundedPosition = position.takeIf { it in entries.indices }
            ?: RecyclerView.NO_POSITION
        if (boundedPosition == selectedPosition) {
            return
        }

        val previousPosition = selectedPosition
        selectedPosition = boundedPosition
        if (previousPosition != RecyclerView.NO_POSITION) {
            notifyItemChangedSafely(previousPosition)
        }
        if (selectedPosition != RecyclerView.NO_POSITION) {
            notifyItemChangedSafely(selectedPosition)
        }
    }

    // 测试接口：仅供单元测试读取缩略图失败记录，不参与运行时逻辑。
    internal val failedPositionsForTest: Set<Int>
        get() = failedPositions

    // 测试接口：仅供单元测试注入解码执行器并触发缩略图加载，不参与运行时逻辑。
    internal var decodeExecutorForTest: ExecutorService
        get() = decodeExecutor
        set(value) {
            decodeExecutor = value
        }

    internal fun loadThumbnailForTest(position: Int) {
        loadThumbnail(position)
    }

    fun close() {
        if (closed) {
            return
        }
        closed = true
        decodeExecutor.shutdownNow()
        loadingPositions.clear()
        failedPositions.clear()
        synchronized(decodeAttempts) {
            decodeAttempts.clear()
        }
        clearVisibleBitmapReferences()
        synchronized(cache) {
            cache.evictAll()
        }
        closeSessionAfterDecoderStops()
    }

    fun trimMemory(level: Int) {
        if (level < android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            return
        }
        clearVisibleBitmapReferences()
        synchronized(cache) {
            cache.evictAll()
        }
        failedPositions.clear()
        synchronized(decodeAttempts) {
            decodeAttempts.clear()
        }
        notifyAfterMemoryTrim()
    }

    private fun clearVisibleBitmapReferences() {
        val recyclerView = attachedRecyclerView ?: return
        for (index in 0 until recyclerView.childCount) {
            val holder = recyclerView.getChildViewHolder(recyclerView.getChildAt(index))
                as? PreviewViewHolder
                ?: continue
            holder.imgPreview.setImageDrawable(null)
        }
    }

    private fun closeSessionAfterDecoderStops() {
        Thread(
            {
                if (!awaitDecoderTermination()) {
                    return@Thread
                }
                synchronized(cache) {
                    cache.evictAll()
                }
                synchronized(sessionLock) {
                    runCatching { (sharedSession ?: readerSession)?.close() }
                        .onFailure { Log.w(LOG_TAG, "reader preview session close failed", it) }
                    readerSession = null
                }
            },
            "ComicLabReaderPreviewClose"
        ).apply { isDaemon = true }.start()
    }

        private fun awaitDecoderTermination(): Boolean {
            var interrupted = false
            try {
                if (decodeExecutor.awaitTermination(
                        PREVIEW_CLOSE_WAIT_TIMEOUT_MS,
                        TimeUnit.MILLISECONDS
                    )
                ) {
                    return true
                }
                Log.w(
                    LOG_TAG,
                    "reader preview close timed out; waiting for active decoder tasks to finish"
                )
            } catch (failure: InterruptedException) {
                interrupted = true
                Log.w(LOG_TAG, "reader preview close wait interrupted; continuing to protect the session", failure)
            }

            while (!decodeExecutor.isTerminated) {
                try {
                    decodeExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
                } catch (failure: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt()
            }
            return true
        }

    private fun loadThumbnail(position: Int) {
        if (closed ||
            position !in entries.indices ||
            position in failedPositions ||
            synchronized(cache) { cache.get(position) != null } ||
            !loadingPositions.add(position)
        ) {
            return
        }

        val attempt = synchronized(decodeAttempts) {
            val nextAttempt = (decodeAttempts[position] ?: 0) + 1
            if (nextAttempt > MAX_PREVIEW_DECODE_ATTEMPTS) {
                null
            } else {
                decodeAttempts[position] = nextAttempt
                nextAttempt
            }
        }
        if (attempt == null) {
            loadingPositions.remove(position)
            failedPositions.add(position)
            return
        }

        runCatching {
            decodeExecutor.execute {
                try {
                    if (closed || position !in entries.indices) {
                        return@execute
                    }

                    val decodeResult = runCatching {
                        openReaderSession().decodePreviewForWidth(entries[position], thumbnailWidth)
                    }
                    val bitmap = decodeResult.getOrNull()

                    if (closed || position !in entries.indices) {
                        bitmap?.recycle()
                        return@execute
                    }

                    if (bitmap == null) {
                        handleThumbnailDecodeFailure(
                            position = position,
                            attempt = attempt,
                            failure = decodeResult.exceptionOrNull()
                        )
                        return@execute
                    }

                    synchronized(cache) {
                        cache.put(position, bitmap)
                    }
                    synchronized(decodeAttempts) {
                        decodeAttempts.remove(position)
                    }
                    failedPositions.remove(position)
                    mainHandler.post {
                        if (!closed && position in entries.indices) {
                            notifyItemChangedSafely(position)
                        }
                    }
                } catch (throwable: Throwable) {
                    handleThumbnailDecodeFailure(position, attempt, throwable)
                } finally {
                    loadingPositions.remove(position)
                }
            }
        }.onFailure {
            loadingPositions.remove(position)
            handleThumbnailDecodeFailure(position, attempt, it)
        }
    }

    private fun handleThumbnailDecodeFailure(
        position: Int,
        attempt: Int,
        failure: Throwable?
    ) {
        if (closed || synchronized(cache) { cache.get(position) != null }) {
            return
        }

        if (failure is OutOfMemoryError) {
            synchronized(cache) {
                cache.evictAll()
            }
            Log.w(LOG_TAG, "reader preview thumbnail OOM position=$position")
            System.gc()
        } else if (failure != null) {
            Log.w(LOG_TAG, "reader preview thumbnail failed position=$position", failure)
        }

        if (failure !is OutOfMemoryError && attempt < MAX_PREVIEW_DECODE_ATTEMPTS) {
            mainHandler.postDelayed(
                {
                    if (!closed && position in entries.indices) {
                        loadThumbnail(position)
                    }
                },
                PREVIEW_RETRY_DELAY_MS
            )
        } else {
            failedPositions.add(position)
        }
    }

    private fun notifyAfterMemoryTrim() {
        if (closed) {
            return
        }

        val recyclerView = attachedRecyclerView
        if (recyclerView?.isComputingLayout == true) {
            mainHandler.post {
                if (!closed) {
                    notifyDataSetChangedSafely()
                }
            }
        } else {
            notifyDataSetChangedSafely()
        }
    }

    private fun notifyDataSetChangedSafely() {
        runCatching {
            notifyDataSetChanged()
        }.onFailure {
            Log.w(LOG_TAG, "reader preview refresh failed after memory trim", it)
        }
    }

    private fun notifyItemChangedSafely(position: Int) {
        runCatching {
            notifyItemChanged(position)
        }.onFailure {
            Log.w(LOG_TAG, "reader preview refresh failed position=$position", it)
        }
    }

    private fun openReaderSession(): ComicArchive.ImageReaderSession {
        sharedSession?.let { return it }
        readerSession?.let { return it }
        return synchronized(sessionLock) {
            readerSession ?: ComicArchive.openReaderSession(archiveFile, context.cacheDir)
                .also { readerSession = it }
        }
    }

    class PreviewViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imgPreview: ImageView = itemView.findViewById(R.id.imgReaderPreview)
        val tvPage: TextView = itemView.findViewById(R.id.tvReaderPreviewPage)
    }

    companion object {
        private const val LOG_TAG = "ComicLabReader"
        private const val PREVIEW_CLOSE_WAIT_TIMEOUT_MS = 8_000L
        private const val MAX_PREVIEW_DECODE_ATTEMPTS = 2
        private const val PREVIEW_RETRY_DELAY_MS = 120L

        private fun thumbnailCacheSizeKb(): Int {
            val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
            return (maxMemoryKb / 64)
                .coerceAtLeast(2 * 1024)
                .coerceAtMost(8 * 1024)
        }
    }
}
