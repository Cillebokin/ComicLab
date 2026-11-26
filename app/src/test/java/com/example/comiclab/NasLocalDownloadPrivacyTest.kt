package com.example.comiclab

import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import com.example.comiclab.core.nas.NasCachedArchiveStore
import com.example.comiclab.core.nas.NasDirectoryEntry
import com.example.comiclab.core.nas.NasShareArchiveDownloader
import com.example.comiclab.core.nas.NasShareProfile
import com.example.comiclab.core.nas.NasShareStore
import com.example.comiclab.core.store.PrivacyStore
import com.example.comiclab.ui.browser.model.FileItem
import com.example.comiclab.ui.privacy.PrivacySessionManager
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowEnvironment
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [ShadowManageExternalStorage::class])
class NasLocalDownloadPrivacyTest {
    @get:Rule val folder = TemporaryFolder()
    private val app get() = RuntimeEnvironment.getApplication()

    @After fun lock() {
        PrivacySessionManager.lock()
        PrivacySessionManager.consumePendingNormalRestorePath()
    }

    @Test fun settingsHideBlockedHistoryAndOnlyAcceptOrdinaryDirectories() {
        val root = storageRoot()
        val hidden = File(root, "hidden shelf").apply { check(mkdir()) }
        File(root, "file.txt").writeText("fixture")
        val oldPath = "${hidden.name}/downloads"
        NasShareStore.setDownloadPath(app, oldPath)
        assertTrue(PrivacyStore.addPrivacyDirectory(app, hidden))

        val intent = Intent(app, SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_SETTINGS_SECTION, SettingsActivity.SETTINGS_SECTION_NAS)
        Robolectric.buildActivity(SettingsActivity::class.java, intent).setup().use { controller ->
            val settings = controller.get()
            val summary = settings.findViewById<TextView>(R.id.tvNasDownloadPath)
            assertTrue(summary.text.isNotBlank())
            assertFalse(summary.text.contains(hidden.name))
            settings.findViewById<View>(R.id.btnNasDownloadPathChange).performClick()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val input = checkNotNull(dialog.findViewById<EditText>(R.id.inputNasDownloadPath))
            val error = checkNotNull(dialog.findViewById<TextView>(R.id.tvNasDownloadPathError))
            assertEquals("", input.text.toString())

            val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            for (invalid in listOf(oldPath, "file.txt/subfolder", "../outside")) {
                input.setText(invalid)
                save.performClick()
                assertTrue(dialog.isShowing)
                assertEquals(View.VISIBLE, error.visibility)
                assertEquals(settings.getString(R.string.nas_download_path_choose_ordinary), error.text.toString())
                assertEquals(oldPath, NasShareStore.downloadPath(app))
            }

            input.setText("Download\\Manga")
            save.performClick()
            assertFalse(dialog.isShowing)
            assertEquals("Download/Manga", NasShareStore.downloadPath(app))
            assertEquals("Download/Manga", summary.text.toString())
            assertFalse(File(root, "Download").exists())
        }
    }

    @Test fun settingsHideTheSavedPathWhenItsDirectoryIsMarkedWithTheEditorOpen() {
        val root = storageRoot()
        val path = File(root, "later hidden").apply { check(mkdir()) }
        NasShareStore.setDownloadPath(app, path.name)
        Robolectric.buildActivity(SettingsActivity::class.java).setup().use { controller ->
            val settings = controller.get()
            val summary = settings.findViewById<TextView>(R.id.tvNasDownloadPath)
            assertEquals(path.name, summary.text.toString())
            settings.findViewById<View>(R.id.btnNasDownloadPathChange).performClick()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertTrue(dialog.isShowing)
            assertTrue(PrivacyStore.addPrivacyDirectory(app, path))
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(dialog.isShowing)
            assertEquals(settings.getString(R.string.nas_download_path_choose_ordinary), summary.text.toString())
            assertEquals(path.name, NasShareStore.downloadPath(app))
        }
    }

    @Test fun downloadButtonRejectsNewlyMarkedDirectoryBeforeCreatingIt() {
        val root = storageRoot()
        val hidden = File(root, "hidden shelf").apply { check(mkdir()) }
        val relativePath = "${hidden.name}/NotCreated"
        NasShareStore.setDownloadPath(app, relativePath)
        assertTrue(PrivacyStore.addPrivacyDirectory(app, hidden))
        withMain(root) { activity ->
            ReflectionHelpers.setField(activity, "selectedNasProfile", profile.copy(username = ""))
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "startNasArchiveDownload",
                ReflectionHelpers.ClassParameter.from(NasDirectoryEntry::class.java, archive),
                ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, true))

            assertFalse(File(root, relativePath).exists())
            assertFalse(ReflectionHelpers.getField(activity, "nasDownloadInProgress"))
            assertEquals(activity.getString(R.string.nas_download_path_choose_ordinary),
                ShadowToast.getTextOfLatestToast())
        }
    }

    @Test fun fromPrivacyTheNasButtonLocksBeforeSwitchAndRestoresNormalScroll() =
        checkLocalScrollAfterNasSwitch { controller ->
            assertTrue(controller.get().findViewById<View>(R.id.btnSourceLocal).performClick())
        }

    @Test fun returningFromBackgroundInNasKeepsTheSavedLocalScroll() =
        checkLocalScrollAfterNasSwitch { controller ->
            val activity = controller.get()
            controller.pause().stop().start().resume()
            awaitDirectory(activity)
            assertNasSelected(activity)
            assertEquals(View.GONE, activity.findViewById<ListView>(R.id.listFiles).visibility)
            assertTrue(activity.findViewById<View>(R.id.btnSourceLocal).performClick())
        }

    @Test fun disablingNasFromItsSettingsAutomaticallyRestoresTheSavedLocalScroll() =
        checkLocalScrollAfterNasSwitch { controller ->
            val activity = controller.get()
            assertTrue(activity.findViewById<View>(R.id.btnNasManageShares).performClick())
            val intent = checkNotNull(shadowOf(activity).nextStartedActivity)
            assertEquals(SettingsActivity::class.java.name, intent.component?.className)
            assertEquals(SettingsActivity.SETTINGS_SECTION_NAS,
                intent.getStringExtra(SettingsActivity.EXTRA_SETTINGS_SECTION))

            controller.pause().stop()
            Robolectric.buildActivity(SettingsActivity::class.java, intent).setup().use { settingsController ->
                val settings = settingsController.get()
                val nasSwitch = settings.findViewById<SwitchCompat>(R.id.switchNasEnabled)
                assertTrue(nasSwitch.isChecked)
                nasSwitch.performClick()
                assertFalse(nasSwitch.isChecked)
                assertFalse(NasShareStore.isNasEnabled(app))
                assertTrue(settings.findViewById<View>(R.id.btnBack).performClick())
                assertTrue(settings.isFinishing)
            }
            controller.start().resume()
            assertEquals(View.GONE, activity.findViewById<View>(R.id.sourceSwitchDock).visibility)
        }

    private fun checkLocalScrollAfterNasSwitch(returnToLocal: (ActivityController<MainActivity>) -> Unit) {
        val root = storageRoot()
        val ordinary = File(root, "ordinary").apply { check(mkdir()) }
        repeat(24) { File(ordinary, "page%02d.cbz".format(it)).writeText("fixture") }
        val hidden = File(root, "private shelf").apply { check(mkdir()) }
        assertTrue(PrivacyStore.addPrivacyDirectory(app, hidden))
        withMainController(ordinary) { controller ->
            val activity = controller.get()
            val list = activity.findViewById<ListView>(R.id.listFiles)
            layout(list)
            list.setSelectionFromTop(6, -17)
            layout(list)
            val anchor = (list.getItemAtPosition(list.firstVisiblePosition) as FileItem).file
            val offset = list.getChildAt(0).top
            assertNotNull(anchor)
            assertTrue(activity.enterPrivacyBrowser(PrivacySessionManager.sessionEpoch))
            awaitDirectory(activity)
            layout(list)
            assertTrue(displayedFiles(list).contains(hidden))
            val epoch = PrivacySessionManager.sessionEpoch

            val nasButton = activity.findViewById<View>(R.id.btnSourceNas)
            assertTrue(nasButton.isEnabled)
            val lockObserver = PrivacySessionManager.Listener {
                assertFalse(ReflectionHelpers.getField(activity, "isNasSourceSelected"))
            }
            PrivacySessionManager.addListener(lockObserver)
            try {
                nasButton.performClick()
            } finally {
                PrivacySessionManager.removeListener(lockObserver)
            }
            assertFalse(PrivacySessionManager.isCurrentSession(epoch))
            assertNasSelected(activity)
            assertFalse(displayedFiles(list).contains(hidden))
            assertTrue(isSecure(activity))
            awaitDirectory(activity)
            activity.window.decorView.viewTreeObserver.dispatchOnPreDraw()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(isSecure(activity))
            assertFalse(activity.findViewById<EditText>(R.id.etPath).text.contains(ordinary.path))
            assertNull(PrivacySessionManager.consumePendingNormalRestorePath())

            returnToLocal(controller)
            awaitDirectory(activity)
            layout(list)
            assertFalse(ReflectionHelpers.getField(activity, "isNasSourceSelected"))
            assertEquals(ordinary.path, activity.currentPathForTest)
            assertEquals(anchor, (list.getItemAtPosition(list.firstVisiblePosition) as FileItem).file)
            assertEquals(offset, list.getChildAt(0).top)
        }
    }

    @Test fun returnFromNasFallsBackWhenThePreviousLocalDirectoryHasBeenMarked() {
        val root = storageRoot()
        val ordinary = File(root, "later hidden").apply { check(mkdir()) }
        val book = File(ordinary, "private.cbz").apply { writeText("fixture") }
        withMain(ordinary) { activity ->
            activity.findViewById<View>(R.id.btnSourceNas).performClick()
            assertNasSelected(activity)
            ReflectionHelpers.setField(activity, "pendingLocalDownloadDirectory", ordinary)
            assertTrue(PrivacyStore.addPrivacyDirectory(app, ordinary))
            activity.findViewById<View>(R.id.btnSourceLocal).performClick()
            awaitDirectory(activity)
            assertEquals(root.path, activity.currentPathForTest)
            assertFalse(displayedFiles(activity.findViewById(R.id.listFiles)).contains(book))
            assertNull(ReflectionHelpers.getField(activity, "pendingLocalDownloadDirectory"))
        }
    }

    @Test fun returningFromNasRefreshesOnlyTheMatchingOrdinaryDownloadDirectory() {
        val root = storageRoot()
        val ordinary = File(root, "ordinary").apply { check(mkdir()) }
        val other = File(root, "other").apply { check(mkdir()) }
        withMain(ordinary) { activity ->
            val list = activity.findViewById<ListView>(R.id.listFiles)
            val generation = ReflectionHelpers.getField<AtomicInteger>(activity, "directoryLoadGeneration")
            activity.findViewById<View>(R.id.btnSourceNas).performClick()
            assertNasSelected(activity)
            val unchangedGeneration = generation.get()
            ReflectionHelpers.setField(activity, "pendingLocalDownloadDirectory", other)
            activity.findViewById<View>(R.id.btnSourceLocal).performClick()
            assertEquals(unchangedGeneration, generation.get())

            activity.findViewById<View>(R.id.btnSourceNas).performClick()
            val newBook = File(ordinary, "new download.cbz").apply { writeText("fixture") }
            ReflectionHelpers.setField(activity, "pendingLocalDownloadDirectory", ordinary)
            activity.findViewById<View>(R.id.btnSourceLocal).performClick()
            awaitDirectory(activity)
            assertTrue(displayedFiles(list).contains(newBook))
            assertNull(ReflectionHelpers.getField(activity, "pendingLocalDownloadDirectory"))
        }
    }

    @Test fun nasReconnectFromCachedArchiveLocksBeforeOpeningItsRemoteFolder() {
        val root = storageRoot()
        NasShareStore.save(app, profile, "", rememberPassword = false)
        withMain(root) { activity ->
            val cache = NasShareArchiveDownloader.cacheDirectory(activity.cacheDir)
            val file = NasShareArchiveDownloader.cacheFile(cache, profile, archive).apply {
                check(parentFile!!.mkdirs())
                writeText("sample")
            }
            NasCachedArchiveStore.prepareForReading(app, file, profile, archive, downloaded = false)
            assertTrue(file.delete())
            assertTrue(activity.enterPrivacyBrowser(PrivacySessionManager.sessionEpoch))
            awaitDirectory(activity)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "reopenNasArchive",
                ReflectionHelpers.ClassParameter.from(File::class.java, file),
                ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, true))

            assertFalse(PrivacySessionManager.isUnlocked)
            assertNasSelected(activity)
            assertEquals("series", ReflectionHelpers.getField(activity, "currentNasRelativePath"))
            assertNotNull(ReflectionHelpers.getField(activity, "pendingNasArchiveReopen"))
        }
    }

    private fun storageRoot(): File = folder.newFolder("storage").also {
        ShadowEnvironment.setExternalStorageDirectory(it.toPath())
    }

    private fun withMain(directory: File, block: (MainActivity) -> Unit) =
        withMainController(directory) { block(it.get()) }

    private fun withMainController(directory: File, block: (ActivityController<MainActivity>) -> Unit) {
        NasShareStore.setNasEnabled(app, true)
        assertTrue(app.getSharedPreferences("saf_prefs", 0).edit()
            .putString("current_path", directory.path)
            .putBoolean("storage_permission_prompted", true).commit())
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            awaitDirectory(controller.get())
            block(controller)
        }
    }

    private fun assertNasSelected(activity: MainActivity) {
        assertTrue(ReflectionHelpers.getField(activity, "isNasSourceSelected"))
    }

    private fun isSecure(activity: MainActivity): Boolean =
        activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    private fun awaitDirectory(activity: MainActivity) {
        activity.directoryLoadExecutorForTest.submit {}.get(10, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun layout(list: ListView) {
        list.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY))
        list.layout(0, 0, 800, 500)
        shadowOf(Looper.getMainLooper()).idle()
        list.layout(0, 0, 800, 500)
    }

    private fun displayedFiles(list: ListView): List<File> = (0 until list.adapter.count)
        .mapNotNull { (list.adapter.getItem(it) as FileItem).file }

    private val profile = NasShareProfile("nas", "Library", "localhost", "comics", "user", false)
    private val archive = NasDirectoryEntry("book.cbz", "series/book.cbz", false, 6L, 1L)
}
