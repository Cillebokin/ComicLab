package com.example.comiclab

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections
import java.util.concurrent.Executors

class FavoriteComicAdapter(
    private val context: Context,
    private val items: MutableList<FavoriteComicStore.Item>,
    private val onItemClick: (FavoriteComicStore.Item) -> Unit,
    private val onItemsEmptyChanged: (Boolean) -> Unit
) : RecyclerView.Adapter<FavoriteComicAdapter.ComicViewHolder>() {

    private val coverCache = object : LruCache<String, Bitmap>(FAVORITE_COMIC_COVER_CACHE_SIZE) {}
    private val loadingCovers = Collections.synchronizedSet(mutableSetOf<String>())
    private val failedCovers = Collections.synchronizedSet(mutableSetOf<String>())
    private val coverExecutor = Executors.newFixedThreadPool(FAVORITE_COMIC_COVER_THREAD_COUNT)

    @Volatile
    private var closed = false

    init {
        setHasStableIds(true)
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

    override fun onBindViewHolder(holder: ComicViewHolder, position: Int) {
        val item = items[position]
        val file = item.file

        holder.tvName.text = file.name
        holder.tvPath.text = file.parentFile?.absolutePath.orEmpty()
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
        coverExecutor.shutdownNow()
        loadingCovers.clear()
        failedCovers.clear()
    }

    private fun showItemOptionsMenu(anchor: View, position: Int) {
        val item = items.getOrNull(position) ?: return

        PopupMenu(context, anchor).apply {
            menu.add(R.string.delete_comic)
            setOnMenuItemClickListener {
                removeItem(item)
                true
            }
            show()
        }
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
                    ComicArchive.decodeImage(file, firstImageEntry, FAVORITE_COMIC_COVER_MAX_SIZE)
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

    class ComicViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val content: View = itemView.findViewById(R.id.layoutFavoriteComicContent)
        val imgCover: ImageView = itemView.findViewById(R.id.imgFavoriteComicCover)
        val tvName: TextView = itemView.findViewById(R.id.tvFavoriteComicName)
        val tvPath: TextView = itemView.findViewById(R.id.tvFavoriteComicPath)
        val btnOptions: ImageButton = itemView.findViewById(R.id.btnFavoriteComicItemOptions)
    }

    companion object {
        private const val FAVORITE_COMIC_COVER_CACHE_SIZE = 40
        private const val FAVORITE_COMIC_COVER_MAX_SIZE = 128
        private const val FAVORITE_COMIC_COVER_THREAD_COUNT = 2
    }
}
