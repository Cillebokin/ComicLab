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
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors

data class FileItem(
    val file: File? = null,
    val isParent: Boolean = false
)

class FileListAdapter(
    context: Context,
    private val items: List<FileItem>
) : ArrayAdapter<FileItem>(context, 0, items) {

    private val archiveCoverCache = object : LruCache<String, Bitmap>(ARCHIVE_COVER_CACHE_SIZE) {}
    private val loadingArchiveCovers = Collections.synchronizedSet(mutableSetOf<String>())
    private val failedArchiveCovers = Collections.synchronizedSet(mutableSetOf<String>())
    private val archiveCoverExecutor = Executors.newFixedThreadPool(ARCHIVE_COVER_THREAD_COUNT)

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.file_item, parent, false)

        view.isPressed = false
        view.isSelected = false
        view.isActivated = false

        val imgIcon = view.findViewById<ImageView>(R.id.imgIcon)
        val tvName = view.findViewById<TextView>(R.id.tvName)
        val tvInfo = view.findViewById<TextView>(R.id.tvInfo)
        val tvDate = view.findViewById<TextView>(R.id.tvDate)

        val item = items[position]

        if (item.isParent) {
            imgIcon.tag = null
            setDefaultIconLayout(imgIcon)
            imgIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
            imgIcon.setImageResource(R.drawable.png_back_icon)
            tvName.text = ".."
            tvInfo.text = context.getString(R.string.parent_directory)
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
            tvDate.text = ""
            return view
        }

        tvName.text = file.name
        tvDate.text = CommonFunc.formatDate(file.lastModified())

        if (file.isDirectory) {
            imgIcon.tag = null
            setDefaultIconLayout(imgIcon)
            imgIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
            imgIcon.setImageResource(R.drawable.png_directory_icon)
            tvInfo.text = context.getString(R.string.item_count, file.listFiles()?.size ?: 0)
            return view
        }

        if (ComicArchive.isArchive(file)) {
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

    private fun bindArchiveIcon(file: File, imgIcon: ImageView) {
        setDefaultIconLayout(imgIcon)
        imgIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
        imgIcon.setImageResource(R.drawable.png_press_package_icon)

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

        archiveCoverExecutor.execute {
            val cover = runCatching {
                val firstImageEntry = ComicArchive.imageEntries(file).firstOrNull()
                firstImageEntry?.let {
                    ComicArchive.decodeImage(file, it, ARCHIVE_COVER_MAX_SIZE)
                }
            }.getOrNull()

            loadingArchiveCovers.remove(cacheKey)

            if (cover == null) {
                failedArchiveCovers.add(cacheKey)
                return@execute
            }

            archiveCoverCache.put(cacheKey, cover)
            imgIcon.post {
                if (imgIcon.tag == cacheKey) {
                    setArchiveCoverLayout(imgIcon)
                    imgIcon.scaleType = ImageView.ScaleType.CENTER_CROP
                    imgIcon.setImageBitmap(cover)
                }
            }
        }
    }

    private fun setDefaultIconLayout(imgIcon: ImageView) {
        updateIconSize(
            imgIcon,
            dpToPx(DEFAULT_ICON_SIZE_DP),
            dpToPx(DEFAULT_ICON_SIZE_DP)
        )
    }

    private fun setArchiveCoverLayout(imgIcon: ImageView) {
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

    companion object {
        private const val ARCHIVE_COVER_CACHE_SIZE = 80
        private const val ARCHIVE_COVER_MAX_SIZE = 128
        private const val ARCHIVE_COVER_THREAD_COUNT = 2
        private const val ARCHIVE_COVER_WIDTH_DP = 56
        private const val DEFAULT_ICON_SIZE_DP = 32
    }
}
