package com.example.comiclab

import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.comiclab.core.reader.ExportDocumentJournal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExportInterruptionDeviceTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val mode = InstrumentationRegistry.getArguments().getString("exportInterruptionFixture")
    private val resolver = app.contentResolver
    private val journal = ExportDocumentJournal(app)
    private val fixture = app.getSharedPreferences("cl037_process_fixture", 0)
    private val expected = mapOf(
        "reserved.cbz" to "",
        "writing.cbz" to "part",
        "closed.cbz" to "complete",
        "published.cbz" to "published",
        "unrelated.cbz" to "unrelated"
    )

    @Test
    fun prepareAndWaitForProcessStop() {
        assumeTrue(mode == "prepare")
        check(fixture.all.isEmpty() && journal.interruptedExports().isEmpty())
        fun reserve(name: String) = checkNotNull(journal.reserve(create(name), name))
        reserve("reserved.cbz")
        reserve("closed.cbz").openOutput().use { it.write("complete".toByteArray()) }
        val published = reserve("published.cbz")
        published.openOutput().use { it.write("published".toByteArray()) }
        published.complete()
        resolver.openOutputStream(create("unrelated.cbz"), "wt")!!.use { it.write("unrelated".toByteArray()) }
        reserve("writing.cbz").openOutput().use { stream ->
            stream.write("part".toByteArray())
            stream.flush()
            assertTrue(journal.interruptedExports().isEmpty())
            Log.i("CL037Fixture", "TARGETS_READY_FORCE_STOP")
            Thread.sleep(90_000)
            error("Fixture process was not stopped")
        }
    }

    @Test
    fun verifyAfterProcessStop() {
        assumeTrue(mode == "verify")
        check(fixture.all.keys == expected.keys)
        try {
            val interruptions = journal.interruptedExports().associate { it.name to it.writing }
            assertEquals(mapOf("reserved.cbz" to false, "writing.cbz" to true, "closed.cbz" to true), interruptions)
            ActivityScenario.launch(MainActivity::class.java).use {
                onView(withText(R.string.reader_export_interrupted_title)).check { view, _ ->
                    assertTrue(view.isShown)
                }
                val anticipated = app.getString(R.string.reader_export_interrupted_notice, listOf(
                    app.getString(R.string.reader_export_check_written, "closed.cbz"),
                    app.getString(R.string.reader_export_check_reserved, "reserved.cbz"),
                    app.getString(R.string.reader_export_check_written, "writing.cbz")
                ).joinToString("\n"))
                onView(withText(anticipated)).check { view, _ -> assertTrue(view.isShown) }
                onView(withId(android.R.id.button1)).perform(click())
                assertTrue(journal.interruptedExports().isEmpty())
            }
            for ((name, content) in expected) {
                val actual = resolver.openInputStream(uri(name))!!.bufferedReader().use { it.readText() }
                assertEquals(name, content, actual)
            }
            assertFalse(journal.interruptedExports().any { it.name == "published.cbz" })
        } finally {
            expected.keys.forEach { resolver.delete(uri(it), null, null) }
            check(fixture.edit().clear().commit())
        }
    }

    private fun create(name: String): Uri {
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.comicbook+zip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/ComicLab-CL037-fixture/")
        }))
        // The picker reserves a real empty file; a MediaStore insert alone only reserves a row.
        checkNotNull(resolver.openOutputStream(uri, "wt")).close()
        check(fixture.edit().putString(name, uri.toString()).commit())
        return uri
    }

    private fun uri(name: String): Uri = Uri.parse(checkNotNull(fixture.getString(name, null)))
}
