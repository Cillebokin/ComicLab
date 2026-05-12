package com.example.comiclab

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.concurrent.Executors

class MangaReaderPrepareActivity : AppCompatActivity() {

    private lateinit var tvPrepareTitle: TextView
    private lateinit var tvPrepareStatus: TextView
    private lateinit var progressPrepareReader: ProgressBar

    private val prepareExecutor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var destroyed = false
    private var preparedDirectory: File? = null
    private var launchedReader = false
    private var startFromBeginning = false
    private var explicitStartPageIndex = NO_EXPLICIT_START_PAGE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader_prepare)

        tvPrepareTitle = findViewById(R.id.tvPrepareTitle)
        tvPrepareStatus = findViewById(R.id.tvPrepareStatus)
        progressPrepareReader = findViewById(R.id.progressPrepareReader)

        configureBackHandling()

        val path = intent.getStringExtra(MangaReaderActivity.EXTRA_ARCHIVE_PATH)
        val file = path?.let(::File)
        startFromBeginning = intent.getBooleanExtra(
            MangaReaderActivity.EXTRA_START_FROM_BEGINNING,
            false
        )
        explicitStartPageIndex = intent.getIntExtra(
            MangaReaderActivity.EXTRA_START_PAGE_INDEX,
            NO_EXPLICIT_START_PAGE
        )
        if (file == null || !file.isFile) {
            showError("无效文件")
            return
        }

        tvPrepareTitle.text = file.nameWithoutExtension
        prepareReader(file)
    }

    override fun onDestroy() {
        destroyed = true
        prepareExecutor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        if (!launchedReader) {
            preparedDirectory?.deleteRecursively()
        }
        super.onDestroy()
    }

    private fun configureBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                destroyed = true
                preparedDirectory?.deleteRecursively()
                finish()
            }
        })
    }

    private fun prepareReader(file: File) {
        clearOldPreparedReaderCaches()
        val outputDir = createPreparedReaderDirectory(file)
        preparedDirectory = outputDir
        setProgress(0, 0)

        prepareExecutor.execute {
            val entries = runCatching {
                ComicArchive.imageEntries(file)
            }.getOrElse {
                emptyList()
            }

            if (entries.isEmpty()) {
                runOnUiThread {
                    if (!destroyed) {
                        outputDir.deleteRecursively()
                        showError("没有可阅读的图片")
                    }
                }
                return@execute
            }

            val manifestWritten = runCatching {
                ComicArchive.writePreparedReaderManifest(outputDir, entries)
            }.isSuccess
            if (!manifestWritten) {
                runOnUiThread {
                    if (!destroyed) {
                        outputDir.deleteRecursively()
                        showError("准备阅读失败，可能是缓存空间不足")
                    }
                }
                return@execute
            }

            val startPosition = initialReaderPosition(file, entries.size)
            val initialEntries = initialPreparedEntries(entries, startPosition)
            val extractedFiles = runCatching {
                ComicArchive.extractPreparedImagesToDirectory(
                    file = file,
                    entries = initialEntries,
                    outputDir = outputDir,
                    shouldContinue = { !destroyed }
                ) { completed, total ->
                    runOnUiThread {
                        if (!destroyed) {
                            setProgress(completed, total)
                        }
                    }
                }
            }.getOrElse {
                emptyList()
            }

            runOnUiThread {
                if (destroyed) {
                    return@runOnUiThread
                }

                if (extractedFiles.isEmpty()) {
                    outputDir.deleteRecursively()
                    showError("准备阅读失败，可能是缓存空间不足")
                    return@runOnUiThread
                }

                openReader(file, outputDir)
            }
        }
    }

    private fun initialReaderPosition(file: File, totalCount: Int): Int {
        if (explicitStartPageIndex >= 0 && totalCount > 0) {
            return explicitStartPageIndex.coerceIn(0, totalCount - 1)
        }

        if (startFromBeginning || totalCount <= 0) {
            return 0
        }

        return (MangaReaderActivity.savedReadingPageCount(this, file, totalCount) - 1)
            .coerceIn(0, totalCount - 1)
    }

    private fun initialPreparedEntries(
        entries: List<String>,
        startPosition: Int
    ): List<ComicArchive.PreparedImageEntry> {
        if (entries.isEmpty()) {
            return emptyList()
        }

        val lastIndex = entries.lastIndex
        val orderedPositions = linkedSetOf<Int>()
        orderedPositions.add(startPosition.coerceIn(0, lastIndex))
        (1..INITIAL_FORWARD_PREPARE_COUNT).forEach { offset ->
            orderedPositions.add((startPosition + offset).coerceAtMost(lastIndex))
        }
        (1..INITIAL_BACKWARD_PREPARE_COUNT).forEach { offset ->
            orderedPositions.add((startPosition - offset).coerceAtLeast(0))
        }

        return orderedPositions
            .filter { it in entries.indices }
            .map { position -> ComicArchive.PreparedImageEntry(position, entries[position]) }
    }

    private fun setProgress(completed: Int, total: Int) {
        if (total <= 0) {
            progressPrepareReader.isIndeterminate = true
            tvPrepareStatus.text = "正在准备阅读..."
            return
        }

        progressPrepareReader.isIndeterminate = false
        progressPrepareReader.max = total
        progressPrepareReader.progress = completed.coerceIn(0, total)
        val percent = ((completed.toFloat() / total.toFloat()) * 100f).toInt().coerceIn(0, 100)
        tvPrepareStatus.text = "正在准备快速阅读 $completed / $total  $percent%"
    }

    private fun openReader(file: File, outputDir: File) {
        launchedReader = true
        val intent = Intent(this, MangaReaderActivity::class.java).apply {
            putExtra(MangaReaderActivity.EXTRA_ARCHIVE_PATH, file.absolutePath)
            putExtra(MangaReaderActivity.EXTRA_START_FROM_BEGINNING, startFromBeginning)
            putExtra(MangaReaderActivity.EXTRA_START_PAGE_INDEX, explicitStartPageIndex)
            putExtra(MangaReaderActivity.EXTRA_PREPARED_READER_CACHE_DIR, outputDir.absolutePath)
        }
        startActivity(intent)
        finish()
    }

    private fun showError(message: String) {
        progressPrepareReader.visibility = View.GONE
        tvPrepareStatus.text = message
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun createPreparedReaderDirectory(file: File): File {
        val root = File(cacheDir, PREPARED_READER_CACHE_ROOT)
        root.mkdirs()
        val identity = "${file.absolutePath.hashCode()}_${file.lastModified()}_${file.length()}"
        return File(root, "${Integer.toHexString(identity.hashCode())}_${System.currentTimeMillis()}")
    }

    private fun clearOldPreparedReaderCaches() {
        val root = File(cacheDir, PREPARED_READER_CACHE_ROOT)
        val now = System.currentTimeMillis()
        root.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory }
            ?.filter { now - it.lastModified() > PREPARED_READER_CACHE_MAX_AGE_MS }
            ?.forEach { it.deleteRecursively() }
    }

    companion object {
        private const val PREPARED_READER_CACHE_ROOT = "prepared_reader"
        private const val PREPARED_READER_CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1000L
        private const val INITIAL_FORWARD_PREPARE_COUNT = 5
        private const val INITIAL_BACKWARD_PREPARE_COUNT = 1
        private const val NO_EXPLICIT_START_PAGE = -1
    }
}
