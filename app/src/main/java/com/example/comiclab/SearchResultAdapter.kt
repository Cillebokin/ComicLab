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
import com.example.comiclab.ebook.ReaderFileDetector
import java.io.File
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Executors

class SearchResultAdapter(
    context: Context,
    private val items: List<File>
) : ArrayAdapter<File>(context, 0, items) {

    private val coverCache = object : LruCache<String, Bitmap>(SEARCH_COVER_CACHE_SIZE) {}
    private val loadingCovers = Collections.synchronizedSet(mutableSetOf<String>())
    private val failedCovers = Collections.synchronizedSet(mutableSetOf<String>())
    private val coverExecutor = Executors.newFixedThreadPool(SEARCH_COVER_THREAD_COUNT)
    @Volatile
    private var closed = false

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.file_item, parent, false)

        view.isPressed = false
        view.isSelected = false
        view.isActivated = false

        val file = items[position]
        val imgIcon = view.findViewById<ImageView>(R.id.imgIcon)
        val imgFavoriteMarker = view.findViewById<ImageView>(R.id.imgFavoriteMarker)
        val tvName = view.findViewById<TextView>(R.id.tvName)
        val tvInfo = view.findViewById<TextView>(R.id.tvInfo)
        val tvTypeMarker = view.findViewById<TextView>(R.id.tvTypeMarker)
        val tvDate = view.findViewById<TextView>(R.id.tvDate)

        imgFavoriteMarker.setImageDrawable(null)
        imgFavoriteMarker.visibility = View.GONE

        bindIcon(file, imgIcon)

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
        failedCovers.clear()
    }

    private fun bindIcon(file: File, imgIcon: ImageView) {
        when {
            file.isDirectory -> bindDirectoryIcon(file, imgIcon)
            ComicArchive.isArchive(file) || ReaderFileDetector.isEbook(file) -> bindArchiveIcon(file, imgIcon)
            else -> {
                imgIcon.tag = null
                setDefaultIconLayout(imgIcon)
                imgIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
                imgIcon.setImageResource(R.drawable.png_file_icon)
            }
        }
    }

    private fun bindDirectoryIcon(directory: File, imgIcon: ImageView) {
        setDefaultIconLayout(imgIcon)
        imgIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
        imgIcon.setImageResource(R.drawable.png_directory_icon)

        if (!AppSettings.isDetectMangaCollectionsEnabled(context)) {
            imgIcon.tag = null
            return
        }

        val cacheKey = mangaCollectionCoverCacheKey(directory)
        imgIcon.tag = cacheKey

        val cachedCover = coverCache.get(cacheKey)
        if (cachedCover != null) {
            bindCoverBitmap(imgIcon, cachedCover)
            return
        }

        if (cacheKey in failedCovers || !loadingCovers.add(cacheKey)) {
            return
        }

        executeCoverTask {
            val cover = runCatching {
                val firstFile = firstVisibleFileInDirectory(directory)
                    ?.takeIf { ComicArchive.isSupportedArchive(it) }
                    ?: return@runCatching null
                val firstImageEntry = ComicArchive.firstImageEntryIfFirstFileIsImage(firstFile)
                    ?: return@runCatching null
                ComicArchive.decodeImage(firstFile, firstImageEntry, SEARCH_COVER_MAX_SIZE)
            }.getOrNull()
            handleCoverResult(cacheKey, cover, imgIcon)
        }
    }

    private fun bindArchiveIcon(file: File, imgIcon: ImageView) {
        setDefaultIconLayout(imgIcon)
        imgIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
        imgIcon.setImageResource(R.drawable.png_press_package_icon)

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
            bindCoverBitmap(imgIcon, cachedCover)
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

    private fun handleCoverResult(cacheKey: String, cover: Bitmap?, imgIcon: ImageView) {
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
                bindCoverBitmap(imgIcon, cover)
            }
        }
    }

    private fun bindCoverBitmap(imgIcon: ImageView, bitmap: Bitmap) {
        setCoverIconLayout(imgIcon)
        imgIcon.scaleType = ImageView.ScaleType.CENTER_CROP
        imgIcon.setImageBitmap(bitmap)
    }

    private fun setDefaultIconLayout(imgIcon: ImageView) {
        imgIcon.setBackgroundResource(R.drawable.bg_file_icon_frame)
        imgIcon.setPadding(
            dpToPx(DEFAULT_ICON_PADDING_DP),
            dpToPx(DEFAULT_ICON_PADDING_DP),
            dpToPx(DEFAULT_ICON_PADDING_DP),
            dpToPx(DEFAULT_ICON_PADDING_DP)
        )
        updateIconSize(
            imgIcon,
            dpToPx(DEFAULT_ICON_SIZE_DP),
            dpToPx(DEFAULT_ICON_SIZE_DP)
        )
    }

    private fun setCoverIconLayout(imgIcon: ImageView) {
        imgIcon.setBackgroundColor(android.graphics.Color.BLACK)
        imgIcon.setPadding(0, 0, 0, 0)
        updateIconSize(
            imgIcon,
            dpToPx(SEARCH_COVER_WIDTH_DP),
            ViewGroup.LayoutParams.MATCH_PARENT
        )
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

    private fun mangaCollectionCoverCacheKey(directory: File): String {
        val firstFile = firstVisibleFileInDirectory(directory)
        return if (firstFile == null) {
            "collection:${directory.absolutePath}:${directory.lastModified()}:empty"
        } else {
            "collection:${directory.absolutePath}:${directory.lastModified()}:" +
                "${firstFile.absolutePath}:${firstFile.lastModified()}:${firstFile.length()}"
        }
    }

    private fun firstVisibleFileInDirectory(directory: File): File? {
        return try {
            directory.listFiles()
                ?.asSequence()
                ?.filter { it.isFile && !it.name.startsWith(".") }
                ?.sortedBy { it.name.lowercase(Locale.ROOT) }
                ?.firstOrNull()
        } catch (_: SecurityException) {
            null
        }
    }

    companion object {
        private const val SEARCH_COVER_CACHE_SIZE = 80
        private const val SEARCH_COVER_MAX_SIZE = 128
        private const val SEARCH_COVER_THREAD_COUNT = 2
        private const val SEARCH_COVER_WIDTH_DP = 56
        private const val DEFAULT_ICON_SIZE_DP = 34
        private const val DEFAULT_ICON_PADDING_DP = 4
    }
}
