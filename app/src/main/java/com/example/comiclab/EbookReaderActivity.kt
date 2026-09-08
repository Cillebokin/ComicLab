package com.example.comiclab

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.comiclab.ebook.EbookProgress
import com.example.comiclab.ebook.EbookProgressStore
import com.example.comiclab.ebook.EbookReaderProgressMapper
import com.example.comiclab.ebook.EbookSession
import com.example.comiclab.ebook.EbookSessionFactory
import com.example.comiclab.ebook.html.EbookHtmlRenderer
import com.example.comiclab.ebook.html.EbookStyle
import com.example.comiclab.ebook.epub.EpubParseError
import com.example.comiclab.ebook.epub.EpubParseException
import com.example.comiclab.ebook.mobi.MobiParseException
import com.example.comiclab.ebook.mobi.MobiParseError
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.Executors

class EbookReaderActivity : AppCompatActivity() {

    private lateinit var rootView: View
    private lateinit var layoutEbookToolbar: View
    private lateinit var layoutEbookReaderProgress: View
    private lateinit var tvTitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var webReader: WebView
    private lateinit var btnContents: Button
    private lateinit var sliderReaderProgress: SeekBar
    private lateinit var tvReaderProgress: TextView
    private lateinit var sliderScreenBrightness: SeekBar
    private lateinit var checkboxCustomBrightness: CheckBox

    private val loadExecutor = Executors.newSingleThreadExecutor()
    private val htmlRenderer = EbookHtmlRenderer()
    @Volatile
    private var loadGeneration = 0
    @Volatile
    private var session: EbookSession? = null
    private var bookFile: File? = null
    private val style = EbookStyle()
    private var currentProgress: EbookProgress? = null
    private var pageReady = false
    private var lastProgressSavedAt = 0L
    private var isUpdatingProgressControls = false
    private var isDraggingReaderProgress = false
    private var isUpdatingBrightnessControls = false
    private var customReaderBrightnessEnabled = false
    private lateinit var readerControlsController: ReaderControlsController
    private val readerControlsVisible: Boolean
        get() = if (::readerControlsController.isInitialized) {
            readerControlsController.isVisible
        } else {
            true
        }
    private var autoHideSystemBarsEnabled = true

    @Volatile
    private var destroyed = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ebook_reader)
        autoHideSystemBarsEnabled = AppSettings.isAutoHideSystemBarsEnabled(this)

        rootView = findViewById(R.id.main)
        layoutEbookToolbar = findViewById(R.id.layoutEbookToolbar)
        layoutEbookReaderProgress = findViewById(R.id.layoutEbookReaderProgress)
        tvTitle = findViewById(R.id.tvEbookReaderTitle)
        tvStatus = findViewById(R.id.tvEbookReaderStatus)
        webReader = findViewById(R.id.webEbookReader)
        btnContents = findViewById(R.id.btnEbookContents)
        sliderReaderProgress = findViewById(R.id.sliderEbookReaderProgress)
        tvReaderProgress = findViewById(R.id.tvEbookReaderProgress)
        sliderScreenBrightness = findViewById(R.id.sliderEbookScreenBrightness)
        checkboxCustomBrightness = findViewById(R.id.checkboxEbookCustomBrightness)

        readerControlsController = ReaderControlsController(
            window = window,
            rootView = rootView,
            toolbar = layoutEbookToolbar,
            progressPanel = layoutEbookReaderProgress,
            onControlsShown = { updateBrightnessControls() }
        )
        readerControlsController.setAutoHideSystemBarsEnabled(autoHideSystemBarsEnabled)

        configureImmersiveSystemBars()
        configureWebView()
        configureActions()
        configureProgressControls()
        configureBrightnessControls()

        val file = intent.getStringExtra(EXTRA_BOOK_PATH)?.let(::File)
        if (file == null || !file.isFile) {
            showError(getString(R.string.message_invalid_file))
            return
        }

        bookFile = file
        if (intent.getBooleanExtra(EXTRA_START_FROM_BEGINNING, false)) {
            EbookProgressStore.clear(this, file)
            currentProgress = null
        } else {
            currentProgress = EbookProgressStore.load(this, file)
        }
        tvTitle.text = file.nameWithoutExtension
        updateProgressControls(currentProgress)
        loadBook(file)
        showReaderControlsTemporarily()
    }

    override fun onResume() {
        super.onResume()
        autoHideSystemBarsEnabled = AppSettings.isAutoHideSystemBarsEnabled(this)
        readerControlsController.setAutoHideSystemBarsEnabled(autoHideSystemBarsEnabled)
        applyReaderBrightnessSetting()
        updateBrightnessControls()
        if (readerControlsVisible) {
            showReaderControlsTemporarily()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::readerControlsController.isInitialized) {
            readerControlsController.onWindowFocusChanged(hasFocus)
        }
    }

    override fun onPause() {
        readerControlsController.cancelAutoHide()
        saveCurrentProgress(force = true)
        restoreSystemBrightness()
        super.onPause()
    }

    override fun onStop() {
        saveCurrentProgress(force = true)
        super.onStop()
    }

    override fun onDestroy() {
        destroyed = true
        loadGeneration++
        readerControlsController.close()
        webReader.removeJavascriptInterface(JS_BRIDGE_NAME)
        webReader.stopLoading()
        webReader.destroy()
        session?.close()
        session = null
        restoreSystemBrightness()
        loadExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun configureActions() {
        val tapDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean {
                    return true
                }

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    handleReaderTap()
                    return true
                }
            }
        )
        webReader.setOnTouchListener { _, event ->
            tapDetector.onTouchEvent(event)
            false
        }
        btnContents.setOnClickListener {
            showReaderControlsTemporarily()
            showContents()
        }
    }

    private fun configureImmersiveSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowInsetsControllerCompat(window, rootView).systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        val toolbarInitialMarginTop =
            (layoutEbookToolbar.layoutParams as FrameLayout.LayoutParams).topMargin
        val progressInitialMarginBottom =
            (layoutEbookReaderProgress.layoutParams as FrameLayout.LayoutParams).bottomMargin
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (layoutEbookToolbar.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = toolbarInitialMarginTop + systemBars.top
                layoutEbookToolbar.layoutParams = this
            }
            (layoutEbookReaderProgress.layoutParams as FrameLayout.LayoutParams).apply {
                bottomMargin = progressInitialMarginBottom + systemBars.bottom
                layoutEbookReaderProgress.layoutParams = this
            }
            insets
        }
        ViewCompat.requestApplyInsets(rootView)
    }

    private fun handleReaderTap() {
        if (readerControlsVisible) {
            setReaderControlsVisible(false)
        } else {
            showReaderControlsTemporarily()
        }
    }

    private fun showReaderControlsTemporarily() {
        readerControlsController.showTemporarily()
    }

    private fun setReaderControlsVisible(visible: Boolean) {
        readerControlsController.setVisible(visible)
    }

    private fun configureProgressControls() {
        sliderReaderProgress.max = EbookReaderProgressMapper.SLIDER_MAX
        sliderReaderProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && !isUpdatingProgressControls) {
                    updateProgressText(
                        EbookReaderProgressMapper.fromSlider(progress, session?.book?.chapters?.size ?: 0)
                    )
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                isDraggingReaderProgress = true
                readerControlsController.cancelAutoHide()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                isDraggingReaderProgress = false
                val chapterCount = session?.book?.chapters?.size ?: return
                val target = EbookReaderProgressMapper.fromSlider(seekBar.progress, chapterCount)
                currentProgress = target
                updateProgressControls(target)
                if (pageReady) {
                    webReader.evaluateJavascript(
                        "javascript:window.restoreComicLabProgress(${target.chapterIndex},${target.scrollFraction});",
                        null
                    )
                }
                saveCurrentProgress(force = true)
                showReaderControlsTemporarily()
            }
        })
        updateProgressControls(currentProgress)
    }

    private fun updateProgressControls(progress: EbookProgress?) {
        if (!::sliderReaderProgress.isInitialized || !::tvReaderProgress.isInitialized) {
            return
        }

        val chapterCount = session?.book?.chapters?.size ?: 0
        val safeProgress = if (chapterCount > 0) {
            EbookReaderProgressMapper.clampToBook(progress ?: EbookProgress(0, 0f), chapterCount)
        } else {
            EbookProgress(0, 0f)
        }

        isUpdatingProgressControls = true
        sliderReaderProgress.max = EbookReaderProgressMapper.SLIDER_MAX
        sliderReaderProgress.isEnabled = chapterCount > 0
        sliderReaderProgress.progress = EbookReaderProgressMapper.toSlider(safeProgress, chapterCount)
        isUpdatingProgressControls = false
        updateProgressText(safeProgress)
    }

    private fun updateProgressText(progress: EbookProgress) {
        val chapterCount = session?.book?.chapters?.size ?: return
        val bookFraction = (
            (progress.chapterIndex + progress.scrollFraction) / chapterCount.toFloat()
            ).coerceIn(0f, 1f)
        tvReaderProgress.text = getString(
            R.string.ebook_reader_progress_value,
            bookFraction * 100f,
            progress.chapterIndex + 1,
            chapterCount
        )
    }

    private fun configureBrightnessControls() {
        sliderScreenBrightness.min = AppSettings.MIN_READER_BRIGHTNESS
        sliderScreenBrightness.max = AppSettings.MAX_READER_BRIGHTNESS
        sliderScreenBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser || isUpdatingBrightnessControls || !customReaderBrightnessEnabled) {
                    return
                }

                val brightness = progress.coerceIn(
                    AppSettings.MIN_READER_BRIGHTNESS,
                    AppSettings.MAX_READER_BRIGHTNESS
                )
                AppSettings.setCustomReaderBrightness(this@EbookReaderActivity, brightness)
                applyCustomReaderBrightness(brightness)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                if (customReaderBrightnessEnabled) {
                    readerControlsController.cancelAutoHide()
                }
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                if (customReaderBrightnessEnabled) {
                    val brightness = seekBar.progress.coerceIn(
                        AppSettings.MIN_READER_BRIGHTNESS,
                        AppSettings.MAX_READER_BRIGHTNESS
                    )
                    AppSettings.setCustomReaderBrightness(this@EbookReaderActivity, brightness)
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
                val brightness = sliderScreenBrightness.progress.coerceIn(
                    AppSettings.MIN_READER_BRIGHTNESS,
                    AppSettings.MAX_READER_BRIGHTNESS
                )
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

    private fun updateBrightnessControls() {
        if (!::sliderScreenBrightness.isInitialized || !::checkboxCustomBrightness.isInitialized) {
            return
        }

        isUpdatingBrightnessControls = true
        customReaderBrightnessEnabled = AppSettings.isCustomReaderBrightnessEnabled(this)
        checkboxCustomBrightness.isChecked = customReaderBrightnessEnabled
        sliderScreenBrightness.alpha = if (customReaderBrightnessEnabled) 1f else 0.72f
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
        val normalized = brightness.coerceIn(
            AppSettings.MIN_READER_BRIGHTNESS,
            AppSettings.MAX_READER_BRIGHTNESS
        )
        val params = window.attributes
        params.screenBrightness = (normalized.toFloat() / AppSettings.MAX_READER_BRIGHTNESS)
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
                AppSettings.DEFAULT_READER_BRIGHTNESS
            )
        }.getOrDefault(AppSettings.DEFAULT_READER_BRIGHTNESS)
            .coerceIn(AppSettings.MIN_READER_BRIGHTNESS, AppSettings.MAX_READER_BRIGHTNESS)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webReader.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
        }
        webReader.addJavascriptInterface(ReaderBridge(), JS_BRIDGE_NAME)
        webReader.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return true
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val uri = request.url
                if (uri.scheme == RESOURCE_SCHEME) {
                    val currentSession = session ?: return null
                    val resourceId = uri.host ?: return null
                    val resource = currentSession.book.resources.firstOrNull { it.id == resourceId }
                        ?: return null
                    val stream = currentSession.openResource(resourceId) ?: return null
                    return WebResourceResponse(resource.mimeType, "binary", stream)
                }

                if (uri.scheme == "data" || uri.scheme == "about") {
                    return null
                }
                return blockedResponse()
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (destroyed) return
                pageReady = true
                restoreProgress(currentProgress)
                tvStatus.visibility = android.view.View.GONE
            }
        }
    }

    private fun loadBook(file: File) {
        val generation = ++loadGeneration
        pageReady = false
        tvStatus.text = getString(R.string.ebook_loading)
        tvStatus.visibility = android.view.View.VISIBLE

        runCatching {
            loadExecutor.execute {
                val result = runCatching {
                    val loadedSession = EbookSessionFactory.open(file)
                    try {
                        PreparedBook(
                            session = loadedSession,
                            html = htmlRenderer.render(loadedSession.book, style)
                        )
                    } catch (error: Throwable) {
                        loadedSession.close()
                        throw error
                    }
                }
                if (destroyed || generation != loadGeneration) {
                    result.getOrNull()?.session?.close()
                    return@execute
                }

                runOnUiThread {
                    if (destroyed || generation != loadGeneration) {
                        result.getOrNull()?.session?.close()
                        return@runOnUiThread
                    }

                    result.onSuccess { preparedBook ->
                        session?.close()
                        session = preparedBook.session
                        ReadingHistoryStore.record(this@EbookReaderActivity, file)
                        tvTitle.text = preparedBook.session.book.title
                        currentProgress = currentProgress?.let {
                            EbookReaderProgressMapper.clampToBook(
                                it,
                                preparedBook.session.book.chapters.size
                            )
                        }
                        updateProgressControls(currentProgress)
                        if (preparedBook.session.book.chapters.isEmpty()) {
                            showError(getString(R.string.ebook_empty))
                        } else {
                            loadHtml(preparedBook.html)
                        }
                    }.onFailure { error ->
                        showError(errorMessage(error))
                    }
                }
            }
        }.onFailure { error ->
            showError(errorMessage(error))
        }
    }

    private fun loadHtml(html: String) {
        pageReady = false
        webReader.loadDataWithBaseURL(
            BASE_URL,
            html,
            "text/html",
            "UTF-8",
            null
        )
    }

    private fun restoreProgress(progress: EbookProgress?) {
        if (!pageReady) return
        val chapterCount = session?.book?.chapters?.size ?: return
        val normalized = EbookReaderProgressMapper.clampToBook(
            progress ?: EbookProgress(0, 0f),
            chapterCount
        )
        val script = "javascript:window.restoreComicLabProgress(${normalized.chapterIndex},${normalized.scrollFraction});"
        webReader.evaluateJavascript(script, null)
    }

    private fun saveCurrentProgress(force: Boolean) {
        val file = bookFile ?: return
        val progress = currentProgress ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastProgressSavedAt < MIN_PROGRESS_SAVE_INTERVAL_MS) {
            return
        }
        lastProgressSavedAt = now
        EbookProgressStore.save(this, file, progress)
    }

    private fun showContents() {
        val book = session?.book ?: return
        val titles = book.chapters.mapIndexed { index, chapter ->
            "${index + 1}. ${chapter.title}"
        }.toTypedArray()
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.ebook_contents)
            .setItems(titles) { _, which ->
                currentProgress = EbookProgress(which, 0f)
                updateProgressControls(currentProgress)
                saveCurrentProgress(force = true)
                if (pageReady) {
                    webReader.evaluateJavascript(
                        "javascript:window.jumpToComicLabChapter($which);",
                        null
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnDismissListener {
            if (!destroyed) {
                showReaderControlsTemporarily()
            }
        }
        dialog.show()
    }

    private fun showError(message: String) {
        tvStatus.text = message
        tvStatus.visibility = android.view.View.VISIBLE
    }

    private fun errorMessage(error: Throwable): String {
        return when (error) {
            is MobiParseException -> when (error.reason) {
                MobiParseError.DRM_PROTECTED -> getString(R.string.ebook_drm_unsupported)
                MobiParseError.UNSUPPORTED_COMPRESSION,
                MobiParseError.UNSUPPORTED_FORMAT -> getString(R.string.ebook_format_unsupported)
                MobiParseError.EMPTY_BOOK -> getString(R.string.ebook_empty)
                MobiParseError.INVALID_FILE,
                MobiParseError.TRUNCATED_FILE,
                MobiParseError.INVALID_MOBI_HEADER,
                MobiParseError.INVALID_RECORD -> getString(R.string.ebook_corrupted)
            }
            is EpubParseException -> when (error.reason) {
                EpubParseError.ENCRYPTED -> getString(R.string.ebook_encrypted_unsupported)
                EpubParseError.EMPTY_BOOK -> getString(R.string.ebook_empty)
                EpubParseError.UNSUPPORTED_FORMAT -> getString(R.string.ebook_format_unsupported)
                EpubParseError.INVALID_FILE,
                EpubParseError.INVALID_ARCHIVE,
                EpubParseError.MISSING_MIMETYPE,
                EpubParseError.INVALID_CONTAINER,
                EpubParseError.INVALID_PACKAGE -> getString(R.string.ebook_corrupted)
            }
            else -> getString(R.string.ebook_load_failed)
        }
    }

    private fun blockedResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            403,
            "Blocked",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0))
        )
    }

    private inner class ReaderBridge {
        @JavascriptInterface
        fun reportProgress(chapterIndex: Int, fraction: Float) {
            if (destroyed) return
            val generation = loadGeneration
            val chapterCount = session?.book?.chapters?.size ?: return
            val reportedProgress = EbookProgress(
                chapterIndex = chapterIndex.coerceIn(0, (chapterCount - 1).coerceAtLeast(0)),
                scrollFraction = fraction
            ).normalized()
            runOnUiThread {
                if (destroyed || generation != loadGeneration || session == null || !pageReady) {
                    return@runOnUiThread
                }

                currentProgress = reportedProgress
                if (!isDraggingReaderProgress) {
                    updateProgressControls(reportedProgress)
                }
                saveCurrentProgress(force = false)
            }
        }
    }

    companion object {
        const val EXTRA_BOOK_PATH = "archive_path"
        const val EXTRA_START_FROM_BEGINNING = "start_from_beginning"

        private const val RESOURCE_SCHEME = "ebook-resource"
        private const val BASE_URL = "https://comiclab.invalid/"
        private const val JS_BRIDGE_NAME = "ComicLabBridge"
        private const val MIN_PROGRESS_SAVE_INTERVAL_MS = 500L
        private const val MIN_WINDOW_BRIGHTNESS = 0.01f
        private const val MAX_WINDOW_BRIGHTNESS = 1f
    }

    private data class PreparedBook(
        val session: EbookSession,
        val html: String
    )
}
