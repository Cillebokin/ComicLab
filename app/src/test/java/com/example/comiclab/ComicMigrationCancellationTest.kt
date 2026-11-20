package com.example.comiclab

import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.os.Looper
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [ShadowManageExternalStorage::class])
class ComicMigrationCancellationTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun stopBeforeChoosingTargetLeavesAllFilesUnprocessed() {
        withMigration(listOf("first.cbz", "second.cbz")) { activity, source, destination ->
            latestDialog().getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(activity.getString(R.string.comic_migration_stopped, 0, 0, 0), status(activity))
            assertEquals("0/2", progress(activity))
            assertEquals(0, history(activity).childCount)
            assertTrue(destination.listFiles().orEmpty().isEmpty())
            assertEquals(2, source.listFiles().orEmpty().size)
        }
    }

    @Test
    fun stopAfterPublicationRecordsTheCopyWithoutOpeningTheNextTargetPicker() {
        withMigration(listOf("first.cbz", "second.cbz")) { activity, source, destination ->
            selectDefaultTarget()
            val published = File(destination, "first.cbz")
            awaitCondition("first copy was not published", drainMain = false) { published.isFile }
            assertEquals(0, history(activity).childCount)

            // Invoke the same stop path as the dialog while the result is still queued.
            activity.findViewById<ImageButton>(R.id.btnBack).performClick()
            assertEquals(activity.getString(R.string.comic_migration_stopping), status(activity))
            awaitCondition("published copy was not recorded") { history(activity).childCount == 1 }

            assertEquals(File(source, "first.cbz").readText(), published.readText())
            assertFalse(File(destination, "second.cbz").exists())
            assertEquals("1/2", progress(activity))
            assertEquals(activity.getString(R.string.comic_migration_stopped, 1, 0, 0), status(activity))
            val entry = history(activity).getChildAt(0)
            assertEquals("first.cbz", entry.findViewById<TextView>(R.id.tvComicMigrationHistoryFileName).text.toString())
            assertEquals(
                activity.getString(R.string.comic_migration_history_copied, destination.absolutePath),
                entry.findViewById<TextView>(R.id.tvComicMigrationHistoryDestination).text.toString()
            )
            assertFalse(latestDialog().isShowing)
        }
    }

    @Test
    fun stopAfterFinalPublicationStillShowsTheMigrationCompleted() {
        withMigration(listOf("only.cbz")) { activity, _, destination ->
            selectDefaultTarget()
            awaitCondition("final copy was not published", drainMain = false) {
                File(destination, "only.cbz").isFile
            }

            activity.findViewById<ImageButton>(R.id.btnBack).performClick()
            awaitCondition("final copy was not recorded") { history(activity).childCount == 1 }

            assertEquals("1/1", progress(activity))
            assertEquals(
                activity.getString(R.string.comic_migration_complete, 1, 1, 0, 0),
                status(activity)
            )
        }
    }

    private fun withMigration(names: List<String>, test: (ComicMigrationActivity, File, File) -> Unit) {
        val root = temporaryFolder.newFolder()
        val source = File(root, "source").apply { check(mkdir()) }
        val destination = File(root, "destination").apply { check(mkdir()) }
        names.forEach { name -> File(source, name).writeText("fixture for $name") }

        ShadowEnvironment.setExternalStorageDirectory(root.toPath())
        val intent = Intent(RuntimeEnvironment.getApplication(), ComicMigrationActivity::class.java)
            .putExtra(ComicMigrationActivity.EXTRA_SOURCE_DIRECTORY_PATH, source.absolutePath)
        val controller = Robolectric.buildActivity(ComicMigrationActivity::class.java, intent).setup()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            val directories = latestDialog().findViewById<ListView>(R.id.listComicMigrationPickerDirectories)
            val position = (0 until directories.count).single { directories.getItemAtPosition(it) == destination }
            directories.performItemClick(null, position, directories.getItemIdAtPosition(position))
            latestDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            latestDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            awaitCondition("target picker was not shown") {
                val dialog = latestDialog()
                dialog.isShowing && dialog.findViewById<ListView>(R.id.listComicMigrationTargetDirectories) != null
            }
            test(controller.get(), source, destination)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun selectDefaultTarget() {
        val targets = latestDialog().findViewById<ListView>(R.id.listComicMigrationTargetDirectories)
        assertEquals(1, targets.count)
        targets.performItemClick(null, 0, targets.getItemIdAtPosition(0))
    }

    private fun latestDialog(): AlertDialog = checkNotNull(ShadowAlertDialog.getLatestAlertDialog())

    private fun history(activity: ComicMigrationActivity): LinearLayout =
        activity.findViewById(R.id.tvComicMigrationHistory)

    private fun status(activity: ComicMigrationActivity): String =
        activity.findViewById<TextView>(R.id.tvComicMigrationStatus).text.toString()

    private fun progress(activity: ComicMigrationActivity): String =
        activity.findViewById<TextView>(R.id.tvComicMigrationProgress).text.toString()

    private fun awaitCondition(message: String, drainMain: Boolean = true, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            if (drainMain) shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        assertTrue(message, condition())
    }
}
