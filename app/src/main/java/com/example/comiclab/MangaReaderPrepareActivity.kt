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
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import com.example.comiclab.core.archive.cache.PreparedReaderCacheLeaseRegistry
import com.example.comiclab.core.archive.cache.PreparedReaderCacheLeaseTask
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
    private var prepareOwnerLease: PreparedReaderCacheLeaseRegistry.Lease? = null
    private var handoffLease: PreparedReaderCacheLeaseRegistry.Lease? = null
    private var launchedReader = false
    private var startFromBeginning = false
    private var explicitStartPageIndex = NO_EXPLICIT_START_PAGE
    private var readerLoadStartedAtNanos: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readerLoadStartedAtNanos = ComicLabPerformance.nowNanos()
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
            showError(R.string.reader_prepare_invalid_file)
            return
        }

        tvPrepareTitle.text = file.nameWithoutExtension
        prepareReader(file)
    }

    override fun onDestroy() {
        destroyed = true
        prepareExecutor.shutdownNow().forEach { task ->
            (task as? PreparedReaderCacheLeaseTask)?.cancelBeforeRun()
        }
        handler.removeCallbacksAndMessages(null)
        if (!launchedReader) {
            discardPreparedCache()
        } else {
            prepareOwnerLease?.close()
            prepareOwnerLease = null
        }
        super.onDestroy()
    }

    private fun configureBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                destroyed = true
                discardPreparedCache()
                finish()
            }
        })
    }

    private fun prepareReader(file: File) {
        if (ComicArchive.isPdf(file)) {
            openReaderDirect(file)
            return
        }

        val preparedCache = createPreparedReaderDirectory(file)
        if (preparedCache == null) {
            showError(R.string.reader_prepare_cache_failure)
            return
        }
        val (outputDir, prepareOwner) = preparedCache
        preparedDirectory = outputDir
        prepareOwnerLease = prepareOwner
        setProgress(0, 0)

        val writerLease = PreparedReaderCacheLeaseRegistry.process.acquireWriterLease(outputDir)
        if (writerLease == null) {
            discardPreparedCache()
            showError(R.string.reader_prepare_cache_failure)
            return
        }
        val task = PreparedReaderCacheLeaseTask(writerLease, shouldRun = { !destroyed }) {
            try {
                prepareCacheOnWorker(file, outputDir)
            } catch (throwable: Throwable) {
                android.util.Log.e("ComicLabPrepare", "prepared reader task failed", throwable)
                handler.post {
                    if (!destroyed) {
                        discardPreparedCache()
                        showError(R.string.reader_prepare_cache_failure)
                    }
                }
            }
        }
        try {
            prepareExecutor.execute(task)
        } catch (throwable: Throwable) {
            task.cancelBeforeRun()
            discardPreparedCache()
            showError(R.string.reader_prepare_cache_failure)
        }
    }

    private fun prepareCacheOnWorker(file: File, outputDir: File) {
        clearOldPreparedReaderCaches()
        if (destroyed || Thread.currentThread().isInterrupted) {
            return
        }

        val indexStartedAtNanos = ComicLabPerformance.nowNanos()
        val entriesResult = runCatching {
            ComicLabPerformance.trace("comiclab.reader.prepare_archive_index") {
                ComicArchive.imageEntries(file)
            }
        }
        val entries = entriesResult.getOrElse { emptyList() }
        ComicLabPerformance.record(
            event = "reader_prepare_archive_index",
            startedAtNanos = indexStartedAtNanos,
            fields = "entries=${entries.size} bytes=${file.length()} success=${entriesResult.isSuccess}"
        )

        if (entries.isEmpty()) {
            handler.post {
                if (!destroyed) {
                    discardPreparedCache()
                    showError(R.string.reader_prepare_no_images)
                }
            }
            return
        }

        val manifestWritten = runCatching {
            ComicArchive.writePreparedReaderManifest(outputDir, entries)
        }.isSuccess
        if (!manifestWritten) {
            handler.post {
                if (!destroyed) {
                    discardPreparedCache()
                    showError(R.string.reader_prepare_cache_failure)
                }
            }
            return
        }

        val startPosition = initialReaderPosition(file, entries.size)
        val initialEntries = initialPreparedEntries(entries, startPosition)
        val extractionStartedAtNanos = ComicLabPerformance.nowNanos()
        val extractedFiles = runCatching {
            ComicLabPerformance.trace("comiclab.reader.prepare_initial_pages") {
                ComicArchive.extractPreparedImagesToDirectory(
                    file = file,
                    entries = initialEntries,
                    outputDir = outputDir,
                    shouldContinue = { !destroyed }
                ) { completed, total ->
                    handler.post {
                        if (!destroyed) {
                            setProgress(completed, total)
                        }
                    }
                }
            }
        }.getOrElse {
            emptyList()
        }
        ComicLabPerformance.record(
            event = "reader_prepare_initial_pages",
            startedAtNanos = extractionStartedAtNanos,
            fields = "requested=${initialEntries.size} extracted=${extractedFiles.size} " +
                "bytes=${extractedFiles.sumOf { it.length() }}"
        )

        handler.post {
            if (destroyed) {
                return@post
            }

            if (extractedFiles.isEmpty()) {
                discardPreparedCache()
                showError(R.string.reader_prepare_cache_failure)
                return@post
            }

            openReader(file, outputDir)
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
            tvPrepareStatus.setText(R.string.preparing_reading)
            return
        }

        progressPrepareReader.isIndeterminate = false
        progressPrepareReader.max = total
        progressPrepareReader.progress = completed.coerceIn(0, total)
        val percent = ((completed.toFloat() / total.toFloat()) * 100f).toInt().coerceIn(0, 100)
        tvPrepareStatus.text = getString(
            R.string.preparing_fast_reading_progress,
            completed,
            total,
            percent
        )
    }

    private fun openReader(file: File, outputDir: File) {
        val handoff = PreparedReaderCacheLeaseRegistry.process.acquireHandoffLease(outputDir)
        if (handoff == null) {
            discardPreparedCache()
            showError(R.string.reader_prepare_cache_failure)
            return
        }
        handoffLease = handoff
        val intent = Intent(this, MangaReaderActivity::class.java).apply {
            putExtra(MangaReaderActivity.EXTRA_ARCHIVE_PATH, file.absolutePath)
            putExtra(MangaReaderActivity.EXTRA_START_FROM_BEGINNING, startFromBeginning)
            putExtra(MangaReaderActivity.EXTRA_START_PAGE_INDEX, explicitStartPageIndex)
            putExtra(MangaReaderActivity.EXTRA_PREPARED_READER_CACHE_DIR, outputDir.absolutePath)
            putExtra(
                MangaReaderActivity.EXTRA_READER_LOAD_STARTED_AT_NANOS,
                readerLoadStartedAtNanos ?: 0L
            )
        }
        try {
            startActivity(intent)
        } catch (throwable: Throwable) {
            handoff.requestDeleteAndClose()
            handoffLease = null
            discardPreparedCache()
            android.util.Log.e("ComicLabPrepare", "failed to launch prepared reader", throwable)
            showError(R.string.reader_prepare_cache_failure)
            return
        }
        launchedReader = true
        prepareOwnerLease?.close()
        prepareOwnerLease = null
        ComicLabPerformance.record(
            event = "reader_prepare_to_launch",
            startedAtNanos = readerLoadStartedAtNanos,
            fields = "prepared=true"
        )
        finish()
    }

    private fun openReaderDirect(file: File) {
        val intent = Intent(this, MangaReaderActivity::class.java).apply {
            putExtra(MangaReaderActivity.EXTRA_ARCHIVE_PATH, file.absolutePath)
            putExtra(MangaReaderActivity.EXTRA_START_FROM_BEGINNING, startFromBeginning)
            putExtra(MangaReaderActivity.EXTRA_START_PAGE_INDEX, explicitStartPageIndex)
            putExtra(
                MangaReaderActivity.EXTRA_READER_LOAD_STARTED_AT_NANOS,
                readerLoadStartedAtNanos ?: 0L
            )
        }
        try {
            startActivity(intent)
        } catch (throwable: Throwable) {
            android.util.Log.e("ComicLabPrepare", "failed to launch reader", throwable)
            showError(R.string.reader_prepare_cache_failure)
            return
        }
        launchedReader = true
        ComicLabPerformance.record(
            event = "reader_prepare_to_launch",
            startedAtNanos = readerLoadStartedAtNanos,
            fields = "prepared=false"
        )
        finish()
    }

    private fun showError(@StringRes messageResId: Int) {
        val message = getString(messageResId)
        progressPrepareReader.visibility = View.GONE
        tvPrepareStatus.text = message
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun createPreparedReaderDirectory(
        file: File
    ): Pair<File, PreparedReaderCacheLeaseRegistry.Lease>? {
        val root = File(cacheDir, PREPARED_READER_CACHE_ROOT)
        if (!root.mkdirs() && !root.isDirectory) {
            return null
        }
        val identity = "${file.absolutePath.hashCode()}_${file.lastModified()}_${file.length()}"
        val baseName = "${Integer.toHexString(identity.hashCode())}_${System.currentTimeMillis()}"
        repeat(MAX_CACHE_DIRECTORY_COLLISION_ATTEMPTS) { suffix ->
            val name = if (suffix == 0) baseName else "${baseName}_$suffix"
            val directory = File(root, name)
            val ownerLease = PreparedReaderCacheLeaseRegistry.process.acquirePrepareOwner(directory)
                ?: return@repeat
            if (directory.mkdir()) {
                return directory to ownerLease
            }
            ownerLease.close()
        }
        return null
    }

    private fun clearOldPreparedReaderCaches() {
        val cleanupStartedAtNanos = ComicLabPerformance.nowNanos()
        val root = File(cacheDir, PREPARED_READER_CACHE_ROOT)
        val now = System.currentTimeMillis()
        val staleDirectoriesResult = runCatching {
            root.listFiles()
                ?.asSequence()
                ?.filter { it.isDirectory }
                ?.filter { now - it.lastModified() > PREPARED_READER_CACHE_MAX_AGE_MS }
                ?.toList()
                .orEmpty()
        }
        val staleDirectories = staleDirectoriesResult.getOrDefault(emptyList())
        val deletionRequests = staleDirectories.count { directory ->
            PreparedReaderCacheLeaseRegistry.process.requestDeleteIfStale(
                directory = directory,
                maxAgeMs = PREPARED_READER_CACHE_MAX_AGE_MS,
                nowMillis = now
            )
        }
        val failedDirectories = if (staleDirectoriesResult.isFailure) 1 else 0
        ComicLabPerformance.record(
            event = "reader_prepare_stale_cache_cleanup",
            startedAtNanos = cleanupStartedAtNanos,
            fields = "mainThread=${Looper.myLooper() == Looper.getMainLooper()} " +
                "candidates=${staleDirectories.size} requested=$deletionRequests " +
                "scanFailed=$failedDirectories"
        )
    }

    private fun discardPreparedCache() {
        val handoff = handoffLease
        handoffLease = null
        handoff?.requestDeleteAndClose()
        val owner = prepareOwnerLease
        prepareOwnerLease = null
        owner?.requestDeleteAndClose()
        if (handoff == null && owner == null) {
            preparedDirectory?.let(PreparedReaderCacheLeaseRegistry.process::requestDelete)
        }
    }

    companion object {
        private const val PREPARED_READER_CACHE_ROOT = "prepared_reader"
        private const val PREPARED_READER_CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1000L
        private const val MAX_CACHE_DIRECTORY_COLLISION_ATTEMPTS = 100
        private const val INITIAL_FORWARD_PREPARE_COUNT = 5
        private const val INITIAL_BACKWARD_PREPARE_COUNT = 1
        private const val NO_EXPLICIT_START_PAGE = -1
    }
}
