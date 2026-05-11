package com.example.comiclab

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections
import java.util.concurrent.Executors

class ReadingHistoryAdapter(
    private val context: Context,
    private val items: MutableList<ReadingHistoryStore.Item>,
    private val onItemClick: (ReadingHistoryStore.Item) -> Unit,
    private val onItemsEmptyChanged: (Boolean) -> Unit
) : RecyclerView.Adapter<ReadingHistoryAdapter.HistoryViewHolder>() {

    private val coverCache = object : LruCache<String, Bitmap>(HISTORY_COVER_CACHE_SIZE) {}
    private val loadingCovers = Collections.synchronizedSet(mutableSetOf<String>())
    private val failedCovers = Collections.synchronizedSet(mutableSetOf<String>())
    private val coverExecutor = Executors.newFixedThreadPool(HISTORY_COVER_THREAD_COUNT)

    private var openItemPath: String? = null

    @Volatile
    private var closed = false

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return items.getOrNull(position)?.file?.absolutePath?.hashCode()?.toLong()
            ?: RecyclerView.NO_ID
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_reading_history, parent, false)
        return HistoryViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        val item = items[position]
        val file = item.file
        val isDeleteRevealed = openItemPath == file.absolutePath

        holder.tvName.text = file.name
        holder.tvPath.text = file.parentFile?.absolutePath.orEmpty()
        holder.setDeleteRevealed(isDeleteRevealed)
        holder.foreground.setOnClickListener {
            if (closeOpenItem()) {
                return@setOnClickListener
            }
            items.getOrNull(holder.bindingAdapterPosition)?.let(onItemClick)
        }
        holder.btnDelete.setOnClickListener {
            removeAt(holder.bindingAdapterPosition)
        }
        bindCover(item, holder.imgCover)
    }

    fun createSwipeCallback(): ItemTouchHelper.Callback {
        return object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onChildDraw(
                c: android.graphics.Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                if (actionState != ItemTouchHelper.ACTION_STATE_SWIPE ||
                    viewHolder !is HistoryViewHolder
                ) {
                    super.onChildDraw(
                        c,
                        recyclerView,
                        viewHolder,
                        dX,
                        dY,
                        actionState,
                        isCurrentlyActive
                    )
                    return
                }

                val clampedDx = dX.coerceIn(-viewHolder.deleteRevealWidth(), 0f)
                viewHolder.setForegroundTranslation(clampedDx)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) {
                    return
                }

                revealDeleteAt(position)
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ) {
                super.clearView(recyclerView, viewHolder)
                val holder = viewHolder as? HistoryViewHolder ?: return
                val position = holder.bindingAdapterPosition
                val itemPath = items.getOrNull(position)?.file?.absolutePath
                holder.setDeleteRevealed(itemPath != null && itemPath == openItemPath)
            }

            override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = 0.25f
        }
    }

    fun clearItems() {
        openItemPath = null
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

    private fun revealDeleteAt(position: Int) {
        val itemPath = items.getOrNull(position)?.file?.absolutePath ?: return
        val previousOpenPath = openItemPath
        openItemPath = itemPath

        previousOpenPath
            ?.takeIf { it != itemPath }
            ?.let { previousPath ->
                findPositionByPath(previousPath)
                    .takeIf { it != RecyclerView.NO_POSITION }
                    ?.let(::notifyItemChanged)
            }

        notifyItemChanged(position)
    }

    private fun closeOpenItem(): Boolean {
        val previousOpenPath = openItemPath ?: return false
        openItemPath = null
        findPositionByPath(previousOpenPath)
            .takeIf { it != RecyclerView.NO_POSITION }
            ?.let(::notifyItemChanged)
        return true
    }

    private fun removeAt(position: Int) {
        if (position == RecyclerView.NO_POSITION) {
            return
        }

        val removedItem = items.getOrNull(position) ?: return
        ReadingHistoryStore.remove(context, removedItem.file)
        openItemPath = null
        items.removeAt(position)
        notifyItemRemoved(position)
        onItemsEmptyChanged(items.isEmpty())
    }

    private fun findPositionByPath(path: String): Int {
        return items.indexOfFirst { it.file.absolutePath == path }
            .takeIf { it >= 0 }
            ?: RecyclerView.NO_POSITION
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

    class HistoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val foreground: View = itemView.findViewById(R.id.layoutReadingHistoryForeground)
        val btnDelete: TextView = itemView.findViewById(R.id.btnDeleteReadingHistory)
        val imgCover: ImageView = itemView.findViewById(R.id.imgReadingHistoryCover)
        val tvName: TextView = itemView.findViewById(R.id.tvReadingHistoryName)
        val tvPath: TextView = itemView.findViewById(R.id.tvReadingHistoryPath)

        fun deleteRevealWidth(): Float {
            val measuredWidth = btnDelete.width.takeIf { it > 0 }
                ?: (DEFAULT_DELETE_WIDTH_DP * itemView.resources.displayMetrics.density).toInt()
            return measuredWidth.toFloat()
        }

        fun setForegroundTranslation(value: Float) {
            foreground.translationX = value
        }

        fun setDeleteRevealed(revealed: Boolean) {
            val translation = if (revealed) -deleteRevealWidth() else 0f
            if (btnDelete.width > 0) {
                setForegroundTranslation(translation)
            } else {
                foreground.post {
                    setForegroundTranslation(if (revealed) -deleteRevealWidth() else 0f)
                }
            }
        }
    }

    companion object {
        private const val HISTORY_COVER_CACHE_SIZE = 40
        private const val HISTORY_COVER_MAX_SIZE = 128
        private const val HISTORY_COVER_THREAD_COUNT = 2
        private const val DEFAULT_DELETE_WIDTH_DP = 72
    }
}
