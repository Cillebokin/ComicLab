package com.example.comiclab

import android.graphics.Bitmap
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class MangaPreviewActivity : AppCompatActivity() {

    private lateinit var imgCover: ImageView
    private lateinit var tvComicName: TextView
    private lateinit var btnFullRead: Button
    private lateinit var btnExitPreview: Button
    private lateinit var gridPreview: GridLayout
    private lateinit var btnTogglePreview: Button
    private lateinit var tvStatus: TextView

    private var archiveFile: File? = null
    private var imageEntries: List<String> = emptyList()
    private var showingAll = false
    private var coverGeneration = 0
    private var gridGeneration = 0

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
        btnFullRead = findViewById(R.id.btnFullRead)
        btnExitPreview = findViewById(R.id.btnExitPreview)
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

        btnFullRead.setOnClickListener {
            openMangaReader(file)
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

    private fun loadArchive(file: File) {
        tvStatus.text = getString(R.string.loading_preview)
        tvStatus.visibility = View.VISIBLE
        btnTogglePreview.visibility = View.GONE

        Thread {
            val result = runCatching {
                ComicArchive.imageEntries(file)
            }

            runOnUiThread {
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
                loadCover(entries.first())
                renderPreviewGrid()
            }
        }.start()
    }

    private fun loadCover(entryName: String) {
        val file = archiveFile ?: return
        val generation = ++coverGeneration

        Thread {
            val bitmap = runCatching {
                ComicArchive.decodeImage(file, entryName, COVER_IMAGE_MAX_SIZE)
            }.getOrNull()

            runOnUiThread {
                if (generation == coverGeneration && bitmap != null) {
                    imgCover.setImageBitmap(bitmap)
                }
            }
        }.start()
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

        Thread {
            entriesToShow.forEachIndexed { index, entryName ->
                val bitmap = runCatching {
                    ComicArchive.decodeImage(file, entryName, PREVIEW_IMAGE_MAX_SIZE)
                }.getOrNull()

                runOnUiThread {
                    if (generation != gridGeneration) {
                        return@runOnUiThread
                    }

                    if (bitmap != null) {
                        addPreviewImage(bitmap, index)
                    }

                    if (index == entriesToShow.lastIndex) {
                        tvStatus.visibility = View.INVISIBLE
                    }
                }
            }
        }.start()
    }

    private fun addPreviewImage(bitmap: Bitmap, index: Int) {
        val margin = resources.getDimensionPixelSize(R.dimen.preview_image_margin)
        val imageView = ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.CENTER_CROP
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
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
        finish()
    }

    private fun openMangaReader(file: File) {
        val intent = Intent(this, MangaReaderActivity::class.java).apply {
            putExtra(MangaReaderActivity.EXTRA_ARCHIVE_PATH, file.absolutePath)
        }
        startActivity(intent)
    }

    companion object {
        const val EXTRA_ARCHIVE_PATH = "archive_path"

        private const val DEFAULT_PREVIEW_COUNT = 20
        private const val PREVIEW_COLUMNS = 3
        private const val COVER_IMAGE_MAX_SIZE = 720
        private const val PREVIEW_IMAGE_MAX_SIZE = 360
    }
}
