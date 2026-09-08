package com.example.comiclab

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.LruCache
import android.view.Gravity
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.davemorrissey.labs.subscaleview.ImageSource
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToInt

private fun readerPageContentGravity(
    readingDirection: String,
    position: Int,
    doublePageReading: Boolean
): Int {
    if (!doublePageReading) {
        return Gravity.CENTER
    }

    val isVisualLeftPage = if (readingDirection == AppSettings.READING_DIRECTION_RIGHT_TO_LEFT) {
        position % 2 != 0
    } else {
        position % 2 == 0
    }
    return if (isVisualLeftPage) {
        Gravity.RIGHT or Gravity.CENTER_VERTICAL
    } else {
        Gravity.LEFT or Gravity.CENTER_VERTICAL
    }
}

class MangaReaderActivity : AppCompatActivity() {

    private lateinit var rootView: View
    private lateinit var listReaderPages: ZoomableReaderRecyclerView
    private lateinit var readerLayoutManager: LinearLayoutManager
    private lateinit var layoutReaderToolbar: View
    private lateinit var layoutReaderProgress: View
    private lateinit var readerPreviewScrim: View
    private lateinit var layoutReaderPreviewPanel: View
    private lateinit var listReaderPreview: RecyclerView
    private lateinit var sliderReaderProgress: SeekBar
    private lateinit var sliderScreenBrightness: SeekBar
    private lateinit var checkboxCustomBrightness: CheckBox
    private lateinit var btnReaderPreview: Button
    private lateinit var tvReaderTitle: TextView
    private lateinit var tvReaderProgress: TextView
    private lateinit var tvReaderStatus: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val readerLoadExecutor = Executors.newSingleThreadExecutor()
    private val readerProgressExecutor = Executors.newSingleThreadExecutor()
    private val readerLoadGeneration = AtomicInteger(0)
    private val readerPrefs by lazy {
        getSharedPreferences(READER_PREFS_NAME, MODE_PRIVATE)
    }
    private val clearSuppressReaderTapRunnable = Runnable {
        suppressReaderTap = false
    }
    private val readerCheckpointRunnable = Runnable {
        saveReaderPosition()
    }
    private val debugReaderStressRunnable = object : Runnable {
        override fun run() {
            runDebugReaderStressStep()
        }
    }

    private var archiveFile: File? = null
    private var readerSessionMarker: String? = null
    private var imageEntries: List<String> = emptyList()
    private var pageAdapter: ReaderPageAdapterController? = null
    private var readerPreviewAdapter: ReaderPreviewAdapter? = null
    private lateinit var readerControlsController: ReaderControlsController
    private val readerControlsVisible: Boolean
        get() = if (::readerControlsController.isInitialized) {
            readerControlsController.isVisible
        } else {
            true
        }
    private var readerPreviewPanelVisible = false
    private var suppressReaderTap = false
    private var isDraggingReaderSlider = false
    private var shouldStartFromBeginning = false
    private var explicitStartPageIndex = NO_EXPLICIT_START_PAGE
    private var pendingRestorePosition: Int? = null
    private var pendingRestoreOffset = 0
    private var restoreAttemptCount = 0
    private var restoreRetryScheduled = false
    private var currentReaderPosition = 0
    private var currentReaderOffset = 0
    private var readerScrollDirection = SCROLL_DIRECTION_FORWARD
    private var readerScrollState = RecyclerView.SCROLL_STATE_IDLE
    private var readingDirection = AppSettings.READING_DIRECTION_TOP_TO_BOTTOM
    private var readerCoverSinglePageEnabled = false
    private var lastReaderPreloadSpreadStart = RecyclerView.NO_POSITION
    private var volumeKeyPageTurnEnabled = true
    private var autoHideSystemBarsEnabled = true
    private var lastVolumePageTurnAt = 0L
    private var customReaderBrightnessEnabled = false
    private var isUpdatingBrightnessControls = false
    private var debugReaderStressEnabled = false
    private var debugReaderStressStarted = false
    private var debugReaderStressRemainingIterations = 0
    private var debugReaderStressStep = 0
    private var debugReaderStressDelayMs = DEFAULT_DEBUG_READER_STRESS_DELAY_MS
    private var debugReaderStressTrimEvery = DEFAULT_DEBUG_READER_STRESS_TRIM_EVERY
    @Volatile
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manga_reader)
        readingDirection = AppSettings.getReadingDirection(this)
        volumeKeyPageTurnEnabled = AppSettings.isVolumeKeyPageTurnEnabled(this)
        autoHideSystemBarsEnabled = AppSettings.isAutoHideSystemBarsEnabled(this)
        customReaderBrightnessEnabled = AppSettings.isCustomReaderBrightnessEnabled(this)

        bindViews()
        configureImmersiveSystemBars()
        configureBackHandling()
        configureReaderActions()
        configureBrightnessControls()

        val path = intent.getStringExtra(EXTRA_ARCHIVE_PATH)
        val file = path?.let(::File)
        if (file == null || !file.isFile) {
            showError(getString(R.string.message_invalid_file))
            return
        }

        archiveFile = file
        tvReaderTitle.text = file.nameWithoutExtension
        readerSessionMarker = CrashLogManager.recordReaderSessionStarted(this, file)
        shouldStartFromBeginning = intent.getBooleanExtra(EXTRA_START_FROM_BEGINNING, false)
        explicitStartPageIndex = intent.getIntExtra(EXTRA_START_PAGE_INDEX, NO_EXPLICIT_START_PAGE)
        configureDebugReaderStress()
        if (shouldStartFromBeginning) {
            clearSavedReadingProgress(this, file)
        }

        val preparedCachePath = intent.getStringExtra(EXTRA_PREPARED_READER_CACHE_DIR)
        val preparedCacheDir = preparedCachePath?.let(::File)
        if (preparedCacheDir != null && preparedCacheDir.isDirectory) {
            loadPreparedArchive(file, preparedCacheDir)
        } else {
            loadArchive(file)
        }
        showReaderControlsTemporarily()
    }

    override fun onResume() {
        super.onResume()
        volumeKeyPageTurnEnabled = AppSettings.isVolumeKeyPageTurnEnabled(this)
        autoHideSystemBarsEnabled = AppSettings.isAutoHideSystemBarsEnabled(this)
        readerControlsController.setAutoHideSystemBarsEnabled(autoHideSystemBarsEnabled)
        updateReaderGestureExclusionRects()
        applyReaderBrightnessSetting()
        updateBrightnessControls()
    }

    override fun onPause() {
        handler.removeCallbacks(readerCheckpointRunnable)
        saveReaderPosition(forceCommit = true)
        restoreSystemBrightness()
        super.onPause()
    }

    override fun onStop() {
        handler.removeCallbacks(readerCheckpointRunnable)
        saveReaderPosition(forceCommit = true)
        super.onStop()
    }

    override fun onDestroy() {
        destroyed = true
        readerLoadGeneration.incrementAndGet()
        readerLoadExecutor.shutdownNow()
        readerProgressExecutor.shutdown()
        handler.removeCallbacksAndMessages(null)
        readerControlsController.close()
        CrashLogManager.recordReaderSessionFinished(this, readerSessionMarker)
        readerSessionMarker = null
        pageAdapter?.close()
        pageAdapter = null
        readerPreviewAdapter?.close()
        readerPreviewAdapter = null
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        pageAdapter?.trimMemory(level)
        readerPreviewAdapter?.trimMemory(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        pageAdapter?.trimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        readerPreviewAdapter?.trimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::readerControlsController.isInitialized) {
            readerControlsController.onWindowFocusChanged(hasFocus)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (handleVolumePageTurnKey(event)) {
            return true
        }

        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (handleVolumePageTurnKey(event)) {
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (handleVolumePageTurnKey(event)) {
            return true
        }

        return super.onKeyUp(keyCode, event)
    }

    private fun bindViews() {
        rootView = findViewById(R.id.main)
        listReaderPages = findViewById(R.id.listReaderPages)
        layoutReaderToolbar = findViewById(R.id.layoutReaderToolbar)
        layoutReaderProgress = findViewById(R.id.layoutReaderProgress)
        readerPreviewScrim = findViewById(R.id.readerPreviewScrim)
        layoutReaderPreviewPanel = findViewById(R.id.layoutReaderPreviewPanel)
        listReaderPreview = findViewById(R.id.listReaderPreview)
        sliderReaderProgress = findViewById(R.id.sliderReaderProgress)
        sliderScreenBrightness = findViewById(R.id.sliderScreenBrightness)
        checkboxCustomBrightness = findViewById(R.id.checkboxCustomBrightness)
        btnReaderPreview = findViewById(R.id.btnReaderPreview)
        tvReaderTitle = findViewById(R.id.tvReaderTitle)
        tvReaderProgress = findViewById(R.id.tvReaderProgress)
        tvReaderStatus = findViewById(R.id.tvReaderStatus)
        readerControlsController = ReaderControlsController(
            window = window,
            rootView = rootView,
            toolbar = layoutReaderToolbar,
            progressPanel = layoutReaderProgress,
            onControlsShown = { updateBrightnessControls() },
            onVisibilityChanged = { updateReaderGestureExclusionRects() }
        )
        readerControlsController.setAutoHideSystemBarsEnabled(autoHideSystemBarsEnabled)

        readerLayoutManager = LinearLayoutManager(
            this,
            readerLayoutOrientation(),
            readingDirection == AppSettings.READING_DIRECTION_RIGHT_TO_LEFT
        )
        listReaderPages.layoutManager = readerLayoutManager
        if (isHorizontalReading()) {
            if (isDoublePageReading()) {
                ReaderDoublePageSnapHelper().attachToRecyclerView(listReaderPages)
            } else {
                PagerSnapHelper().attachToRecyclerView(listReaderPages)
            }
        }
        listReaderPages.itemAnimator = null
        listReaderPages.setHasFixedSize(false)
        listReaderPages.setItemViewCacheSize(READER_VIEW_CACHE_SIZE)
        rootView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateReaderGestureExclusionRects()
        }

        listReaderPreview.layoutManager = LinearLayoutManager(this)
        listReaderPreview.itemAnimator = null
        listReaderPreview.setHasFixedSize(true)
    }

    private fun configureImmersiveSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowInsetsControllerCompat(window, rootView).systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        val toolbarInitialMarginTop =
            (layoutReaderToolbar.layoutParams as FrameLayout.LayoutParams).topMargin
        val progressInitialMarginBottom =
            (layoutReaderProgress.layoutParams as FrameLayout.LayoutParams).bottomMargin
        val previewPanelInitialTopMargin =
            (layoutReaderPreviewPanel.layoutParams as FrameLayout.LayoutParams).topMargin
        val previewPanelInitialBottomMargin =
            (layoutReaderPreviewPanel.layoutParams as FrameLayout.LayoutParams).bottomMargin
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (layoutReaderToolbar.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = toolbarInitialMarginTop + systemBars.top
                layoutReaderToolbar.layoutParams = this
            }
            (layoutReaderProgress.layoutParams as FrameLayout.LayoutParams).apply {
                bottomMargin = progressInitialMarginBottom + systemBars.bottom
                layoutReaderProgress.layoutParams = this
            }
            (layoutReaderPreviewPanel.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = previewPanelInitialTopMargin + systemBars.top
                bottomMargin = previewPanelInitialBottomMargin + systemBars.bottom
                layoutReaderPreviewPanel.layoutParams = this
            }
            insets
        }
        ViewCompat.requestApplyInsets(rootView)
    }

    private fun configureBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (readerPreviewPanelVisible) {
                    hideReaderPreviewPanel()
                    return
                }

                saveReaderPosition(forceCommit = true)
                finish()
            }
        })
    }

    private fun configureReaderActions() {
        listReaderPages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                readerScrollState = newState
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    clearPendingReaderPosition()
                }

                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    handler.removeCallbacks(readerCheckpointRunnable)
                    saveReaderPosition(forceCommit = true)
                    pageAdapter?.flushPendingRefreshesIfIdle()
                    val firstVisibleItem = readerLayoutManager.findFirstVisibleItemPosition()
                        .takeIf { it != RecyclerView.NO_POSITION }
                        ?: 0
                    preloadReaderAround(
                        firstVisiblePosition = readerSpreadStartLayoutPosition(firstVisibleItem),
                        visibleItemCount = visibleReaderItemCount(),
                        scrollDirection = readerScrollDirection,
                        isFastScroll = false,
                        isIdle = true,
                        force = true
                    )
                }
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val firstVisibleItem = readerLayoutManager.findFirstVisibleItemPosition()
                    .takeIf { it != RecyclerView.NO_POSITION }
                    ?: 0
                val visibleItemCount = visibleReaderItemCount()
                val totalItemCount = pageAdapter?.readerItemCount() ?: 0
                updateCurrentReaderPosition(firstVisibleItem)
                updateReaderProgress(firstVisibleItem, totalItemCount)
                scheduleReaderPositionCheckpoint()

                val primaryDelta = if (isHorizontalReading()) dx else dy
                val direction = when {
                    primaryDelta > 0 -> SCROLL_DIRECTION_FORWARD
                    primaryDelta < 0 -> SCROLL_DIRECTION_BACKWARD
                    else -> readerScrollDirection
                }
                if (primaryDelta != 0) {
                    readerScrollDirection = direction
                }
                val fastThresholdBase = if (isHorizontalReading()) recyclerView.width else recyclerView.height
                val fastThreshold = (fastThresholdBase / 4).coerceAtLeast(FAST_SCROLL_DY_THRESHOLD_PX)
                val isFastScroll = readerScrollState == RecyclerView.SCROLL_STATE_SETTLING ||
                    abs(primaryDelta) >= fastThreshold
                preloadReaderAround(
                    firstVisiblePosition = readerSpreadStartLayoutPosition(firstVisibleItem),
                    visibleItemCount = visibleItemCount,
                    scrollDirection = direction,
                    isFastScroll = isFastScroll,
                    isIdle = false
                )
            }
        })
        sliderReaderProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    updateReaderProgressText(progress, imageEntries.size)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                isDraggingReaderSlider = true
                clearPendingReaderPosition()
                readerControlsController.cancelAutoHide()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                isDraggingReaderSlider = false
                jumpReaderToPage(seekBar.progress)
                showReaderControlsTemporarily()
            }
        })

        btnReaderPreview.setOnClickListener {
            if (readerPreviewPanelVisible) {
                hideReaderPreviewPanel()
            } else {
                showReaderPreviewPanel()
            }
        }

        readerPreviewScrim.setOnClickListener {
            hideReaderPreviewPanel()
        }
    }

    private fun configureBrightnessControls() {
        sliderScreenBrightness.min = MIN_READER_BRIGHTNESS
        sliderScreenBrightness.max = MAX_READER_BRIGHTNESS
        sliderScreenBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser || isUpdatingBrightnessControls || !customReaderBrightnessEnabled) {
                    return
                }

                val brightness = normalizedReaderBrightness(progress)
                AppSettings.setCustomReaderBrightness(this@MangaReaderActivity, brightness)
                applyCustomReaderBrightness(brightness)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                if (customReaderBrightnessEnabled) {
                    readerControlsController.cancelAutoHide()
                }
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                if (customReaderBrightnessEnabled) {
                    val brightness = normalizedReaderBrightness(seekBar.progress)
                    AppSettings.setCustomReaderBrightness(this@MangaReaderActivity, brightness)
                    applyCustomReaderBrightness(brightness)
                    showReaderControlsTemporarily()
                }
            }
        })

        checkboxCustomBrightness.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingBrightnessControls) {
                return@setOnCheckedChangeListener
            }

            customReaderBrightnessEnabled = isChecked
            AppSettings.setCustomReaderBrightnessEnabled(this, isChecked)
            if (isChecked) {
                val brightness = normalizedReaderBrightness(sliderScreenBrightness.progress)
                AppSettings.setCustomReaderBrightness(this, brightness)
                applyCustomReaderBrightness(brightness)
            } else {
                restoreSystemBrightness()
            }
            updateBrightnessControls()
            showReaderControlsTemporarily()
        }

        updateBrightnessControls()
        applyReaderBrightnessSetting()
    }

    private fun loadArchive(file: File) {
        val generation = readerLoadGeneration.incrementAndGet()
        tvReaderStatus.text = getString(R.string.loading_reading)
        tvReaderStatus.visibility = View.VISIBLE

        executeReaderLoadTask {
            val entries = runCatching {
                ComicArchive.imageEntries(file)
            }.getOrElse {
                runOnUiThread {
                    if (isReaderActive() && generation == readerLoadGeneration.get()) {
                        showError(getString(R.string.unsupported_archive_format))
                    }
                }
                return@executeReaderLoadTask
            }

            runOnUiThread {
                if (!isReaderActive() || generation != readerLoadGeneration.get()) {
                    return@runOnUiThread
                }

                if (entries.isEmpty()) {
                    showError(getString(R.string.no_reading_images))
                    return@runOnUiThread
                }

                imageEntries = entries
                ReadingHistoryStore.record(this, file)
                tvReaderStatus.visibility = View.GONE
                bindArchiveReaderAdapter(file, entries)
            }
        }
    }

    private fun loadPreparedArchive(file: File, preparedCacheDir: File) {
        val entries = ComicArchive.preparedImageEntries(preparedCacheDir)
        if (entries.isEmpty()) {
            preparedCacheDir.deleteRecursively()
            showError(getString(R.string.no_reading_images))
            return
        }

        val generation = readerLoadGeneration.incrementAndGet()
        tvReaderStatus.text = getString(R.string.loading_reading)
        tvReaderStatus.visibility = View.VISIBLE
        executeReaderLoadTask {
            val pageFiles = entries.mapIndexed { index, entryName ->
                ComicArchive.preparedImageFile(preparedCacheDir, index, entryName)
            }
            val boundsByPosition = pageFiles.mapIndexedNotNull { index, imageFile ->
                if (imageFile.isFile) {
                    ComicArchive.imageFileBounds(imageFile)?.let { index to it }
                } else {
                    null
                }
            }.toMap()

            runOnUiThread {
                if (!isReaderActive() || generation != readerLoadGeneration.get()) {
                    preparedCacheDir.deleteRecursively()
                    return@runOnUiThread
                }

                imageEntries = entries
                ReadingHistoryStore.record(this, file)
                tvReaderStatus.visibility = View.GONE
                bindPreparedReaderAdapter(entries, pageFiles, boundsByPosition, preparedCacheDir, file)
            }
        }
    }

    private fun bindArchiveReaderAdapter(file: File, entries: List<String>) {
        val session = runCatching {
            ComicArchive.openReaderSession(file, cacheDir)
        }.getOrElse {
            showError(getString(R.string.unsupported_archive_format))
            return
        }
        bindReaderAdapter(entries, session)
    }

    private fun bindPreparedReaderAdapter(
        entries: List<String>,
        pageFiles: List<File>,
        boundsByPosition: Map<Int, ComicArchive.ImageBounds>,
        preparedCacheDir: File,
        archiveFile: File
    ) {
        val displayWidth = readerPageDisplayWidth()
        val displayHeight = listReaderPages.height
            .takeIf { it > 0 }
            ?: resources.displayMetrics.heightPixels.coerceAtLeast(MIN_READER_IMAGE_WIDTH)

        pageAdapter?.close()
        readerCoverSinglePageEnabled = AppSettings.isDoublePageCoverSingleEnabled(this)
        lastReaderPreloadSpreadStart = RecyclerView.NO_POSITION
        val adapter = SubsamplingMangaPageAdapter(
            context = this,
            recyclerView = listReaderPages,
            archiveFile = archiveFile,
            entryNames = entries,
            pageFiles = pageFiles,
            boundsByPosition = boundsByPosition,
            preparedCacheDir = preparedCacheDir,
            baseDisplayWidth = displayWidth,
            baseDisplayHeight = displayHeight,
            readingDirection = readingDirection,
            doublePageReading = isDoublePageReading(),
            coverSinglePage = readerCoverSinglePageEnabled,
            onPageReady = { position ->
                if (isPendingReaderPosition(position)) {
                    applyPendingReaderPosition()
                }
            },
            onPageTap = {
                handleReaderTap()
            }
        )
        pageAdapter = adapter
        listReaderPages.adapter = adapter
        bindReaderPreviewAdapter(entries, archiveFile)
        initializeReaderPosition(entries)
    }

    private fun bindReaderAdapter(entries: List<String>, session: ComicArchive.ImageReaderSession) {
        val readerFile = archiveFile ?: return
        val displayWidth = readerPageDisplayWidth()
        val displayHeight = listReaderPages.height
            .takeIf { it > 0 }
            ?: resources.displayMetrics.heightPixels.coerceAtLeast(MIN_READER_IMAGE_WIDTH)
        val decodeScale = readerPageDecodeScale()
        val decodeWidth = (displayWidth * decodeScale).coerceAtMost(MAX_READER_DECODE_WIDTH)
        val decodeHeight = (displayHeight * decodeScale).coerceAtMost(MAX_READER_DECODE_HEIGHT)
        val sharedSession = if (session.isPdfSource()) {
            ComicArchive.SharedImageReaderSession.wrap(session)
        } else {
            null
        }
        val pageSession = sharedSession?.acquire() ?: session
        val previewSession = sharedSession?.acquire()

        pageAdapter?.close()
        readerCoverSinglePageEnabled = !session.isPdfSource() &&
            AppSettings.isDoublePageCoverSingleEnabled(this)
        lastReaderPreloadSpreadStart = RecyclerView.NO_POSITION
        val adapter = MangaPageAdapter(
            context = this,
            recyclerView = listReaderPages,
            archiveFile = readerFile,
            session = pageSession,
            entries = entries,
            baseDisplayWidth = displayWidth,
            baseDisplayHeight = displayHeight,
            decodeWidth = decodeWidth,
            decodeHeight = decodeHeight,
            readingDirection = readingDirection,
            doublePageReading = isDoublePageReading(),
            coverSinglePage = readerCoverSinglePageEnabled,
            onPageReady = { position ->
                if (isPendingReaderPosition(position)) {
                    applyPendingReaderPosition()
                }
            },
            onPageTap = {
                handleReaderTap()
            }
        )
        pageAdapter = adapter
        listReaderPages.adapter = adapter
        bindReaderPreviewAdapter(entries, readerFile, previewSession)
        initializeReaderPosition(entries)
    }

    private fun bindReaderPreviewAdapter(
        entries: List<String>,
        file: File,
        sharedSession: ComicArchive.ImageReaderSession? = null
    ) {
        readerPreviewAdapter?.close()
        val adapter = ReaderPreviewAdapter(
            context = this,
            archiveFile = file,
            entries = entries,
            sharedSession = sharedSession,
            onPageClick = { position ->
                hideReaderPreviewPanel()
                jumpReaderToPage(position)
                showReaderControlsTemporarily()
            }
        )
        readerPreviewAdapter = adapter
        listReaderPreview.adapter = adapter
        adapter.setSelectedPosition(currentReaderPosition)
    }

    private fun initializeReaderPosition(entries: List<String>) {
        sliderReaderProgress.max = (entries.size - 1).coerceAtLeast(0)
        sliderReaderProgress.progress = 0
        val startPageIndex = explicitStartPageIndex.takeIf { it in entries.indices }
        when {
            startPageIndex != null -> {
                val layoutPosition = readerSpreadStartPosition(startPageIndex)
                readerLayoutManager.scrollToPositionWithOffset(layoutPosition, 0)
                currentReaderPosition = readerSpreadStartSourcePosition(startPageIndex)
                currentReaderOffset = 0
                updateReaderProgress(layoutPosition, entries.size)
            }
            shouldStartFromBeginning -> {
                readerLayoutManager.scrollToPositionWithOffset(0, 0)
                updateReaderProgress(0, entries.size)
            }
            else -> {
                restoreReaderPosition()
                updateReaderProgress(readerLayoutManager.findFirstVisibleItemPosition().coerceAtLeast(0), entries.size)
            }
        }
        listReaderPages.post {
            preloadReaderAround(
                firstVisiblePosition = readerLayoutManager.findFirstVisibleItemPosition()
                    .coerceAtLeast(0),
                visibleItemCount = visibleReaderItemCount().coerceAtLeast(1),
                force = true
            )
            startDebugReaderStressIfNeeded()
        }
    }

    private fun configureDebugReaderStress() {
        if (!isDebuggableBuild()) {
            return
        }

        debugReaderStressEnabled = intent.getBooleanExtra(EXTRA_DEBUG_READER_STRESS, false)
        if (!debugReaderStressEnabled) {
            return
        }

        debugReaderStressRemainingIterations = intent.getIntExtra(
            EXTRA_DEBUG_READER_STRESS_ITERATIONS,
            DEFAULT_DEBUG_READER_STRESS_ITERATIONS
        ).coerceIn(1, MAX_DEBUG_READER_STRESS_ITERATIONS)
        debugReaderStressDelayMs = intent.getLongExtra(
            EXTRA_DEBUG_READER_STRESS_DELAY_MS,
            DEFAULT_DEBUG_READER_STRESS_DELAY_MS
        ).coerceIn(MIN_DEBUG_READER_STRESS_DELAY_MS, MAX_DEBUG_READER_STRESS_DELAY_MS)
        debugReaderStressTrimEvery = intent.getIntExtra(
            EXTRA_DEBUG_READER_STRESS_TRIM_EVERY,
            DEFAULT_DEBUG_READER_STRESS_TRIM_EVERY
        ).coerceIn(0, MAX_DEBUG_READER_STRESS_TRIM_EVERY)
    }

    private fun startDebugReaderStressIfNeeded() {
        if (!isDebuggableBuild() ||
            !debugReaderStressEnabled ||
            debugReaderStressStarted ||
            imageEntries.isEmpty() ||
            pageAdapter == null
        ) {
            return
        }

        debugReaderStressStarted = true
        setReaderControlsVisible(false)
        Log.i(
            DEBUG_READER_STRESS_TAG,
            "started pages=${imageEntries.size} iterations=$debugReaderStressRemainingIterations " +
                "delayMs=$debugReaderStressDelayMs trimEvery=$debugReaderStressTrimEvery"
        )
        handler.postDelayed(debugReaderStressRunnable, DEBUG_READER_STRESS_START_DELAY_MS)
    }

    private fun runDebugReaderStressStep() {
        if (!isDebuggableBuild() || destroyed || !debugReaderStressEnabled || imageEntries.isEmpty()) {
            return
        }

        val adapter = pageAdapter ?: return
        if (debugReaderStressRemainingIterations <= 0) {
            applyDebugPageZoom(ReaderPageZoomLayout.MIN_SCALE, 0f, 0f)
            adapter.flushPendingRefreshesIfIdle()
            saveReaderPosition(forceCommit = true)
            Log.i(
                DEBUG_READER_STRESS_TAG,
                "finished steps=$debugReaderStressStep current=$currentReaderPosition " +
                    "heapKb=${usedHeapKb()}"
            )
            return
        }

        val totalItemCount = imageEntries.size
        val lastIndex = imageEntries.lastIndex
        val step = debugReaderStressStep++
        debugReaderStressRemainingIterations--
        val targetPosition = debugReaderStressTargetPosition(step, totalItemCount)
        val visibleItemCount = visibleReaderItemCount().coerceAtLeast(1)
        val direction = if (step % 2 == 0) SCROLL_DIRECTION_FORWARD else SCROLL_DIRECTION_BACKWARD

        when (step % DEBUG_READER_STRESS_PATTERN_SIZE) {
            0 -> {
                jumpReaderToPage(targetPosition)
            }

            1 -> {
                val layoutPosition = readerSpreadStartPosition(targetPosition)
                readerLayoutManager.scrollToPositionWithOffset(layoutPosition, 0)
                updateCurrentReaderPosition(layoutPosition)
                updateReaderProgress(layoutPosition, totalItemCount)
                adapter.preloadAround(
                    firstVisiblePosition = layoutPosition,
                    visibleItemCount = visibleItemCount,
                    scrollDirection = direction,
                    isFastScroll = true,
                    isIdle = false
                )
            }

            2 -> {
                adapter.preloadAround(
                    firstVisiblePosition = readerSpreadStartPosition(targetPosition),
                    visibleItemCount = visibleItemCount,
                    scrollDirection = direction,
                    isFastScroll = true,
                    isIdle = false
                )
                adapter.preloadAround(
                    firstVisiblePosition = readerSpreadStartPosition(
                        (lastIndex - targetPosition).coerceIn(0, lastIndex)
                    ),
                    visibleItemCount = visibleItemCount,
                    scrollDirection = -direction,
                    isFastScroll = true,
                    isIdle = false
                )
            }

            3 -> {
                applyDebugPageZoom(ReaderPageZoomLayout.MAX_SCALE, listReaderPages.width * 0.35f, 0f)
                adapter.preloadAround(
                    firstVisiblePosition = readerSpreadStartPosition(targetPosition),
                    visibleItemCount = visibleItemCount,
                    scrollDirection = direction,
                    isFastScroll = false,
                    isIdle = true
                )
            }

            4 -> {
                applyDebugPageZoom(ReaderPageZoomLayout.MAX_SCALE, -listReaderPages.width * 0.35f, 0f)
                jumpReaderToPage(targetPosition)
            }

            5 -> {
                applyDebugPageZoom(ReaderPageZoomLayout.MIN_SCALE, 0f, 0f)
                turnReaderPage(1)
                turnReaderPage(1)
            }

            6 -> {
                turnReaderPage(-1)
                adapter.preloadAround(
                    firstVisiblePosition = readerSpreadStartPosition(targetPosition),
                    visibleItemCount = visibleItemCount,
                    scrollDirection = SCROLL_DIRECTION_BACKWARD,
                    isFastScroll = false,
                    isIdle = true
                )
            }

            else -> {
                adapter.trimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
                adapter.preloadAround(
                    firstVisiblePosition = readerSpreadStartPosition(targetPosition),
                    visibleItemCount = visibleItemCount,
                    scrollDirection = direction,
                    isFastScroll = false,
                    isIdle = true
                )
            }
        }

        if (debugReaderStressTrimEvery > 0 &&
            debugReaderStressStep % debugReaderStressTrimEvery == 0
        ) {
            adapter.trimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        }

        if (debugReaderStressStep % DEBUG_READER_STRESS_LOG_EVERY == 0) {
            Log.i(
                DEBUG_READER_STRESS_TAG,
                "step=$debugReaderStressStep remaining=$debugReaderStressRemainingIterations " +
                    "current=$currentReaderPosition target=$targetPosition heapKb=${usedHeapKb()}"
            )
        }

        handler.postDelayed(debugReaderStressRunnable, debugReaderStressDelayMs)
    }

    private fun debugReaderStressTargetPosition(step: Int, totalItemCount: Int): Int {
        if (totalItemCount <= 1) {
            return 0
        }

        val lastIndex = totalItemCount - 1
        return when (step % DEBUG_READER_STRESS_TARGET_PATTERN_SIZE) {
            0 -> 0
            1 -> (totalItemCount / 4).coerceIn(0, lastIndex)
            2 -> (totalItemCount / 2).coerceIn(0, lastIndex)
            3 -> (totalItemCount * 3 / 4).coerceIn(0, lastIndex)
            4 -> lastIndex
            5 -> (lastIndex - 1).coerceAtLeast(0)
            6 -> (currentReaderPosition + 5).coerceIn(0, lastIndex)
            7 -> (currentReaderPosition - 4).coerceIn(0, lastIndex)
            8 -> (step * 7 % totalItemCount).coerceIn(0, lastIndex)
            else -> (lastIndex - (step * 11 % totalItemCount)).coerceIn(0, lastIndex)
        }
    }

    private fun usedHeapKb(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / 1024L
    }

    private fun isDebuggableBuild(): Boolean {
        return applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }

    private fun handleReaderTap() {
        if (readerPreviewPanelVisible) {
            hideReaderPreviewPanel()
            return
        }

        if (suppressReaderTap) {
            return
        }

        if (readerControlsVisible) {
            setReaderControlsVisible(false)
        } else {
            showReaderControlsTemporarily()
        }
    }

    private fun showReaderPreviewPanel() {
        if (readerPreviewPanelVisible || imageEntries.isEmpty()) {
            return
        }

        readerPreviewPanelVisible = true
        readerControlsController.cancelAutoHide()
        setReaderControlsVisible(true)
        scrollReaderPreviewToCurrentPage()

        readerPreviewScrim.animate().cancel()
        readerPreviewScrim.alpha = 0f
        readerPreviewScrim.visibility = View.VISIBLE
        readerPreviewScrim.animate()
            .alpha(1f)
            .setDuration(READER_PREVIEW_PANEL_ANIMATION_MS)
            .start()

        val panelWidth = readerPreviewPanelWidth()
        layoutReaderPreviewPanel.animate().cancel()
        layoutReaderPreviewPanel.translationX = panelWidth.toFloat()
        layoutReaderPreviewPanel.visibility = View.VISIBLE
        layoutReaderPreviewPanel.animate()
            .translationX(0f)
            .setDuration(READER_PREVIEW_PANEL_ANIMATION_MS)
            .start()
    }

    private fun hideReaderPreviewPanel() {
        if (!readerPreviewPanelVisible) {
            return
        }

        readerPreviewPanelVisible = false
        readerPreviewScrim.animate().cancel()
        readerPreviewScrim.animate()
            .alpha(0f)
            .setDuration(READER_PREVIEW_PANEL_ANIMATION_MS)
            .withEndAction {
                if (!readerPreviewPanelVisible) {
                    readerPreviewScrim.visibility = View.GONE
                    readerPreviewScrim.alpha = 1f
                }
            }
            .start()

        val panelWidth = readerPreviewPanelWidth()
        layoutReaderPreviewPanel.animate().cancel()
        layoutReaderPreviewPanel.animate()
            .translationX(panelWidth.toFloat())
            .setDuration(READER_PREVIEW_PANEL_ANIMATION_MS)
            .withEndAction {
                if (!readerPreviewPanelVisible) {
                    layoutReaderPreviewPanel.visibility = View.GONE
                    layoutReaderPreviewPanel.translationX = 0f
                    showReaderControlsTemporarily()
                }
            }
            .start()
    }

    private fun scrollReaderPreviewToCurrentPage() {
        val position = currentReaderPosition.coerceIn(0, imageEntries.lastIndex.coerceAtLeast(0))
        readerPreviewAdapter?.setSelectedPosition(position)
        listReaderPreview.post {
            if (imageEntries.isEmpty()) {
                return@post
            }

            val itemHeight = resources.getDimensionPixelSize(R.dimen.reader_preview_item_height)
            val offset = ((listReaderPreview.height - itemHeight) / 2).coerceAtLeast(0)
            (listReaderPreview.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(position, offset)
                ?: listReaderPreview.scrollToPosition(position)
        }
    }

    private fun readerPreviewPanelWidth(): Int {
        return layoutReaderPreviewPanel.width
            .takeIf { it > 0 }
            ?: resources.getDimensionPixelSize(R.dimen.reader_preview_panel_width)
    }

    private fun applyDebugPageZoom(scale: Float, panX: Float, panY: Float) {
        suppressReaderTap = true
        handler.removeCallbacks(clearSuppressReaderTapRunnable)
        handler.postDelayed(clearSuppressReaderTapRunnable, SUPPRESS_TAP_AFTER_ZOOM_MS)

        pageAdapter?.applyDebugZoomToVisiblePages(listReaderPages, scale, panX, panY)
    }

    private fun updateReaderProgress(firstVisibleItem: Int, totalItemCount: Int) {
        if (totalItemCount <= 0) {
            tvReaderProgress.text = ""
            sliderReaderProgress.max = 0
            sliderReaderProgress.progress = 0
            return
        }

        val currentPosition = displayedReaderPosition(firstVisibleItem, totalItemCount)
        readerPreviewAdapter?.setSelectedPosition(currentPosition)
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

    private fun preloadReaderAround(
        firstVisiblePosition: Int,
        visibleItemCount: Int,
        scrollDirection: Int = readerScrollDirection,
        isFastScroll: Boolean = false,
        isIdle: Boolean = false,
        isDiscreteJump: Boolean = false,
        force: Boolean = false
    ) {
        val adapter = pageAdapter ?: return
        val layoutSpreadStart = readerSpreadStartLayoutPosition(firstVisiblePosition)
        if (!force && layoutSpreadStart == lastReaderPreloadSpreadStart) {
            return
        }

        lastReaderPreloadSpreadStart = layoutSpreadStart
        adapter.preloadAround(
            firstVisiblePosition = layoutSpreadStart,
            visibleItemCount = visibleItemCount,
            scrollDirection = scrollDirection,
            isFastScroll = isFastScroll,
            isIdle = isIdle,
            isDiscreteJump = isDiscreteJump
        )
    }

    private fun jumpReaderToPage(position: Int) {
        if (imageEntries.isEmpty()) {
            return
        }

        clearPendingReaderPosition()
        val targetPosition = position.coerceIn(0, imageEntries.lastIndex)
        val layoutPosition = readerSpreadStartPosition(targetPosition)
        readerLayoutManager.scrollToPositionWithOffset(layoutPosition, 0)
        currentReaderPosition = readerSpreadStartSourcePosition(targetPosition)
        currentReaderOffset = 0
        updateReaderProgress(layoutPosition, imageEntries.size)
        saveReaderPosition(forceCommit = true)
        preloadReaderAround(
            firstVisiblePosition = layoutPosition,
            visibleItemCount = if (isDoublePageReading()) 2 else 1,
            isDiscreteJump = true,
            force = true
        )
    }

    private fun turnReaderPage(delta: Int) {
        if (imageEntries.isEmpty()) {
            return
        }

        clearPendingReaderPosition()
        val firstVisiblePosition = readerLayoutManager.findFirstVisibleItemPosition()
            .takeIf { it != RecyclerView.NO_POSITION }
            ?: readerSpreadStartPosition(currentReaderPosition)
        val currentPosition = displayedReaderPosition(firstVisiblePosition, imageEntries.size)
        val mapper = readerPagePositionMapper(imageEntries.size)
        val currentLayoutSpreadStart = mapper.spreadStartAdapterPositionForSourcePosition(currentPosition)
        val layoutStep = if (isDoublePageReading()) 2 else 1
        val lastLayoutSpreadStart = mapper.spreadStartAdapterPosition(mapper.adapterItemCount - 1)
        val targetLayoutPosition = (currentLayoutSpreadStart + (delta * layoutStep))
            .coerceIn(0, lastLayoutSpreadStart)
        val targetPosition = mapper.sourcePositionForAdapterPosition(targetLayoutPosition)
            ?: return
        if (targetLayoutPosition == currentLayoutSpreadStart) {
            return
        }

        jumpReaderToPage(targetPosition)
    }

    private fun scheduleReaderPositionCheckpoint() {
        if (destroyed) {
            return
        }
        handler.removeCallbacks(readerCheckpointRunnable)
        handler.postDelayed(readerCheckpointRunnable, READER_PROGRESS_CHECKPOINT_DELAY_MS)
    }

    private fun saveReaderPosition(forceCommit: Boolean = false) {
        val file = archiveFile ?: return
        if (imageEntries.isEmpty()) {
            return
        }

        val firstVisible = readerLayoutManager.findFirstVisibleItemPosition()
            .takeIf { it != RecyclerView.NO_POSITION }
            ?: currentReaderPosition
        val pendingPosition = pendingRestorePosition
        val positionToSave: Int
        val offsetToSave: Int
        if (pendingPosition != null) {
            positionToSave = pendingPosition.coerceIn(0, imageEntries.lastIndex)
            offsetToSave = if (isHorizontalReading()) 0 else pendingRestoreOffset
        } else {
            updateCurrentReaderPosition(firstVisible)
            positionToSave = currentReaderPosition
            offsetToSave = currentReaderOffset
        }

        val editor = readerPrefs.edit()
            .putInt(readerPositionKey(file), positionToSave)
            .putInt(readerOffsetKey(file), offsetToSave)
        if (readerProgressExecutor.isShutdown) {
            return
        }
        readerProgressExecutor.execute {
            if (forceCommit) {
                editor.commit()
            } else {
                editor.apply()
            }
            CrashLogManager.recordReaderCheckpoint(
                context = this,
                file = file,
                position = positionToSave,
                offset = offsetToSave,
                durable = forceCommit
            )
        }
    }

    private fun updateCurrentReaderPosition(firstVisiblePosition: Int) {
        currentReaderPosition = displayedReaderPosition(firstVisiblePosition, imageEntries.size)
        currentReaderOffset = if (isHorizontalReading()) {
            0
        } else {
            readerChildStartForPosition(currentReaderPosition) ?: currentReaderOffset
        }
    }

    private fun restoreReaderPosition() {
        val file = archiveFile ?: return
        val position = readerPrefs.getInt(readerPositionKey(file), 0)
            .coerceIn(0, imageEntries.lastIndex.coerceAtLeast(0))
        val offset = if (isHorizontalReading()) {
            0
        } else {
            readerPrefs.getInt(readerOffsetKey(file), 0)
        }

        currentReaderPosition = readerSpreadStartSourcePosition(position)
        currentReaderOffset = offset
        pendingRestorePosition = position
        pendingRestoreOffset = offset
        restoreAttemptCount = 0
        applyPendingReaderPosition()
    }

    private fun applyPendingReaderPosition() {
        val position = pendingRestorePosition ?: return
        if (imageEntries.isEmpty()) {
            clearPendingReaderPosition()
            return
        }

        listReaderPages.post {
            if (pendingRestorePosition != position) {
                return@post
            }

            val layoutPosition = readerSpreadStartPosition(position)
            readerLayoutManager.scrollToPositionWithOffset(layoutPosition, pendingRestoreOffset)
            updateReaderProgress(layoutPosition, imageEntries.size)

            if (restoreAttemptCount < RESTORE_READER_POSITION_MAX_ATTEMPTS) {
                restoreAttemptCount++
                schedulePendingReaderPositionRetry()
            } else {
                clearPendingReaderPosition()
            }
        }
    }

    private fun schedulePendingReaderPositionRetry() {
        if (restoreRetryScheduled) {
            return
        }

        restoreRetryScheduled = true
        handler.postDelayed(
            {
                restoreRetryScheduled = false
                applyPendingReaderPosition()
            },
            RESTORE_READER_POSITION_RETRY_MS
        )
    }

    private fun clearPendingReaderPosition() {
        pendingRestorePosition = null
        pendingRestoreOffset = 0
        restoreAttemptCount = 0
        restoreRetryScheduled = false
    }

    private fun visibleReaderItemCount(): Int {
        val first = readerLayoutManager.findFirstVisibleItemPosition()
        val last = readerLayoutManager.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) {
            return 1
        }
        return (last - first + 1).coerceAtLeast(1)
    }

    private fun readerChildStartForPosition(position: Int): Int? {
        val child = readerLayoutManager.findViewByPosition(position) ?: return null
        return if (isHorizontalReading()) {
            child.left
        } else {
            child.top
        }
    }

    private fun displayedReaderPosition(firstVisiblePosition: Int, totalItemCount: Int): Int {
        if (totalItemCount <= 0) {
            return 0
        }

        if (isHorizontalReading()) {
            val mapper = readerPagePositionMapper(totalItemCount)
            val spreadStart = mapper.spreadStartAdapterPosition(firstVisiblePosition)
            return mapper.sourcePositionForAdapterPosition(spreadStart)
                ?: firstVisiblePosition.coerceIn(0, totalItemCount - 1)
        }

        if (isAtVerticalReaderEnd(totalItemCount)) {
            return totalItemCount - 1
        }

        val centeredPosition = centerVisibleReaderPosition()
        return (if (centeredPosition != RecyclerView.NO_POSITION) {
            centeredPosition
        } else {
            firstVisiblePosition
        }).coerceIn(0, totalItemCount - 1)
    }

    private fun isAtVerticalReaderEnd(totalItemCount: Int): Boolean {
        if (isHorizontalReading() || totalItemCount <= 0 || listReaderPages.childCount <= 0) {
            return false
        }

        val lastVisiblePosition = readerLayoutManager.findLastVisibleItemPosition()
        return lastVisiblePosition == totalItemCount - 1 && !listReaderPages.canScrollVertically(1)
    }

    private fun centerVisibleReaderPosition(): Int {
        if (listReaderPages.childCount <= 0) {
            return RecyclerView.NO_POSITION
        }

        val viewportCenterY = listReaderPages.height / 2
        var bestPosition = RecyclerView.NO_POSITION
        var bestDistance = Int.MAX_VALUE
        for (index in 0 until listReaderPages.childCount) {
            val child = listReaderPages.getChildAt(index)
            val position = listReaderPages.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION ||
                child.bottom <= 0 ||
                child.top >= listReaderPages.height
            ) {
                continue
            }

            val childCenterY = (child.top + child.bottom) / 2
            val distance = abs(childCenterY - viewportCenterY)
            if (distance < bestDistance) {
                bestDistance = distance
                bestPosition = position
            }
        }
        return bestPosition
    }

    private fun readerLayoutOrientation(): Int {
        return if (isHorizontalReading()) {
            LinearLayoutManager.HORIZONTAL
        } else {
            LinearLayoutManager.VERTICAL
        }
    }

    private fun isHorizontalReading(): Boolean {
        return readingDirection == AppSettings.READING_DIRECTION_RIGHT_TO_LEFT ||
            readingDirection == AppSettings.READING_DIRECTION_LEFT_TO_RIGHT
    }

    private fun isDoublePageReading(): Boolean {
        return isHorizontalReading() &&
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    private fun readerPagePositionMapper(sourceItemCount: Int = imageEntries.size): ReaderPagePositionMapper {
        return ReaderPagePositionMapper(
            sourceItemCount = sourceItemCount,
            doublePageReading = isDoublePageReading(),
            coverSinglePage = readerCoverSinglePageEnabled
        )
    }

    private fun readerPageDisplayWidth(): Int {
        val viewportWidth = listReaderPages.width
            .takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels.coerceAtLeast(MIN_READER_IMAGE_WIDTH)
        return if (isDoublePageReading()) {
            (viewportWidth / 2).coerceAtLeast(1)
        } else {
            viewportWidth
        }
    }

    private fun readerSpreadStartPosition(pagePosition: Int): Int {
        return readerPagePositionMapper().spreadStartAdapterPositionForSourcePosition(pagePosition)
    }

    private fun readerSpreadStartLayoutPosition(layoutPosition: Int): Int {
        return readerPagePositionMapper().spreadStartAdapterPosition(layoutPosition)
    }

    private fun readerSpreadStartSourcePosition(pagePosition: Int): Int {
        return readerPagePositionMapper()
            .spreadStartSourcePositionForSourcePosition(pagePosition)
    }

    private fun isPendingReaderPosition(position: Int): Boolean {
        val pendingPosition = pendingRestorePosition ?: return false
        return readerSpreadStartSourcePosition(position) == readerSpreadStartSourcePosition(pendingPosition)
    }

    private fun readerPageDecodeScale(): Int {
        return HIGH_QUALITY_READER_DECODE_SCALE
    }

    private fun handleVolumePageTurnKey(event: KeyEvent): Boolean {
        if (!volumeKeyPageTurnEnabled || !isVolumePageTurnKey(event.keyCode)) {
            return false
        }

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastVolumePageTurnAt >= VOLUME_KEY_PAGE_TURN_MIN_INTERVAL_MS) {
                lastVolumePageTurnAt = now
                val delta = if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                    1
                } else {
                    -1
                }
                turnReaderPage(delta)
            }
        }
        return true
    }

    private fun isVolumePageTurnKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP
    }

    private fun showReaderControlsTemporarily() {
        readerControlsController.showTemporarily()
    }

    private fun setReaderControlsVisible(visible: Boolean) {
        if (!visible && readerPreviewPanelVisible) {
            return
        }

        readerControlsController.setVisible(visible)
    }

    private fun updateReaderGestureExclusionRects() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !::rootView.isInitialized) {
            return
        }

        val width = rootView.width
        val height = rootView.height
        val exclusionRects = if (!readerControlsVisible &&
            !readerPreviewPanelVisible &&
            width > 0 &&
            height > 0
        ) {
            val edgeWidth = (resources.displayMetrics.density *
                READER_GESTURE_EXCLUSION_EDGE_WIDTH_DP)
                .roundToInt()
                .coerceAtLeast(1)
                .coerceAtMost((width / 4).coerceAtLeast(1))
            listOf(
                Rect(0, 0, edgeWidth, height),
                Rect(width - edgeWidth, 0, width, height)
            )
        } else {
            emptyList()
        }
        rootView.systemGestureExclusionRects = exclusionRects
    }

    private fun showError(message: String) {
        tvReaderStatus.text = message
        tvReaderStatus.visibility = View.VISIBLE
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun executeReaderLoadTask(block: () -> Unit) {
        if (destroyed) {
            return
        }

        runCatching {
            readerLoadExecutor.execute {
                if (!destroyed) {
                    try {
                        block()
                    } catch (throwable: Throwable) {
                        Log.e(READER_LOG_TAG, "reader load task failed", throwable)
                    }
                }
            }
        }
    }

    private fun isReaderActive(): Boolean {
        return !destroyed && !isFinishing && !isDestroyed
    }

    private fun updateBrightnessControls() {
        if (!::sliderScreenBrightness.isInitialized || !::checkboxCustomBrightness.isInitialized) {
            return
        }

        isUpdatingBrightnessControls = true
        customReaderBrightnessEnabled = AppSettings.isCustomReaderBrightnessEnabled(this)
        checkboxCustomBrightness.isChecked = customReaderBrightnessEnabled
        sliderScreenBrightness.visibility = View.VISIBLE
        sliderScreenBrightness.alpha = if (customReaderBrightnessEnabled) {
            ENABLED_BRIGHTNESS_SLIDER_ALPHA
        } else {
            DISABLED_BRIGHTNESS_SLIDER_ALPHA
        }
        sliderScreenBrightness.isEnabled = customReaderBrightnessEnabled
        sliderScreenBrightness.isClickable = customReaderBrightnessEnabled
        sliderScreenBrightness.isFocusable = customReaderBrightnessEnabled
        sliderScreenBrightness.progress = if (customReaderBrightnessEnabled) {
            AppSettings.getCustomReaderBrightness(this)
        } else {
            readSystemBrightness()
        }
        isUpdatingBrightnessControls = false
    }

    private fun applyReaderBrightnessSetting() {
        customReaderBrightnessEnabled = AppSettings.isCustomReaderBrightnessEnabled(this)
        if (customReaderBrightnessEnabled) {
            applyCustomReaderBrightness(AppSettings.getCustomReaderBrightness(this))
        } else {
            restoreSystemBrightness()
        }
    }

    private fun applyCustomReaderBrightness(brightness: Int) {
        val normalizedBrightness = normalizedReaderBrightness(brightness)
        val params = window.attributes
        params.screenBrightness = (normalizedBrightness.toFloat() / MAX_READER_BRIGHTNESS.toFloat())
            .coerceIn(MIN_WINDOW_BRIGHTNESS, MAX_WINDOW_BRIGHTNESS)
        window.attributes = params
    }

    private fun restoreSystemBrightness() {
        val params = window.attributes
        params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = params
    }

    private fun readSystemBrightness(): Int {
        return runCatching {
            Settings.System.getInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                DEFAULT_READER_BRIGHTNESS
            )
        }.getOrDefault(DEFAULT_READER_BRIGHTNESS)
            .coerceIn(MIN_READER_BRIGHTNESS, MAX_READER_BRIGHTNESS)
    }

    private fun normalizedReaderBrightness(brightness: Int): Int {
        return brightness.coerceIn(MIN_READER_BRIGHTNESS, MAX_READER_BRIGHTNESS)
    }

    private fun readerPositionKey(file: File): String {
        return readerPositionKeyFor(file)
    }

    private fun readerOffsetKey(file: File): String {
        return readerOffsetKeyFor(file)
    }

    private interface ReaderPageAdapterController {
        fun readerItemCount(): Int

        fun preloadAround(
            firstVisiblePosition: Int,
            visibleItemCount: Int,
            scrollDirection: Int = 1,
            isFastScroll: Boolean = false,
            isIdle: Boolean = false,
            isDiscreteJump: Boolean = false
        )

        fun applyDebugZoomToVisiblePages(
            recyclerView: RecyclerView,
            scale: Float,
            panX: Float,
            panY: Float
        )

        fun trimMemory(level: Int)

        fun flushPendingRefreshesIfIdle()

        fun close()
    }

    private class SubsamplingMangaPageAdapter(
        private val context: Context,
        private val recyclerView: RecyclerView,
        private val archiveFile: File,
        private val entryNames: List<String>,
        private val pageFiles: List<File>,
        boundsByPosition: Map<Int, ComicArchive.ImageBounds>,
        private val preparedCacheDir: File,
        private val baseDisplayWidth: Int,
        private val baseDisplayHeight: Int,
        private val readingDirection: String,
        private val doublePageReading: Boolean,
        private val coverSinglePage: Boolean,
        private val onPageReady: (position: Int) -> Unit,
        private val onPageTap: () -> Unit
    ) : RecyclerView.Adapter<SubsamplingMangaPageAdapter.PageViewHolder>(),
        ReaderPageAdapterController {

        private val mainHandler = Handler(Looper.getMainLooper())
        private val extractExecutor = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            PriorityBlockingQueue<Runnable>()
        )
        private val taskSequence = AtomicLong(0L)
        private val readyPositions = Collections.synchronizedSet(mutableSetOf<Int>())
        private val loadingPositions = Collections.synchronizedSet(mutableSetOf<Int>())
        private val failedPositions = Collections.synchronizedSet(mutableSetOf<Int>())
        private val pageBounds = Collections.synchronizedMap(boundsByPosition.toMutableMap())
        private val positionMapper = ReaderPagePositionMapper(
            sourceItemCount = entryNames.size,
            doublePageReading = doublePageReading,
            coverSinglePage = coverSinglePage
        )

        @Volatile
        private var closed = false

        @Volatile
        private var extractor: ComicArchive.PreparedImageExtractor? = null

        init {
            setHasStableIds(true)
            pageFiles.forEachIndexed { index, file ->
                if (file.isFile && file.length() > 0L) {
                    readyPositions.add(index)
                }
            }
        }

        private val adapterItemCount = positionMapper.adapterItemCount

        override fun getItemCount(): Int = adapterItemCount

        override fun readerItemCount(): Int = entryNames.size

        override fun getItemId(position: Int): Long {
            return position.toLong()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
            val container = ReaderTapFrameLayout(context).apply {
                setBackgroundColor(Color.BLACK)
                isClickable = true
                layoutParams = initialPageLayoutParams()
                onReaderTap = onPageTap
            }
            val imageView = ReaderSubsamplingImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            val statusText = TextView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
                setTextColor(Color.WHITE)
                textSize = 14f
                visibility = View.GONE
            }
            container.addView(imageView)
            container.addView(statusText)
            return PageViewHolder(container, imageView, statusText)
        }

        override fun onBindViewHolder(holder: PageViewHolder, adapterPosition: Int) {
            val sourcePosition = positionMapper.sourcePositionForAdapterPosition(adapterPosition)
            if (closed || sourcePosition == null || sourcePosition !in entryNames.indices ||
                sourcePosition !in pageFiles.indices
            ) {
                bindEmptyPage(holder)
                return
            }

            val file = pageFiles[sourcePosition]
            val bounds = pageBounds[sourcePosition]
            holder.container.onReaderTap = onPageTap
            applyPageLayout(holder.container, bounds)
            applyImageLayout(holder.imageView, bounds, adapterPosition)

            if (!file.isFile || file.length() <= 0L) {
                bindEmptyPage(holder)
                submitExtraction(sourcePosition, PRIORITY_VISIBLE)
                onPageReady(sourcePosition)
                return
            }

            readyPositions.add(sourcePosition)
            holder.statusText.visibility = View.GONE
            holder.imageView.visibility = View.VISIBLE
            if (holder.boundFilePath != file.absolutePath) {
                holder.imageView.recycle()
                holder.boundFilePath = file.absolutePath
                runCatching {
                    holder.imageView.setImage(ImageSource.uri(Uri.fromFile(file)))
                }.onFailure {
                    holder.boundFilePath = null
                }
            }

            onPageReady(sourcePosition)
        }

        private fun bindEmptyPage(holder: PageViewHolder) {
            holder.statusText.visibility = View.GONE
            holder.imageView.recycle()
            holder.imageView.visibility = View.INVISIBLE
            holder.boundFilePath = null
        }

        override fun onViewRecycled(holder: PageViewHolder) {
            holder.boundFilePath = null
            holder.imageView.recycle()
            super.onViewRecycled(holder)
        }

        override fun preloadAround(
            firstVisiblePosition: Int,
            visibleItemCount: Int,
            scrollDirection: Int,
            isFastScroll: Boolean,
            isIdle: Boolean,
            isDiscreteJump: Boolean
        ) {
            if (closed || entryNames.isEmpty()) {
                return
            }

            val firstVisibleAdapterPosition = positionMapper
                .spreadStartAdapterPosition(firstVisiblePosition)
            val visiblePositions = positionMapper.sourcePositionsInAdapterRange(
                firstAdapterPosition = firstVisibleAdapterPosition,
                visibleItemCount = visibleItemCount.coerceAtLeast(1)
            )
            if (visiblePositions.isEmpty()) {
                return
            }
            val firstVisible = visiblePositions.first()
            val lastVisible = visiblePositions.last()
            val movingForward = scrollDirection >= 0

            (firstVisible..lastVisible).forEach { position ->
                submitExtraction(position, PRIORITY_VISIBLE)
            }

            orderedNearPositions(
                firstVisible = firstVisible,
                lastVisible = lastVisible,
                movingForward = movingForward,
                isFastScroll = isFastScroll,
                isIdle = isIdle
            ).forEach { position ->
                submitExtraction(position, PRIORITY_NEAR)
            }

            orderedBackgroundPositions(
                firstVisible = firstVisible,
                lastVisible = lastVisible,
                movingForward = movingForward
            ).forEach { position ->
                submitExtraction(position, PRIORITY_BACKGROUND)
            }
        }

        override fun applyDebugZoomToVisiblePages(
            recyclerView: RecyclerView,
            scale: Float,
            panX: Float,
            panY: Float
        ) {
            // The subsampling view owns scale state internally; stress tests still cover page jumps.
        }

        override fun trimMemory(level: Int) {
            if (level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                return
            }

            for (index in 0 until recyclerView.childCount) {
                val holder = recyclerView.getChildViewHolder(recyclerView.getChildAt(index))
                    as? PageViewHolder
                    ?: continue
                holder.imageView.recycle()
                holder.boundFilePath = null
            }
            notifyDataSetChangedSafely()
        }

        override fun flushPendingRefreshesIfIdle() = Unit

        override fun close() {
            closed = true
            extractExecutor.shutdownNow()
            for (index in 0 until recyclerView.childCount) {
                val holder = recyclerView.getChildViewHolder(recyclerView.getChildAt(index))
                    as? PageViewHolder
                    ?: continue
                holder.imageView.recycle()
                holder.boundFilePath = null
            }
            loadingPositions.clear()
            failedPositions.clear()
            closeExtractorAndDeleteCacheAfterExtractionStops()
        }

        private fun closeExtractorAndDeleteCacheAfterExtractionStops() {
            Thread(
                {
                    if (!awaitExtractorTermination()) {
                        return@Thread
                    }
                    runCatching { extractor?.close() }
                        .onFailure { Log.w(READER_ADAPTER_LOG_TAG, "prepared extractor close failed", it) }
                    runCatching { preparedCacheDir.deleteRecursively() }
                        .onFailure { Log.w(READER_ADAPTER_LOG_TAG, "prepared cache delete failed", it) }
                },
                "ComicLabPreparedReaderClose"
            ).start()
        }

        private fun awaitExtractorTermination(): Boolean {
            while (true) {
                try {
                    if (extractExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                        return true
                    }
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    Log.w(READER_ADAPTER_LOG_TAG, "prepared extractor close interrupted", interrupted)
                    return false
                }
            }
        }

        private fun notifyDataSetChangedSafely() {
            mainHandler.post {
                if (closed) {
                    return@post
                }

                if (!recyclerView.isComputingLayout) {
                    notifyDataSetChanged()
                } else {
                    mainHandler.postDelayed({ notifyDataSetChangedSafely() }, UI_REFRESH_RETRY_MS)
                }
            }
        }

        private fun initialPageLayoutParams(): RecyclerView.LayoutParams {
            return if (isHorizontalReading()) {
                RecyclerView.LayoutParams(
                    baseDisplayWidth,
                    RecyclerView.LayoutParams.MATCH_PARENT
                )
            } else {
                RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    MIN_READER_PAGE_HEIGHT
                )
            }
        }

        private fun applyPageLayout(
            container: FrameLayout,
            bounds: ComicArchive.ImageBounds?
        ) {
            val layoutParams = container.layoutParams as? RecyclerView.LayoutParams
                ?: initialPageLayoutParams()

            val targetWidth: Int
            val targetHeight: Int
            if (isHorizontalReading()) {
                targetWidth = baseDisplayWidth
                targetHeight = RecyclerView.LayoutParams.MATCH_PARENT
            } else {
                targetWidth = RecyclerView.LayoutParams.MATCH_PARENT
                targetHeight = if (bounds != null) {
                    calculateDisplayHeight(bounds.width, bounds.height, baseDisplayWidth)
                } else {
                    (baseDisplayWidth * ESTIMATED_READER_PAGE_HEIGHT_RATIO)
                        .roundToInt()
                        .coerceAtLeast(MIN_READER_PAGE_HEIGHT)
                }
            }

            if (layoutParams.width != targetWidth || layoutParams.height != targetHeight) {
                layoutParams.width = targetWidth
                layoutParams.height = targetHeight
                container.layoutParams = layoutParams
            }
        }

        private fun applyImageLayout(
            imageView: ReaderSubsamplingImageView,
            bounds: ComicArchive.ImageBounds?,
            position: Int
        ) {
            val layoutParams = imageView.layoutParams as? FrameLayout.LayoutParams
                ?: FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            val targetWidth: Int
            val targetHeight: Int
            val targetGravity: Int
            if (doublePageReading &&
                bounds != null &&
                bounds.width > 0 &&
                bounds.height > 0 &&
                baseDisplayWidth > 0 &&
                baseDisplayHeight > 0
            ) {
                val widthScale = baseDisplayWidth.toFloat() / bounds.width.toFloat()
                val heightScale = baseDisplayHeight.toFloat() / bounds.height.toFloat()
                val scale = minOf(widthScale, heightScale)
                targetWidth = (bounds.width * scale).roundToInt().coerceAtLeast(1)
                targetHeight = (bounds.height * scale).roundToInt().coerceAtLeast(1)
                targetGravity = readerPageContentGravity(
                    readingDirection,
                    position,
                    doublePageReading = true
                )
            } else {
                targetWidth = FrameLayout.LayoutParams.MATCH_PARENT
                targetHeight = FrameLayout.LayoutParams.MATCH_PARENT
                targetGravity = Gravity.CENTER
            }

            if (layoutParams.width != targetWidth ||
                layoutParams.height != targetHeight ||
                layoutParams.gravity != targetGravity
            ) {
                layoutParams.width = targetWidth
                layoutParams.height = targetHeight
                layoutParams.gravity = targetGravity
                imageView.layoutParams = layoutParams
            }
        }

        private fun submitExtraction(position: Int, priority: Int) {
            if (closed ||
                position !in entryNames.indices ||
                position !in pageFiles.indices ||
                position in readyPositions
            ) {
                return
            }
            if (position in failedPositions && priority < PRIORITY_VISIBLE) {
                return
            }
            if (priority >= PRIORITY_VISIBLE) {
                failedPositions.remove(position)
            }

            val wasAlreadyLoading = !loadingPositions.add(position)
            if (wasAlreadyLoading && !promoteQueuedExtraction(position, priority)) {
                return
            }

            val task = ExtractTask(
                priority = priority,
                position = position,
                sequence = taskSequence.getAndIncrement()
            ) {
                extractPage(position)
            }
            runCatching {
                extractExecutor.execute(task)
            }.onFailure {
                loadingPositions.remove(position)
            }
        }

        private fun promoteQueuedExtraction(position: Int, priority: Int): Boolean {
            val queue = extractExecutor.queue
            val queuedTask = queue.toList()
                .filterIsInstance<ExtractTask>()
                .firstOrNull { it.position == position }
                ?: return false

            if (queuedTask.priority >= priority) {
                return false
            }

            return if (queue.remove(queuedTask)) {
                queuedTask.cancelled = true
                true
            } else {
                false
            }
        }

        private fun extractPage(position: Int) {
            try {
                if (closed || position !in entryNames.indices || position !in pageFiles.indices) {
                    return
                }

                val targetFile = pageFiles[position]
                val extractedFile = if (targetFile.isFile && targetFile.length() > 0L) {
                    targetFile
                } else {
                    preparedExtractor()?.extract(position, entryNames[position])
                }

                if (closed) {
                    return
                }

                if (extractedFile != null && extractedFile.isFile && extractedFile.length() > 0L) {
                    readyPositions.add(position)
                    failedPositions.remove(position)
                    ComicArchive.imageFileBounds(extractedFile)?.let { bounds ->
                        pageBounds[position] = bounds
                    }
                    notifyPageFileReady(position)
                } else {
                    failedPositions.add(position)
                }
            } catch (throwable: Throwable) {
                failedPositions.add(position)
                if (throwable is OutOfMemoryError) {
                    Log.w(READER_ADAPTER_LOG_TAG, "prepared page extraction OOM position=$position")
                    System.gc()
                } else {
                    Log.w(
                        READER_ADAPTER_LOG_TAG,
                        "prepared page extraction failed position=$position",
                        throwable
                    )
                }
            } finally {
                loadingPositions.remove(position)
            }
        }

        private fun notifyPageFileReady(position: Int) {
            mainHandler.post {
                if (closed || position !in entryNames.indices) {
                    return@post
                }

                if (recyclerView.isComputingLayout) {
                    mainHandler.postDelayed({ notifyPageFileReady(position) }, UI_REFRESH_RETRY_MS)
                    return@post
                }

                val adapterPosition = positionMapper.adapterPositionForSourcePosition(position)
                if (adapterPosition >= 0) {
                    notifyItemChanged(adapterPosition)
                }
                onPageReady(position)
            }
        }

        private fun preparedExtractor(): ComicArchive.PreparedImageExtractor? {
            extractor?.let { return it }
            return synchronized(this) {
                extractor ?: runCatching {
                    ComicArchive.openPreparedImageExtractor(archiveFile, preparedCacheDir)
                }.getOrNull().also { createdExtractor ->
                    extractor = createdExtractor
                }
            }
        }

        private fun orderedNearPositions(
            firstVisible: Int,
            lastVisible: Int,
            movingForward: Boolean,
            isFastScroll: Boolean,
            isIdle: Boolean
        ): List<Int> {
            val beforeCount = if (isIdle) IDLE_PREPARE_BEFORE_COUNT else NORMAL_PREPARE_BEFORE_COUNT
            val afterCount = when {
                isFastScroll -> FAST_PREPARE_FORWARD_COUNT
                isIdle -> IDLE_PREPARE_FORWARD_COUNT
                else -> NORMAL_PREPARE_FORWARD_COUNT
            }
            val before = (firstVisible - 1 downTo (firstVisible - beforeCount).coerceAtLeast(0))
                .filter { it !in firstVisible..lastVisible }
            val after = ((lastVisible + 1)..(lastVisible + afterCount).coerceAtMost(entryNames.lastIndex))
                .filter { it !in firstVisible..lastVisible }
            return if (movingForward) {
                after + before
            } else {
                before + after
            }
        }

        private fun orderedBackgroundPositions(
            firstVisible: Int,
            lastVisible: Int,
            movingForward: Boolean
        ): List<Int> {
            val after = if (lastVisible + 1 <= entryNames.lastIndex) {
                (lastVisible + 1..entryNames.lastIndex).toList()
            } else {
                emptyList()
            }
            val before = if (firstVisible - 1 >= 0) {
                (firstVisible - 1 downTo 0).toList()
            } else {
                emptyList()
            }
            return if (movingForward) {
                after + before
            } else {
                before + after
            }
        }

        private fun calculateDisplayHeight(width: Int, height: Int, imageWidth: Int): Int {
            if (width <= 0 || height <= 0 || imageWidth <= 0) {
                return MIN_READER_PAGE_HEIGHT
            }

            return (imageWidth.toFloat() / width.toFloat() * height.toFloat())
                .roundToInt()
                .coerceAtLeast(1)
        }

        private fun isHorizontalReading(): Boolean {
            return readingDirection == AppSettings.READING_DIRECTION_RIGHT_TO_LEFT ||
                readingDirection == AppSettings.READING_DIRECTION_LEFT_TO_RIGHT
        }

        private class ExtractTask(
            val priority: Int,
            val position: Int,
            private val sequence: Long,
            private val block: () -> Unit
        ) : Runnable, Comparable<ExtractTask> {

            @Volatile
            var cancelled = false

            override fun run() {
                if (!cancelled) {
                    block()
                }
            }

            override fun compareTo(other: ExtractTask): Int {
                val priorityComparison = other.priority.compareTo(priority)
                if (priorityComparison != 0) {
                    return priorityComparison
                }
                return sequence.compareTo(other.sequence)
            }
        }

        private class ReaderTapFrameLayout(context: Context) : FrameLayout(context) {
            var onReaderTap: (() -> Unit)? = null

            private val tapDetector = GestureDetector(
                context,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent): Boolean {
                        return true
                    }

                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        performClick()
                        return true
                    }
                }
            )

            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                tapDetector.onTouchEvent(event)
                return super.dispatchTouchEvent(event)
            }

            override fun performClick(): Boolean {
                super.performClick()
                onReaderTap?.invoke()
                return true
            }
        }

        class PageViewHolder(
            val container: ReaderTapFrameLayout,
            val imageView: ReaderSubsamplingImageView,
            val statusText: TextView
        ) : RecyclerView.ViewHolder(container) {
            var boundFilePath: String? = null
        }

        companion object {
            private const val ESTIMATED_READER_PAGE_HEIGHT_RATIO = 1.45f
            private const val MIN_READER_PAGE_HEIGHT = 320
            private const val PRIORITY_VISIBLE = 100
            private const val PRIORITY_NEAR = 60
            private const val PRIORITY_BACKGROUND = 5
            private const val NORMAL_PREPARE_BEFORE_COUNT = 1
            private const val NORMAL_PREPARE_FORWARD_COUNT = 8
            private const val FAST_PREPARE_FORWARD_COUNT = 14
            private const val IDLE_PREPARE_BEFORE_COUNT = 3
            private const val IDLE_PREPARE_FORWARD_COUNT = 18
            private const val UI_REFRESH_RETRY_MS = 48L
            private const val READER_ADAPTER_LOG_TAG = "ComicLabReader"
        }
    }

    private class MangaPageAdapter(
        private val context: Context,
        private val recyclerView: RecyclerView,
        private val archiveFile: File,
        private val session: ComicArchive.ImageReaderSession,
        private val entries: List<String>,
        private val baseDisplayWidth: Int,
        private val baseDisplayHeight: Int,
        private val decodeWidth: Int,
        private val decodeHeight: Int,
        private val readingDirection: String,
        private val doublePageReading: Boolean,
        private val coverSinglePage: Boolean,
        private val onPageReady: (position: Int) -> Unit,
        private val onPageTap: () -> Unit
    ) : RecyclerView.Adapter<MangaPageAdapter.PageViewHolder>(), ReaderPageAdapterController {

        private val mainHandler = Handler(Looper.getMainLooper())
        private val decodeExecutor = ThreadPoolExecutor(
            READER_DECODE_THREAD_COUNT,
            READER_DECODE_THREAD_COUNT,
            0L,
            TimeUnit.MILLISECONDS,
            PriorityBlockingQueue<Runnable>()
        )
        private val taskSequence = AtomicLong(0L)
        private val preloadGeneration = AtomicInteger(0)
        private val loadingPreviewPages = Collections.synchronizedSet(mutableSetOf<Int>())
        private val loadingFullPages = Collections.synchronizedSet(mutableSetOf<Int>())
        private val loadingTiles = Collections.synchronizedSet(mutableSetOf<TileKey>())
        private val failedPreviewPages = Collections.synchronizedSet(mutableSetOf<Int>())
        private val failedFullPages = Collections.synchronizedSet(mutableSetOf<Int>())
        private val failedTileKeys = Collections.synchronizedSet(mutableSetOf<TileKey>())
        private val pdfPreviewRetryUsedPages = Collections.synchronizedSet(mutableSetOf<Int>())
        private val pdfFullFailureRetryUsedPages = Collections.synchronizedSet(mutableSetOf<Int>())
        private val fullDecodeRetriedPages = Collections.synchronizedSet(mutableSetOf<Int>())
        private val tiledPages = Collections.synchronizedSet(mutableSetOf<Int>())
        private val boundPositions = Collections.synchronizedSet(mutableSetOf<Int>())
        private val pendingRefreshPositions = Collections.synchronizedSet(mutableSetOf<Int>())
        private val pageBounds = Collections.synchronizedMap(mutableMapOf<Int, ComicArchive.ImageBounds>())
        private val isPdfSource = session.isPdfSource()
        private val positionMapper = ReaderPagePositionMapper(
            sourceItemCount = entries.size,
            doublePageReading = doublePageReading,
            coverSinglePage = coverSinglePage
        )
        private val cacheLock = Any()
        private val previewBitmapCache = object : LruCache<Int, Bitmap>(previewBitmapCacheSizeKb(isPdfSource)) {
            override fun sizeOf(key: Int, value: Bitmap): Int {
                return (value.byteCount / 1024).coerceAtLeast(1)
            }
        }
        private val fullBitmapCache = object : LruCache<Int, Bitmap>(
            fullBitmapCacheSizeKb(isPdfSource, doublePageReading)
        ) {
            override fun sizeOf(key: Int, value: Bitmap): Int {
                return (value.byteCount / 1024).coerceAtLeast(1)
            }
        }
        private val tileBitmapCache = object : LruCache<TileKey, Bitmap>(tileBitmapCacheSizeKb(isPdfSource)) {
            override fun sizeOf(key: TileKey, value: Bitmap): Int {
                return (value.byteCount / 1024).coerceAtLeast(1)
            }
        }

        @Volatile
        private var closed = false

        @Volatile
        private var pdfLowMemoryMode = false

        @Volatile
        private var preloadWindowStart = 0

        @Volatile
        private var preloadWindowEnd = -1

        @Volatile
        private var visibleWindowStart = 0

        @Volatile
        private var visibleWindowEnd = -1

        @Volatile
        private var protectedWindowStart = 0

        @Volatile
        private var protectedWindowEnd = -1

        @Volatile
        private var refreshScheduled = false

        private val flushRefreshRunnable = Runnable {
            flushPendingRefreshes()
        }

        init {
            setHasStableIds(true)
        }

        private val adapterItemCount = positionMapper.adapterItemCount

        override fun getItemCount(): Int = adapterItemCount

        override fun readerItemCount(): Int = entries.size

        override fun getItemId(position: Int): Long = position.toLong()

        private fun initialContainerLayoutParams(): RecyclerView.LayoutParams {
            return if (isHorizontalReading()) {
                RecyclerView.LayoutParams(
                    baseDisplayWidth,
                    RecyclerView.LayoutParams.MATCH_PARENT
                )
            } else {
                RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    MIN_READER_PAGE_HEIGHT
                )
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
            val container = ReaderPageZoomLayout(context).apply {
                setBackgroundColor(Color.BLACK)
                clipChildren = true
                isClickable = true
                layoutParams = initialContainerLayoutParams()
            }
            val imageView = ImageView(context).apply {
                setBackgroundColor(Color.BLACK)
                scaleType = ImageView.ScaleType.FIT_XY
                adjustViewBounds = false
                contentDescription = context.getString(R.string.reader_image)
            }
            val tileContainer = FrameLayout(context).apply {
                visibility = View.GONE
                clipChildren = true
            }
            container.addView(imageView)
            container.addView(tileContainer)
            return PageViewHolder(container, imageView, tileContainer).also { holder ->
                holder.container.onTap = onPageTap
            }
        }

        override fun onBindViewHolder(holder: PageViewHolder, adapterPosition: Int) {
            val previousPosition = holder.boundPosition
            if (previousPosition != RecyclerView.NO_POSITION) {
                boundPositions.remove(previousPosition)
            }
            val sourcePosition = positionMapper.sourcePositionForAdapterPosition(adapterPosition)
            holder.boundPosition = sourcePosition ?: RecyclerView.NO_POSITION
            if (previousPosition != holder.boundPosition) {
                holder.container.resetZoom()
            }
            if (sourcePosition == null || sourcePosition !in entries.indices) {
                bindBlankPage(holder)
                return
            }
            boundPositions.add(sourcePosition)
            bindBestAvailable(holder, sourcePosition, adapterPosition)
            ensureVisiblePage(sourcePosition)
        }

        override fun onViewRecycled(holder: PageViewHolder) {
            if (holder.boundPosition != RecyclerView.NO_POSITION) {
                boundPositions.remove(holder.boundPosition)
            }
            holder.boundPosition = RecyclerView.NO_POSITION
            holder.container.resetZoom()
            holder.imageView.setImageDrawable(null)
            holder.tileContainer.removeAllViews()
            super.onViewRecycled(holder)
        }

        override fun preloadAround(
            firstVisiblePosition: Int,
            visibleItemCount: Int,
            scrollDirection: Int,
            isFastScroll: Boolean,
            isIdle: Boolean,
            isDiscreteJump: Boolean
        ) {
            if (entries.isEmpty() || closed) {
                return
            }

            val firstVisibleAdapterPosition = positionMapper
                .spreadStartAdapterPosition(firstVisiblePosition)
            val visiblePositions = positionMapper.sourcePositionsInAdapterRange(
                firstAdapterPosition = firstVisibleAdapterPosition,
                visibleItemCount = visibleItemCount.coerceAtLeast(1)
            )
            if (visiblePositions.isEmpty()) {
                return
            }
            val firstVisible = visiblePositions.first()
            val lastVisible = visiblePositions.last()
            val generation = preloadGeneration.incrementAndGet()
            val window = PreloadWindow.from(
                firstVisible = firstVisible,
                lastVisible = lastVisible,
                lastIndex = entries.lastIndex,
                scrollDirection = scrollDirection,
                isFastScroll = isFastScroll,
                isIdle = isIdle,
                isPdfSource = isPdfSource
            )

            visibleWindowStart = firstVisible
            visibleWindowEnd = lastVisible
            protectedWindowStart = window.protectedStart
            protectedWindowEnd = window.protectedEnd
            preloadWindowStart = window.previewStart
            preloadWindowEnd = window.previewEnd

            cancelStalePreloadTasks(generation)
            trimDistantCaches()

            if (!shouldSkipPdfPreloadAfterMemoryTrim(
                    isPdfSource = isPdfSource,
                    lowMemoryMode = pdfLowMemoryMode,
                    isPreload = true
                )
            ) {
                window.previewPositions.forEach { position ->
                    ensurePreview(position, PRIORITY_PRELOAD_PREVIEW, generation, isPreload = true)
                }
            }

            if (isPdfSource && shouldScheduleImmediateFullDecodeForReader(
                    isPdfSource = true,
                    isReaderIdle = isIdle,
                    isDiscreteJump = isDiscreteJump
                )
            ) {
                (firstVisible..lastVisible).forEach { position ->
                    ensureFullOrTiledPage(
                        position,
                        PRIORITY_VISIBLE,
                        generation = 0,
                        isPreload = false
                    )
                }
            }

            if (shouldPreloadFullPages(isFastScroll, isIdle)) {
                window.fullPositions.forEach { position ->
                    ensureFullOrTiledPage(position, PRIORITY_PRELOAD, generation, isPreload = true)
                }
            }

            if (isPdfSource) {
                mainHandler.post {
                    if (!closed) {
                        ensureVisibleTilesForBoundPages()
                    }
                }
            }
        }

        override fun applyDebugZoomToVisiblePages(
            recyclerView: RecyclerView,
            scale: Float,
            panX: Float,
            panY: Float
        ) {
            for (index in 0 until recyclerView.childCount) {
                val holder = recyclerView.getChildViewHolder(recyclerView.getChildAt(index))
                    as? PageViewHolder
                    ?: continue
                holder.container.setZoomForDebug(scale, panX, panY)
            }
        }

        override fun trimMemory(level: Int) {
            if (isPdfSource && level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                pdfLowMemoryMode = true
                cancelQueuedPreloadTasks()
                clearNonProtectedCachesAfterOom()
                if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
                    synchronized(cacheLock) {
                        fullBitmapCache.evictAll()
                    }
                }
                return
            }

            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                synchronized(cacheLock) {
                    previewBitmapCache.evictAll()
                    tileBitmapCache.evictAll()
                }
                notifyDataSetChangedSafely()
            }
            if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
                synchronized(cacheLock) {
                    fullBitmapCache.evictAll()
                }
                notifyDataSetChangedSafely()
            }
        }

        override fun flushPendingRefreshesIfIdle() {
            mainHandler.post {
                flushPendingRefreshes()
            }
        }

        override fun close() {
            closed = true
            mainHandler.removeCallbacks(flushRefreshRunnable)
            decodeExecutor.shutdownNow()
            synchronized(cacheLock) {
                previewBitmapCache.evictAll()
                fullBitmapCache.evictAll()
                tileBitmapCache.evictAll()
            }
            loadingPreviewPages.clear()
            loadingFullPages.clear()
            loadingTiles.clear()
            failedPreviewPages.clear()
            failedFullPages.clear()
            failedTileKeys.clear()
            pdfPreviewRetryUsedPages.clear()
            pdfFullFailureRetryUsedPages.clear()
            fullDecodeRetriedPages.clear()
            tiledPages.clear()
            boundPositions.clear()
            pendingRefreshPositions.clear()
            pageBounds.clear()
            closeSessionAfterDecoderStops()
        }

        private fun closeSessionAfterDecoderStops() {
            Thread(
                {
                    if (!awaitDecodeExecutorTermination()) {
                        return@Thread
                    }
                    synchronized(cacheLock) {
                        previewBitmapCache.evictAll()
                        fullBitmapCache.evictAll()
                        tileBitmapCache.evictAll()
                    }
                    runCatching { session.close() }
                        .onFailure { Log.w(READER_ADAPTER_LOG_TAG, "reader session close failed", it) }
                },
                "ComicLabReaderSessionClose"
            ).start()
        }

        private fun awaitDecodeExecutorTermination(): Boolean {
            val startedAt = SystemClock.elapsedRealtime()
            var timeoutLogged = false
            while (true) {
                try {
                    if (decodeExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                        return true
                    }
                    if (!timeoutLogged &&
                        SystemClock.elapsedRealtime() - startedAt >= READER_CLOSE_WAIT_LOG_MS
                    ) {
                        timeoutLogged = true
                        Log.w(
                            READER_ADAPTER_LOG_TAG,
                            "reader session close is waiting for decoder termination"
                        )
                    }
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    Log.w(READER_ADAPTER_LOG_TAG, "reader session close interrupted", interrupted)
                    return false
                }
            }
        }

        private fun bindBestAvailable(
            holder: PageViewHolder,
            sourcePosition: Int,
            adapterPosition: Int
        ) {
            val bounds = pageBounds[sourcePosition]
            val isTiled = sourcePosition in tiledPages && bounds != null
            if (isTiled) {
                bindTiledPage(holder, sourcePosition, bounds)
                return
            }

            val fullBitmap = fullBitmap(sourcePosition)
            if (fullBitmap != null) {
                bindBitmap(holder, adapterPosition, fullBitmap)
                return
            }

            val previewBitmap = previewBitmap(sourcePosition)
            if (previewBitmap != null) {
                bindBitmap(holder, adapterPosition, previewBitmap)
                return
            }

            bindPlaceholder(holder, adapterPosition, bounds)
        }

        private fun bindBlankPage(holder: PageViewHolder) {
            holder.tileContainer.visibility = View.GONE
            holder.tileContainer.removeAllViews()
            holder.imageView.visibility = View.VISIBLE
            holder.imageView.setImageDrawable(null)
            holder.imageView.setBackgroundColor(Color.BLACK)
            setContainerSize(holder.container, baseDisplayWidth, ViewGroup.LayoutParams.MATCH_PARENT)
            setFrameChildSize(
                holder.imageView,
                baseDisplayWidth,
                baseDisplayHeight,
                Gravity.CENTER
            )
            holder.container.refreshContentLayout()
        }

        private fun bindPlaceholder(
            holder: PageViewHolder,
            position: Int,
            bounds: ComicArchive.ImageBounds?
        ) {
            holder.tileContainer.visibility = View.GONE
            holder.tileContainer.removeAllViews()
            holder.imageView.visibility = View.VISIBLE
            holder.imageView.setImageDrawable(null)
            holder.imageView.setBackgroundColor(PLACEHOLDER_COLOR)

            if (isHorizontalReading()) {
                setContainerSize(holder.container, baseDisplayWidth, ViewGroup.LayoutParams.MATCH_PARENT)
                val imageWidth = baseDisplayWidth
                val imageHeight = if (bounds != null) {
                    val size = calculateFitInsideSize(bounds.width, bounds.height)
                    size.height
                } else {
                    baseDisplayHeight
                }
                val centeredWidth = if (bounds != null) {
                    calculateFitInsideSize(bounds.width, bounds.height).width
                } else {
                    imageWidth
                }
                setFrameChildSize(
                    holder.imageView,
                    centeredWidth,
                    imageHeight,
                    readerPageContentGravity(readingDirection, position, doublePageReading)
                )
                holder.container.refreshContentLayout()
                return
            }

            val imageWidth = zoomedDisplayWidth()
            val imageHeight = if (bounds != null) {
                calculateDisplayHeight(bounds.width, bounds.height, imageWidth)
            } else {
                (imageWidth * ESTIMATED_READER_PAGE_HEIGHT_RATIO)
                    .roundToInt()
                    .coerceAtLeast(MIN_READER_PAGE_HEIGHT)
            }
            setContainerHeight(holder.container, imageHeight)
            setFrameChildSize(holder.imageView, imageWidth, imageHeight)
            holder.container.refreshContentLayout()
        }

        private fun bindBitmap(holder: PageViewHolder, position: Int, bitmap: Bitmap) {
            holder.tileContainer.visibility = View.GONE
            holder.tileContainer.removeAllViews()
            holder.imageView.visibility = View.VISIBLE
            holder.imageView.setBackgroundColor(Color.BLACK)

            if (isHorizontalReading()) {
                val imageSize = calculateFitInsideSize(bitmap.width, bitmap.height)
                setContainerSize(holder.container, baseDisplayWidth, ViewGroup.LayoutParams.MATCH_PARENT)
                setFrameChildSize(
                    holder.imageView,
                    imageSize.width,
                    imageSize.height,
                    readerPageContentGravity(readingDirection, position, doublePageReading)
                )
                holder.imageView.setImageBitmap(bitmap)
                holder.container.refreshContentLayout()
                return
            }

            val imageWidth = zoomedDisplayWidth()
            val imageHeight = calculateDisplayHeight(bitmap.width, bitmap.height, imageWidth)
            setContainerHeight(holder.container, imageHeight)
            setFrameChildSize(holder.imageView, imageWidth, imageHeight)
            holder.imageView.setImageBitmap(bitmap)
            holder.container.refreshContentLayout()
        }

        private fun bindTiledPage(
            holder: PageViewHolder,
            sourcePosition: Int,
            bounds: ComicArchive.ImageBounds
        ) {
            val imageWidth = zoomedDisplayWidth()
            val imageHeight = calculateDisplayHeight(bounds.width, bounds.height, imageWidth)
            setContainerHeight(holder.container, imageHeight)
            setFrameChildSize(holder.imageView, imageWidth, imageHeight)
            setFrameChildSize(holder.tileContainer, imageWidth, imageHeight)
            holder.imageView.visibility = View.VISIBLE
            holder.tileContainer.visibility = View.VISIBLE
            holder.imageView.setBackgroundColor(Color.BLACK)
            holder.imageView.setImageBitmap(previewBitmap(sourcePosition))

            val tileDecodeWidth = tileDecodeWidth(imageWidth, bounds.width)
            val tiles = buildTiles(bounds, imageWidth, tileDecodeWidth)
            holder.tileContainer.removeAllViews()
            holder.container.refreshContentLayout()
            mainHandler.post {
                if (!closed && holder.boundPosition == sourcePosition) {
                    ensureVisibleTiles(holder, sourcePosition, bounds, imageWidth, tileDecodeWidth, tiles)
                }
            }
        }

        private fun ensureVisibleTilesForBoundPages() {
            for (index in 0 until recyclerView.childCount) {
                val holder = recyclerView.getChildViewHolder(recyclerView.getChildAt(index))
                    as? PageViewHolder
                    ?: continue
                val position = holder.boundPosition
                if (position !in tiledPages) {
                    continue
                }
                val bounds = pageBounds[position] ?: continue
                val imageWidth = zoomedDisplayWidth()
                val imageHeight = calculateDisplayHeight(bounds.width, bounds.height, imageWidth)
                val tileDecodeWidth = tileDecodeWidth(imageWidth, bounds.width)
                val tiles = buildTiles(bounds, imageWidth, tileDecodeWidth)
                ensureVisibleTiles(holder, position, bounds, imageWidth, tileDecodeWidth, tiles)
            }
        }

        private fun ensureVisibleTiles(
            holder: PageViewHolder,
            position: Int,
            bounds: ComicArchive.ImageBounds,
            imageWidth: Int,
            tileDecodeWidth: Int,
            tiles: List<PageTile>
        ) {
            if (closed || holder.boundPosition != position) {
                return
            }

            val imageHeight = calculateDisplayHeight(bounds.width, bounds.height, imageWidth)
            val containerTop = holder.container.top
            val viewportTop = (-containerTop).coerceIn(0, imageHeight)
            val viewportBottom = (recyclerView.height - containerTop).coerceIn(0, imageHeight)
            if (viewportBottom <= viewportTop) {
                holder.tileContainer.removeAllViews()
                return
            }

            val visibleKeys = mutableSetOf<TileKey>()
            tiles.forEach { tile ->
                val tileTop = sourceToDisplayY(tile.sourceRect.top, bounds.height, imageHeight)
                val tileBottom = sourceToDisplayY(tile.sourceRect.bottom, bounds.height, imageHeight)
                    .coerceAtLeast(tileTop + 1)
                if (!isReaderTileVisible(tileTop, tileBottom, viewportTop, viewportBottom)) {
                    return@forEach
                }

                val key = TileKey(position, tile.index, imageWidth, tileDecodeWidth)
                visibleKeys.add(key)
                val tileView = (0 until holder.tileContainer.childCount)
                    .asSequence()
                    .mapNotNull { childIndex -> holder.tileContainer.getChildAt(childIndex) as? ImageView }
                    .firstOrNull { it.tag == key }
                    ?: ImageView(context).apply {
                        setBackgroundColor(Color.TRANSPARENT)
                        scaleType = ImageView.ScaleType.FIT_XY
                        adjustViewBounds = false
                        tag = key
                        setTileChildSize(this, bounds, tile.sourceRect, imageWidth, imageHeight)
                        holder.tileContainer.addView(this)
                    }
                val cachedTile = tileBitmap(key)
                if (cachedTile != null) {
                    tileView.setImageBitmap(cachedTile)
                } else {
                    ensureTile(position, tile, tileDecodeWidth, tileView, holder)
                }
            }

            for (childIndex in holder.tileContainer.childCount - 1 downTo 0) {
                val tileView = holder.tileContainer.getChildAt(childIndex)
                if (tileView.tag !in visibleKeys) {
                    holder.tileContainer.removeViewAt(childIndex)
                }
            }
        }

        private fun ensureVisiblePage(position: Int) {
            if (isPdfSource) {
                ensurePreview(
                    position,
                    visiblePreviewPriorityForReader(
                        isPdfSource = true,
                        previewPriority = PRIORITY_VISIBLE_PREVIEW,
                        fullPriority = PRIORITY_VISIBLE
                    ),
                    generation = 0,
                    isPreload = false
                )
                if (shouldScheduleImmediateFullDecodeForReader(
                        isPdfSource = true,
                        isReaderIdle = recyclerView.scrollState == RecyclerView.SCROLL_STATE_IDLE
                    )
                ) {
                    ensureFullOrTiledPage(position, PRIORITY_VISIBLE, generation = 0, isPreload = false)
                }
                return
            }

            ensurePreview(position, PRIORITY_VISIBLE_PREVIEW, generation = 0, isPreload = false)
            ensureFullOrTiledPage(position, PRIORITY_VISIBLE, generation = 0, isPreload = false)
        }

        private fun ensurePreview(
            position: Int,
            priority: Int,
            generation: Int,
            isPreload: Boolean
        ) {
            if (!isPreload) {
                cancelQueuedPreloadForPosition(position, DecodeTaskKind.PREVIEW)
            }

            if (position !in entries.indices ||
                closed ||
                previewBitmap(position) != null
            ) {
                return
            }
            if (position in failedPreviewPages) {
                if (isPreload) {
                    return
                }
                if (isPdfSource && !shouldRetryPdfPageAfterFailure(
                        isPdfSource = true,
                        isPreload = isPreload,
                        retryAlreadyUsed = position in pdfPreviewRetryUsedPages
                    )
                ) {
                    return
                }
                failedPreviewPages.remove(position)
                if (isPdfSource) {
                    pdfPreviewRetryUsedPages.add(position)
                }
            }

            val registeredLoading = loadingPreviewPages.add(position)
            if (!registeredLoading && isPreload) {
                return
            }

            submitTask(
                priority = priority,
                position = position,
                generation = generation,
                kind = DecodeTaskKind.PREVIEW,
                cancelable = isPreload
            ) {
                try {
                    if (isPreload && !isUsefulPreload(position, generation)) {
                        return@submitTask
                    }
                    if (previewBitmap(position) != null) {
                        return@submitTask
                    }

                    val targetWidth = previewDecodeWidth()
                    val bitmap = decodeWithRenderDiagnostics(
                        position = position,
                        kind = "PREVIEW",
                        targetWidth = targetWidth,
                        targetHeight = null
                    ) {
                        runCatching {
                            session.decodePreviewForWidth(entries[position], targetWidth)
                        }.onFailure {
                            handleDecodeFailure(
                                position = position,
                                kind = DecodeTaskKind.PREVIEW,
                                throwable = it,
                                targetWidth = targetWidth
                            )
                        }.getOrNull()
                    }

                    if (bitmap == null) {
                        if (isPdfSource || !isPreload) {
                            failedPreviewPages.add(position)
                        }
                        if (isPdfSource && !isPreload) {
                            pdfPreviewRetryUsedPages.add(position)
                        }
                        return@submitTask
                    }

                    CrashLogManager.recordReaderRender(
                        context = context,
                        file = archiveFile,
                        position = position,
                        kind = "PREVIEW",
                        targetWidth = targetWidth,
                        targetHeight = null,
                        bitmapBytes = bitmap.byteCount
                    )

                    if (isPreload && !isUsefulPreload(position, generation)) {
                        bitmap.recycle()
                        return@submitTask
                    }
                    if (shouldSkipPdfPreloadAfterMemoryTrim(
                            isPdfSource = isPdfSource,
                            lowMemoryMode = pdfLowMemoryMode,
                            isPreload = isPreload
                        )
                    ) {
                        bitmap.recycle()
                        return@submitTask
                    }

                    putPreviewBitmap(position, bitmap)
                    requestItemRefresh(position, immediate = !isPreload)
                } finally {
                    if (registeredLoading) {
                        loadingPreviewPages.remove(position)
                    }
                }
            }
        }

        private fun ensureFullOrTiledPage(
            position: Int,
            priority: Int,
            generation: Int,
            isPreload: Boolean
        ) {
            if (!isPreload) {
                cancelQueuedPreloadForPosition(position, DecodeTaskKind.FULL)
            }

            if (position !in entries.indices ||
                closed ||
                position in tiledPages ||
                fullBitmap(position) != null
            ) {
                return
            }
            if (position in failedFullPages) {
                if (isPdfSource) {
                    if (!shouldRetryPdfPageAfterFailure(
                            isPdfSource = true,
                            isPreload = isPreload,
                            retryAlreadyUsed = position in pdfFullFailureRetryUsedPages
                        )
                    ) {
                        return
                    }
                    failedFullPages.remove(position)
                    pdfFullFailureRetryUsedPages.add(position)
                }
                if (!isPdfSource && isPreload) {
                    return
                }
                if (!isPdfSource) {
                    failedFullPages.remove(position)
                }
            }

            val registeredLoading = loadingFullPages.add(position)
            if (!registeredLoading && isPreload) {
                return
            }

            submitTask(
                priority = priority,
                position = position,
                generation = generation,
                kind = DecodeTaskKind.FULL,
                cancelable = isPreload
            ) {
                try {
                    if (isPreload && !isUsefulPreload(position, generation)) {
                        return@submitTask
                    }
                    if (position in tiledPages || fullBitmap(position) != null) {
                        return@submitTask
                    }

                    val bounds = pageBounds[position]
                        ?: runCatching { session.readBounds(entries[position]) }
                            .onFailure { handleDecodeFailure(position, DecodeTaskKind.FULL, it) }
                            .getOrNull()
                            ?.also { pageBounds[position] = it }

                    if (bounds == null) {
                        if (isPdfSource || !isPreload) {
                            failedFullPages.add(position)
                        }
                        if (isPdfSource && !isPreload) {
                            pdfFullFailureRetryUsedPages.add(position)
                        }
                        return@submitTask
                    }

                    if (isPreload && !isUsefulPreload(position, generation)) {
                        return@submitTask
                    }

                    if (shouldUseTiledPage(bounds)) {
                        if (shouldSkipTiledPdfPreviewDecodeAfterMemoryTrim(
                                isPdfSource = isPdfSource,
                                lowMemoryMode = pdfLowMemoryMode,
                                isPreload = isPreload
                            )
                        ) {
                            return@submitTask
                        }

                        if (previewBitmap(position) == null) {
                            val previewWidth = previewDecodeWidth()
                            val previewBitmap = decodeWithRenderDiagnostics(
                                position = position,
                                kind = "PREVIEW",
                                targetWidth = previewWidth,
                                targetHeight = null
                            ) {
                                runCatching {
                                    session.decodePreviewForWidth(entries[position], previewWidth)
                                }.onFailure {
                                    handleDecodeFailure(
                                        position = position,
                                        kind = DecodeTaskKind.PREVIEW,
                                        throwable = it,
                                        targetWidth = previewWidth
                                    )
                                }.getOrNull()
                            }
                            if (previewBitmap != null) {
                                if (shouldSkipTiledPdfPreviewDecodeAfterMemoryTrim(
                                        isPdfSource = isPdfSource,
                                        lowMemoryMode = pdfLowMemoryMode,
                                        isPreload = isPreload
                                    )
                                ) {
                                    previewBitmap.recycle()
                                    return@submitTask
                                }
                                putPreviewBitmap(position, previewBitmap)
                            }
                        }
                        if (isPreload && !isUsefulPreload(position, generation)) {
                            return@submitTask
                        }
                        tiledPages.add(position)
                        notifyPageReady(position, immediate = !isPreload)
                        return@submitTask
                    }

                    val targetWidth = fullPageDecodeWidth(bounds)
                    val targetHeight = fullPageDecodeHeight(bounds)
                    var bitmap = decodeFullPage(
                        position = position,
                        targetWidth = targetWidth,
                        targetHeight = targetHeight
                    )

                    if (bitmap == null && isPdfSource && shouldRetryFullPageAfterFailure(
                            isPreload = isPreload,
                            alreadyRetried = position in fullDecodeRetriedPages
                        )
                    ) {
                        fullDecodeRetriedPages.add(position)
                        val retryWidth = reducedDecodeDimensionForRetry(
                            currentDimension = targetWidth,
                            minimumDimension = previewDecodeWidth()
                        )
                        val retryHeight = reducedDecodeDimensionForRetry(
                            currentDimension = targetHeight,
                            minimumDimension = baseDisplayHeight
                        )
                        bitmap = decodeFullPage(
                            position = position,
                            targetWidth = retryWidth,
                            targetHeight = retryHeight
                        )
                    }

                    if (bitmap == null) {
                        if (isPdfSource || !isPreload) {
                            failedFullPages.add(position)
                        }
                        if (isPdfSource && !isPreload) {
                            pdfFullFailureRetryUsedPages.add(position)
                        }
                        return@submitTask
                    }

                    if (isPreload && !isUsefulPreload(position, generation)) {
                        bitmap.recycle()
                        return@submitTask
                    }
                    if (shouldSkipPdfPreloadAfterMemoryTrim(
                            isPdfSource = isPdfSource,
                            lowMemoryMode = pdfLowMemoryMode,
                            isPreload = isPreload
                        )
                    ) {
                        bitmap.recycle()
                        return@submitTask
                    }

                    if (shouldReleasePreviewAfterFullDecode(position in tiledPages)) {
                        removePreviewBitmap(position)
                    }
                    putFullBitmap(position, bitmap)
                    notifyPageReady(position, immediate = !isPreload)
                } finally {
                    if (registeredLoading) {
                        loadingFullPages.remove(position)
                    }
                }
            }
        }

        private fun <T> decodeWithRenderDiagnostics(
            position: Int,
            kind: String,
            targetWidth: Int?,
            targetHeight: Int?,
            block: () -> T
        ): T {
            runCatching {
                if (isPdfSource) {
                    CrashLogManager.recordReaderRenderStarted(
                        context = context,
                        file = archiveFile,
                        position = position,
                        kind = kind,
                        targetWidth = targetWidth,
                        targetHeight = targetHeight
                    )
                } else {
                    CrashLogManager.recordReaderRender(
                        context = context,
                        file = archiveFile,
                        position = position,
                        kind = kind,
                        targetWidth = targetWidth,
                        targetHeight = targetHeight
                    )
                }
            }

            return try {
                block()
            } finally {
                if (isPdfSource) {
                    runCatching {
                        CrashLogManager.recordReaderRenderCompleted(context)
                    }
                }
            }
        }

        private fun decodeFullPage(
            position: Int,
            targetWidth: Int,
            targetHeight: Int
        ): Bitmap? {
            return decodeWithRenderDiagnostics(
                position = position,
                kind = "FULL",
                targetWidth = targetWidth,
                targetHeight = targetHeight
            ) {
                runCatching {
                    if (isHorizontalReading()) {
                        session.decodeImageForPage(
                            entryName = entries[position],
                            targetWidth = targetWidth,
                            targetHeight = targetHeight
                        )
                    } else {
                        session.decodeImageForWidth(
                            entries[position],
                            targetWidth
                        )
                    }
                }.onFailure {
                    handleDecodeFailure(
                        position = position,
                        kind = DecodeTaskKind.FULL,
                        throwable = it,
                        targetWidth = targetWidth,
                        targetHeight = targetHeight
                    )
                }.getOrNull()
            }?.also { bitmap ->
                CrashLogManager.recordReaderRender(
                    context = context,
                    file = archiveFile,
                    position = position,
                    kind = "FULL",
                    targetWidth = targetWidth,
                    targetHeight = targetHeight,
                    bitmapBytes = bitmap.byteCount
                )
            }
        }

        private fun ensureTile(
            position: Int,
            tile: PageTile,
            tileDecodeWidth: Int,
            tileView: ImageView,
            holder: PageViewHolder
        ) {
            if (closed || position !in entries.indices) {
                return
            }

            val displayImageWidth = (tileView.layoutParams?.width ?: zoomedDisplayWidth())
                .coerceAtLeast(baseDisplayWidth)
            val key = TileKey(position, tile.index, displayImageWidth, tileDecodeWidth)
            if (tileBitmap(key) != null || key in failedTileKeys || !loadingTiles.add(key)) {
                return
            }

            submitTask(
                priority = PRIORITY_TILE,
                position = position,
                generation = preloadGeneration.get(),
                kind = DecodeTaskKind.TILE,
                cancelable = true,
                tileKey = key
            ) {
                try {
                    val targetHeight = (
                        tile.sourceRect.height().toLong() * tileDecodeWidth /
                            tile.sourceRect.width().coerceAtLeast(1)
                        ).toInt().coerceAtLeast(1)
                    val bitmap = decodeWithRenderDiagnostics(
                        position = position,
                        kind = "TILE",
                        targetWidth = tileDecodeWidth,
                        targetHeight = targetHeight
                    ) {
                        runCatching {
                            session.decodeRegionForWidth(
                                entries[position],
                                tile.sourceRect,
                                tileDecodeWidth
                            )
                        }.onFailure {
                            handleDecodeFailure(
                                position = position,
                                kind = DecodeTaskKind.TILE,
                                throwable = it,
                                targetWidth = tileDecodeWidth,
                                targetHeight = targetHeight
                            )
                        }.getOrNull()
                    } ?: run {
                        failedTileKeys.add(key)
                        return@submitTask
                    }

                    CrashLogManager.recordReaderRender(
                        context = context,
                        file = archiveFile,
                        position = position,
                        kind = "TILE",
                        targetWidth = tileDecodeWidth,
                        targetHeight = targetHeight,
                        bitmapBytes = bitmap.byteCount
                    )

                    putTileBitmap(key, bitmap)
                    mainHandler.post {
                        if (!closed && holder.boundPosition == position && tileView.tag == key) {
                            tileView.setImageBitmap(bitmap)
                        }
                    }
                } finally {
                    loadingTiles.remove(key)
                }
            }
        }

        private fun notifyPageReady(position: Int, immediate: Boolean) {
            mainHandler.post {
                if (closed || position !in entries.indices) {
                    return@post
                }

                requestItemRefresh(position, immediate || position in boundPositions)
                onPageReady(position)
            }
        }

        private fun requestItemRefresh(position: Int, immediate: Boolean) {
            if (closed || position !in entries.indices || !shouldRefreshPosition(position)) {
                return
            }

            if (immediate) {
                mainHandler.post {
                    if (!closed && position in entries.indices && shouldRefreshPosition(position)) {
                        if (refreshBoundPositionDirectly(position)) {
                            return@post
                        }
                        refreshPositionOrDefer(position)
                    }
                }
                return
            }

            mainHandler.post {
                if (closed || position !in entries.indices || !shouldRefreshPosition(position)) {
                    return@post
                }

                pendingRefreshPositions.add(position)
                if (!refreshScheduled) {
                    refreshScheduled = true
                    mainHandler.postDelayed(flushRefreshRunnable, UI_REFRESH_THROTTLE_MS)
                }
            }
        }

        private fun flushPendingRefreshes() {
            refreshScheduled = false
            if (closed) {
                return
            }
            if (recyclerView.isComputingLayout) {
                scheduleRefreshFlush()
                return
            }

            val positions = synchronized(pendingRefreshPositions) {
                pendingRefreshPositions.toList().also {
                    pendingRefreshPositions.clear()
                }
            }

            positions.forEach { position ->
                if (closed || position !in entries.indices || !shouldRefreshPosition(position)) {
                    return@forEach
                }

                if (refreshBoundPositionDirectly(position)) {
                    return@forEach
                }

                if (canNotifyRecyclerView()) {
                    val adapterPosition = positionMapper.adapterPositionForSourcePosition(position)
                    if (adapterPosition >= 0) {
                        notifyItemChanged(adapterPosition)
                    }
                } else {
                    pendingRefreshPositions.add(position)
                }
            }

            if (pendingRefreshPositions.isNotEmpty()) {
                scheduleRefreshFlush()
            }
        }

        private fun refreshPositionOrDefer(position: Int) {
            if (refreshBoundPositionDirectly(position)) {
                return
            }

            if (canNotifyRecyclerView()) {
                val adapterPosition = positionMapper.adapterPositionForSourcePosition(position)
                if (adapterPosition >= 0) {
                    notifyItemChanged(adapterPosition)
                }
                return
            }

            pendingRefreshPositions.add(position)
            scheduleRefreshFlush()
        }

        private fun scheduleRefreshFlush() {
            if (refreshScheduled || closed) {
                return
            }

            refreshScheduled = true
            mainHandler.postDelayed(flushRefreshRunnable, UI_REFRESH_RETRY_MS)
        }

        private fun refreshBoundPositionDirectly(position: Int): Boolean {
            if (recyclerView.isComputingLayout) {
                return false
            }

            val adapterPosition = positionMapper.adapterPositionForSourcePosition(position)
            if (adapterPosition < 0) {
                return false
            }

            val holder = recyclerView.findViewHolderForAdapterPosition(adapterPosition) as? PageViewHolder
                ?: return false
            if (!shouldRefreshBoundReaderPageImmediately(
                    isBoundToPosition = holder.boundPosition == position,
                    isComputingLayout = recyclerView.isComputingLayout
                )
            ) {
                return false
            }

            bindBestAvailable(holder, position, adapterPosition)
            return true
        }

        private fun canNotifyRecyclerView(): Boolean {
            return !recyclerView.isComputingLayout &&
                recyclerView.scrollState == RecyclerView.SCROLL_STATE_IDLE
        }

        private fun notifyDataSetChangedSafely() {
            mainHandler.post {
                if (!closed && canNotifyRecyclerView()) {
                    notifyDataSetChanged()
                } else if (!closed) {
                    mainHandler.postDelayed(
                        {
                            if (!closed) {
                                notifyDataSetChangedSafely()
                            }
                        },
                        UI_REFRESH_RETRY_MS
                    )
                }
            }
        }

        private fun submitTask(
            priority: Int,
            position: Int,
            generation: Int,
            kind: DecodeTaskKind,
            cancelable: Boolean,
            tileKey: TileKey? = null,
            block: () -> Unit
        ) {
            if (closed) {
                return
            }

            val task = DecodeTask(
                priority = priority,
                position = position,
                generation = generation,
                kind = kind,
                cancelable = cancelable,
                tileKey = tileKey,
                sequence = taskSequence.getAndIncrement(),
                block = block
            )
            runCatching {
                decodeExecutor.execute(task)
            }.onFailure {
                clearLoadingForCanceledTask(task)
                handleDecodeFailure(position, kind, it)
            }
        }

        private fun handleDecodeFailure(
            position: Int,
            kind: DecodeTaskKind,
            throwable: Throwable,
            targetWidth: Int? = null,
            targetHeight: Int? = null
        ) {
            if (closed) {
                return
            }

            val runtime = Runtime.getRuntime()
            val usedHeapKb = (runtime.totalMemory() - runtime.freeMemory()) / 1024L
            val maxHeapKb = runtime.maxMemory() / 1024L
            val target = "${targetWidth ?: "?"}x${targetHeight ?: "?"}"
            runCatching {
                CrashLogManager.recordReaderRender(
                    context = context,
                    file = archiveFile,
                    position = position,
                    kind = kind.name,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight,
                    usedHeapKb = usedHeapKb,
                    maxHeapKb = maxHeapKb
                )
            }
            if (throwable is OutOfMemoryError) {
                Log.w(
                    READER_ADAPTER_LOG_TAG,
                    "decode OOM kind=$kind position=$position target=$target " +
                        "usedHeapKb=$usedHeapKb maxHeapKb=$maxHeapKb",
                    throwable
                )
                cancelNonVisiblePreloadTasksAfterOom()
                clearNonProtectedCachesAfterOom()
                System.gc()
            } else {
                Log.w(
                    READER_ADAPTER_LOG_TAG,
                    "decode failed kind=$kind position=$position target=$target " +
                        "usedHeapKb=$usedHeapKb maxHeapKb=$maxHeapKb",
                    throwable
                )
            }
        }

        private fun cancelNonVisiblePreloadTasksAfterOom() {
            val queue = decodeExecutor.queue
            queue.toList().forEach { runnable ->
                val task = runnable as? DecodeTask ?: return@forEach
                if (!shouldCancelPreloadAfterOutOfMemory(
                        isCancelable = task.cancelable,
                        isVisible = task.position in visibleWindowStart..visibleWindowEnd
                    )
                ) {
                    return@forEach
                }

                if (queue.remove(task)) {
                    task.cancelled = true
                    clearLoadingForCanceledTask(task)
                }
            }
        }

        private fun cancelQueuedPreloadTasks() {
            val queue = decodeExecutor.queue
            queue.toList().forEach { runnable ->
                val task = runnable as? DecodeTask ?: return@forEach
                if (!task.cancelable) {
                    return@forEach
                }

                if (queue.remove(task)) {
                    task.cancelled = true
                    clearLoadingForCanceledTask(task)
                }
            }
        }

        private fun clearNonProtectedCachesAfterOom() {
            synchronized(cacheLock) {
                previewBitmapCache.evictAll()
                tileBitmapCache.evictAll()
                fullBitmapCache.snapshot().keys
                    .filter { it !in protectedWindowStart..protectedWindowEnd }
                    .forEach { fullBitmapCache.remove(it) }
            }
        }

        private fun isUsefulPreload(position: Int, generation: Int): Boolean {
            return !closed &&
                generation == preloadGeneration.get() &&
                position in preloadWindowStart..preloadWindowEnd
        }

        private fun shouldRefreshPosition(position: Int): Boolean {
            return position in visibleWindowStart..visibleWindowEnd || position in boundPositions
        }

        private fun cancelStalePreloadTasks(currentGeneration: Int) {
            val queue = decodeExecutor.queue
            queue.toList().forEach { runnable ->
                val task = runnable as? DecodeTask ?: return@forEach
                if (!task.cancelable) {
                    return@forEach
                }

                if (task.generation != currentGeneration ||
                    task.position !in preloadWindowStart..preloadWindowEnd
                ) {
                    if (queue.remove(task)) {
                        task.cancelled = true
                        clearLoadingForCanceledTask(task)
                    }
                }
            }
        }

        private fun cancelQueuedPreloadForPosition(position: Int, kind: DecodeTaskKind) {
            val queue = decodeExecutor.queue
            queue.toList().forEach { runnable ->
                val task = runnable as? DecodeTask ?: return@forEach
                if (task.cancelable && task.position == position && task.kind == kind) {
                    if (queue.remove(task)) {
                        task.cancelled = true
                        clearLoadingForCanceledTask(task)
                    }
                }
            }
        }

        private fun clearLoadingForCanceledTask(task: DecodeTask) {
            when (task.kind) {
                DecodeTaskKind.PREVIEW -> loadingPreviewPages.remove(task.position)
                DecodeTaskKind.FULL -> loadingFullPages.remove(task.position)
                DecodeTaskKind.TILE -> task.tileKey?.let { loadingTiles.remove(it) }
            }
        }

        private fun trimDistantCaches() {
            if (protectedWindowEnd < protectedWindowStart) {
                return
            }

            synchronized(cacheLock) {
                tileBitmapCache.snapshot().keys
                    .filter { it.position !in protectedWindowStart..protectedWindowEnd }
                    .forEach { tileBitmapCache.remove(it) }

                if (isPdfSource ||
                    fullBitmapCache.size() >= fullBitmapCache.maxSize() * FULL_CACHE_TRIM_THRESHOLD_PERCENT / 100
                ) {
                    fullBitmapCache.snapshot().keys
                        .filter { it !in protectedWindowStart..protectedWindowEnd }
                        .forEach { fullBitmapCache.remove(it) }
                }
            }
        }

        private fun buildTiles(
            bounds: ComicArchive.ImageBounds,
            imageWidth: Int,
            tileDecodeWidth: Int
        ): List<PageTile> {
            val sourceTileHeightByDisplay = (TILE_MAX_DISPLAY_HEIGHT.toFloat() * bounds.width / imageWidth)
                .roundToInt()
            val sourceTileHeightByPixels = (TILE_MAX_SOURCE_PIXELS / bounds.width.coerceAtLeast(1))
                .coerceAtLeast(TILE_MIN_SOURCE_HEIGHT)
            val sourceTileHeightByDecodedPixels = if (tileDecodeWidth > 0 && bounds.width > 0) {
                (TILE_MAX_DECODED_PIXELS.toFloat() * bounds.width.toFloat() /
                    (tileDecodeWidth.toFloat() * tileDecodeWidth.toFloat()))
                    .roundToInt()
            } else {
                sourceTileHeightByPixels
            }
            val minSourceTileHeight = if (isPdfSource) {
                PDF_TILE_MIN_SOURCE_HEIGHT
            } else {
                TILE_MIN_SOURCE_HEIGHT
            }
            val sourceTileHeight = minOf(
                sourceTileHeightByDisplay,
                sourceTileHeightByPixels,
                sourceTileHeightByDecodedPixels
            )
                .coerceAtLeast(minSourceTileHeight)
            val tiles = mutableListOf<PageTile>()
            var top = 0
            var index = 0
            while (top < bounds.height) {
                val bottom = (top + sourceTileHeight).coerceAtMost(bounds.height)
                tiles.add(PageTile(index, Rect(0, top, bounds.width, bottom)))
                top = bottom
                index++
            }
            return tiles
        }

        private fun shouldUseTiledPage(bounds: ComicArchive.ImageBounds): Boolean {
            if (isHorizontalReading()) {
                return false
            }

            if (isPdfSource) {
                return shouldUsePdfTiledRendering(
                    pageWidth = bounds.width,
                    pageHeight = bounds.height,
                    displayWidth = zoomedDisplayWidth(),
                    renderPixelThreshold = PDF_TILED_RENDER_PIXEL_THRESHOLD
                )
            }

            val sourcePixels = bounds.width.toLong() * bounds.height.toLong()
            val decodedHeight = calculateDisplayHeight(bounds.width, bounds.height, decodeWidth)
            val sampleSize = calculateSampleSizeForWidth(bounds.width, decodeWidth)
            val decodedPixels = (bounds.width / sampleSize).toLong() *
                (bounds.height / sampleSize).toLong()
            return bounds.width > MAX_READER_DECODE_WIDTH ||
                bounds.height >= TILED_SOURCE_HEIGHT_THRESHOLD ||
                decodedHeight >= TILED_DECODED_HEIGHT_THRESHOLD ||
                sourcePixels >= TILED_SOURCE_PIXEL_THRESHOLD ||
                decodedPixels >= TILED_DECODED_PIXEL_THRESHOLD
        }

        private fun calculateSampleSizeForWidth(width: Int, targetWidth: Int): Int {
            if (width <= 0 || targetWidth <= 0) {
                return 1
            }

            var sampleSize = 1
            while (width / (sampleSize * 2) >= targetWidth) {
                sampleSize *= 2
            }
            return sampleSize
        }

        private fun setContainerHeight(container: FrameLayout, height: Int) {
            setContainerSize(container, RecyclerView.LayoutParams.MATCH_PARENT, height)
        }

        private fun setContainerSize(container: FrameLayout, width: Int, height: Int) {
            val layoutParams = container.layoutParams as? RecyclerView.LayoutParams
                ?: RecyclerView.LayoutParams(
                    width,
                    height
                )
            if (layoutParams.width != width || layoutParams.height != height) {
                layoutParams.width = width
                layoutParams.height = height
                container.layoutParams = layoutParams
            }
        }

        private fun setFrameChildSize(
            view: View,
            width: Int,
            height: Int,
            gravity: Int = Gravity.CENTER_HORIZONTAL
        ) {
            val layoutParams = view.layoutParams as? FrameLayout.LayoutParams
                ?: FrameLayout.LayoutParams(width, height, gravity)
            if (layoutParams.width != width ||
                layoutParams.height != height ||
                layoutParams.gravity != gravity
            ) {
                layoutParams.width = width
                layoutParams.height = height
                layoutParams.gravity = gravity
                view.layoutParams = layoutParams
            }
        }

        private fun setTileChildSize(
            view: View,
            bounds: ComicArchive.ImageBounds,
            sourceRect: Rect,
            imageWidth: Int,
            imageHeight: Int
        ) {
            val top = sourceToDisplayY(sourceRect.top, bounds.height, imageHeight)
            val bottom = sourceToDisplayY(sourceRect.bottom, bounds.height, imageHeight)
                .coerceAtLeast(top + 1)
            val height = (bottom - top).coerceAtLeast(1)
            val layoutParams = view.layoutParams as? FrameLayout.LayoutParams
                ?: FrameLayout.LayoutParams(imageWidth, height)
            if (layoutParams.width != imageWidth ||
                layoutParams.height != height ||
                layoutParams.topMargin != top
            ) {
                layoutParams.width = imageWidth
                layoutParams.height = height
                layoutParams.topMargin = top
                view.layoutParams = layoutParams
            }
        }

        private fun sourceToDisplayY(sourceY: Int, sourceHeight: Int, imageHeight: Int): Int {
            if (sourceHeight <= 0 || imageHeight <= 0) {
                return 0
            }

            return (sourceY.toFloat() / sourceHeight.toFloat() * imageHeight.toFloat())
                .roundToInt()
                .coerceIn(0, imageHeight)
        }

        private fun calculateDisplayHeight(width: Int, height: Int, imageWidth: Int): Int {
            if (width <= 0 || height <= 0 || imageWidth <= 0) {
                return MIN_READER_PAGE_HEIGHT
            }

            return (imageWidth.toFloat() / width.toFloat() * height.toFloat())
                .roundToInt()
                .coerceAtLeast(1)
        }

        private fun calculateFitInsideSize(width: Int, height: Int): DisplaySize {
            if (width <= 0 || height <= 0 || baseDisplayWidth <= 0 || baseDisplayHeight <= 0) {
                return DisplaySize(baseDisplayWidth, baseDisplayHeight)
            }

            val widthScale = baseDisplayWidth.toFloat() / width.toFloat()
            val heightScale = baseDisplayHeight.toFloat() / height.toFloat()
            val scale = minOf(widthScale, heightScale)
            return DisplaySize(
                width = (width * scale).roundToInt().coerceAtLeast(1),
                height = (height * scale).roundToInt().coerceAtLeast(1)
            )
        }

        private fun zoomedDisplayWidth(): Int {
            return baseDisplayWidth
        }

        private fun previewDecodeWidth(): Int {
            if (isPdfSource) {
                return calculatePdfPreviewWidth(
                    baseDisplayWidth = baseDisplayWidth,
                    minimumWidth = PDF_MIN_PREVIEW_DECODE_WIDTH,
                    maximumWidth = PDF_MAX_PREVIEW_DECODE_WIDTH
                )
            }
            return (baseDisplayWidth / 2).coerceIn(MIN_PREVIEW_DECODE_WIDTH, MAX_PREVIEW_DECODE_WIDTH)
        }

        private fun fullPageDecodeWidth(bounds: ComicArchive.ImageBounds): Int {
            if (isPdfSource) {
                return calculatePdfRenderWidthByPixelBudget(
                    pageWidth = bounds.width,
                    pageHeight = bounds.height,
                    baseDisplayWidth = baseDisplayWidth,
                    renderScale = PDF_FULL_RENDER_SCALE,
                    maxRenderWidth = PDF_MAX_FULL_RENDER_WIDTH,
                    maxRenderPixels = if (doublePageReading) {
                        PDF_DOUBLE_PAGE_FULL_RENDER_PIXELS
                    } else {
                        PDF_MAX_FULL_RENDER_PIXELS
                    }
                )
            }

            val sourceWidth = bounds.width.coerceAtMost(MAX_READER_DECODE_WIDTH).coerceAtLeast(1)
            val minimumWidth = baseDisplayWidth.coerceAtMost(sourceWidth).coerceAtLeast(1)
            return decodeWidth
                .coerceAtMost(sourceWidth)
                .coerceAtLeast(minimumWidth)
        }

        private fun fullPageDecodeHeight(bounds: ComicArchive.ImageBounds): Int {
            if (isPdfSource) {
                return (baseDisplayHeight * PDF_FULL_RENDER_SCALE)
                    .coerceAtLeast(baseDisplayHeight)
                    .coerceAtMost(PDF_MAX_FULL_RENDER_HEIGHT)
            }

            if (!isHorizontalReading()) {
                return MAX_READER_DECODE_HEIGHT
            }

            val sourceHeight = bounds.height.coerceAtMost(MAX_READER_DECODE_HEIGHT).coerceAtLeast(1)
            val minimumHeight = baseDisplayHeight.coerceAtMost(sourceHeight).coerceAtLeast(1)
            return decodeHeight
                .coerceAtMost(sourceHeight)
                .coerceAtLeast(minimumHeight)
        }

        private fun tileDecodeWidth(displayImageWidth: Int, sourceWidth: Int): Int {
            if (isPdfSource) {
                return (displayImageWidth * PDF_TILE_RENDER_SCALE)
                    .coerceAtLeast(displayImageWidth)
                    .coerceAtMost(PDF_MAX_TILE_RENDER_WIDTH)
            }

            return maxOf(
                displayImageWidth,
                decodeWidth,
                sourceWidth.coerceAtMost(MAX_READER_TILE_DECODE_WIDTH)
            )
                .coerceAtMost(MAX_READER_TILE_DECODE_WIDTH)
        }

        private fun shouldPreloadFullPages(isFastScroll: Boolean, isIdle: Boolean): Boolean {
            return shouldPreloadBackgroundFullPagesForReader(isPdfSource, isFastScroll, isIdle)
        }

        private fun isHorizontalReading(): Boolean {
            return readingDirection == AppSettings.READING_DIRECTION_RIGHT_TO_LEFT ||
                readingDirection == AppSettings.READING_DIRECTION_LEFT_TO_RIGHT
        }

        private fun previewBitmap(position: Int): Bitmap? {
            return synchronized(cacheLock) {
                previewBitmapCache.get(position)
            }
        }

        private fun removePreviewBitmap(position: Int) {
            synchronized(cacheLock) {
                previewBitmapCache.remove(position)
            }
        }

        private fun fullBitmap(position: Int): Bitmap? {
            return synchronized(cacheLock) {
                fullBitmapCache.get(position)
            }
        }

        private fun tileBitmap(key: TileKey): Bitmap? {
            return synchronized(cacheLock) {
                tileBitmapCache.get(key)
            }
        }

        private fun putPreviewBitmap(position: Int, bitmap: Bitmap) {
            if (closed) {
                bitmap.recycle()
                return
            }
            synchronized(cacheLock) {
                previewBitmapCache.put(position, bitmap)
            }
        }

        private fun putFullBitmap(position: Int, bitmap: Bitmap) {
            if (closed) {
                bitmap.recycle()
                return
            }
            synchronized(cacheLock) {
                fullBitmapCache.put(position, bitmap)
            }
        }

        private fun putTileBitmap(key: TileKey, bitmap: Bitmap) {
            if (closed) {
                bitmap.recycle()
                return
            }
            synchronized(cacheLock) {
                tileBitmapCache.put(key, bitmap)
            }
        }

        private class PageViewHolder(
            val container: ReaderPageZoomLayout,
            val imageView: ImageView,
            val tileContainer: FrameLayout
        ) : RecyclerView.ViewHolder(container) {
            var boundPosition: Int = RecyclerView.NO_POSITION
        }

        private data class PageTile(
            val index: Int,
            val sourceRect: Rect
        )

        private data class TileKey(
            val position: Int,
            val tileIndex: Int,
            val imageWidth: Int,
            val decodeWidth: Int
        )

        private data class DisplaySize(
            val width: Int,
            val height: Int
        )

        private enum class DecodeTaskKind {
            PREVIEW,
            FULL,
            TILE
        }

        private data class PreloadWindow(
            val previewStart: Int,
            val previewEnd: Int,
            val protectedStart: Int,
            val protectedEnd: Int,
            val previewPositions: List<Int>,
            val fullPositions: List<Int>
        ) {
            companion object {
                fun from(
                    firstVisible: Int,
                    lastVisible: Int,
                    lastIndex: Int,
                    scrollDirection: Int,
                    isFastScroll: Boolean,
                    isIdle: Boolean,
                    isPdfSource: Boolean
                ): PreloadWindow {
                    val movingForward = scrollDirection >= 0
                    val fullBefore: Int
                    val fullAfter: Int
                    val previewBefore: Int
                    val previewAfter: Int

                    when {
                        isPdfSource && isIdle -> {
                            fullBefore = PDF_IDLE_FULL_PRELOAD_BEFORE_COUNT
                            fullAfter = PDF_IDLE_FULL_PRELOAD_AFTER_COUNT
                            previewBefore = PDF_IDLE_PREVIEW_PRELOAD_BEFORE_COUNT
                            previewAfter = PDF_IDLE_PREVIEW_PRELOAD_AFTER_COUNT
                        }

                        isPdfSource && isFastScroll && movingForward -> {
                            fullBefore = 0
                            fullAfter = 0
                            previewBefore = PDF_FAST_PREVIEW_PRELOAD_BEFORE_COUNT
                            previewAfter = PDF_FAST_PREVIEW_PRELOAD_FORWARD_COUNT
                        }

                        isPdfSource && isFastScroll -> {
                            fullBefore = 0
                            fullAfter = 0
                            previewBefore = PDF_FAST_PREVIEW_PRELOAD_FORWARD_COUNT
                            previewAfter = PDF_FAST_PREVIEW_PRELOAD_BEFORE_COUNT
                        }

                        isPdfSource && movingForward -> {
                            fullBefore = 0
                            fullAfter = 0
                            previewBefore = PDF_NORMAL_PREVIEW_PRELOAD_BEFORE_COUNT
                            previewAfter = PDF_NORMAL_PREVIEW_PRELOAD_FORWARD_COUNT
                        }

                        isPdfSource -> {
                            fullBefore = 0
                            fullAfter = 0
                            previewBefore = PDF_NORMAL_PREVIEW_PRELOAD_FORWARD_COUNT
                            previewAfter = PDF_NORMAL_PREVIEW_PRELOAD_BEFORE_COUNT
                        }

                        isIdle -> {
                            fullBefore = IDLE_FULL_PRELOAD_BEFORE_COUNT
                            fullAfter = IDLE_FULL_PRELOAD_AFTER_COUNT
                            previewBefore = IDLE_PREVIEW_PRELOAD_BEFORE_COUNT
                            previewAfter = IDLE_PREVIEW_PRELOAD_AFTER_COUNT
                        }

                        isFastScroll && movingForward -> {
                            fullBefore = 0
                            fullAfter = 0
                            previewBefore = FAST_PREVIEW_PRELOAD_BEFORE_COUNT
                            previewAfter = FAST_PREVIEW_PRELOAD_FORWARD_COUNT
                        }

                        isFastScroll -> {
                            fullBefore = 0
                            fullAfter = 0
                            previewBefore = FAST_PREVIEW_PRELOAD_FORWARD_COUNT
                            previewAfter = FAST_PREVIEW_PRELOAD_BEFORE_COUNT
                        }

                        movingForward -> {
                            fullBefore = NORMAL_FULL_PRELOAD_BEFORE_COUNT
                            fullAfter = NORMAL_FULL_PRELOAD_FORWARD_COUNT
                            previewBefore = NORMAL_PREVIEW_PRELOAD_BEFORE_COUNT
                            previewAfter = NORMAL_PREVIEW_PRELOAD_FORWARD_COUNT
                        }

                        else -> {
                            fullBefore = NORMAL_FULL_PRELOAD_FORWARD_COUNT
                            fullAfter = NORMAL_FULL_PRELOAD_BEFORE_COUNT
                            previewBefore = NORMAL_PREVIEW_PRELOAD_FORWARD_COUNT
                            previewAfter = NORMAL_PREVIEW_PRELOAD_BEFORE_COUNT
                        }
                    }

                    val fullStart = (firstVisible - fullBefore).coerceAtLeast(0)
                    val fullEnd = (lastVisible + fullAfter).coerceAtMost(lastIndex)
                    val previewStart = (firstVisible - previewBefore).coerceAtLeast(0)
                    val previewEnd = (lastVisible + previewAfter).coerceAtMost(lastIndex)
                    val cacheProtectedBefore = if (isPdfSource) {
                        PDF_CACHE_PROTECTED_BEFORE_COUNT
                    } else {
                        CACHE_PROTECTED_BEFORE_COUNT
                    }
                    val cacheProtectedAfter = if (isPdfSource) {
                        PDF_CACHE_PROTECTED_AFTER_COUNT
                    } else {
                        CACHE_PROTECTED_AFTER_COUNT
                    }
                    val protectedStart = (firstVisible - cacheProtectedBefore).coerceAtLeast(0)
                    val protectedEnd = (lastVisible + cacheProtectedAfter).coerceAtMost(lastIndex)

                    val previewPositions = orderedPreloadPositions(
                        firstVisible = firstVisible,
                        lastVisible = lastVisible,
                        start = previewStart,
                        end = previewEnd,
                        movingForward = movingForward
                    )
                    val fullPositions = orderedPreloadPositions(
                        firstVisible = firstVisible,
                        lastVisible = lastVisible,
                        start = fullStart,
                        end = fullEnd,
                        movingForward = movingForward
                    )

                    return PreloadWindow(
                        previewStart = previewStart,
                        previewEnd = previewEnd,
                        protectedStart = protectedStart,
                        protectedEnd = protectedEnd,
                        previewPositions = previewPositions,
                        fullPositions = fullPositions
                    )
                }

                private fun orderedPreloadPositions(
                    firstVisible: Int,
                    lastVisible: Int,
                    start: Int,
                    end: Int,
                    movingForward: Boolean
                ): List<Int> {
                    if (end < start) {
                        return emptyList()
                    }

                    val forwardPositions = if (lastVisible + 1 <= end) {
                        (lastVisible + 1..end).toList()
                    } else {
                        emptyList()
                    }
                    val backwardPositions = if (firstVisible - 1 >= start) {
                        (firstVisible - 1 downTo start).toList()
                    } else {
                        emptyList()
                    }

                    return if (movingForward) {
                        forwardPositions + backwardPositions
                    } else {
                        backwardPositions + forwardPositions
                    }
                }
            }
        }

        private class DecodeTask(
            private val priority: Int,
            val position: Int,
            val generation: Int,
            val kind: DecodeTaskKind,
            val cancelable: Boolean,
            val tileKey: TileKey?,
            private val sequence: Long,
            private val block: () -> Unit
        ) : Runnable, Comparable<DecodeTask> {

            @Volatile
            var cancelled = false

            override fun run() {
                if (cancelled) {
                    return
                }
                try {
                    block()
                } catch (throwable: Throwable) {
                    if (throwable is OutOfMemoryError) {
                        Log.w(READER_ADAPTER_LOG_TAG, "decode task OOM kind=$kind position=$position")
                        System.gc()
                    } else {
                        Log.w(
                            READER_ADAPTER_LOG_TAG,
                            "decode task failed kind=$kind position=$position",
                            throwable
                        )
                    }
                }
            }

            override fun compareTo(other: DecodeTask): Int {
                val priorityComparison = other.priority.compareTo(priority)
                if (priorityComparison != 0) {
                    return priorityComparison
                }
                return sequence.compareTo(other.sequence)
            }
        }

        companion object {
            private const val READER_DECODE_THREAD_COUNT = 1
            private const val READER_CLOSE_WAIT_LOG_MS = 3_000L
            private const val ESTIMATED_READER_PAGE_HEIGHT_RATIO = 1.45f
            private const val MIN_READER_PAGE_HEIGHT = 320
            private const val PLACEHOLDER_COLOR = 0xFF101010.toInt()
            private const val MIN_PREVIEW_DECODE_WIDTH = 240
            private const val MAX_PREVIEW_DECODE_WIDTH = 720
            private const val PDF_MIN_PREVIEW_DECODE_WIDTH = 360
            private const val PDF_MAX_PREVIEW_DECODE_WIDTH = 1080
            private const val NORMAL_FULL_PRELOAD_BEFORE_COUNT = 1
            private const val NORMAL_FULL_PRELOAD_FORWARD_COUNT = 1
            private const val NORMAL_PREVIEW_PRELOAD_BEFORE_COUNT = 1
            private const val NORMAL_PREVIEW_PRELOAD_FORWARD_COUNT = 5
            private const val FAST_PREVIEW_PRELOAD_BEFORE_COUNT = 1
            private const val FAST_PREVIEW_PRELOAD_FORWARD_COUNT = 8
            private const val IDLE_FULL_PRELOAD_BEFORE_COUNT = 1
            private const val IDLE_FULL_PRELOAD_AFTER_COUNT = 1
            private const val IDLE_PREVIEW_PRELOAD_BEFORE_COUNT = 2
            private const val IDLE_PREVIEW_PRELOAD_AFTER_COUNT = 4
            private const val CACHE_PROTECTED_BEFORE_COUNT = 1
            private const val CACHE_PROTECTED_AFTER_COUNT = 3
            private const val PDF_NORMAL_PREVIEW_PRELOAD_BEFORE_COUNT = 1
            private const val PDF_NORMAL_PREVIEW_PRELOAD_FORWARD_COUNT = 3
            private const val PDF_FAST_PREVIEW_PRELOAD_BEFORE_COUNT = 1
            private const val PDF_FAST_PREVIEW_PRELOAD_FORWARD_COUNT = 5
            private const val PDF_IDLE_FULL_PRELOAD_BEFORE_COUNT = 1
            private const val PDF_IDLE_FULL_PRELOAD_AFTER_COUNT = 1
            private const val PDF_IDLE_PREVIEW_PRELOAD_BEFORE_COUNT = 2
            private const val PDF_IDLE_PREVIEW_PRELOAD_AFTER_COUNT = 4
            private const val PDF_CACHE_PROTECTED_BEFORE_COUNT = 1
            private const val PDF_CACHE_PROTECTED_AFTER_COUNT = 2
            private const val FULL_CACHE_TRIM_THRESHOLD_PERCENT = 88
            private const val UI_REFRESH_THROTTLE_MS = 24L
            private const val UI_REFRESH_RETRY_MS = 48L
            private const val PRIORITY_VISIBLE = 100
            private const val PRIORITY_VISIBLE_PREVIEW = 90
            private const val PRIORITY_TILE = 80
            private const val PRIORITY_PRELOAD = 30
            private const val PRIORITY_PRELOAD_PREVIEW = 20
            private const val TILED_SOURCE_HEIGHT_THRESHOLD = 5200
            private const val TILED_DECODED_HEIGHT_THRESHOLD = 5200
            private const val TILED_SOURCE_PIXEL_THRESHOLD = 12_000_000L
            private const val TILED_DECODED_PIXEL_THRESHOLD = 8_000_000L
            private const val TILE_MAX_DISPLAY_HEIGHT = 2400
            private const val TILE_MIN_SOURCE_HEIGHT = 512
            private const val PDF_TILE_MIN_SOURCE_HEIGHT = 128
            private const val TILE_MAX_SOURCE_PIXELS = 4_000_000
            private const val TILE_MAX_DECODED_PIXELS = 4_000_000L
            private const val PDF_FULL_RENDER_SCALE = 2
            private const val PDF_TILE_RENDER_SCALE = 2
            private const val PDF_MAX_FULL_RENDER_WIDTH = 3072
            private const val PDF_MAX_FULL_RENDER_HEIGHT = 4096
            private const val PDF_MAX_TILE_RENDER_WIDTH = 3072
            private const val PDF_MAX_FULL_RENDER_PIXELS = 4_000_000L
            private const val PDF_DOUBLE_PAGE_FULL_RENDER_PIXELS = 2_000_000L
            private const val PDF_TILED_RENDER_PIXEL_THRESHOLD = 8_000_000L
            private const val READER_ADAPTER_LOG_TAG = "ComicLabReader"

            private fun previewBitmapCacheSizeKb(isPdfSource: Boolean): Int {
                val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
                if (isPdfSource) {
                    return (maxMemoryKb / 32)
                        .coerceAtLeast(4 * 1024)
                        .coerceAtMost(12 * 1024)
                }
                return (maxMemoryKb / 48)
                    .coerceAtLeast(4 * 1024)
                    .coerceAtMost(12 * 1024)
            }

            private fun fullBitmapCacheSizeKb(
                isPdfSource: Boolean,
                doublePageReading: Boolean
            ): Int {
                val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
                if (isPdfSource) {
                    return pdfFullBitmapCacheSizeKb(maxMemoryKb, doublePageReading)
                }
                return (maxMemoryKb / 10)
                    .coerceAtLeast(12 * 1024)
                    .coerceAtMost(64 * 1024)
            }

            private fun tileBitmapCacheSizeKb(isPdfSource: Boolean): Int {
                val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
                if (isPdfSource) {
                    return pdfTileBitmapCacheSizeKb(maxMemoryKb)
                }
                return (maxMemoryKb / 10)
                    .coerceAtLeast(8 * 1024)
                    .coerceAtMost(48 * 1024)
            }
        }
    }

    companion object {
        const val EXTRA_ARCHIVE_PATH = "archive_path"
        const val EXTRA_START_FROM_BEGINNING = "start_from_beginning"
        const val EXTRA_START_PAGE_INDEX = "start_page_index"
        const val EXTRA_PREPARED_READER_CACHE_DIR = "prepared_reader_cache_dir"
        const val EXTRA_DEBUG_READER_STRESS = "debug_reader_stress"
        const val EXTRA_DEBUG_READER_STRESS_ITERATIONS = "debug_reader_stress_iterations"
        const val EXTRA_DEBUG_READER_STRESS_DELAY_MS = "debug_reader_stress_delay_ms"
        const val EXTRA_DEBUG_READER_STRESS_TRIM_EVERY = "debug_reader_stress_trim_every"

        private const val READER_PREFS_NAME = "reader_prefs"
        private const val MIN_READER_IMAGE_WIDTH = 320
        private const val HIGH_QUALITY_READER_DECODE_SCALE = 3
        private const val MAX_READER_DECODE_WIDTH = 8192
        private const val MAX_READER_DECODE_HEIGHT = 8192
        private const val MAX_READER_TILE_DECODE_WIDTH = 8192
        private const val READER_PREVIEW_PANEL_ANIMATION_MS = 180L
        private const val SUPPRESS_TAP_AFTER_ZOOM_MS = 250L
        private const val RESTORE_READER_POSITION_MAX_ATTEMPTS = 16
        private const val RESTORE_READER_POSITION_RETRY_MS = 250L
        private const val READER_VIEW_CACHE_SIZE = 2
        private const val READER_GESTURE_EXCLUSION_EDGE_WIDTH_DP = 36f
        private const val FAST_SCROLL_DY_THRESHOLD_PX = 160
        private const val VOLUME_KEY_PAGE_TURN_MIN_INTERVAL_MS = 180L
        private const val DEFAULT_READER_BRIGHTNESS = 128
        private const val MIN_READER_BRIGHTNESS = 1
        private const val MAX_READER_BRIGHTNESS = 255
        private const val MIN_WINDOW_BRIGHTNESS = 0.01f
        private const val MAX_WINDOW_BRIGHTNESS = 1f
        private const val ENABLED_BRIGHTNESS_SLIDER_ALPHA = 1f
        private const val DISABLED_BRIGHTNESS_SLIDER_ALPHA = 0.72f
        private const val SCROLL_DIRECTION_FORWARD = 1
        private const val SCROLL_DIRECTION_BACKWARD = -1
        private const val DEFAULT_DEBUG_READER_STRESS_ITERATIONS = 360
        private const val MAX_DEBUG_READER_STRESS_ITERATIONS = 5_000
        private const val DEFAULT_DEBUG_READER_STRESS_DELAY_MS = 45L
        private const val MIN_DEBUG_READER_STRESS_DELAY_MS = 16L
        private const val MAX_DEBUG_READER_STRESS_DELAY_MS = 1_000L
        private const val DEFAULT_DEBUG_READER_STRESS_TRIM_EVERY = 24
        private const val MAX_DEBUG_READER_STRESS_TRIM_EVERY = 500
        private const val DEBUG_READER_STRESS_PATTERN_SIZE = 8
        private const val DEBUG_READER_STRESS_TARGET_PATTERN_SIZE = 10
        private const val DEBUG_READER_STRESS_START_DELAY_MS = 700L
        private const val DEBUG_READER_STRESS_LOG_EVERY = 30
        private const val DEBUG_READER_STRESS_TAG = "ComicLabReaderStress"
        private const val READER_LOG_TAG = "ComicLabReader"
        private const val NO_EXPLICIT_START_PAGE = -1

        fun hasSavedReadingProgress(context: Context, file: File): Boolean {
            val prefs = context.getSharedPreferences(READER_PREFS_NAME, Context.MODE_PRIVATE)
            val position = prefs.getInt(readerPositionKeyFor(file), 0)
            val offset = prefs.getInt(readerOffsetKeyFor(file), 0)
            return position > 0 || offset != 0
        }

        fun savedReadingPageCount(context: Context, file: File, totalCount: Int): Int {
            if (totalCount <= 0) {
                return 0
            }

            val prefs = context.getSharedPreferences(READER_PREFS_NAME, Context.MODE_PRIVATE)
            val position = prefs.getInt(readerPositionKeyFor(file), 0)
                .coerceIn(0, totalCount - 1)
            val offset = prefs.getInt(readerOffsetKeyFor(file), 0)
            if (position <= 0 && offset == 0) {
                return 0
            }
            return (position + 1).coerceIn(0, totalCount)
        }

        private fun clearSavedReadingProgress(context: Context, file: File) {
            context.getSharedPreferences(READER_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(readerPositionKeyFor(file))
                .remove(readerOffsetKeyFor(file))
                .apply()
        }

        private fun readerPositionKeyFor(file: File): String {
            return "${readerArchiveKeyFor(file)}:position"
        }

        private fun readerOffsetKeyFor(file: File): String {
            return "${readerArchiveKeyFor(file)}:offset"
        }

        private fun readerArchiveKeyFor(file: File): String {
            return "reader:${file.absolutePath}:${file.lastModified()}:${file.length()}"
        }
    }
}

internal fun calculatePdfRenderWidthByPixelBudget(
    pageWidth: Int,
    pageHeight: Int,
    baseDisplayWidth: Int,
    renderScale: Int,
    maxRenderWidth: Int,
    maxRenderPixels: Long
): Int {
    val safePageWidth = pageWidth.coerceAtLeast(1)
    val safePageHeight = pageHeight.coerceAtLeast(1)
    val safeBaseDisplayWidth = baseDisplayWidth.coerceAtLeast(1).toLong()
    val safeRenderScale = renderScale.coerceAtLeast(1).toLong()
    val safeMaxRenderWidth = maxRenderWidth.coerceAtLeast(1)
    val safeMaxRenderPixels = maxRenderPixels.coerceAtLeast(1L)
    val maxWidthByPixels = kotlin.math.sqrt(
        safeMaxRenderPixels.toDouble() * safePageWidth.toDouble() / safePageHeight.toDouble()
    ).toInt().coerceAtLeast(1)
    val preferredWidth = (safeBaseDisplayWidth * safeRenderScale)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()

    return minOf(preferredWidth, safeMaxRenderWidth, maxWidthByPixels)
}

internal fun visiblePreviewPriorityForReader(
    isPdfSource: Boolean,
    previewPriority: Int,
    fullPriority: Int
): Int {
    return if (isPdfSource) {
        maxOf(previewPriority, fullPriority + 1)
    } else {
        previewPriority
    }
}

internal fun shouldScheduleImmediateFullDecodeForReader(
    isPdfSource: Boolean,
    isReaderIdle: Boolean,
    isDiscreteJump: Boolean = false
): Boolean {
    return !isPdfSource || isReaderIdle || isDiscreteJump
}

internal fun shouldReleasePreviewAfterFullDecode(isTiled: Boolean): Boolean {
    return !isTiled
}

internal fun shouldSkipPdfPreloadAfterMemoryTrim(
    isPdfSource: Boolean,
    lowMemoryMode: Boolean,
    isPreload: Boolean
): Boolean {
    return isPdfSource && lowMemoryMode && isPreload
}

internal fun shouldSkipTiledPdfPreviewDecodeAfterMemoryTrim(
    isPdfSource: Boolean,
    lowMemoryMode: Boolean,
    isPreload: Boolean
): Boolean {
    return shouldSkipPdfPreloadAfterMemoryTrim(
        isPdfSource = isPdfSource,
        lowMemoryMode = lowMemoryMode,
        isPreload = isPreload
    )
}

internal fun shouldRetryPdfPageAfterFailure(
    isPdfSource: Boolean,
    isPreload: Boolean,
    retryAlreadyUsed: Boolean
): Boolean {
    return isPdfSource && !isPreload && !retryAlreadyUsed
}

internal fun shouldSkipReaderPreviewDecodeAfterMemoryTrim(lowMemoryMode: Boolean): Boolean {
    return lowMemoryMode
}

internal fun calculatePdfPreviewWidth(
    baseDisplayWidth: Int,
    minimumWidth: Int,
    maximumWidth: Int
): Int {
    val safeMinimumWidth = minimumWidth.coerceAtLeast(1)
    val safeMaximumWidth = maximumWidth.coerceAtLeast(safeMinimumWidth)
    val preferredWidth = (baseDisplayWidth.coerceAtLeast(1).toLong() * 3L / 4L)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
    return preferredWidth.coerceIn(safeMinimumWidth, safeMaximumWidth)
}

internal fun shouldPreloadBackgroundFullPagesForReader(
    isPdfSource: Boolean,
    isFastScroll: Boolean,
    isIdle: Boolean
): Boolean {
    return if (isPdfSource) {
        false
    } else {
        !isFastScroll || isIdle
    }
}

internal fun shouldRefreshBoundReaderPageImmediately(
    isBoundToPosition: Boolean,
    isComputingLayout: Boolean
): Boolean {
    return isBoundToPosition && !isComputingLayout
}

internal fun pdfFullBitmapCacheSizeKb(
    maxMemoryKb: Int,
    doublePageReading: Boolean = false
): Int {
    if (doublePageReading) {
        return (maxMemoryKb / 10)
            .coerceAtLeast(12 * 1024)
            .coerceAtMost(16 * 1024)
    }

    return (maxMemoryKb / 8)
        .coerceAtLeast(16 * 1024)
        .coerceAtMost(24 * 1024)
}

internal fun shouldRetryFullPageAfterFailure(
    isPreload: Boolean,
    alreadyRetried: Boolean
): Boolean {
    return !isPreload && !alreadyRetried
}

internal fun reducedDecodeDimensionForRetry(
    currentDimension: Int,
    minimumDimension: Int
): Int {
    val safeCurrentDimension = currentDimension.coerceAtLeast(1)
    val safeMinimumDimension = minimumDimension.coerceAtLeast(1)
    return if (safeCurrentDimension <= safeMinimumDimension) {
        safeCurrentDimension
    } else {
        maxOf(safeMinimumDimension, safeCurrentDimension / 2)
    }
}

internal fun shouldCancelPreloadAfterOutOfMemory(
    isCancelable: Boolean,
    isVisible: Boolean
): Boolean {
    return isCancelable && !isVisible
}

internal const val READER_PROGRESS_CHECKPOINT_DELAY_MS = 650L

internal fun shouldPersistReaderCheckpointImmediately(
    isIdle: Boolean,
    isDiscreteJump: Boolean,
    isLifecycleEvent: Boolean
): Boolean {
    return isIdle || isDiscreteJump || isLifecycleEvent
}

internal fun isReaderTileVisible(
    tileTop: Int,
    tileBottom: Int,
    viewportTop: Int,
    viewportBottom: Int
): Boolean {
    return tileBottom > tileTop &&
        viewportBottom > viewportTop &&
        tileBottom > viewportTop &&
        tileTop < viewportBottom
}

internal fun shouldUsePdfTiledRendering(
    pageWidth: Int,
    pageHeight: Int,
    displayWidth: Int,
    renderPixelThreshold: Long
): Boolean {
    if (pageWidth <= 0 || pageHeight <= 0 || displayWidth <= 0) {
        return false
    }

    val displayHeight = (displayWidth.toLong() * pageHeight / pageWidth).coerceAtLeast(1L)
    return displayWidth.toLong() * displayHeight >= renderPixelThreshold.coerceAtLeast(1L)
}

internal fun pdfTileBitmapCacheSizeKb(maxMemoryKb: Int): Int {
    return (maxMemoryKb / 16)
        .coerceAtLeast(8 * 1024)
        .coerceAtMost(16 * 1024)
}
