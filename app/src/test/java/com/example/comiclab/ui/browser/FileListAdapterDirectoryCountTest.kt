package com.example.comiclab.ui.browser

import android.widget.FrameLayout
import android.widget.TextView
import com.example.comiclab.R
import com.example.comiclab.ui.browser.FileListAdapter
import com.example.comiclab.ui.browser.model.FileItem
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FileListAdapterDirectoryCountTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun pendingDirectoryCountShowsPlaceholderAndRequestsCount() {
        val directory = Files.createTempDirectory("comiclab-pending-count").toFile()
        val requests = mutableListOf<java.io.File>()
        val adapter = FileListAdapter(
            context = context,
            items = mutableListOf(FileItem(file = directory)),
            onDirectoryChildCountRequested = requests::add
        )

        try {
            val row = adapter.getView(0, null, FrameLayout(context))

            assertEquals(
                context.getString(R.string.item_count_pending),
                row.findViewById<TextView>(R.id.tvInfo).text.toString()
            )
            assertEquals(listOf(directory), requests)
        } finally {
            adapter.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun knownDirectoryCountShowsCountWithoutRequestingAgain() {
        val directory = Files.createTempDirectory("comiclab-known-count").toFile()
        val requests = mutableListOf<java.io.File>()
        val adapter = FileListAdapter(
            context = context,
            items = mutableListOf(FileItem(file = directory, childCount = 3)),
            onDirectoryChildCountRequested = requests::add
        )

        try {
            val row = adapter.getView(0, null, FrameLayout(context))

            assertEquals(
                context.getString(R.string.item_count, 3),
                row.findViewById<TextView>(R.id.tvInfo).text.toString()
            )
            assertEquals(emptyList<java.io.File>(), requests)
        } finally {
            adapter.close()
            directory.deleteRecursively()
        }
    }
}
