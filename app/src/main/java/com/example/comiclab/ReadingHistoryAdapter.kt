package com.example.comiclab

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import java.util.Collections
import java.util.concurrent.Executors

class ReadingHistoryAdapter(
    context: Context,
    private val items: List<ReadingHistoryStore.Item>
) : ArrayAdapter<ReadingHistoryStore.Item>(context, 0, items) {

    private val coverCache = object : LruCache<String, Bitmap>(HISTORY_COVER_CACHE_SIZE) {}
    private val loadingCovers = Collections.synchronizedSet(mutableSetOf<String>())
    private val failedCovers = Collections.synchronizedSet(mutableSetOf<String>())
    private val coverExecutor = Executors.newFixedThreadPool(HISTORY_COVER_THREAD_COUNT)
    @Volatile
    private var closed = false

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_reading_history, parent, false)

        val imgCover = view.findViewById<ImageView>(R.id.imgReadingHistoryCover)
        val tvName = view.findViewById<TextView>(R.id.tvReadingHistoryName)
        val tvPath = view.findViewById<TextView>(R.id.tvReadingHistoryPath)
        val item = items[position]
        val file = item.file

        tvName.text = file.name
        tvPath.text = file.parentFile?.absolutePath.orEmpty()
        bindCover(item, imgCover)

        return view
    }

    fun close() {
        closed = true
        coverExecutor.shutdownNow()
        loadingCovers.clear()
        failedCovers.clear()
    }

    private fun bindCover(item: ReadingHistoryStore.Item, imgCover: ImageView) {
        val file = item.file
        imgCover.scaleType = ImageView.ScaleType.CENTER_INSIDE
        imgCover.setImageResource(R.drawable.png_press_package_icon)

        val cacheKey = "${file.absolutePath}:${file.lastModified()}:${file.length()}"
        imgCover.tag = cacheKey
        val cachedCover = coverCache.get(cacheKey)
        if (cachedCover != null) {
            imgCover.scaleType = ImageView.ScaleType.CENTER_CROP
            imgCover.setImageBitmap(cachedCover)
            return
        }

        if (cacheKey in failedCovers || !loadingCovers.add(cacheKey)) {
            return
        }

        runCatching {
            coverExecutor.execute {
                val cover = runCatching {
                    val firstImageEntry = ComicArchive.imageEntries(file).firstOrNull()
                        ?: return@runCatching null
                    ComicArchive.decodeImage(file, firstImageEntry, HISTORY_COVER_MAX_SIZE)
                }.getOrNull()

                loadingCovers.remove(cacheKey)
                if (closed) {
                    return@execute
                }

                if (cover == null) {
                    failedCovers.add(cacheKey)
                    return@execute
                }

                coverCache.put(cacheKey, cover)
                imgCover.post {
                    if (!closed && imgCover.tag == cacheKey) {
                        imgCover.scaleType = ImageView.ScaleType.CENTER_CROP
                        imgCover.setImageBitmap(cover)
                    }
                }
            }
        }.onFailure {
            loadingCovers.remove(cacheKey)
            failedCovers.add(cacheKey)
        }
    }

    companion object {
        private const val HISTORY_COVER_CACHE_SIZE = 40
        private const val HISTORY_COVER_MAX_SIZE = 128
        private const val HISTORY_COVER_THREAD_COUNT = 2
    }
}
