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

data class FileItem(
    val file: File? = null,
    val isParent: Boolean = false,
    val childCount: Int? = null
)

class FileListAdapter(
    context: Context,
    private val items: List<FileItem>
) : ArrayAdapter<FileItem>(context, 0, items) {

    private val archiveCoverCache = object : LruCache<String, Bitmap>(ARCHIVE_COVER_CACHE_SIZE) {}
    private val loadingArchiveCovers = Collections.synchronizedSet(mutableSetOf<String>())
    private val failedArchiveCovers = Collections.synchronizedSet(mutableSetOf<String>())
    private val archiveCoverExecutor = Executors.newFixedThreadPool(ARCHIVE_COVER_THREAD_COUNT)
    private var favoriteComicPaths = FavoriteComicStore.favoriteFilePaths(context)
    private var favoriteDirectoryPaths = FavoritePathStore.favoriteDirectoryPaths(context)
    @Volatile
    private var closed = false

    override fun notifyDataSetChanged() {
        refreshFavoriteMarkerCache()
        super.notifyDataSetChanged()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.file_item, parent, false)

        view.isPressed = false
        view.isSelected = false
        view.isActivated = false

        val imgIcon = view.findViewById<ImageView>(R.id.imgIcon)
        val imgFavoriteMarker = view.findViewById<ImageView>(R.id.imgFavoriteMarker)
        val tvName = view.findViewById<TextView>(R.id.tvName)
        val tvInfo = view.findViewById<TextView>(R.id.tvInfo)
        val tvTypeMarker = view.findViewById<TextView>(R.id.tvTypeMarker)
        val tvDate = view.findViewById<TextView>(R.id.tvDate)

        val item = items[position]
        hideFavoriteMarker(imgFavoriteMarker)

        if (item.isParent) {
            imgIcon.tag = null
            setDefaultIconLayout(imgIcon)
            imgIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
            imgIcon.setImageResource(R.drawable.png_back_icon)
            tvName.text = ".."
            tvInfo.text = context.getString(R.string.parent_directory)
            tvTypeMarker.text = "(D)"
            tvDate.text = ""
            return view
        }

        val file = item.file
        if (file == null) {
            imgIcon.tag = null
            setDefaultIconLayout(imgIcon)
            imgIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
            imgIcon.setImageResource(R.drawable.png_file_icon)
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
            bindDirectoryIcon(file, imgIcon)
            tvInfo.text = context.getString(R.string.item_count, item.childCount ?: 0)
            return view
        }

        if (ComicArchive.isArchive(file) || ReaderFileDetector.isMobi(file)) {
            bindArchiveIcon(file, imgIcon)
        } else {
            imgIcon.tag = null
            setDefaultIconLayout(imgIcon)
            imgIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
            imgIcon.setImageResource(R.drawable.png_file_icon)
        }
        tvInfo.text = CommonFunc.formatFileSize(file.length())

        return view
    }

    private fun refreshFavoriteMarkerCache() {
        favoriteComicPaths = FavoriteComicStore.favoriteFilePaths(context)
        favoriteDirectoryPaths = FavoritePathStore.favoriteDirectoryPaths(context)
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

        val cachedCover = archiveCoverCache.get(cacheKey)
        if (cachedCover != null) {
            setArchiveCoverLayout(imgIcon)
            imgIcon.scaleType = ImageView.ScaleType.CENTER_CROP
            imgIcon.setImageBitmap(cachedCover)
            return
        }

        if (cacheKey in failedArchiveCovers || !loadingArchiveCovers.add(cacheKey)) {
            return
        }

        executeCoverTask {
            val cover = runCatching {
                val firstFile = firstVisibleFileInDirectory(directory)
                    ?.takeIf { ComicArchive.isSupportedArchive(it) }
                    ?: return@runCatching null
                val firstImageEntry = ComicArchive.firstImageEntryIfFirstFileIsImage(firstFile)
                    ?: return@runCatching null
                ComicArchive.decodeImage(firstFile, firstImageEntry, ARCHIVE_COVER_MAX_SIZE)
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
                    setArchiveCoverLayout(imgIcon)
                    imgIcon.scaleType = ImageView.ScaleType.CENTER_CROP
                    imgIcon.setImageBitmap(cover)
                }
            }
        }
    }

    private fun bindArchiveIcon(file: File, imgIcon: ImageView) {
        setDefaultIconLayout(imgIcon)
        imgIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
        imgIcon.setImageResource(R.drawable.png_press_package_icon)

        if (ReaderFileDetector.isMobi(file)) {
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
            setArchiveCoverLayout(imgIcon)
            imgIcon.scaleType = ImageView.ScaleType.CENTER_CROP
            imgIcon.setImageBitmap(cachedCover)
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
                    setArchiveCoverLayout(imgIcon)
                    imgIcon.scaleType = ImageView.ScaleType.CENTER_CROP
                    imgIcon.setImageBitmap(cover)
                }
            }
        }
    }

    fun close() {
        closed = true
        archiveCoverExecutor.shutdownNow()
        loadingArchiveCovers.clear()
        failedArchiveCovers.clear()
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

    private fun setDefaultIconLayout(imgIcon: ImageView) {
        imgIcon.setBackgroundResource(R.drawable.bg_file_icon_frame)
        imgIcon.setPadding(dpToPx(DEFAULT_ICON_PADDING_DP), dpToPx(DEFAULT_ICON_PADDING_DP), dpToPx(DEFAULT_ICON_PADDING_DP), dpToPx(DEFAULT_ICON_PADDING_DP))
        updateIconSize(
            imgIcon,
            dpToPx(DEFAULT_ICON_SIZE_DP),
            dpToPx(DEFAULT_ICON_SIZE_DP)
        )
    }

    private fun setArchiveCoverLayout(imgIcon: ImageView) {
        imgIcon.setBackgroundColor(android.graphics.Color.BLACK)
        imgIcon.setPadding(0, 0, 0, 0)
        updateIconSize(
            imgIcon,
            dpToPx(ARCHIVE_COVER_WIDTH_DP),
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
        private const val ARCHIVE_COVER_CACHE_SIZE = 80
        private const val ARCHIVE_COVER_MAX_SIZE = 128
        private const val ARCHIVE_COVER_THREAD_COUNT = 2
        private const val ARCHIVE_COVER_WIDTH_DP = 56
        private const val DEFAULT_ICON_SIZE_DP = 34
        private const val DEFAULT_ICON_PADDING_DP = 4
    }
}
