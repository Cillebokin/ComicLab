package com.example.comiclab

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Executors

class ReadingHistoryAdapter(
    private val context: Context,
    private val items: MutableList<ReadingHistoryStore.Item>,
    private val onItemClick: (ReadingHistoryStore.Item) -> Unit,
    private val onItemsEmptyChanged: (Boolean) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val coverCache = object : LruCache<String, Bitmap>(HISTORY_COVER_CACHE_SIZE) {}
    private val loadingCovers = Collections.synchronizedSet(mutableSetOf<String>())
    private val failedCovers = Collections.synchronizedSet(mutableSetOf<String>())
    private val coverExecutor = Executors.newFixedThreadPool(HISTORY_COVER_THREAD_COUNT)

    private var rows = buildRows()

    @Volatile
    private var closed = false

    init {
        setHasStableIds(true)
    }

    override fun getItemViewType(position: Int): Int {
        return when (rows[position]) {
            is HistoryRow.DateHeader -> VIEW_TYPE_DATE
            is HistoryRow.HistoryItem -> VIEW_TYPE_HISTORY
        }
    }

    override fun getItemId(position: Int): Long {
        return when (val row = rows.getOrNull(position)) {
            is HistoryRow.DateHeader -> row.dayStartMillis xor DATE_HEADER_ID_MASK
            is HistoryRow.HistoryItem -> row.item.file.absolutePath.hashCode().toLong()
            null -> RecyclerView.NO_ID
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_DATE -> DateViewHolder(
                inflater.inflate(R.layout.item_reading_history_date, parent, false)
            )

            else -> HistoryViewHolder(
                inflater.inflate(R.layout.item_reading_history, parent, false)
            )
        }
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is HistoryRow.DateHeader -> bindDateHeader(holder as DateViewHolder, row)
            is HistoryRow.HistoryItem -> bindHistoryItem(holder as HistoryViewHolder, row.item)
        }
    }

    fun clearItems() {
        items.clear()
        rebuildRows()
        notifyDataSetChanged()
        onItemsEmptyChanged(true)
    }

    fun close() {
        closed = true
        coverExecutor.shutdownNow()
        loadingCovers.clear()
        failedCovers.clear()
    }

    private fun bindDateHeader(holder: DateViewHolder, row: HistoryRow.DateHeader) {
        holder.tvDate.text = row.label
    }

    private fun bindHistoryItem(holder: HistoryViewHolder, item: ReadingHistoryStore.Item) {
        val file = item.file

        holder.tvName.text = file.name
        holder.tvPath.text = file.parentFile?.absolutePath.orEmpty()
        holder.content.setOnClickListener {
            historyItemAt(holder.bindingAdapterPosition)?.let(onItemClick)
        }
        holder.btnDelete.setOnClickListener {
            removeAt(holder.bindingAdapterPosition)
        }
        bindCover(item, holder.imgCover)
    }

    private fun removeAt(position: Int) {
        val item = historyItemAt(position) ?: return
        val itemIndex = items.indexOfFirst { it.file.absolutePath == item.file.absolutePath }
        if (itemIndex < 0) {
            return
        }

        ReadingHistoryStore.remove(context, item.file)
        items.removeAt(itemIndex)
        rebuildRows()
        notifyDataSetChanged()
        onItemsEmptyChanged(items.isEmpty())
    }

    private fun historyItemAt(position: Int): ReadingHistoryStore.Item? {
        val row = rows.getOrNull(position) as? HistoryRow.HistoryItem
        return row?.item
    }

    private fun rebuildRows() {
        rows = buildRows()
    }

    private fun buildRows(): List<HistoryRow> {
        val builtRows = mutableListOf<HistoryRow>()
        var currentDayStart: Long? = null

        items.forEach { item ->
            val itemDayStart = startOfDayMillis(item.lastReadAt)
            if (currentDayStart != itemDayStart) {
                currentDayStart = itemDayStart
                builtRows.add(
                    HistoryRow.DateHeader(
                        dayStartMillis = itemDayStart,
                        label = formatHistoryDate(item.lastReadAt)
                    )
                )
            }
            builtRows.add(HistoryRow.HistoryItem(item))
        }

        return builtRows
    }

    private fun startOfDayMillis(value: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = value
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun formatHistoryDate(value: Long): String {
        val itemCalendar = Calendar.getInstance().apply {
            timeInMillis = value
        }
        val today = Calendar.getInstance()
        val yesterday = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -1)
        }

        return when {
            isSameDay(itemCalendar, today) -> context.getString(R.string.today)
            isSameDay(itemCalendar, yesterday) -> context.getString(R.string.yesterday)
            itemCalendar.get(Calendar.YEAR) == today.get(Calendar.YEAR) ->
                SimpleDateFormat("M月d日", Locale.getDefault()).format(itemCalendar.time)

            else -> SimpleDateFormat("yyyy年M月d日", Locale.getDefault()).format(itemCalendar.time)
        }
    }

    private fun isSameDay(left: Calendar, right: Calendar): Boolean {
        return left.get(Calendar.YEAR) == right.get(Calendar.YEAR) &&
            left.get(Calendar.DAY_OF_YEAR) == right.get(Calendar.DAY_OF_YEAR)
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

    private sealed class HistoryRow {
        data class DateHeader(
            val dayStartMillis: Long,
            val label: String
        ) : HistoryRow()

        data class HistoryItem(
            val item: ReadingHistoryStore.Item
        ) : HistoryRow()
    }

    private class DateViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvDate: TextView = itemView.findViewById(R.id.tvReadingHistoryDate)
    }

    private class HistoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val content: View = itemView.findViewById(R.id.layoutReadingHistoryForeground)
        val btnDelete: TextView = itemView.findViewById(R.id.btnDeleteReadingHistory)
        val imgCover: ImageView = itemView.findViewById(R.id.imgReadingHistoryCover)
        val tvName: TextView = itemView.findViewById(R.id.tvReadingHistoryName)
        val tvPath: TextView = itemView.findViewById(R.id.tvReadingHistoryPath)
    }

    companion object {
        private const val VIEW_TYPE_DATE = 0
        private const val VIEW_TYPE_HISTORY = 1
        private const val HISTORY_COVER_CACHE_SIZE = 40
        private const val HISTORY_COVER_MAX_SIZE = 128
        private const val HISTORY_COVER_THREAD_COUNT = 2
        private const val DATE_HEADER_ID_MASK = Long.MIN_VALUE
    }
}
