package com.example.comiclab.ui.browser.panels

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.os.Handler
import android.os.Looper
import androidx.appcompat.content.res.AppCompatResources
import androidx.recyclerview.widget.RecyclerView
import com.example.comiclab.core.concurrent.BoundedKeyedTaskQueue
import com.example.comiclab.ComicArchive
import com.example.comiclab.R
import com.example.comiclab.core.store.EbookProgressStore
import com.example.comiclab.core.store.FavoriteComicStore
import com.example.comiclab.core.store.ReaderProgressStore
import com.example.comiclab.ui.browser.bindReadingProgressBar
import com.example.comiclab.ui.common.RoundedPopupMenu
import java.util.Collections

class FavoriteComicAdapter(
    private val context: Context,
    private val items: MutableList<FavoriteComicStore.Item>,
    private val onItemClick: (FavoriteComicStore.Item) -> Unit,
    private val onJumpToPath: (FavoriteComicStore.Item) -> Unit,
    private val onItemsEmptyChanged: (Boolean) -> Unit
) : RecyclerView.Adapter<FavoriteComicAdapter.ComicViewHolder>() {

    private val coverCache = object : LruCache<String, Bitmap>(FAVORITE_COMIC_COVER_CACHE_SIZE) {}
    private val pageCounts = object : LruCache<String, Int>(FAVORITE_COMIC_COVER_CACHE_SIZE) {}
    private val mainHandler = Handler(Looper.getMainLooper())
    private val refreshProgress = Runnable { if (!closed) notifyDataSetChanged() }
    private val progressListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        mainHandler.removeCallbacks(refreshProgress)
        mainHandler.post(refreshProgress)
    }
    private val loadingCovers = Collections.synchronizedSet(mutableSetOf<String>())
    private val failedCovers = Collections.synchronizedSet(mutableSetOf<String>())
    private val coverTasks = BoundedKeyedTaskQueue(FAVORITE_COMIC_COVER_THREAD_COUNT)

    @Volatile
    private var closed = false

    init {
        setHasStableIds(true)
        ReaderProgressStore.registerChangeListener(context, progressListener)
        EbookProgressStore.registerChangeListener(context, progressListener)
    }

    override fun getItemId(position: Int): Long {
        return items.getOrNull(position)?.file?.absolutePath?.hashCode()?.toLong()
            ?: RecyclerView.NO_ID
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ComicViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_favorite_comic, parent, false)
        return ComicViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onViewRecycled(holder: ComicViewHolder) {
        val cacheKey = holder.imgCover.tag as? String
        if (cacheKey != null && coverTasks.cancelQueued(cacheKey)) {
            loadingCovers.remove(cacheKey)
        }
        super.onViewRecycled(holder)
    }

    override fun onBindViewHolder(holder: ComicViewHolder, position: Int) {
        val item = items[position]
        val file = item.file

        holder.tvName.text = file.name
        holder.tvPath.text = file.parentFile?.absolutePath.orEmpty()
        val cacheKey = "${file.absolutePath}:${file.lastModified()}:${file.length()}"
        bindReadingProgressBar(context, file, holder.progress, pageCounts.get(cacheKey) ?: 0)
        holder.content.setOnClickListener {
            items.getOrNull(holder.bindingAdapterPosition)?.let(onItemClick)
        }
        holder.btnOptions.setOnClickListener { anchor ->
            showItemOptionsMenu(anchor, holder.bindingAdapterPosition)
        }
        bindCover(item, holder.imgCover)
    }

    fun clearItems() {
        items.clear()
        notifyDataSetChanged()
        onItemsEmptyChanged(true)
    }

    fun close() {
        closed = true
        ReaderProgressStore.unregisterChangeListener(context, progressListener)
        EbookProgressStore.unregisterChangeListener(context, progressListener)
        mainHandler.removeCallbacks(refreshProgress)
        coverTasks.close()
        loadingCovers.clear()
        failedCovers.clear()
    }

    private fun showItemOptionsMenu(anchor: View, position: Int) {
        val item = items.getOrNull(position) ?: return

        RoundedPopupMenu.show(
            context = context,
            anchor = anchor,
            widthDp = 148,
            items = listOf(
                RoundedPopupMenu.Item(context.getString(R.string.jump_to_path)) {
                    onJumpToPath(item)
                },
                RoundedPopupMenu.Item(context.getString(R.string.delete_comic)) {
                    removeItem(item)
                }
            )
        )
    }

    private fun removeItem(item: FavoriteComicStore.Item) {
        val itemIndex = items.indexOfFirst {
            it.file.absolutePath == item.file.absolutePath
        }
        if (itemIndex < 0) {
            return
        }

        FavoriteComicStore.remove(context, item.file)
        items.removeAt(itemIndex)
        notifyItemRemoved(itemIndex)
        onItemsEmptyChanged(items.isEmpty())
    }

    private fun bindCover(item: FavoriteComicStore.Item, imgCover: ImageView) {
        val file = item.file
        imgCover.scaleType = ImageView.ScaleType.CENTER_INSIDE
        imgCover.imageTintList = AppCompatResources.getColorStateList(
            context,
            R.color.comiclab_icon
        )
        imgCover.setImageResource(R.drawable.png_press_package_icon)

        val cacheKey = "${file.absolutePath}:${file.lastModified()}:${file.length()}"
        imgCover.tag = cacheKey
        val cachedCover = coverCache.get(cacheKey)
        if (cachedCover != null) {
            imgCover.imageTintList = null
            imgCover.scaleType = ImageView.ScaleType.CENTER_CROP
            imgCover.setImageBitmap(cachedCover)
            if (pageCounts.get(cacheKey) != null) return
        }

        if ((cacheKey in failedCovers && pageCounts.get(cacheKey) != null) || !loadingCovers.add(cacheKey)) {
            return
        }

        coverTasks.executeCancellable(
            key = cacheKey,
            onRejected = { loadingCovers.remove(cacheKey) }
        ) { isActive ->
            val entries = runCatching { ComicArchive.imageEntries(file) }.getOrDefault(emptyList())
            val firstImageEntry = entries.firstOrNull()
            if (!isActive()) {
                loadingCovers.remove(cacheKey)
                return@executeCancellable
            }
            val cover = firstImageEntry?.let { entryName ->
                runCatching {
                    ComicArchive.decodeImage(file, entryName, FAVORITE_COMIC_COVER_MAX_SIZE, context.cacheDir)
                }.getOrNull()
            }

            loadingCovers.remove(cacheKey)
            if (closed || !isActive()) {
                return@executeCancellable
            }

            pageCounts.put(cacheKey, entries.size)
            if (cover == null) failedCovers.add(cacheKey) else coverCache.put(cacheKey, cover)
            mainHandler.post {
                if (!closed) {
                    val index = items.indexOfFirst { it.file.absolutePath == file.absolutePath }
                    if (index >= 0) notifyItemChanged(index)
                }
            }

            if (cover == null) {
                return@executeCancellable
            }

            imgCover.post {
                if (!closed && imgCover.tag == cacheKey) {
                    imgCover.imageTintList = null
                    imgCover.scaleType = ImageView.ScaleType.CENTER_CROP
                    imgCover.setImageBitmap(cover)
                }
            }
        }
    }

    class ComicViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val content: View = itemView.findViewById(R.id.layoutFavoriteComicContent)
        val imgCover: ImageView = itemView.findViewById(R.id.imgFavoriteComicCover)
        val tvName: TextView = itemView.findViewById(R.id.tvFavoriteComicName)
        val tvPath: TextView = itemView.findViewById(R.id.tvFavoriteComicPath)
        val progress: ProgressBar = itemView.findViewById(R.id.progressPanelReading)
        val btnOptions: ImageButton = itemView.findViewById(R.id.btnFavoriteComicItemOptions)
    }

    companion object {
        private const val FAVORITE_COMIC_COVER_CACHE_SIZE = 40
        private const val FAVORITE_COMIC_COVER_MAX_SIZE = 128
        private const val FAVORITE_COMIC_COVER_THREAD_COUNT = 2
    }
}
