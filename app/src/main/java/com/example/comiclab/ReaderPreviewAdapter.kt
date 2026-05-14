package com.example.comiclab

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ReaderPreviewAdapter(
    private val context: Context,
    private val archiveFile: File,
    private val entries: List<String>,
    private val onPageClick: (position: Int) -> Unit
) : RecyclerView.Adapter<ReaderPreviewAdapter.PreviewViewHolder>() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val decodeExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val loadingPositions = Collections.synchronizedSet(mutableSetOf<Int>())
    private val failedPositions = Collections.synchronizedSet(mutableSetOf<Int>())
    private val thumbnailWidth = context.resources.getDimensionPixelSize(
        R.dimen.reader_preview_thumbnail_width
    )
    private val cache = object : LruCache<Int, Bitmap>(thumbnailCacheSizeKb()) {
        override fun sizeOf(key: Int, value: Bitmap): Int {
            return (value.byteCount / 1024).coerceAtLeast(1)
        }
    }

    private val sessionLock = Any()

    @Volatile
    private var readerSession: ComicArchive.ImageReaderSession? = null

    @Volatile
    private var closed = false

    private var selectedPosition = RecyclerView.NO_POSITION

    init {
        setHasStableIds(true)
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
            notifyItemChanged(previousPosition)
        }
        if (selectedPosition != RecyclerView.NO_POSITION) {
            notifyItemChanged(selectedPosition)
        }
    }

    fun close() {
        closed = true
        decodeExecutor.shutdownNow()
        loadingPositions.clear()
        failedPositions.clear()
        synchronized(cache) {
            cache.evictAll()
        }
        synchronized(sessionLock) {
            runCatching { readerSession?.close() }
            readerSession = null
        }
    }

    private fun loadThumbnail(position: Int) {
        if (closed ||
            position !in entries.indices ||
            position in failedPositions ||
            !loadingPositions.add(position)
        ) {
            return
        }

        runCatching {
            decodeExecutor.execute {
                try {
                    if (closed || position !in entries.indices) {
                        return@execute
                    }

                    val bitmap = runCatching {
                        openReaderSession().decodePreviewForWidth(entries[position], thumbnailWidth)
                    }.getOrNull()

                    if (closed || position !in entries.indices) {
                        bitmap?.recycle()
                        return@execute
                    }

                    if (bitmap == null) {
                        failedPositions.add(position)
                        return@execute
                    }

                    synchronized(cache) {
                        cache.put(position, bitmap)
                    }
                    mainHandler.post {
                        if (!closed && position in entries.indices) {
                            notifyItemChanged(position)
                        }
                    }
                } finally {
                    loadingPositions.remove(position)
                }
            }
        }.onFailure {
            loadingPositions.remove(position)
        }
    }

    private fun openReaderSession(): ComicArchive.ImageReaderSession {
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
        private fun thumbnailCacheSizeKb(): Int {
            val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
            return (maxMemoryKb / 32).coerceAtLeast(4 * 1024)
        }
    }
}
