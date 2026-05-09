package com.example.comiclab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MangaReaderActivity : AppCompatActivity() {

    private lateinit var rootView: View
    private lateinit var listReaderPages: ZoomableReaderListView
    private lateinit var layoutReaderToolbar: View
    private lateinit var layoutReaderProgress: View
    private lateinit var sliderReaderProgress: SeekBar
    private lateinit var tvReaderTitle: TextView
    private lateinit var tvReaderProgress: TextView
    private lateinit var tvReaderStatus: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val readerPrefs by lazy {
        getSharedPreferences(READER_PREFS_NAME, MODE_PRIVATE)
    }
    private val autoHideControlsRunnable = Runnable {
        setReaderControlsVisible(false)
    }
    private val clearSuppressReaderTapRunnable = Runnable {
        suppressReaderTap = false
    }

    private var archiveFile: File? = null
    private var imageEntries: List<String> = emptyList()
    private var pageAdapter: MangaPageAdapter? = null
    private var readerControlsVisible = true
    private var suppressReaderTap = false
    private var isDraggingReaderSlider = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manga_reader)

        bindViews()
        configureImmersiveSystemBars()
        configureBackHandling()
        configureReaderActions()

        val path = intent.getStringExtra(EXTRA_ARCHIVE_PATH)
        val file = path?.let(::File)
        if (file == null || !file.isFile) {
            showError(getString(R.string.message_invalid_file))
            return
        }

        archiveFile = file
        tvReaderTitle.text = file.nameWithoutExtension

        loadArchive(file)
        showReaderControlsTemporarily()
    }

    override fun onPause() {
        saveReaderPosition()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        pageAdapter?.close()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !readerControlsVisible) {
            setSystemBarsVisible(false)
        }
    }

    private fun bindViews() {
        rootView = findViewById(R.id.main)
        listReaderPages = findViewById(R.id.listReaderPages)
        layoutReaderToolbar = findViewById(R.id.layoutReaderToolbar)
        layoutReaderProgress = findViewById(R.id.layoutReaderProgress)
        sliderReaderProgress = findViewById(R.id.sliderReaderProgress)
        tvReaderTitle = findViewById(R.id.tvReaderTitle)
        tvReaderProgress = findViewById(R.id.tvReaderProgress)
        tvReaderStatus = findViewById(R.id.tvReaderStatus)
    }

    private fun configureImmersiveSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowInsetsControllerCompat(window, rootView).systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        val toolbarInitialPaddingTop = layoutReaderToolbar.paddingTop
        val progressInitialMarginBottom =
            (layoutReaderProgress.layoutParams as FrameLayout.LayoutParams).bottomMargin
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            layoutReaderToolbar.setPadding(
                layoutReaderToolbar.paddingLeft,
                toolbarInitialPaddingTop + systemBars.top,
                layoutReaderToolbar.paddingRight,
                layoutReaderToolbar.paddingBottom
            )
            (layoutReaderProgress.layoutParams as FrameLayout.LayoutParams).apply {
                bottomMargin = progressInitialMarginBottom + systemBars.bottom
                layoutReaderProgress.layoutParams = this
            }
            insets
        }
        ViewCompat.requestApplyInsets(rootView)
    }

    private fun configureBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })
    }

    private fun configureReaderActions() {
        listReaderPages.setOnItemClickListener { _, _, _, _ ->
            if (suppressReaderTap) {
                return@setOnItemClickListener
            }

            if (readerControlsVisible) {
                setReaderControlsVisible(false)
            } else {
                showReaderControlsTemporarily()
            }
        }
        listReaderPages.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) = Unit

            override fun onScroll(
                view: AbsListView?,
                firstVisibleItem: Int,
                visibleItemCount: Int,
                totalItemCount: Int
            ) {
                updateReaderProgress(firstVisibleItem, totalItemCount)
            }
        })
        listReaderPages.onZoomChanged = { scale ->
            updateReaderZoom(scale)
        }
        sliderReaderProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    updateReaderProgressText(progress, imageEntries.size)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                isDraggingReaderSlider = true
                handler.removeCallbacks(autoHideControlsRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                isDraggingReaderSlider = false
                jumpReaderToPage(seekBar.progress)
                showReaderControlsTemporarily()
            }
        })
        listReaderPages.divider = ColorDrawable(Color.BLACK)
    }

    private fun loadArchive(file: File) {
        tvReaderStatus.text = getString(R.string.loading_reading)
        tvReaderStatus.visibility = View.VISIBLE

        Thread {
            val entries = runCatching {
                ComicArchive.imageEntries(file)
            }.getOrElse {
                runOnUiThread {
                    showError(getString(R.string.unsupported_archive_format))
                }
                return@Thread
            }

            runOnUiThread {
                if (entries.isEmpty()) {
                    showError(getString(R.string.no_reading_images))
                    return@runOnUiThread
                }

                imageEntries = entries
                tvReaderStatus.visibility = View.GONE
                bindReaderAdapter(file, entries)
            }
        }.start()
    }

    private fun bindReaderAdapter(file: File, entries: List<String>) {
        val displayWidth = listReaderPages.width
            .takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels.coerceAtLeast(MIN_READER_IMAGE_WIDTH)
        val decodeWidth = displayWidth * READER_PAGE_DECODE_SCALE
        pageAdapter?.close()
        pageAdapter = MangaPageAdapter(
            context = this,
            archiveFile = file,
            entries = entries,
            baseDisplayWidth = displayWidth,
            decodeWidth = decodeWidth
        )
        listReaderPages.adapter = pageAdapter
        sliderReaderProgress.max = (entries.size - 1).coerceAtLeast(0)
        sliderReaderProgress.progress = 0
        restoreReaderPosition()
        updateReaderProgress(listReaderPages.firstVisiblePosition, entries.size)
    }

    private fun updateReaderZoom(scale: Float) {
        suppressReaderTap = true
        handler.removeCallbacks(clearSuppressReaderTapRunnable)
        handler.postDelayed(clearSuppressReaderTapRunnable, SUPPRESS_TAP_AFTER_ZOOM_MS)

        val firstVisiblePosition = listReaderPages.firstVisiblePosition.coerceAtLeast(0)
        val firstChildTop = if (listReaderPages.childCount > 0) {
            listReaderPages.getChildAt(0).top - listReaderPages.paddingTop
        } else {
            0
        }

        pageAdapter?.setZoomScale(scale)
        listReaderPages.setSelectionFromTop(firstVisiblePosition, firstChildTop)
    }

    private fun updateReaderProgress(firstVisibleItem: Int, totalItemCount: Int) {
        if (totalItemCount <= 0) {
            tvReaderProgress.text = ""
            sliderReaderProgress.max = 0
            sliderReaderProgress.progress = 0
            return
        }

        val currentPosition = firstVisibleItem.coerceIn(0, totalItemCount - 1)
        updateReaderProgressText(currentPosition, totalItemCount)
        if (!isDraggingReaderSlider) {
            sliderReaderProgress.max = (totalItemCount - 1).coerceAtLeast(0)
            sliderReaderProgress.progress = currentPosition
        }
    }

    private fun updateReaderProgressText(position: Int, totalItemCount: Int) {
        if (totalItemCount <= 0) {
            tvReaderProgress.text = ""
            return
        }

        val currentPage = position.coerceIn(0, totalItemCount - 1) + 1
        tvReaderProgress.text = getString(R.string.reader_page_progress, currentPage, totalItemCount)
    }

    private fun jumpReaderToPage(position: Int) {
        if (imageEntries.isEmpty()) {
            return
        }

        val targetPosition = position.coerceIn(0, imageEntries.lastIndex)
        listReaderPages.setSelectionFromTop(targetPosition, 0)
        updateReaderProgress(targetPosition, imageEntries.size)
    }

    private fun saveReaderPosition() {
        val file = archiveFile ?: return
        if (imageEntries.isEmpty()) {
            return
        }

        val firstVisiblePosition = listReaderPages.firstVisiblePosition.coerceAtLeast(0)
        val firstChildTop = if (listReaderPages.childCount > 0) {
            listReaderPages.getChildAt(0).top - listReaderPages.paddingTop
        } else {
            0
        }

        readerPrefs.edit()
            .putInt(readerPositionKey(file), firstVisiblePosition)
            .putInt(readerOffsetKey(file), firstChildTop)
            .apply()
    }

    private fun restoreReaderPosition() {
        val file = archiveFile ?: return
        val position = readerPrefs.getInt(readerPositionKey(file), 0)
            .coerceIn(0, imageEntries.lastIndex.coerceAtLeast(0))
        val offset = readerPrefs.getInt(readerOffsetKey(file), 0)

        listReaderPages.post {
            listReaderPages.setSelectionFromTop(position, offset)
            updateReaderProgress(position, imageEntries.size)
        }
    }

    private fun showReaderControlsTemporarily() {
        setReaderControlsVisible(true)
        handler.removeCallbacks(autoHideControlsRunnable)
        handler.postDelayed(autoHideControlsRunnable, READER_CONTROLS_AUTO_HIDE_MS)
    }

    private fun setReaderControlsVisible(visible: Boolean) {
        readerControlsVisible = visible
        layoutReaderToolbar.visibility = if (visible) View.VISIBLE else View.GONE
        layoutReaderProgress.visibility = if (visible) View.VISIBLE else View.GONE
        setSystemBarsVisible(visible)
    }

    private fun setSystemBarsVisible(visible: Boolean) {
        val controller = WindowInsetsControllerCompat(window, rootView)
        if (visible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun showError(message: String) {
        tvReaderStatus.text = message
        tvReaderStatus.visibility = View.VISIBLE
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun readerPositionKey(file: File): String {
        return "${readerArchiveKey(file)}:position"
    }

    private fun readerOffsetKey(file: File): String {
        return "${readerArchiveKey(file)}:offset"
    }

    private fun readerArchiveKey(file: File): String {
        return "reader:${file.absolutePath}:${file.lastModified()}:${file.length()}"
    }

    private class MangaPageAdapter(
        private val context: Context,
        private val archiveFile: File,
        private val entries: List<String>,
        private val baseDisplayWidth: Int,
        private val decodeWidth: Int
    ) : BaseAdapter() {

        private val executor = Executors.newFixedThreadPool(READER_DECODE_THREAD_COUNT)
        private val loadingPages = Collections.synchronizedSet(mutableSetOf<Int>())
        private val failedPages = Collections.synchronizedSet(mutableSetOf<Int>())
        private val bitmapCache = object : LruCache<Int, Bitmap>(bitmapCacheSizeKb()) {
            override fun sizeOf(key: Int, value: Bitmap): Int {
                return (value.byteCount / 1024).coerceAtLeast(1)
            }
        }

        private var zoomScale = ZoomableReaderListView.MIN_ZOOM

        override fun getCount(): Int = entries.size

        override fun getItem(position: Int): String = entries[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val holder: PageViewHolder
            val view = if (convertView == null) {
                val container = FrameLayout(context).apply {
                    setBackgroundColor(Color.BLACK)
                    clipChildren = true
                }
                val imageView = ImageView(context).apply {
                    setBackgroundColor(Color.BLACK)
                    scaleType = ImageView.ScaleType.FIT_XY
                    adjustViewBounds = false
                    contentDescription = context.getString(R.string.reader_image)
                }
                container.addView(imageView)
                holder = PageViewHolder(imageView)
                container.tag = holder
                container
            } else {
                holder = convertView.tag as PageViewHolder
                convertView as FrameLayout
            }

            holder.position = position
            val cachedBitmap = bitmapCache.get(position)
            if (cachedBitmap != null) {
                bindBitmap(view, holder.imageView, cachedBitmap)
            } else {
                bindPlaceholder(view, holder.imageView)
                loadBitmap(position, view, holder)
            }

            return view
        }

        fun setZoomScale(scale: Float) {
            val newScale = scale.coerceIn(
                ZoomableReaderListView.MIN_ZOOM,
                ZoomableReaderListView.MAX_ZOOM
            )
            if (newScale == zoomScale) {
                return
            }

            zoomScale = newScale
            notifyDataSetChanged()
        }

        fun close() {
            executor.shutdownNow()
            bitmapCache.evictAll()
            loadingPages.clear()
            failedPages.clear()
        }

        private fun bindPlaceholder(container: FrameLayout, imageView: ImageView) {
            imageView.setImageDrawable(null)
            setContainerHeight(container, ESTIMATED_READER_PAGE_HEIGHT_RATIO)
            setImageSize(imageView, zoomedDisplayWidth(), ViewGroup.LayoutParams.MATCH_PARENT)
        }

        private fun loadBitmap(position: Int, container: FrameLayout, holder: PageViewHolder) {
            if (position in failedPages || !loadingPages.add(position)) {
                return
            }

            executor.execute {
                val bitmap = runCatching {
                    ComicArchive.decodeImageForWidth(archiveFile, entries[position], decodeWidth)
                }.getOrNull()
                loadingPages.remove(position)

                if (bitmap == null) {
                    failedPages.add(position)
                    return@execute
                }

                bitmapCache.put(position, bitmap)
                container.post {
                    if (holder.position == position) {
                        bindBitmap(container, holder.imageView, bitmap)
                    }
                }
            }
        }

        private fun bindBitmap(container: FrameLayout, imageView: ImageView, bitmap: Bitmap) {
            val imageWidth = zoomedDisplayWidth()
            val imageHeight = calculateImageHeight(bitmap, imageWidth)
            setContainerHeight(container, imageHeight)
            setImageSize(imageView, imageWidth, imageHeight)
            imageView.setImageBitmap(bitmap)
        }

        private fun setContainerHeight(container: FrameLayout, height: Int) {
            val layoutParams = container.layoutParams as? AbsListView.LayoutParams
                ?: AbsListView.LayoutParams(
                    AbsListView.LayoutParams.MATCH_PARENT,
                    height
                )
            if (layoutParams.height != height) {
                layoutParams.height = height
                container.layoutParams = layoutParams
            }
        }

        private fun setImageSize(imageView: ImageView, width: Int, height: Int) {
            val layoutParams = imageView.layoutParams as? FrameLayout.LayoutParams
                ?: FrameLayout.LayoutParams(width, height, Gravity.CENTER_HORIZONTAL)
            if (layoutParams.width != width || layoutParams.height != height) {
                layoutParams.width = width
                layoutParams.height = height
                layoutParams.gravity = Gravity.CENTER_HORIZONTAL
                imageView.layoutParams = layoutParams
            }
        }

        private fun setContainerHeight(container: FrameLayout, ratio: Float) {
            setContainerHeight(
                container,
                (zoomedDisplayWidth() * ratio).roundToInt().coerceAtLeast(MIN_READER_PAGE_HEIGHT)
            )
        }

        private fun calculateImageHeight(bitmap: Bitmap, imageWidth: Int): Int {
            if (bitmap.width <= 0 || bitmap.height <= 0 || imageWidth <= 0) {
                return MIN_READER_PAGE_HEIGHT
            }

            return (imageWidth.toFloat() / bitmap.width.toFloat() * bitmap.height.toFloat())
                .roundToInt()
                .coerceAtLeast(1)
        }

        private fun zoomedDisplayWidth(): Int {
            return (baseDisplayWidth * zoomScale).roundToInt().coerceAtLeast(baseDisplayWidth)
        }

        private data class PageViewHolder(
            val imageView: ImageView,
            var position: Int = -1
        )

        companion object {
            private const val READER_DECODE_THREAD_COUNT = 2
            private const val ESTIMATED_READER_PAGE_HEIGHT_RATIO = 1.45f
            private const val MIN_READER_PAGE_HEIGHT = 320

            private fun bitmapCacheSizeKb(): Int {
                val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
                return (maxMemoryKb / 3).coerceAtLeast(24 * 1024)
            }
        }
    }

    companion object {
        const val EXTRA_ARCHIVE_PATH = "archive_path"

        private const val READER_PREFS_NAME = "reader_prefs"
        private const val MIN_READER_IMAGE_WIDTH = 320
        private const val READER_PAGE_DECODE_SCALE = 3
        private const val READER_CONTROLS_AUTO_HIDE_MS = 2600L
        private const val SUPPRESS_TAP_AFTER_ZOOM_MS = 250L
    }
}
