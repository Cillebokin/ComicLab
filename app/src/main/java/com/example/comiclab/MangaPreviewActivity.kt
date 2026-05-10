package com.example.comiclab

import android.graphics.Bitmap
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.concurrent.Executors

class MangaPreviewActivity : AppCompatActivity() {

    private lateinit var imgCover: ImageView
    private lateinit var tvComicName: TextView
    private lateinit var tvComicFileSize: TextView
    private lateinit var btnFullRead: Button
    private lateinit var btnExitPreview: Button
    private lateinit var progressReading: ProgressBar
    private lateinit var tvReadingProgress: TextView
    private lateinit var gridPreview: GridLayout
    private lateinit var btnTogglePreview: Button
    private lateinit var tvStatus: TextView

    private var archiveFile: File? = null
    private var imageEntries: List<String> = emptyList()
    private var showingAll = false
    @Volatile
    private var archiveGeneration = 0
    @Volatile
    private var coverGeneration = 0
    @Volatile
    private var gridGeneration = 0
    @Volatile
    private var destroyed = false
    private val previewExecutor = Executors.newFixedThreadPool(PREVIEW_THREAD_COUNT)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manga_preview)
        SystemBars.fitContentBelowSystemBars(
            this,
            findViewById<View>(R.id.main),
            findViewById<View>(R.id.statusBarBackground)
        )

        imgCover = findViewById(R.id.imgCover)
        tvComicName = findViewById(R.id.tvComicName)
        tvComicFileSize = findViewById(R.id.tvComicFileSize)
        btnFullRead = findViewById(R.id.btnFullRead)
        btnExitPreview = findViewById(R.id.btnExitPreview)
        progressReading = findViewById(R.id.progressReading)
        tvReadingProgress = findViewById(R.id.tvReadingProgress)
        gridPreview = findViewById(R.id.gridPreview)
        btnTogglePreview = findViewById(R.id.btnTogglePreview)
        tvStatus = findViewById(R.id.tvStatus)

        val path = intent.getStringExtra(EXTRA_ARCHIVE_PATH)
        val file = path?.let(::File)
        if (file == null || !file.isFile) {
            showError(getString(R.string.message_invalid_file))
            return
        }

        archiveFile = file
        tvComicName.text = file.nameWithoutExtension
        tvComicFileSize.text = CommonFunc.formatFileSize(file.length())

        btnFullRead.setOnClickListener {
            handleReadClick(file)
        }

        btnExitPreview.setOnClickListener {
            returnToFileBrowser()
        }

        btnTogglePreview.setOnClickListener {
            showingAll = !showingAll
            renderPreviewGrid()
        }

        loadArchive(file)
    }

    override fun onResume() {
        super.onResume()
        updateReadingProgress()
    }

    override fun onDestroy() {
        destroyed = true
        archiveGeneration++
        coverGeneration++
        gridGeneration++
        previewExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun loadArchive(file: File) {
        val generation = ++archiveGeneration
        tvStatus.text = getString(R.string.loading_preview)
        tvStatus.visibility = View.VISIBLE
        btnTogglePreview.visibility = View.GONE

        executePreviewTask {
            val result = runCatching {
                ComicArchive.imageEntries(file)
            }

            runOnUiThread {
                if (!isPreviewActive() || generation != archiveGeneration) {
                    return@runOnUiThread
                }

                val entries = result.getOrElse {
                    showError(getString(R.string.unsupported_archive_format))
                    return@runOnUiThread
                }

                if (entries.isEmpty()) {
                    showError(getString(R.string.no_preview_images))
                    return@runOnUiThread
                }

                imageEntries = entries
                tvStatus.visibility = View.INVISIBLE
                updateReadingProgress()
                loadCover(entries.first())
                renderPreviewGrid()
            }
        }
    }

    private fun updateReadingProgress() {
        val file = archiveFile
        val totalCount = imageEntries.size
        if (file == null || totalCount <= 0) {
            progressReading.max = 0
            progressReading.progress = 0
            tvReadingProgress.text = getString(R.string.preview_reading_progress, 0, 0)
            return
        }

        val readCount = MangaReaderActivity.savedReadingPageCount(this, file, totalCount)
        progressReading.max = totalCount
        progressReading.progress = readCount
        tvReadingProgress.text = getString(R.string.preview_reading_progress, readCount, totalCount)
    }

    private fun loadCover(entryName: String) {
        val file = archiveFile ?: return
        val generation = ++coverGeneration

        executePreviewTask {
            val bitmap = runCatching {
                ComicArchive.decodeImage(file, entryName, COVER_IMAGE_MAX_SIZE)
            }.getOrNull()

            runOnUiThread {
                if (isPreviewActive() && generation == coverGeneration && bitmap != null) {
                    imgCover.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun renderPreviewGrid() {
        val file = archiveFile ?: return
        val generation = ++gridGeneration
        val entriesToShow = if (showingAll) {
            imageEntries
        } else {
            imageEntries.take(DEFAULT_PREVIEW_COUNT)
        }

        gridPreview.removeAllViews()
        tvStatus.text = getString(R.string.loading_preview)
        tvStatus.visibility = View.VISIBLE

        if (entriesToShow.isEmpty()) {
            tvStatus.visibility = View.INVISIBLE
            return
        }

        btnTogglePreview.text = if (showingAll) {
            getString(R.string.collapse_preview)
        } else {
            getString(R.string.preview_all)
        }
        btnTogglePreview.visibility = if (imageEntries.size > DEFAULT_PREVIEW_COUNT) {
            View.VISIBLE
        } else {
            View.GONE
        }

        val indexesByEntry = entriesToShow.withIndex().associate { it.value to it.index }

        executePreviewTask {
            runCatching {
                ComicArchive.decodeImages(file, entriesToShow, PREVIEW_IMAGE_MAX_SIZE) { entryName, bitmap ->
                    if (!isPreviewActive() || generation != gridGeneration) {
                        return@decodeImages false
                    }

                    val index = indexesByEntry[entryName] ?: return@decodeImages true
                    runOnUiThread {
                        if (!isPreviewActive() || generation != gridGeneration) {
                            return@runOnUiThread
                        }

                        if (bitmap != null) {
                            addPreviewImage(bitmap, index)
                        }
                    }

                    true
                }
            }.onSuccess {
                hidePreviewLoading(generation)
            }.onFailure {
                hidePreviewLoading(generation)
            }
        }
    }

    private fun hidePreviewLoading(generation: Int) {
        runOnUiThread {
            if (isPreviewActive() && generation == gridGeneration) {
                tvStatus.visibility = View.INVISIBLE
            }
        }
    }

    private fun addPreviewImage(bitmap: Bitmap, index: Int) {
        val margin = resources.getDimensionPixelSize(R.dimen.preview_image_margin)
        val imageView = ImageView(this).apply {
            setImageBitmap(bitmap)
            setBackgroundColor(android.graphics.Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = false
        }

        val layoutParams = GridLayout.LayoutParams(
            GridLayout.spec(index / PREVIEW_COLUMNS),
            GridLayout.spec(index % PREVIEW_COLUMNS, 1f)
        ).apply {
            width = 0
            height = resources.getDimensionPixelSize(R.dimen.preview_image_height)
            setMargins(margin, margin, margin, margin)
        }

        gridPreview.addView(imageView, layoutParams)
    }

    private fun showError(message: String) {
        tvStatus.text = message
        tvStatus.visibility = View.VISIBLE
        btnTogglePreview.visibility = View.GONE
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun returnToFileBrowser() {
        if (!isTaskRoot) {
            finish()
            return
        }

        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }

    private fun handleReadClick(file: File) {
        if (MangaReaderActivity.hasSavedReadingProgress(this, file)) {
            showReadingProgressDialog(file)
        } else {
            openMangaReader(file, startFromBeginning = false)
        }
    }

    private fun showReadingProgressDialog(file: File) {
        AlertDialog.Builder(this)
            .setTitle(R.string.reader_progress_dialog_title)
            .setMessage(R.string.reader_progress_dialog_message)
            .setPositiveButton(R.string.continue_reading) { _, _ ->
                openMangaReader(file, startFromBeginning = false)
            }
            .setNegativeButton(R.string.read_from_beginning) { _, _ ->
                openMangaReader(file, startFromBeginning = true)
            }
            .show()
    }

    private fun openMangaReader(file: File, startFromBeginning: Boolean) {
        val intent = Intent(this, MangaReaderActivity::class.java).apply {
            putExtra(MangaReaderActivity.EXTRA_ARCHIVE_PATH, file.absolutePath)
            putExtra(MangaReaderActivity.EXTRA_START_FROM_BEGINNING, startFromBeginning)
        }
        startActivity(intent)
    }

    private fun executePreviewTask(block: () -> Unit) {
        if (destroyed) {
            return
        }

        runCatching {
            previewExecutor.execute {
                if (!destroyed) {
                    block()
                }
            }
        }
    }

    private fun isPreviewActive(): Boolean {
        return !destroyed && !isFinishing && !isDestroyed
    }

    companion object {
        const val EXTRA_ARCHIVE_PATH = "archive_path"

        private const val DEFAULT_PREVIEW_COUNT = 20
        private const val PREVIEW_COLUMNS = 3
        private const val COVER_IMAGE_MAX_SIZE = 720
        private const val PREVIEW_IMAGE_MAX_SIZE = 360
        private const val PREVIEW_THREAD_COUNT = 2
    }
}
