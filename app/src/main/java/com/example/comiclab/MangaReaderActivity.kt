package com.example.comiclab

import com.example.comiclab.core.settings.ComicLabMangaProgress
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.comiclab.core.archive.cache.PreparedReaderCacheLeaseRegistry
import com.example.comiclab.core.archive.cache.PreparedReaderCacheLeaseTask
import com.example.comiclab.core.diagnostics.ComicLabPerformance
import com.example.comiclab.core.diagnostics.CrashLogManager
import com.example.comiclab.core.reader.layout.DoublePageModeUnavailableReason
import com.example.comiclab.core.reader.layout.ReaderPagePositionMapper
import com.example.comiclab.core.reader.layout.doublePageModeUnavailableReason
import com.example.comiclab.core.reader.progress.READER_PROGRESS_CHECKPOINT_DELAY_MS
import com.example.comiclab.core.store.ReaderProgressStore
import com.example.comiclab.core.store.ReadingHistoryStore
import com.example.comiclab.ui.reader.common.ReaderBrightnessController
import com.example.comiclab.ui.reader.common.ReaderControlsController
import com.example.comiclab.ui.reader.manga.ReaderDoublePageSnapHelper
import com.example.comiclab.ui.reader.manga.ReaderPageZoomLayout
import com.example.comiclab.ui.reader.manga.MangaPageAdapter
import com.example.comiclab.ui.reader.manga.MangaReaderProgressController
import com.example.comiclab.ui.reader.manga.MangaReaderViewModel
import com.example.comiclab.ui.reader.manga.ReaderPageAdapterController
import com.example.comiclab.ui.reader.manga.ReaderPreviewAdapter
import com.example.comiclab.ui.reader.manga.READER_CONTINUITY_OVERLAY_MAX_MS
import com.example.comiclab.ui.reader.manga.SubsamplingMangaPageAdapter
import com.example.comiclab.ui.reader.manga.shouldRemoveReaderContinuityOverlay
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun readerPageContentGravity(
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

internal const val MAX_READER_DECODE_WIDTH = 8192
internal const val MAX_READER_DECODE_HEIGHT = 8192
internal const val MAX_READER_TILE_DECODE_WIDTH = 8192

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
    private lateinit var readerBrightnessController: ReaderBrightnessController
    private lateinit var btnReaderPreview: Button
    private lateinit var btnReaderDirectionTopToBottom: ImageButton
    private lateinit var btnReaderDirectionLeftToRight: ImageButton
    private lateinit var btnReaderDirectionRightToLeft: ImageButton
    private lateinit var btnReaderDoublePage: ImageButton
    private lateinit var tvReaderTitle: TextView
    private lateinit var tvReaderProgress: TextView
    private lateinit var tvReaderStatus: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var readerLoadExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var readerProgressExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var readerProgressController: MangaReaderProgressController
    private val readerLoadGeneration = AtomicInteger(0)
    private val preparedReaderViewModel by lazy {
        ViewModelProvider(this)[MangaReaderViewModel::class.java]
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
    private var readerLoadStartedAtNanos: Long? = null
    private var firstReaderPageReadyMetricReported = false
    private var restoreReaderPositionAfterOrientationChange = false
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
    private var stableReaderDisplayHeight = 0
    private var suppressReaderTap = false
    private var isDraggingReaderSlider = false
    private var shouldStartFromBeginning = false
    private var explicitStartPageIndex = NO_EXPLICIT_START_PAGE
    private var pendingRestorePosition: Int? = null
    private var pendingRestoreOffset = 0
    private var restoredReaderPosition: Pair<Int, Int>? = null
    private var readerPositionForRecreation: Pair<Int, Int>? = null
    private var doublePageSpreadAnchorSourcePosition: Int? = null
    private var restoreAttemptCount = 0
    private var restoreRetryScheduled = false
    private var currentReaderPosition = 0
    private var currentReaderOffset = 0
    private var readerScrollDirection = SCROLL_DIRECTION_FORWARD
    private var readerScrollState = RecyclerView.SCROLL_STATE_IDLE
    private var readerContinuitySnapshot: Bitmap? = null
    private var readerContinuityOverlay: ImageView? = null
    private var readerContinuityActivatedAtMs: Long? = null
    private var readerContinuityHasScrolled = false
    private val readerContinuityTimeoutRunnable = Runnable {
        updateReaderContinuityOverlay()
    }
    private var readingDirection = AppSettings.READING_DIRECTION_TOP_TO_BOTTOM
    private var doublePageReadingEnabled = AppSettings.DEFAULT_DOUBLE_PAGE_READING_ENABLED
    private var readerCoverSinglePageEnabled = false
    private var lastReaderPreloadSpreadStart = RecyclerView.NO_POSITION
    private var volumeKeyPageTurnEnabled = true
    private var autoHideSystemBarsEnabled = true
    private var lastVolumePageTurnAt = 0L
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
        readerProgressController = MangaReaderProgressController(this) { readerProgressExecutor }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.decorView.setBackgroundColor(Color.BLACK)
        restoreReaderPositionAfterOrientationChange = savedInstanceState?.let { state ->
            state.containsKey(STATE_READER_ORIENTATION) &&
                state.getInt(STATE_READER_ORIENTATION) != resources.configuration.orientation
        } ?: false
        restoredReaderPosition = savedInstanceState?.let { state ->
            if (state.containsKey(STATE_READER_POSITION)) {
                state.getInt(STATE_READER_POSITION) to state.getInt(STATE_READER_OFFSET)
            } else {
                null
            }
        }
        doublePageSpreadAnchorSourcePosition = savedInstanceState?.let { state ->
            if (state.containsKey(STATE_READER_DOUBLE_PAGE_SPREAD_ANCHOR)) {
                state.getInt(STATE_READER_DOUBLE_PAGE_SPREAD_ANCHOR).takeIf { it >= 0 }
            } else {
                null
            }
        }
        setContentView(R.layout.activity_manga_reader)
        readingDirection = AppSettings.getReadingDirection(this)
        doublePageReadingEnabled = AppSettings.isDoublePageReadingEnabled(this)
        volumeKeyPageTurnEnabled = AppSettings.isVolumeKeyPageTurnEnabled(this)
        autoHideSystemBarsEnabled = AppSettings.isAutoHideSystemBarsEnabled(this)

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
        readerLoadStartedAtNanos = ComicLabPerformance.resolveStartedAtNanos(
            upstreamStartedAtNanos = intent.getLongExtra(
                EXTRA_READER_LOAD_STARTED_AT_NANOS,
                0L
            ),
            fallbackStartedAtNanos = ComicLabPerformance.nowNanos()
        )
        tvReaderTitle.text = file.nameWithoutExtension
        readerSessionMarker = CrashLogManager.recordReaderSessionStarted(this, file)
        val isFreshActivityLaunch = savedInstanceState == null
        shouldStartFromBeginning = isFreshActivityLaunch &&
            intent.getBooleanExtra(EXTRA_START_FROM_BEGINNING, false)
        explicitStartPageIndex = if (isFreshActivityLaunch) {
            intent.getIntExtra(EXTRA_START_PAGE_INDEX, NO_EXPLICIT_START_PAGE)
        } else {
            NO_EXPLICIT_START_PAGE
        }
        configureDebugReaderStress()
        if (shouldStartFromBeginning) {
            readerProgressController.clear(file)
        }

        val preparedCachePath = intent.getStringExtra(EXTRA_PREPARED_READER_CACHE_DIR)
        val preparedCacheDir = preparedCachePath?.let(::File)
        if (preparedCacheDir != null &&
            preparedReaderViewModel.acquireReaderOwner(preparedCacheDir) &&
            preparedCacheDir.isDirectory
        ) {
            loadPreparedArchive(file, preparedCacheDir)
        } else {
            if (preparedCacheDir != null) {
                preparedReaderViewModel.discardCache(preparedCacheDir)
            }
            loadArchive(file)
        }
        showReaderControlsTemporarily()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_READER_ORIENTATION, resources.configuration.orientation)
        if (::readerLayoutManager.isInitialized && imageEntries.isNotEmpty()) {
            val (position, offset) = readerPositionForRecreation
                ?: captureReaderPositionForRecreation()
            outState.putInt(STATE_READER_POSITION, position)
            outState.putInt(STATE_READER_OFFSET, offset)
        }
        doublePageSpreadAnchorSourcePosition?.let {
            outState.putInt(STATE_READER_DOUBLE_PAGE_SPREAD_ANCHOR, it)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        doublePageReadingEnabled = AppSettings.isDoublePageReadingEnabled(this)
        volumeKeyPageTurnEnabled = AppSettings.isVolumeKeyPageTurnEnabled(this)
        autoHideSystemBarsEnabled = AppSettings.isAutoHideSystemBarsEnabled(this)
        updateReaderModeButtons()
        readerControlsController.setAutoHideSystemBarsEnabled(autoHideSystemBarsEnabled)
        updateReaderGestureExclusionRects()
        applyReaderBrightnessSetting()
        updateBrightnessControls()
    }

    override fun onPause() {
        handler.removeCallbacks(readerCheckpointRunnable)
        clearReaderContinuityOverlay()
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
        clearReaderContinuityOverlay()
        readerLoadGeneration.incrementAndGet()
        readerLoadExecutor.shutdownNow().forEach { task ->
            (task as? PreparedReaderCacheLeaseTask)?.cancelBeforeRun()
        }
        readerProgressController.shutdown()
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
        listReaderPages.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            if (stableReaderDisplayHeight <= 0 && bottom > top) {
                stableReaderDisplayHeight = bottom - top
            }
        }
        layoutReaderToolbar = findViewById(R.id.layoutReaderToolbar)
        layoutReaderProgress = findViewById(R.id.layoutReaderProgress)
        readerPreviewScrim = findViewById(R.id.readerPreviewScrim)
        layoutReaderPreviewPanel = findViewById(R.id.layoutReaderPreviewPanel)
        listReaderPreview = findViewById(R.id.listReaderPreview)
        sliderReaderProgress = findViewById(R.id.sliderReaderProgress)
        sliderScreenBrightness = findViewById(R.id.sliderScreenBrightness)
        checkboxCustomBrightness = findViewById(R.id.checkboxCustomBrightness)
        btnReaderPreview = findViewById(R.id.btnReaderPreview)
        btnReaderDirectionTopToBottom = findViewById(R.id.btnReaderDirectionTopToBottom)
        btnReaderDirectionLeftToRight = findViewById(R.id.btnReaderDirectionLeftToRight)
        btnReaderDirectionRightToLeft = findViewById(R.id.btnReaderDirectionRightToLeft)
        btnReaderDoublePage = findViewById(R.id.btnReaderDoublePage)
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
        updateReaderModeButtons()
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowInsetsControllerCompat(window, rootView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

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

    private fun captureReaderContinuitySnapshot() {
        if (!archiveFile?.extension.equals("pdf", ignoreCase = true) ||
            readerContinuityActivatedAtMs != null ||
            !::rootView.isInitialized ||
            listReaderPages.width <= 0 ||
            listReaderPages.height <= 0
        ) {
            return
        }
        if (readerContinuityOverlay != null) {
            clearReaderContinuityOverlay()
        }

        val root = rootView as? FrameLayout ?: return
        var bitmap: Bitmap? = null
        try {
            val snapshotWidth = (listReaderPages.width / READER_CONTINUITY_SNAPSHOT_SCALE)
                .roundToInt()
                .coerceAtLeast(1)
            val snapshotHeight = (listReaderPages.height / READER_CONTINUITY_SNAPSHOT_SCALE)
                .roundToInt()
                .coerceAtLeast(1)
            bitmap = Bitmap.createBitmap(snapshotWidth, snapshotHeight, Bitmap.Config.RGB_565)
            val canvas = Canvas(bitmap)
            canvas.scale(
                snapshotWidth / listReaderPages.width.toFloat(),
                snapshotHeight / listReaderPages.height.toFloat()
            )
            listReaderPages.draw(canvas)

            val overlay = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                setImageBitmap(bitmap)
                visibility = View.GONE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            root.addView(
                overlay,
                1.coerceAtMost(root.childCount),
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            readerContinuitySnapshot = bitmap
            readerContinuityOverlay = overlay
            readerContinuityHasScrolled = false
        } catch (failure: Throwable) {
            bitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
            Log.w(READER_LOG_TAG, "reader continuity snapshot capture failed", failure)
        }
    }

    private fun activateReaderContinuityOverlay() {
        if (!archiveFile?.extension.equals("pdf", ignoreCase = true)) {
            return
        }
        if (readerContinuityOverlay == null) {
            captureReaderContinuitySnapshot()
        }
        val overlay = readerContinuityOverlay ?: return
        if (readerContinuityActivatedAtMs != null) {
            return
        }

        readerContinuityActivatedAtMs = SystemClock.elapsedRealtime()
        if (BuildConfig.DEBUG) {
            Log.d(READER_LOG_TAG, "continuity event=activate atMs=$readerContinuityActivatedAtMs")
        }
        overlay.visibility = View.VISIBLE
        handler.removeCallbacks(readerContinuityTimeoutRunnable)
        handler.postDelayed(
            readerContinuityTimeoutRunnable,
            READER_CONTINUITY_OVERLAY_MAX_MS
        )
        updateReaderContinuityOverlay()
    }

    private fun scheduleReaderContinuityOverlayUpdate() {
        if (readerContinuityActivatedAtMs != null) {
            handler.post { updateReaderContinuityOverlay() }
        }
    }

    private fun updateReaderContinuityOverlay() {
        val activatedAt = readerContinuityActivatedAtMs ?: return
        val elapsedMs = (SystemClock.elapsedRealtime() - activatedAt).coerceAtLeast(0L)
        val visiblePagesReady = pageAdapter?.allVisiblePagesHaveRenderableContent(listReaderPages)
            ?: false
        if (shouldRemoveReaderContinuityOverlay(
                hasScrolled = readerContinuityHasScrolled,
                visiblePagesReady = visiblePagesReady,
                elapsedMs = elapsedMs
            )
        ) {
            if (BuildConfig.DEBUG) {
                Log.d(READER_LOG_TAG, "continuity event=remove elapsedMs=$elapsedMs ready=$visiblePagesReady")
            }
            clearReaderContinuityOverlay()
        }
    }

    private fun clearReaderContinuityOverlay() {
        handler.removeCallbacks(readerContinuityTimeoutRunnable)
        readerContinuityActivatedAtMs = null
        readerContinuityHasScrolled = false
        readerContinuityOverlay?.let { overlay ->
            (overlay.parent as? FrameLayout)?.removeView(overlay)
            overlay.setImageDrawable(null)
        }
        readerContinuityOverlay = null
        readerContinuitySnapshot?.takeUnless(Bitmap::isRecycled)?.recycle()
        readerContinuitySnapshot = null
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
                    captureReaderContinuitySnapshot()
                }

                if (newState == RecyclerView.SCROLL_STATE_SETTLING) {
                    activateReaderContinuityOverlay()
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
                    if (readerContinuityActivatedAtMs == null) {
                        clearReaderContinuityOverlay()
                    } else {
                        updateReaderContinuityOverlay()
                    }
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
                    if (readerContinuityOverlay != null) {
                        readerContinuityHasScrolled = true
                    }
                }
                val fastThresholdBase = if (isHorizontalReading()) recyclerView.width else recyclerView.height
                val fastThreshold = (fastThresholdBase / 4).coerceAtLeast(FAST_SCROLL_DY_THRESHOLD_PX)
                val isFastScroll = readerScrollState == RecyclerView.SCROLL_STATE_SETTLING ||
                    abs(primaryDelta) >= fastThreshold
                if (isFastScroll) {
                    activateReaderContinuityOverlay()
                }
                preloadReaderAround(
                    firstVisiblePosition = readerSpreadStartLayoutPosition(firstVisibleItem),
                    visibleItemCount = visibleItemCount,
                    scrollDirection = direction,
                    isFastScroll = isFastScroll,
                    isIdle = false
                )
                updateReaderContinuityOverlay()
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

        btnReaderDirectionTopToBottom.setOnClickListener {
            changeReadingDirection(AppSettings.READING_DIRECTION_TOP_TO_BOTTOM)
        }

        btnReaderDirectionLeftToRight.setOnClickListener {
            changeReadingDirection(AppSettings.READING_DIRECTION_LEFT_TO_RIGHT)
        }

        btnReaderDirectionRightToLeft.setOnClickListener {
            changeReadingDirection(AppSettings.READING_DIRECTION_RIGHT_TO_LEFT)
        }

        btnReaderDoublePage.setOnClickListener {
            toggleDoublePageReading()
        }

        readerPreviewScrim.setOnClickListener {
            hideReaderPreviewPanel()
        }
    }

    private fun configureBrightnessControls() {
        readerBrightnessController = ReaderBrightnessController(
            context = applicationContext,
            window = window,
            slider = sliderScreenBrightness,
            checkbox = checkboxCustomBrightness,
            forceSliderVisible = true,
            onTrackingStarted = readerControlsController::cancelAutoHide,
            onTrackingStopped = readerControlsController::showTemporarily,
            onBrightnessChanged = readerControlsController::showTemporarily
        )
        readerBrightnessController.configure()
    }

    private fun loadArchive(file: File) {
        val generation = readerLoadGeneration.incrementAndGet()
        firstReaderPageReadyMetricReported = false
        tvReaderStatus.text = getString(R.string.loading_reading)
        tvReaderStatus.visibility = View.VISIBLE

        executeReaderLoadTask {
            val indexStartedAtNanos = ComicLabPerformance.nowNanos()
            val entriesResult = runCatching {
                ComicLabPerformance.trace("comiclab.reader.archive_index") {
                    ComicArchive.imageEntries(file)
                }
            }
            val entries = entriesResult.getOrNull().orEmpty()
            ComicLabPerformance.record(
                event = "reader_archive_index",
                startedAtNanos = indexStartedAtNanos,
                fields = "entries=${entries.size} bytes=${file.length()} success=${entriesResult.isSuccess}"
            )
            if (entriesResult.isFailure) {
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
        firstReaderPageReadyMetricReported = false
        val preparedEntriesStartedAtNanos = ComicLabPerformance.nowNanos()
        val entries = runCatching {
            ComicArchive.preparedImageEntries(preparedCacheDir)
        }.getOrDefault(emptyList())
        ComicLabPerformance.record(
            event = "reader_prepared_entries",
            startedAtNanos = preparedEntriesStartedAtNanos,
            fields = "entries=${entries.size}"
        )
        if (entries.isEmpty()) {
            preparedReaderViewModel.discardCache(preparedCacheDir)
            loadArchive(file)
            return
        }

        val loadLease = preparedReaderViewModel.acquireUsageLease(preparedCacheDir)
        if (loadLease == null) {
            preparedReaderViewModel.discardCache(preparedCacheDir)
            loadArchive(file)
            return
        }
        val generation = readerLoadGeneration.incrementAndGet()
        tvReaderStatus.text = getString(R.string.loading_reading)
        tvReaderStatus.visibility = View.VISIBLE
        executeReaderLoadTask(cacheLease = loadLease) {
            val pageFiles = entries.mapIndexed { index, entryName ->
                ComicArchive.preparedImageFile(preparedCacheDir, index, entryName)
            }
            val hasPreparedPage = pageFiles.any { it.isFile && it.length() > 0L }
            val boundsByPosition = pageFiles.mapIndexedNotNull { index, imageFile ->
                if (imageFile.isFile && imageFile.length() > 0L) {
                    ComicArchive.imageFileBounds(imageFile)?.let { index to it }
                } else {
                    null
                }
            }.toMap()

            if (!preparedCacheDir.isDirectory || !hasPreparedPage) {
                runOnUiThread {
                    if (isReaderActive() && generation == readerLoadGeneration.get()) {
                        preparedReaderViewModel.discardCache(preparedCacheDir)
                        loadArchive(file)
                    }
                }
                return@executeReaderLoadTask
            }

            runOnUiThread {
                if (!isReaderActive() || generation != readerLoadGeneration.get()) {
                    return@runOnUiThread
                }

                val adapterLease = preparedReaderViewModel.acquireUsageLease(preparedCacheDir)
                if (adapterLease == null) {
                    preparedReaderViewModel.discardCache(preparedCacheDir)
                    loadArchive(file)
                    return@runOnUiThread
                }
                imageEntries = entries
                ReadingHistoryStore.record(this, file)
                tvReaderStatus.visibility = View.GONE
                bindPreparedReaderAdapter(
                    entries,
                    pageFiles,
                    boundsByPosition,
                    preparedCacheDir,
                    file,
                    adapterLease
                )
            }
        }
    }

    private fun bindArchiveReaderAdapter(file: File, entries: List<String>) {
        val sessionOpenStartedAtNanos = ComicLabPerformance.nowNanos()
        val sessionResult = runCatching {
            ComicLabPerformance.trace("comiclab.reader.session_open") {
                ComicArchive.openReaderSession(file, cacheDir)
            }
        }
        ComicLabPerformance.record(
            event = "reader_session_open",
            startedAtNanos = sessionOpenStartedAtNanos,
            fields = "format=${file.extension.lowercase()} success=${sessionResult.isSuccess}"
        )
        val session = sessionResult.getOrElse {
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
        archiveFile: File,
        cacheUseLease: PreparedReaderCacheLeaseRegistry.Lease
    ) {
        val displayWidth = readerPageDisplayWidth()
        val displayHeight = readerPageDisplayHeight()

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
            cacheUseLease = cacheUseLease,
            baseDisplayWidth = displayWidth,
            baseDisplayHeight = displayHeight,
            readingDirection = readingDirection,
            doublePageReading = isDoublePageReading(),
            coverSinglePage = readerCoverSinglePageEnabled,
            spreadAnchorSourcePosition = doublePageSpreadAnchorSourcePosition,
            onPageReady = { position ->
                recordInitialReaderPageReady(position, prepared = true)
                if (isPendingReaderPosition(position)) {
                    applyPendingReaderPosition()
                }
                scheduleReaderContinuityOverlayUpdate()
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
        val displayHeight = readerPageDisplayHeight()
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
            spreadAnchorSourcePosition = doublePageSpreadAnchorSourcePosition,
            onPageReady = { position ->
                recordInitialReaderPageReady(position, prepared = false)
                if (isPendingReaderPosition(position)) {
                    applyPendingReaderPosition()
                }
                scheduleReaderContinuityOverlayUpdate()
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

    private fun recordInitialReaderPageReady(position: Int, prepared: Boolean) {
        if (firstReaderPageReadyMetricReported || position != currentReaderPosition) {
            return
        }

        firstReaderPageReadyMetricReported = true
        ComicLabPerformance.record(
            event = "reader_initial_page_ready",
            startedAtNanos = readerLoadStartedAtNanos,
            fields = "position=$position prepared=$prepared"
        )
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
                isIdle = archiveFile?.extension.equals("pdf", ignoreCase = true),
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

    // 测试接口：仅供单元测试注入执行器、读取阅读状态并触发保存/跳页，不参与运行时逻辑。
    internal var readerLoadExecutorForTest: ExecutorService
        get() = readerLoadExecutor
        set(value) {
            readerLoadExecutor = value
        }

    internal var readerProgressExecutorForTest: ExecutorService
        get() = readerProgressExecutor
        set(value) {
            readerProgressExecutor = value
        }

    internal val preparedReaderViewModelForTest: MangaReaderViewModel
        get() = preparedReaderViewModel

    internal val imageEntriesForTest: List<String>
        get() = imageEntries

    internal val currentReaderPositionForTest: Int
        get() = currentReaderPosition

    internal fun setPendingReaderProgressForTest(position: Int, offset: Int) {
        pendingRestorePosition = position
        pendingRestoreOffset = offset
    }

    internal fun saveReaderPositionForTest(forceCommit: Boolean) {
        saveReaderPosition(forceCommit)
    }

    internal val listReaderPagesForTest: ZoomableReaderRecyclerView
        get() = listReaderPages

    internal val readerLayoutManagerForTest: LinearLayoutManager
        get() = readerLayoutManager

    internal val currentReaderOffsetForTest: Int
        get() = currentReaderOffset

    internal val pendingRestorePositionForTest: Int?
        get() = pendingRestorePosition

    internal val pendingRestoreOffsetForTest: Int
        get() = pendingRestoreOffset

    internal val restoreAttemptCountForTest: Int
        get() = restoreAttemptCount

    internal fun readerPagePositionMapperForTest(sourceItemCount: Int): ReaderPagePositionMapper =
        readerPagePositionMapper(sourceItemCount)

    internal val tvReaderStatusForTest: TextView
        get() = tvReaderStatus

    internal val firstReaderPageReadyMetricReportedForTest: Boolean
        get() = firstReaderPageReadyMetricReported

    internal val pageAdapterForTest: ReaderPageAdapterController?
        get() = pageAdapter

    internal val debugReaderStressStepForTest: Int
        get() = debugReaderStressStep

    internal fun readerSpreadStartSourcePositionForTest(pagePosition: Int): Int =
        readerSpreadStartSourcePosition(pagePosition)

    internal fun jumpReaderToPageForTest(position: Int) {
        jumpReaderToPage(position)
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

        readerProgressController.save(
            file = file,
            position = positionToSave,
            offset = offsetToSave,
            forceCommit = forceCommit
        )
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
        val (savedPosition, savedOffset) = restoredReaderPosition
            ?.also { restoredReaderPosition = null }
            ?: readerProgressController.readPositionAndOffset(file)
        val position = savedPosition.coerceIn(0, imageEntries.lastIndex.coerceAtLeast(0))
        val offset = if (isHorizontalReading() || restoreReaderPositionAfterOrientationChange) {
            0
        } else {
            savedOffset
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
            return mapper.displayedSourcePositionForAdapterPosition(
                adapterPosition = firstVisiblePosition,
                fallbackSourcePosition = firstVisiblePosition.coerceIn(0, totalItemCount - 1)
            )
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
        return doublePageReadingEnabled &&
            doublePageModeUnavailableReason(
                isHorizontalReading = isHorizontalReading(),
                isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            ) == null
    }

    private fun changeReadingDirection(direction: String) {
        if (direction == readingDirection) {
            return
        }
        readerPositionForRecreation = captureReaderPositionForRecreation()
        AppSettings.setReadingDirection(this, direction)
        val directionNameResource = when (direction) {
            AppSettings.READING_DIRECTION_LEFT_TO_RIGHT -> R.string.reading_direction_left_to_right
            AppSettings.READING_DIRECTION_RIGHT_TO_LEFT -> R.string.reading_direction_right_to_left
            else -> R.string.reading_direction_top_to_bottom
        }
        Toast.makeText(
            this,
            getString(R.string.reading_direction_current, getString(directionNameResource)),
            Toast.LENGTH_SHORT
        ).show()
        recreate()
    }

    private fun toggleDoublePageReading() {
        when (
            doublePageModeUnavailableReason(
                isHorizontalReading = isHorizontalReading(),
                isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            )
        ) {
            DoublePageModeUnavailableReason.VERTICAL_READING -> {
                Toast.makeText(
                    this,
                    R.string.double_page_requires_horizontal_reading,
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            DoublePageModeUnavailableReason.LANDSCAPE_REQUIRED -> {
                Toast.makeText(
                    this,
                    R.string.double_page_requires_landscape,
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            null -> Unit
        }

        val positionForRecreation = captureReaderPositionForRecreation()
        readerPositionForRecreation = positionForRecreation
        doublePageSpreadAnchorSourcePosition = if (doublePageReadingEnabled) {
            null
        } else {
            positionForRecreation.first.coerceIn(0, imageEntries.lastIndex.coerceAtLeast(0))
        }
        doublePageReadingEnabled = !doublePageReadingEnabled
        AppSettings.setDoublePageReadingEnabled(this, doublePageReadingEnabled)
        recreate()
    }

    private fun captureReaderPositionForRecreation(): Pair<Int, Int> {
        pendingRestorePosition?.let { pendingPosition ->
            return pendingPosition to pendingRestoreOffset
        }

        if (imageEntries.isNotEmpty() && ::readerLayoutManager.isInitialized) {
            val firstVisible = readerLayoutManager.findFirstVisibleItemPosition()
            if (firstVisible != RecyclerView.NO_POSITION) {
                updateCurrentReaderPosition(firstVisible)
            }
        }
        return currentReaderPosition to currentReaderOffset
    }

    private fun updateReaderModeButtons() {
        if (
            !::btnReaderDirectionTopToBottom.isInitialized ||
            !::btnReaderDirectionLeftToRight.isInitialized ||
            !::btnReaderDirectionRightToLeft.isInitialized ||
            !::btnReaderDoublePage.isInitialized
        ) {
            return
        }

        btnReaderDirectionTopToBottom.isSelected =
            readingDirection == AppSettings.READING_DIRECTION_TOP_TO_BOTTOM
        btnReaderDirectionLeftToRight.isSelected =
            readingDirection == AppSettings.READING_DIRECTION_LEFT_TO_RIGHT
        btnReaderDirectionRightToLeft.isSelected =
            readingDirection == AppSettings.READING_DIRECTION_RIGHT_TO_LEFT

        val unavailableReason = doublePageModeUnavailableReason(
            isHorizontalReading = isHorizontalReading(),
            isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        )
        val isEnabledForCurrentLayout = doublePageReadingEnabled && unavailableReason == null
        btnReaderDoublePage.setImageResource(R.drawable.png_reader_double_page_mode)
        btnReaderDoublePage.isSelected = isEnabledForCurrentLayout
        btnReaderDoublePage.alpha = if (unavailableReason == null) 1f else 0.5f
        btnReaderDoublePage.contentDescription = getString(
            if (isEnabledForCurrentLayout) {
                R.string.double_page_reading_enabled
            } else {
                R.string.double_page_reading_disabled
            }
        )
    }

    private fun readerPagePositionMapper(sourceItemCount: Int = imageEntries.size): ReaderPagePositionMapper {
        return ReaderPagePositionMapper(
            sourceItemCount = sourceItemCount,
            doublePageReading = isDoublePageReading(),
            coverSinglePage = readerCoverSinglePageEnabled,
            spreadAnchorSourcePosition = doublePageSpreadAnchorSourcePosition
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

    private fun readerPageDisplayHeight(): Int {
        if (stableReaderDisplayHeight <= 0) {
            stableReaderDisplayHeight = listReaderPages.height
                .takeIf { it > 0 }
                ?: 0
        }
        return stableReaderDisplayHeight
            .takeIf { it > 0 }
            ?: resources.displayMetrics.heightPixels.coerceAtLeast(MIN_READER_IMAGE_WIDTH)
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
        executeReaderLoadTask(cacheLease = null, block = block)
    }

    private fun executeReaderLoadTask(
        cacheLease: PreparedReaderCacheLeaseRegistry.Lease?,
        block: () -> Unit
    ) {
        if (destroyed) {
            cacheLease?.close()
            return
        }

        if (cacheLease != null) {
            val task = PreparedReaderCacheLeaseTask(
                lease = cacheLease,
                shouldRun = { !destroyed }
            ) {
                runReaderLoadBlock(block)
            }
            try {
                readerLoadExecutor.execute(task)
            } catch (throwable: Throwable) {
                task.cancelBeforeRun()
                Log.e(READER_LOG_TAG, "reader load task was rejected", throwable)
            }
            return
        }

        try {
            readerLoadExecutor.execute {
                if (!destroyed) {
                    runReaderLoadBlock(block)
                }
            }
        } catch (throwable: Throwable) {
            Log.e(READER_LOG_TAG, "reader load task was rejected", throwable)
        }
    }

    private fun runReaderLoadBlock(block: () -> Unit) {
        try {
            block()
        } catch (throwable: Throwable) {
            Log.e(READER_LOG_TAG, "reader load task failed", throwable)
        }
    }

    private fun isReaderActive(): Boolean {
        return !destroyed && !isFinishing && !isDestroyed
    }

    private fun updateBrightnessControls() {
        if (::readerBrightnessController.isInitialized) {
            readerBrightnessController.updateControls()
        }
    }

    private fun applyReaderBrightnessSetting() {
        readerBrightnessController.applyReaderBrightnessSetting()
    }

    private fun restoreSystemBrightness() {
        readerBrightnessController.restoreSystemBrightness()
    }

    companion object {
        const val EXTRA_ARCHIVE_PATH = "archive_path"
        const val EXTRA_START_FROM_BEGINNING = "start_from_beginning"
        const val EXTRA_START_PAGE_INDEX = "start_page_index"
        const val EXTRA_PREPARED_READER_CACHE_DIR = "prepared_reader_cache_dir"
        const val EXTRA_READER_LOAD_STARTED_AT_NANOS = "reader_load_started_at_nanos"
        const val EXTRA_DEBUG_READER_STRESS = "debug_reader_stress"
        const val EXTRA_DEBUG_READER_STRESS_ITERATIONS = "debug_reader_stress_iterations"
        const val EXTRA_DEBUG_READER_STRESS_DELAY_MS = "debug_reader_stress_delay_ms"
        const val EXTRA_DEBUG_READER_STRESS_TRIM_EVERY = "debug_reader_stress_trim_every"

        private const val MIN_READER_IMAGE_WIDTH = 320
        private const val HIGH_QUALITY_READER_DECODE_SCALE = 3
        private const val READER_PREVIEW_PANEL_ANIMATION_MS = 180L
        private const val SUPPRESS_TAP_AFTER_ZOOM_MS = 250L
        private const val RESTORE_READER_POSITION_MAX_ATTEMPTS = 16
        private const val RESTORE_READER_POSITION_RETRY_MS = 250L
        private const val READER_VIEW_CACHE_SIZE = 2
        private const val READER_GESTURE_EXCLUSION_EDGE_WIDTH_DP = 36f
        private const val FAST_SCROLL_DY_THRESHOLD_PX = 160
        private const val READER_CONTINUITY_SNAPSHOT_SCALE = 0.5f
        private const val VOLUME_KEY_PAGE_TURN_MIN_INTERVAL_MS = 180L
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
        private const val STATE_READER_ORIENTATION = "reader_orientation"
        private const val STATE_READER_POSITION = "reader_position"
        private const val STATE_READER_OFFSET = "reader_offset"
        private const val STATE_READER_DOUBLE_PAGE_SPREAD_ANCHOR = "reader_double_page_spread_anchor"
        private const val NO_EXPLICIT_START_PAGE = -1

        fun savedReadingProgress(context: Context, file: File): ComicLabMangaProgress? {
            return ReaderProgressStore.savedReadingProgress(context, file)
        }

        fun restoreReadingProgress(
            context: Context,
            file: File,
            progress: ComicLabMangaProgress
        ) {
            ReaderProgressStore.restoreReadingProgress(context, file, progress)
        }

        fun hasSavedReadingProgress(context: Context, file: File): Boolean {
            return ReaderProgressStore.hasSavedReadingProgress(context, file)
        }

        fun savedReadingPageCount(context: Context, file: File, totalCount: Int): Int {
            return ReaderProgressStore.savedReadingPageCount(context, file, totalCount)
        }

    }
}
