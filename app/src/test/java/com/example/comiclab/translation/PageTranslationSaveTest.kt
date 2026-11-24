package com.example.comiclab.translation

import android.content.Intent
import android.os.Looper
import android.system.ErrnoException
import android.system.OsConstants
import android.view.View
import android.widget.Button
import androidx.lifecycle.ViewModelProvider
import com.example.comiclab.R
import com.example.comiclab.core.reader.enhance.EnhancementRuntime
import com.example.comiclab.ui.reader.enhance.EnhancementExportActivity
import com.example.comiclab.ui.reader.translation.PageTranslationActivity
import com.example.comiclab.ui.reader.translation.PageTranslationViewModel
import com.example.comiclab.ui.reader.translation.TranslationUiState
import com.example.comiclab.ui.reader.translation.stageTranslatedPage
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PageTranslationSaveTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun stagingFailureShowsFeedbackWithoutOpeningPickerAndCanBeRetried() {
        val intent = Intent(RuntimeEnvironment.getApplication(), PageTranslationActivity::class.java)
            .putExtra("archive", "fixture.cbz")
            .putExtra("entry", "003.png")
            .putExtra("page", 3)
        val controller = Robolectric.buildActivity(PageTranslationActivity::class.java, intent).setup()
        val activity = controller.get()
        val model = ViewModelProvider(activity)[PageTranslationViewModel::class.java]
        val bytes = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j3ioAAAAASUVORK5CYII="
        )
        val result = File(model.directory, "translated.png")
        val staging = File(activity.cacheDir, "enhancement-exports")
        try {
            awaitSave()
            result.writeBytes(bytes)
            model.state.value = TranslationUiState(stage = "done", result = result)
            val views = ArrayList<View>()
            activity.window.decorView.findViewsWithText(
                views, activity.getString(R.string.reader_enhance_save), View.FIND_VIEWS_WITH_TEXT
            )
            val save = views.filterIsInstance<Button>().single()
            ShadowStatFs.registerStats(activity.cacheDir, 128, 128, 128)
            check(staging.createNewFile())
            ShadowToast.reset()

            assertTrue(save.isEnabled)
            save.performClick()
            assertFalse(save.isEnabled)
            awaitSave()

            assertTrue(save.isEnabled)
            assertEquals(activity.getString(R.string.translation_save_prepare_failed), ShadowToast.getTextOfLatestToast())
            assertNull(shadowOf(activity).nextStartedActivity)
            assertArrayEquals(bytes, result.readBytes())
            check(staging.delete())

            ShadowStatFs.registerStats(activity.cacheDir, 128, 0, 0)
            save.performClick()
            awaitSave()
            assertTrue(save.isEnabled)
            assertEquals(activity.getString(R.string.translation_save_no_space), ShadowToast.getTextOfLatestToast())
            assertNull(shadowOf(activity).nextStartedActivity)
            assertFalse(staging.exists())
            assertArrayEquals(bytes, result.readBytes())

            ShadowStatFs.registerStats(activity.cacheDir, 128, 128, 128)
            save.performClick()
            awaitSave()
            val launched = shadowOf(activity).nextStartedActivity
            assertEquals(EnhancementExportActivity::class.java.name, launched.component?.className)
            assertEquals("fixture_3_translated.png", launched.getStringExtra("enhancement_name"))
            val staged = File(checkNotNull(launched.getStringExtra("enhancement_source")))
            assertEquals(staging, staged.parentFile)
            assertArrayEquals(bytes, staged.readBytes())
            val export = Robolectric.buildActivity(EnhancementExportActivity::class.java, launched).setup()
            try {
                val picker = shadowOf(export.get()).nextStartedActivityForResult.intent
                assertEquals(Intent.ACTION_CREATE_DOCUMENT, picker.action)
                assertEquals("image/png", picker.type)
                assertEquals("fixture_3_translated.png", picker.getStringExtra(Intent.EXTRA_TITLE))
            } finally {
                export.pause().stop().destroy()
                awaitSave()
            }
        } finally {
            controller.pause().stop().destroy()
            ShadowStatFs.unregisterStats(activity.cacheDir)
            if (staging.isDirectory) staging.listFiles()?.forEach { it.delete() }
            staging.delete()
        }
    }

    @Test
    fun outOfSpaceAfterWritingBytesRemovesThePartialCopyAndKeepsTheSource() {
        val source = temporaryFolder.newFile("translated.png").apply { writeText("original content") }
        val directory = temporaryFolder.newFolder("staging")
        ShadowStatFs.registerStats(directory, 128, 128, 128)
        try {
            val failure = IOException("Write failed", ErrnoException("write", OsConstants.ENOSPC))
            val caught = assertThrows(IOException::class.java) {
                stageTranslatedPage(source, directory) { _, partial ->
                    partial.writeText("partial")
                    throw failure
                }
            }
            assertSame(failure, caught)
            assertEquals(emptyList<String>(), directory.list()?.toList())
            assertEquals("original content", source.readText())
            assertEquals(source.readText(), stageTranslatedPage(source, directory).readText())
        } finally {
            ShadowStatFs.unregisterStats(directory)
        }
    }

    private fun awaitSave() {
        EnhancementRuntime.io.submit {}.get(10, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }
}
