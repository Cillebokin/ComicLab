package com.example.comiclab

import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import kotlin.math.roundToInt

class MangaReaderActivity : AppCompatActivity() {

    private lateinit var layoutReaderImages: LinearLayout
    private lateinit var tvReaderStatus: TextView

    @Volatile
    private var readerGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manga_reader)
        SystemBars.fitContentBelowSystemBars(
            this,
            findViewById<View>(R.id.main),
            findViewById<View>(R.id.statusBarBackground)
        )

        layoutReaderImages = findViewById(R.id.layoutReaderImages)
        tvReaderStatus = findViewById(R.id.tvReaderStatus)

        val path = intent.getStringExtra(EXTRA_ARCHIVE_PATH)
        val file = path?.let(::File)
        if (file == null || !file.isFile) {
            showError(getString(R.string.message_invalid_file))
            return
        }

        loadReaderImages(file)
    }

    override fun onDestroy() {
        readerGeneration++
        layoutReaderImages.removeAllViews()
        super.onDestroy()
    }

    private fun loadReaderImages(file: File) {
        val generation = ++readerGeneration
        val targetWidth = resources.displayMetrics.widthPixels.coerceAtLeast(MIN_READER_IMAGE_WIDTH)

        layoutReaderImages.removeAllViews()
        tvReaderStatus.text = getString(R.string.loading_reading)
        tvReaderStatus.visibility = View.VISIBLE

        Thread {
            val entries = runCatching {
                ComicArchive.imageEntries(file)
            }.getOrElse {
                runOnUiThread {
                    if (generation == readerGeneration) {
                        showError(getString(R.string.unsupported_archive_format))
                    }
                }
                return@Thread
            }

            if (entries.isEmpty()) {
                runOnUiThread {
                    if (generation == readerGeneration) {
                        showError(getString(R.string.no_reading_images))
                    }
                }
                return@Thread
            }

            var decodedCount = 0
            for ((index, entryName) in entries.withIndex()) {
                if (generation != readerGeneration) {
                    return@Thread
                }

                val bitmap = runCatching {
                    ComicArchive.decodeImageForWidth(file, entryName, targetWidth)
                }.getOrNull()

                var decodedIndex = decodedCount
                if (bitmap != null) {
                    decodedCount++
                    decodedIndex = decodedCount
                }
                val decodedSoFar = decodedCount

                runOnUiThread {
                    if (generation != readerGeneration) {
                        return@runOnUiThread
                    }

                    if (bitmap != null) {
                        addReaderImage(bitmap, targetWidth)
                        if (decodedIndex == 1) {
                            tvReaderStatus.visibility = View.GONE
                        }
                    }

                    if (index == entries.lastIndex) {
                        if (decodedSoFar == 0) {
                            showError(getString(R.string.no_reading_images))
                        } else {
                            tvReaderStatus.visibility = View.GONE
                        }
                    }
                }
            }
        }.start()
    }

    private fun addReaderImage(bitmap: Bitmap, targetWidth: Int) {
        val imageHeight = calculateImageHeight(bitmap, targetWidth)
        val imageView = ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_XY
            adjustViewBounds = false
            contentDescription = getString(R.string.reader_image)
        }

        layoutReaderImages.addView(
            imageView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                imageHeight
            )
        )
    }

    private fun calculateImageHeight(bitmap: Bitmap, targetWidth: Int): Int {
        if (bitmap.width <= 0 || bitmap.height <= 0 || targetWidth <= 0) {
            return LinearLayout.LayoutParams.WRAP_CONTENT
        }

        return (targetWidth.toFloat() / bitmap.width.toFloat() * bitmap.height.toFloat())
            .roundToInt()
            .coerceAtLeast(1)
    }

    private fun showError(message: String) {
        tvReaderStatus.text = message
        tvReaderStatus.visibility = View.VISIBLE
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_ARCHIVE_PATH = "archive_path"

        private const val MIN_READER_IMAGE_WIDTH = 320
    }
}
