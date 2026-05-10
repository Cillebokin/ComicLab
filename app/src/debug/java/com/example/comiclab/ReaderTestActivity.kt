package com.example.comiclab

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import java.io.File

class ReaderTestActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val archivePath = intent.getStringExtra(MangaReaderActivity.EXTRA_ARCHIVE_PATH)
            ?: intent.getStringExtra("archive_path")
        val archiveFile = archivePath?.let(::File)
        if (archiveFile == null || !archiveFile.isFile) {
            Toast.makeText(this, "Invalid archive path", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val readerIntent = Intent(this, MangaReaderActivity::class.java).apply {
            putExtra(MangaReaderActivity.EXTRA_ARCHIVE_PATH, archiveFile.absolutePath)
            putExtra(
                MangaReaderActivity.EXTRA_START_FROM_BEGINNING,
                intent.getBooleanExtra(MangaReaderActivity.EXTRA_START_FROM_BEGINNING, false)
            )
            putExtra(
                MangaReaderActivity.EXTRA_DEBUG_READER_STRESS,
                intent.getBooleanExtra(MangaReaderActivity.EXTRA_DEBUG_READER_STRESS, false)
            )
            putExtra(
                MangaReaderActivity.EXTRA_DEBUG_READER_STRESS_ITERATIONS,
                intent.getIntExtra(MangaReaderActivity.EXTRA_DEBUG_READER_STRESS_ITERATIONS, 360)
            )
            putExtra(
                MangaReaderActivity.EXTRA_DEBUG_READER_STRESS_DELAY_MS,
                intent.getLongExtra(MangaReaderActivity.EXTRA_DEBUG_READER_STRESS_DELAY_MS, 45L)
            )
            putExtra(
                MangaReaderActivity.EXTRA_DEBUG_READER_STRESS_TRIM_EVERY,
                intent.getIntExtra(MangaReaderActivity.EXTRA_DEBUG_READER_STRESS_TRIM_EVERY, 24)
            )
        }
        startActivity(readerIntent)
        finish()
    }
}
