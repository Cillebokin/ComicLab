package com.example.comiclab

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.comiclab.ebook.EbookSession
import com.example.comiclab.ebook.EbookSessionFactory
import com.example.comiclab.ebook.EbookProgressStore
import com.example.comiclab.ebook.epub.EpubParseError
import com.example.comiclab.ebook.epub.EpubParseException
import com.example.comiclab.ebook.mobi.MobiParseException
import com.example.comiclab.ebook.mobi.MobiParseError
import java.io.File
import java.util.concurrent.Executors

class EbookPreviewActivity : AppCompatActivity() {

    private lateinit var imgCover: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var tvAuthor: TextView
    private lateinit var tvChapterCount: TextView
    private lateinit var tvProgress: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnContinue: Button
    private lateinit var btnReadFromBeginning: Button

    private val previewExecutor = Executors.newSingleThreadExecutor()
    private var loadGeneration = 0
    private var session: EbookSession? = null
    private var bookFile: File? = null

    @Volatile
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ebook_preview)
        SystemBars.fitContentBelowSystemBars(
            this,
            findViewById(R.id.main),
            findViewById(R.id.statusBarBackground),
            statusBarColorResId = R.color.comiclab_file_picker_background,
            lightStatusBars = true
        )

        imgCover = findViewById(R.id.imgEbookCover)
        tvTitle = findViewById(R.id.tvEbookTitle)
        tvAuthor = findViewById(R.id.tvEbookAuthor)
        tvChapterCount = findViewById(R.id.tvEbookChapterCount)
        tvProgress = findViewById(R.id.tvEbookProgress)
        tvStatus = findViewById(R.id.tvEbookStatus)
        btnContinue = findViewById(R.id.btnEbookContinue)
        btnReadFromBeginning = findViewById(R.id.btnEbookReadFromBeginning)

        val file = intent.getStringExtra(EXTRA_BOOK_PATH)?.let(::File)
        if (file == null || !file.isFile) {
            showError(getString(R.string.message_invalid_file))
            return
        }

        bookFile = file
        tvTitle.text = file.nameWithoutExtension
        btnContinue.setOnClickListener { openReader(startFromBeginning = false) }
        btnReadFromBeginning.setOnClickListener { openReader(startFromBeginning = true) }
        btnContinue.visibility = View.GONE
        btnReadFromBeginning.visibility = View.GONE
        loadBook(file)
    }

    override fun onResume() {
        super.onResume()
        updateProgress()
    }

    override fun onDestroy() {
        destroyed = true
        loadGeneration++
        session?.close()
        session = null
        previewExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun loadBook(file: File) {
        val generation = ++loadGeneration
        tvStatus.text = getString(R.string.ebook_loading)
        tvStatus.visibility = View.VISIBLE

        runCatching {
            previewExecutor.execute {
                val result = runCatching {
                    val loadedSession = EbookSessionFactory.open(file)
                    val cover = loadedSession.book.coverResourceId?.let { resourceId ->
                        loadedSession.openResource(resourceId)?.use { input ->
                            BitmapFactory.decodeStream(input)
                        }
                    }
                    LoadedBook(loadedSession, cover)
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

                    result.onSuccess { loaded ->
                        session?.close()
                        session = loaded.session
                        tvTitle.text = loaded.session.book.title
                        tvAuthor.text = loaded.session.book.author
                            ?.takeIf(String::isNotBlank)
                            ?.let { getString(R.string.ebook_author, it) }
                            ?: getString(R.string.ebook_author_unknown)
                        tvChapterCount.text = getString(
                            R.string.ebook_chapter_count,
                            loaded.session.book.chapters.size
                        )
                        if (loaded.cover != null) {
                            imgCover.setImageBitmap(loaded.cover)
                        }
                        tvStatus.visibility = View.GONE
                        btnContinue.visibility = View.VISIBLE
                        btnReadFromBeginning.visibility = View.VISIBLE
                        updateProgress()
                    }.onFailure { error ->
                        showError(errorMessage(error))
                    }
                }
            }
        }.onFailure { error ->
            showError(errorMessage(error))
        }
    }

    private fun updateProgress() {
        val file = bookFile ?: return
        val progress = EbookProgressStore.load(this, file)
        tvProgress.text = if (progress == null) {
            getString(R.string.ebook_progress_not_started)
        } else {
            getString(
                R.string.ebook_progress_value,
                progress.chapterIndex + 1,
                progress.scrollFraction * 100f
            )
        }
    }

    private fun openReader(startFromBeginning: Boolean) {
        val file = bookFile ?: return
        startActivity(
            Intent(this, EbookReaderActivity::class.java).apply {
                putExtra(EbookReaderActivity.EXTRA_BOOK_PATH, file.absolutePath)
                putExtra(EbookReaderActivity.EXTRA_START_FROM_BEGINNING, startFromBeginning)
            }
        )
    }

    private fun showError(message: String) {
        tvStatus.text = message
        tvStatus.visibility = View.VISIBLE
        btnContinue.visibility = View.GONE
        btnReadFromBeginning.visibility = View.GONE
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

    private data class LoadedBook(
        val session: EbookSession,
        val cover: Bitmap?
    )

    companion object {
        const val EXTRA_BOOK_PATH = "archive_path"
    }
}
