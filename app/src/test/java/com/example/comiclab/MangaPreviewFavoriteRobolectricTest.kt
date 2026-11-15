package com.example.comiclab

import android.content.Intent
import android.widget.ImageButton
import android.widget.TextView
import android.view.View
import androidx.core.content.ContextCompat
import com.example.comiclab.core.store.FavoriteComicStore
import com.example.comiclab.core.archive.ArchiveReplacementRecovery
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MangaPreviewFavoriteRobolectricTest {
    @Test fun pendingArchiveRecoveryRestoresFavoriteButton() {
        val context = RuntimeEnvironment.getApplication()
        val dir = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "preview-real-recovery").toFile()
        val file = File(dir, "comic.cbz")
        java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("page.jpg"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }
        val temporary = File(dir, "comic.cbz.delete_test.tmp")
        val rollback = File(dir, "comic.cbz.delete_test.bak")
        FavoriteComicStore.clear(context)
        assertEquals(FavoriteComicStore.RecordResult.ADDED, FavoriteComicStore.record(context, file))
        ArchiveReplacementRecovery.begin(file, temporary, rollback)
        assertTrue(file.renameTo(rollback))
        val intent = Intent(context, MangaPreviewActivity::class.java)
            .putExtra(MangaPreviewActivity.EXTRA_ARCHIVE_PATH, file.absolutePath)
        val controller = Robolectric.buildActivity(MangaPreviewActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            val button = activity.findViewById<ImageButton>(R.id.btnFavoriteComic)
            val deadline = System.currentTimeMillis() + 5000
            while ((!file.isFile || !button.isEnabled || button.imageTintList!!.defaultColor !=
                        ContextCompat.getColor(activity, R.color.comiclab_accent)) && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            }
            assertTrue(file.isFile)
            assertTrue(button.isEnabled)
            assertEquals(ContextCompat.getColor(activity, R.color.comiclab_accent), button.imageTintList!!.defaultColor)
        } finally {
            controller.pause().stop().destroy()
            FavoriteComicStore.clear(context)
            dir.deleteRecursively()
        }
    }

    @Test fun favoriteButtonTogglesStoredStateAndColor() {
        val context = RuntimeEnvironment.getApplication()
        val file = File.createTempFile("preview-favorite", ".cbz", context.cacheDir)
        FavoriteComicStore.clear(context)
        val intent = Intent(context, MangaPreviewActivity::class.java)
            .putExtra(MangaPreviewActivity.EXTRA_ARCHIVE_PATH, file.absolutePath)
        val controller = Robolectric.buildActivity(MangaPreviewActivity::class.java, intent).setup()
        val activity = controller.get()
        try {
            val id = activity.resources.getIdentifier("btnFavoriteComic", "id", activity.packageName)
            assertNotEquals("favorite button must exist", 0, id)
            val button = activity.findViewById<ImageButton>(id)
            assertEquals(ContextCompat.getColor(activity, R.color.comiclab_icon), button.imageTintList!!.defaultColor)
            button.performClick()
            assertTrue(FavoriteComicStore.isFavorite(context, file))
            assertEquals(ContextCompat.getColor(activity, R.color.comiclab_accent), button.imageTintList!!.defaultColor)
            button.performClick()
            assertFalse(FavoriteComicStore.isFavorite(context, file))
            assertEquals(ContextCompat.getColor(activity, R.color.comiclab_icon), button.imageTintList!!.defaultColor)
        } finally {
            controller.pause().stop().destroy()
            FavoriteComicStore.clear(context)
            file.delete()
        }
    }

    @Test fun unreadableFavoritesDisableButtonAndPreserveData() {
        val context = RuntimeEnvironment.getApplication()
        val file = File.createTempFile("preview-unreadable", ".cbz", context.cacheDir)
        val prefs = context.getSharedPreferences("favorite_comic_prefs", 0)
        prefs.edit().putString("favorite_comics", "invalid json").commit()
        val intent = Intent(context, MangaPreviewActivity::class.java)
            .putExtra(MangaPreviewActivity.EXTRA_ARCHIVE_PATH, file.absolutePath)
        val controller = Robolectric.buildActivity(MangaPreviewActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            val button = activity.findViewById<ImageButton>(R.id.btnFavoriteComic)
            assertFalse(button.isEnabled)
            val errorId = activity.resources.getIdentifier("tvFavoriteError", "id", activity.packageName)
            assertNotEquals("favorite error must have a persistent message", 0, errorId)
            val error = activity.findViewById<TextView>(errorId)
            assertEquals(View.VISIBLE, error.visibility)
            assertEquals(error.text, button.contentDescription)
            assertFalse(error.text.contains("本次操作未保存"))
            button.performClick()
            assertEquals("invalid json", prefs.getString("favorite_comics", null))
            controller.pause().stop()
            prefs.edit().remove("favorite_comics").commit()
            controller.restart().start().resume()
            assertTrue(button.isEnabled)
            assertEquals(View.GONE, error.visibility)
        } finally {
            controller.pause().stop().destroy()
            prefs.edit().clear().commit()
            file.delete()
        }
    }

    @Test fun missingArchiveDisablesButtonAndReloadRefreshesSavedFavorite() {
        val context = RuntimeEnvironment.getApplication()
        val dir = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "preview-recovery").toFile()
        val file = File(dir, "comic.cbz").apply { writeText("archive") }
        FavoriteComicStore.clear(context)
        assertEquals(FavoriteComicStore.RecordResult.ADDED, FavoriteComicStore.record(context, file))
        assertTrue(file.delete())
        val marker = File(dir, ".ComicLabBackups/comic.cbz.replace.properties")
        marker.parentFile!!.mkdirs()
        marker.writeText("invalid journal")
        val intent = Intent(context, MangaPreviewActivity::class.java)
            .putExtra(MangaPreviewActivity.EXTRA_ARCHIVE_PATH, file.absolutePath)
        val controller = Robolectric.buildActivity(MangaPreviewActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            val button = activity.findViewById<ImageButton>(R.id.btnFavoriteComic)
            assertFalse(button.isEnabled)
            marker.delete()
            file.writeText("archive")
            MangaPreviewActivity::class.java.getDeclaredMethod("loadArchive", File::class.java).apply {
                isAccessible = true
                invoke(activity, file)
            }
            val deadline = System.currentTimeMillis() + 5000
            while (!button.isEnabled && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            }
            assertTrue("button should refresh when loading finishes", button.isEnabled)
            assertEquals(ContextCompat.getColor(activity, R.color.comiclab_accent), button.imageTintList!!.defaultColor)
            assertEquals(activity.getString(R.string.cancel_favorite), button.contentDescription)
        } finally {
            controller.pause().stop().destroy()
            FavoriteComicStore.clear(context)
            dir.deleteRecursively()
        }
    }

}

