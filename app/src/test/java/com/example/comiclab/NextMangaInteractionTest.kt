package com.example.comiclab

import android.app.AlertDialog
import android.content.Intent
import android.widget.Button
import com.example.comiclab.core.settings.ComicLabMangaProgress
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NextMangaInteractionTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun savedProgressOffersContinueStartOverAndCancel() {
        val next = folder.newFile("next.cbz")
        MangaReaderActivity.restoreReadingProgress(RuntimeEnvironment.getApplication(), next,
            ComicLabMangaProgress(next.absolutePath, position = 3, offset = 0))
        val controller = Robolectric.buildActivity(MangaReaderActivity::class.java).setup()
        try {
            val activity = controller.get()
            MangaReaderActivity::class.java.getDeclaredField("nextManga").apply {
                isAccessible = true; set(activity, next)
            }
            activity.findViewById<Button>(R.id.btnNextManga).apply { isEnabled = true; performClick() }
            val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            val inset = dialog.window!!.decorView.background as android.graphics.drawable.InsetDrawable
            val background = inset.drawable as android.graphics.drawable.GradientDrawable
            assertTrue(background.cornerRadius > 0f)
            assertEquals(activity.getString(R.string.continue_reading), dialog.getButton(AlertDialog.BUTTON_POSITIVE).text)
            assertEquals(activity.getString(R.string.read_from_beginning), dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text)
            assertEquals(activity.getString(R.string.cancel), dialog.getButton(AlertDialog.BUTTON_NEUTRAL).text)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val launched = Shadows.shadowOf(activity).nextStartedActivity
            assertEquals(MangaReaderPrepareActivity::class.java.name, launched.component?.className)
            assertEquals(next.absolutePath, launched.getStringExtra(MangaReaderActivity.EXTRA_ARCHIVE_PATH))
            assertTrue(launched.getBooleanExtra(MangaReaderActivity.EXTRA_START_FROM_BEGINNING, false))
            assertTrue(launched.getBooleanExtra(MangaReaderActivity.EXTRA_RETURN_TO_CURRENT_PREVIEW, false))
            assertFalse(activity.isFinishing)
            activity.sendBroadcast(Intent("com.example.comiclab.NEXT_READER_READY").setPackage(activity.packageName)
                .putExtra(MangaReaderActivity.EXTRA_NEXT_HANDOFF_TOKEN,
                    launched.getStringExtra(MangaReaderActivity.EXTRA_NEXT_HANDOFF_TOKEN)))
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(activity.isFinishing)
        } finally { controller.destroy() }
    }

    @Test fun unreadPdfOpensDirectlyAndIgnoresUnrelatedHandoff() {
        val next = folder.newFile("unread.pdf")
        val controller = Robolectric.buildActivity(MangaReaderActivity::class.java).setup()
        try {
            val activity = controller.get()
            MangaReaderActivity::class.java.getDeclaredField("nextManga").apply {
                isAccessible = true; set(activity, next)
            }
            activity.findViewById<Button>(R.id.btnNextManga).apply { isEnabled = true; performClick() }
            val launched = Shadows.shadowOf(activity).nextStartedActivity
            assertEquals(MangaReaderActivity::class.java.name, launched.component?.className)
            assertFalse(launched.getBooleanExtra(MangaReaderActivity.EXTRA_START_FROM_BEGINNING, false))
            activity.sendBroadcast(Intent("com.example.comiclab.NEXT_READER_READY").setPackage(activity.packageName)
                .putExtra(MangaReaderActivity.EXTRA_NEXT_HANDOFF_TOKEN, "unrelated"))
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertFalse(activity.isFinishing)
        } finally { controller.destroy() }
    }

    @Test fun disablingNextComicHidesTheEntryAndPreventsNavigation() {
        val next = folder.newFile("disabled.pdf")
        val controller = Robolectric.buildActivity(MangaReaderActivity::class.java).setup()
        try {
            val activity = controller.get()
            val button = activity.findViewById<Button>(R.id.btnNextManga)
            MangaReaderActivity::class.java.getDeclaredField("nextManga").apply {
                isAccessible = true; set(activity, next)
            }
            button.visibility = android.view.View.VISIBLE
            button.isEnabled = true
            AppSettings.setNextMangaEnabled(activity, false)
            button.performClick()
            assertEquals(android.view.View.GONE, button.visibility)
            assertNull(Shadows.shadowOf(activity).nextStartedActivity)
        } finally {
            AppSettings.setNextMangaEnabled(controller.get(), true)
            controller.destroy()
        }
    }

    @Test fun leavingSuccessfullyOpenedNextComicShowsItsOwnPreview() {
        val current = folder.newFile("current.cbz")
        val intent = Intent(RuntimeEnvironment.getApplication(), MangaReaderActivity::class.java)
            .putExtra(MangaReaderActivity.EXTRA_RETURN_TO_CURRENT_PREVIEW, true)
        val controller = Robolectric.buildActivity(MangaReaderActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            MangaReaderActivity::class.java.getDeclaredField("archiveFile").apply {
                isAccessible = true; set(activity, current)
            }
            MangaReaderActivity::class.java.getDeclaredField("imageEntries").apply {
                isAccessible = true; set(activity, listOf("page.jpg"))
            }
            MangaReaderActivity::class.java.getDeclaredField("nextReaderWasReady").apply {
                isAccessible = true; setBoolean(activity, true)
            }
            activity.onBackPressedDispatcher.onBackPressed()
            val launched = Shadows.shadowOf(activity).nextStartedActivity
            assertEquals(MangaPreviewActivity::class.java.name, launched.component?.className)
            assertEquals(current.absolutePath, launched.getStringExtra(MangaPreviewActivity.EXTRA_ARCHIVE_PATH))
            assertTrue(launched.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
            assertTrue(activity.isFinishing)
        } finally { controller.destroy() }
    }

    @Test fun failedNextComicReturnsToPreviousReaderWithoutOpeningPreview() {
        val intent = Intent(RuntimeEnvironment.getApplication(), MangaReaderActivity::class.java)
            .putExtra(MangaReaderActivity.EXTRA_RETURN_TO_CURRENT_PREVIEW, true)
        val controller = Robolectric.buildActivity(MangaReaderActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            activity.onBackPressedDispatcher.onBackPressed()
            assertNull(Shadows.shadowOf(activity).nextStartedActivity)
            assertTrue(activity.isFinishing)
        } finally { controller.destroy() }
    }

    @Test fun ordinaryReaderExitDoesNotCreateAnExtraPreview() {
        val controller = Robolectric.buildActivity(MangaReaderActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.onBackPressedDispatcher.onBackPressed()
            assertNull(Shadows.shadowOf(activity).nextStartedActivity)
            assertTrue(activity.isFinishing)
        } finally { controller.destroy() }
    }

    @Test fun lateDecodeAfterExitDoesNotClearExistingProgress() {
        val file = folder.newFile("late.cbz")
        MangaReaderActivity.restoreReadingProgress(RuntimeEnvironment.getApplication(), file,
            ComicLabMangaProgress(file.absolutePath, position = 2, offset = 0))
        val controller = Robolectric.buildActivity(MangaReaderActivity::class.java).setup()
        try {
            val activity = controller.get()
            MangaReaderActivity::class.java.getDeclaredField("archiveFile").apply {
                isAccessible = true; set(activity, file)
            }
            MangaReaderActivity::class.java.getDeclaredField("imageEntries").apply {
                isAccessible = true; set(activity, listOf("page.jpg"))
            }
            MangaReaderActivity::class.java.getDeclaredField("startFromBeginningPending").apply {
                isAccessible = true; setBoolean(activity, true)
            }
            activity.finish()
            MangaReaderActivity::class.java.getDeclaredMethod("onReaderPageDecoded", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(activity, 0)
            assertEquals(2, MangaReaderActivity.savedReadingProgress(activity, file)?.position)
        } finally { controller.destroy() }
    }
}


