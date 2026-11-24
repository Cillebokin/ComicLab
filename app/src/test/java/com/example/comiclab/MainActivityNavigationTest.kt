package com.example.comiclab

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ListView
import com.example.comiclab.ui.browser.FileListScrollState
import com.example.comiclab.ui.browser.model.FileItem
import com.example.comiclab.core.store.FavoriteComicStore
import com.example.comiclab.core.store.FavoritePathStore
import com.example.comiclab.core.store.PrivacyStore
import com.example.comiclab.core.store.ReadingHistoryStore
import com.example.comiclab.ui.browser.panels.SidePanelController
import com.example.comiclab.ui.privacy.PrivacyAccessGuard.PRIVACY_VIRTUAL_ROOT_PATH
import com.example.comiclab.ui.privacy.PrivacySessionManager
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowEnvironment
import org.robolectric.util.ReflectionHelpers

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [ShadowManageExternalStorage::class])
class MainActivityNavigationTest {

    companion object {
        // The root is read from the current environment for each Activity instance.
        @ClassRule @JvmField val temporaryFolder = TemporaryFolder()
    }

    @Before
    fun cleanFixture() {
        File(temporaryFolder.root, "storage").deleteRecursively()
    }

    @After
    fun restorePermission() {
        ShadowManageExternalStorage.allowed = true
        PrivacySessionManager.lock()
        PrivacySessionManager.consumePendingNormalRestorePath()
    }

    @Test
    fun explicitOrdinaryIntentAfterPrivacyLockTakesPriorityOverPendingRestore() {
        checkIntentAfterPrivacyLock(targetIsPrivate = false)
    }

    @Test
    fun rejectedPrivateIntentAfterPrivacyLockPreservesPendingRestore() {
        checkIntentAfterPrivacyLock(targetIsPrivate = true)
    }

    private fun checkIntentAfterPrivacyLock(targetIsPrivate: Boolean) {
        val root = temporaryFolder.newFolder("storage")
        val original = File(root, "original ordinary").apply { mkdir() }
        val target = File(root, "explicit target").apply { mkdir() }
        val book = File(target, "target.cbz").apply { writeText("fixture") }
        ShadowEnvironment.setExternalStorageDirectory(root.toPath())
        val context = RuntimeEnvironment.getApplication()
        if (targetIsPrivate) assertTrue(PrivacyStore.addPrivacyDirectory(context, target))
        val prefs = context.getSharedPreferences("saf_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("current_path", original.path).putBoolean("storage_permission_prompted", true).commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            awaitDirectoryLoad(activity)
            assertEquals(original.path, activity.currentPathForTest)
            assertTrue(activity.enterPrivacyBrowser(PrivacySessionManager.sessionEpoch))
            awaitDirectoryLoad(activity)

            controller.pause()
            PrivacySessionManager.lock()
            deliverCenterTarget(activity, book.path)
            controller.resume()
            awaitDirectoryLoad(activity)

            val expected = if (targetIsPrivate) original else target
            assertEquals(expected.path, activity.currentPathForTest)
            assertEquals(expected.path, prefs.getString("current_path", null))
            assertEquals(expected.path, activity.findViewById<EditText>(R.id.etPath).text.toString())
            assertFalse(PrivacySessionManager.isUnlocked)
            assertNull(PrivacySessionManager.consumePendingNormalRestorePath())
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    fun ordinaryLockDismissesSidebarsAndKeepsDirectoryScrollPosition() {
        val root = temporaryFolder.newFolder("storage")
        val ordinary = File(root, "ordinary").apply { mkdir() }
        repeat(24) { File(ordinary, "page%02d.txt".format(it)).writeText("page") }
        ShadowEnvironment.setExternalStorageDirectory(root.toPath())
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("saf_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("current_path", ordinary.path).putBoolean("storage_permission_prompted", true).commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            awaitDirectoryLoad(activity)
            val list = activity.findViewById<ListView>(R.id.listFiles)
            list.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY))
            list.layout(0, 0, 800, 500)
            shadowOf(Looper.getMainLooper()).idle()
            list.setSelectionFromTop(6, -17)
            val panels = ReflectionHelpers.getField<SidePanelController>(activity, "sidePanelController")
            for ((button, popupField) in listOf(
                R.id.btnReadingHistory to "readingHistoryPopupWindow",
                R.id.btnFavoritePaths to "favoritePathsPopupWindow",
                R.id.btnFavoriteComics to "favoriteComicsPopupWindow"
            )) {
                assertTrue(activity.findViewById<View>(button).performClick())
                assertTrue(ReflectionHelpers.getField<Any?>(panels, popupField) != null)
                shadowOf(Looper.getMainLooper()).idle()
                list.layout(0, 0, 800, 500)
                val position = list.firstVisiblePosition
                val offset = list.getChildAt(0).top
                val rows = displayedFiles(list)
                assertTrue(position > 0)
                PrivacySessionManager.lock()
                assertNull(ReflectionHelpers.getField<Any?>(panels, popupField))
                shadowOf(Looper.getMainLooper()).idle()
                list.layout(0, 0, 800, 500)
                assertEquals(ordinary.path, activity.currentPathForTest)
                assertEquals(rows, displayedFiles(list))
                assertEquals(popupField, position, list.firstVisiblePosition)
                assertEquals(popupField, offset, list.getChildAt(0).top)
                assertNull(PrivacySessionManager.consumePendingNormalRestorePath())
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    fun coldStartRejectsSavedPrivatePathAndLockRestoresNormalDirectoryOnce() {
        val root = temporaryFolder.newFolder("storage")
        val ordinary = File(root, "ordinary").apply { mkdir() }
        val hidden = File(root, "private").apply { mkdir() }
        val nested = File(hidden, "nested").apply { mkdir() }
        File(ordinary, "visible.txt").writeText("page")
        ShadowEnvironment.setExternalStorageDirectory(root.toPath())
        val context = RuntimeEnvironment.getApplication()
        assertTrue(PrivacyStore.addPrivacyDirectory(context, hidden))
        val prefs = context.getSharedPreferences("saf_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("current_path", hidden.path).putBoolean("storage_permission_prompted", true).commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val list = activity.findViewById<ListView>(R.id.listFiles)
            val path = activity.findViewById<EditText>(R.id.etPath)
            awaitDirectoryLoad(activity)
            assertEquals(root.path, activity.currentPathForTest)
            assertEquals(listOf(ordinary), displayedFiles(list))
            clickBrowserRow(list, ordinary)
            awaitDirectoryLoad(activity)

            assertTrue(activity.enterPrivacyBrowser(PrivacySessionManager.sessionEpoch))
            awaitDirectoryLoad(activity)
            assertEquals(activity.getString(R.string.privacy_browser_title), path.text.toString())
            assertEquals(listOf(hidden), displayedFiles(list))
            assertEquals(1, list.adapter.count)
            assertTrue(isSecure(activity))
            clickBrowserRow(list, hidden)
            awaitDirectoryLoad(activity)
            clickBrowserRow(list, nested)
            awaitDirectoryLoad(activity)
            activity.findViewById<View>(R.id.btnBack).performClick()
            awaitDirectoryLoad(activity)
            assertEquals(hidden.path, activity.currentPathForTest)
            activity.onBackPressedDispatcher.onBackPressed()
            awaitDirectoryLoad(activity)
            assertEquals(PRIVACY_VIRTUAL_ROOT_PATH, activity.currentPathForTest)
            clickBrowserRow(list, hidden)
            awaitDirectoryLoad(activity)
            assertEquals(ordinary.path, prefs.getString("current_path", null))
            activity.directoryScrollStateCacheForTest.save(
                FileListScrollState(ordinary.path, 1, -17, File(ordinary, "visible.txt").path, false)
            )

            PrivacySessionManager.lock()
            assertEquals("", path.text.toString())
            assertEquals(0, list.adapter.count)
            assertTrue(isSecure(activity))
            controller.pause().resume()
            awaitDirectoryLoad(activity)
            activity.window.decorView.viewTreeObserver.dispatchOnPreDraw()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(ordinary.path, activity.currentPathForTest)
            assertEquals(-17, activity.requestedScrollTopForTest)
            assertFalse(isSecure(activity))
            assertNull(PrivacySessionManager.consumePendingNormalRestorePath())
            val before = displayedFiles(list)
            PrivacySessionManager.lock()
            assertEquals(before, displayedFiles(list))
            assertEquals(ordinary.path, path.text.toString())
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    fun sidebarFiltersAllThreePanelsAndExplicitNormalDirectoryJumpExitsPrivacy() {
        val root = temporaryFolder.newFolder("storage")
        val ordinary = File(root, "ordinary").apply { mkdir() }
        val hidden = File(root, "private").apply { mkdir() }
        val publicBook = File(ordinary, "ordinary.cbz").apply { writeText("fixture") }
        val privateBook = File(hidden, "private.cbz").apply { writeText("fixture") }
        ShadowEnvironment.setExternalStorageDirectory(root.toPath())
        val context = RuntimeEnvironment.getApplication()
        FavoritePathStore.record(context, ordinary)
        FavoritePathStore.record(context, hidden)
        listOf(publicBook, privateBook).forEach {
            ReadingHistoryStore.record(context, it)
            FavoriteComicStore.record(context, it)
        }
        assertTrue(PrivacyStore.addPrivacyDirectory(context, hidden))
        context.getSharedPreferences("saf_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("storage_permission_prompted", true).commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            awaitDirectoryLoad(activity)
            val panels = ReflectionHelpers.getField<SidePanelController>(activity, "sidePanelController")
            fun <T> openPanel(button: Int, adapterName: String): List<T> {
                assertTrue(activity.findViewById<View>(button).performClick())
                val adapter = ReflectionHelpers.getField<Any>(panels, adapterName)
                return ReflectionHelpers.getField<List<T>>(adapter, "items")
            }
            assertEquals(listOf(publicBook), openPanel<ReadingHistoryStore.Item>(R.id.btnReadingHistory, "readingHistoryAdapter").map { it.file })
            assertEquals(listOf(publicBook), openPanel<FavoriteComicStore.Item>(R.id.btnFavoriteComics, "favoriteComicAdapter").map { it.file })
            assertEquals(listOf(ordinary), openPanel<FavoritePathStore.Item>(R.id.btnFavoritePaths, "favoritePathAdapter").map { it.directory })
            assertEquals(2, FavoritePathStore.items(context).size)
            assertTrue(activity.enterPrivacyBrowser(PrivacySessionManager.sessionEpoch))
            awaitDirectoryLoad(activity)
            assertEquals(setOf(publicBook, privateBook), openPanel<ReadingHistoryStore.Item>(R.id.btnReadingHistory, "readingHistoryAdapter").map { it.file }.toSet())
            val comics = openPanel<FavoriteComicStore.Item>(R.id.btnFavoriteComics, "favoriteComicAdapter")
            assertEquals(setOf(publicBook, privateBook), comics.map { it.file }.toSet())
            val comicAdapter = ReflectionHelpers.getField<Any>(panels, "favoriteComicAdapter")
            val openComic = ReflectionHelpers.getField<(FavoriteComicStore.Item) -> Unit>(comicAdapter, "onItemClick")
            openComic(comics.single { it.file == publicBook })
            assertTrue(PrivacySessionManager.isUnlocked)
            assertEquals(publicBook.path, shadowOf(activity).nextStartedActivity.getStringExtra(MangaPreviewActivity.EXTRA_ARCHIVE_PATH))

            val folders = openPanel<FavoritePathStore.Item>(R.id.btnFavoritePaths, "favoritePathAdapter")
            assertEquals(setOf(ordinary, hidden), folders.map { it.directory }.toSet())
            val folderAdapter = ReflectionHelpers.getField<Any>(panels, "favoritePathAdapter")
            val openFolder = ReflectionHelpers.getField<(FavoritePathStore.Item) -> Unit>(folderAdapter, "onItemClick")
            openFolder(folders.single { it.directory == ordinary })
            awaitDirectoryLoad(activity)
            assertFalse(PrivacySessionManager.isUnlocked)
            assertEquals(ordinary.path, activity.currentPathForTest)
            openFolder(folders.single { it.directory == hidden })
            assertEquals(ordinary.path, activity.currentPathForTest)
            assertNull(PrivacySessionManager.consumePendingNormalRestorePath())
            assertEquals(2, FavoriteComicStore.items(context).size)
            assertEquals(2, ReadingHistoryStore.items(context).size)

            openPanel<FavoritePathStore.Item>(R.id.btnFavoritePaths, "favoritePathAdapter")
            assertTrue(PrivacyStore.addPrivacyDirectory(context, ordinary))
            shadowOf(Looper.getMainLooper()).idle()
            awaitDirectoryLoad(activity)
            assertNull(ReflectionHelpers.getField<Any?>(panels, "favoritePathsPopupWindow"))
            assertEquals(root.path, activity.currentPathForTest)
            assertEquals(emptyList<FavoritePathStore.Item>(),
                openPanel<FavoritePathStore.Item>(R.id.btnFavoritePaths, "favoritePathAdapter"))
            assertEquals(2, FavoritePathStore.items(context).size)

            assertTrue(activity.enterPrivacyBrowser(PrivacySessionManager.sessionEpoch))
            assertTrue(PrivacyStore.removePrivacyDirectory(context, hidden))
            assertTrue(PrivacyStore.removePrivacyDirectory(context, ordinary))
            shadowOf(Looper.getMainLooper()).idle()
            awaitDirectoryLoad(activity)
            assertEquals(PRIVACY_VIRTUAL_ROOT_PATH, activity.currentPathForTest)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.tvPrivacyBrowserEmpty).visibility)
            activity.onBackPressedDispatcher.onBackPressed()
            awaitDirectoryLoad(activity)
            assertFalse(PrivacySessionManager.isUnlocked)
            assertEquals(root.path, activity.currentPathForTest)
        } finally { controller.pause().stop().destroy() }
    }

    private fun displayedFiles(list: ListView): List<File> = (0 until list.adapter.count).mapNotNull {
        (list.adapter.getItem(it) as FileItem).file
    }

    private fun clickBrowserRow(list: ListView, file: File) {
        val index = (0 until list.adapter.count).first { (list.adapter.getItem(it) as FileItem).file == file }
        assertTrue(list.performItemClick(View(list.context), index, list.adapter.getItemId(index)))
    }

    private fun isSecure(activity: MainActivity): Boolean =
        activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    @Test
    fun deniedPermissionKeepsSavedPathAndShowsPermissionMessageWithEmptyRows() {
        val storageRoot = temporaryFolder.newFolder("storage")
        val current = File(storageRoot, "current").apply {
            mkdir()
            File(this, "visible.txt").writeText("page")
        }
        ShadowEnvironment.setExternalStorageDirectory(storageRoot.toPath())
        val preferences = RuntimeEnvironment.getApplication()
            .getSharedPreferences("saf_prefs", Context.MODE_PRIVATE)
        preferences.edit().putString("current_path", current.absolutePath)
            .putBoolean("storage_permission_prompted", true).apply()
        ShadowManageExternalStorage.allowed = true
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            awaitDirectoryLoad(activity)
            val list = activity.findViewById<ListView>(R.id.listFiles)
            assertTrue(list.adapter.count > 0)

            ShadowManageExternalStorage.allowed = false
            invokeLoadCurrentDirectory(activity)
            assertEquals(activity.getString(R.string.storage_permission_required),
                activity.findViewById<EditText>(R.id.etPath).text.toString())
            assertEquals(0, list.adapter.count)
            assertEquals(current.absolutePath, preferences.getString("current_path", null))
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    @Config(sdk = [33])
    fun invalidCurrentDirectoryFallsBackToRootAndUpdatesPathField() {
        val storageRoot = temporaryFolder.newFolder("storage")
        File(storageRoot, "visible.txt").writeText("page")
        val current = File(storageRoot, "current").apply {
            mkdir()
            File(this, "child.txt").writeText("page")
            File(this, "child2.txt").writeText("page")
        }
        ShadowEnvironment.setExternalStorageDirectory(storageRoot.toPath())
        val preferences = RuntimeEnvironment.getApplication()
            .getSharedPreferences("saf_prefs", Context.MODE_PRIVATE)
        preferences.edit().putString("current_path", current.absolutePath)
            .putBoolean("storage_permission_prompted", true).apply()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            awaitDirectoryLoad(activity)
            assertEquals(3, activity.findViewById<ListView>(R.id.listFiles).adapter.count)
            assertEquals(current.absolutePath,
                activity.findViewById<EditText>(R.id.etPath).text.toString())

            activity.currentPathForTest = File(storageRoot, "missing").absolutePath
            invokeLoadCurrentDirectory(activity)
            awaitDirectoryLoad(activity)

            assertEquals(storageRoot.absolutePath, activity.currentPathForTest)
            assertEquals(storageRoot.absolutePath,
                activity.findViewById<EditText>(R.id.etPath).text.toString())
            assertEquals(storageRoot.absolutePath, preferences.getString("current_path", null))
            assertEquals(2, activity.findViewById<ListView>(R.id.listFiles).adapter.count)
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    fun externalCenterTargetCannotNavigateOutsideSharedStorageRoot() {
        val storageRoot = temporaryFolder.newFolder("storage")
        val privateDirectory = File(
            RuntimeEnvironment.getApplication().filesDir,
            "p8-private-fixture"
        ).apply { mkdirs() }
        File(privateDirectory, "private.txt").writeText("private")
        val siblingDirectory = File(temporaryFolder.root, "storage-private").apply { mkdirs() }
        File(siblingDirectory, "private.txt").writeText("private")
        ShadowEnvironment.setExternalStorageDirectory(storageRoot.toPath())
        val preferences = RuntimeEnvironment.getApplication()
            .getSharedPreferences("saf_prefs", Context.MODE_PRIVATE)
        preferences.edit()
            .putString("current_path", storageRoot.absolutePath)
            .putBoolean("storage_permission_prompted", true)
            .apply()

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            awaitDirectoryLoad(activity)

            listOf(
                File(privateDirectory, "private.txt").absolutePath,
                File(siblingDirectory, "private.txt").absolutePath,
                File(storageRoot, "../${siblingDirectory.name}/private.txt").path
            ).forEach { targetPath ->
                deliverCenterTarget(activity, targetPath)
                awaitDirectoryLoad(activity)
                assertEquals(
                    storageRoot.canonicalPath,
                    File(activity.currentPathForTest).canonicalPath
                )
            }
        } finally {
            controller.pause().stop().destroy()
            privateDirectory.deleteRecursively()
            siblingDirectory.deleteRecursively()
        }
    }

    @Test
    fun externalCenterTargetInsideSharedStorageRemainsSupported() {
        val preferences = RuntimeEnvironment.getApplication()
            .getSharedPreferences("saf_prefs", Context.MODE_PRIVATE)
        preferences
            .edit()
            .remove("current_path")
            .putBoolean("storage_permission_prompted", true)
            .apply()

        var targetDirectory: File? = null
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            awaitDirectoryLoad(activity)
            val storageRoot = File(activity.currentPathForTest).apply { mkdirs() }
            val createdTargetDirectory = File(storageRoot, "P8_Comics").apply { mkdirs() }
            targetDirectory = createdTargetDirectory
            val targetFile = File(createdTargetDirectory, "comic.cbz").apply { writeText("fixture") }

            deliverCenterTarget(activity, targetFile.absolutePath)
            awaitDirectoryLoad(activity)

            assertEquals(
                createdTargetDirectory.canonicalPath,
                File(activity.currentPathForTest).canonicalPath
            )
        } finally {
            controller.pause().stop().destroy()
            targetDirectory?.deleteRecursively()
        }
    }

    @Test
    fun coldStartCenterTargetCannotNavigateToAppPrivateDirectory() {
        val storageRoot = temporaryFolder.newFolder("storage")
        val appContext = RuntimeEnvironment.getApplication()
        val privateDirectory = File(appContext.filesDir, "p8-cold-start-private").apply { mkdirs() }
        File(privateDirectory, "private.txt").writeText("private")
        ShadowEnvironment.setExternalStorageDirectory(storageRoot.toPath())
        appContext.getSharedPreferences("saf_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("current_path", storageRoot.absolutePath)
            .putBoolean("storage_permission_prompted", true)
            .apply()

        val launchIntent = Intent(appContext, MainActivity::class.java).putExtra(
            MainActivity.EXTRA_CENTER_TARGET_PATH,
            File(privateDirectory, "private.txt").absolutePath
        )
        val controller = Robolectric.buildActivity(MainActivity::class.java, launchIntent).setup()
        try {
            val activity = controller.get()
            awaitDirectoryLoad(activity)
            assertEquals(storageRoot.canonicalPath, File(activity.currentPathForTest).canonicalPath)
        } finally {
            controller.pause().stop().destroy()
            privateDirectory.deleteRecursively()
        }
    }

    @Test
    fun savedPathFromEarlierExternalIntentCannotRestoreAppPrivateDirectory() {
        val appContext = RuntimeEnvironment.getApplication()
        val privateDirectory = File(appContext.filesDir, "p8-saved-private").apply { mkdirs() }
        File(privateDirectory, "private.txt").writeText("private")
        appContext.getSharedPreferences("saf_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("current_path", privateDirectory.absolutePath)
            .putBoolean("storage_permission_prompted", true)
            .apply()

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            awaitDirectoryLoad(activity)
            assertTrue(
                File(activity.currentPathForTest).canonicalPath != privateDirectory.canonicalPath
            )
        } finally {
            controller.pause().stop().destroy()
            privateDirectory.deleteRecursively()
        }
    }

    private fun deliverCenterTarget(activity: MainActivity, targetPath: String) {
        MainActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java).apply {
            isAccessible = true
        }.invoke(
            activity,
            Intent().putExtra(MainActivity.EXTRA_CENTER_TARGET_PATH, targetPath)
        )
    }

    @Test
    fun returningToParentRestoresAnchorPositionAndPixelOffset() {
        val storageRoot = temporaryFolder.newFolder("storage")
        val parent = File(storageRoot, "parent").apply { mkdir() }
        val current = File(parent, "current").apply { mkdir() }
        repeat(30) { File(parent, "page%02d.txt".format(it)).writeText("page") }
        ShadowEnvironment.setExternalStorageDirectory(storageRoot.toPath())
        val preferences = RuntimeEnvironment.getApplication()
            .getSharedPreferences("saf_prefs", Context.MODE_PRIVATE)
        preferences.edit().putString("current_path", current.absolutePath)
            .putBoolean("storage_permission_prompted", true).apply()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            awaitDirectoryLoad(activity)
            val list = activity.findViewById<ListView>(R.id.listFiles)
            list.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY))
            list.layout(0, 0, 800, 500)
            activity.directoryScrollStateCacheForTest.save(
                FileListScrollState(parent.absolutePath, 3, -17,
                    File(parent, "page12.txt").absolutePath, false))
            activity.findViewById<View>(R.id.btnBack).performClick()
            awaitDirectoryLoad(activity)
            // Robolectric lays rows out at zero; inspect the offset requested by the Activity.
            val requestedTop = activity.requestedScrollTopForTest
            assertEquals(-17, requestedTop)
            list.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY))
            list.layout(0, 0, 800, 500)
            shadowOf(Looper.getMainLooper()).idle()
            list.layout(0, 0, 800, 500)
            assertEquals(parent.absolutePath, preferences.getString("current_path", null))
            assertEquals(14, list.firstVisiblePosition)
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    fun activityRecreationRestoresCurrentDirectoryAndScrollAnchor() {
        val storageRoot = temporaryFolder.newFolder("storage")
        val current = File(storageRoot, "current").apply {
            mkdir()
            repeat(30) { File(this, "page%02d.txt".format(it)).writeText("page") }
        }
        ShadowEnvironment.setExternalStorageDirectory(storageRoot.toPath())
        val preferences = RuntimeEnvironment.getApplication()
            .getSharedPreferences("saf_prefs", Context.MODE_PRIVATE)
        preferences.edit().putString("current_path", current.absolutePath)
            .putBoolean("storage_permission_prompted", true).apply()

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val originalActivity = controller.get()
            awaitDirectoryLoad(originalActivity)
            val originalList = originalActivity.findViewById<ListView>(R.id.listFiles)
            originalList.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY))
            originalList.layout(0, 0, 800, 500)
            originalList.setSelectionFromTop(9, -23)
            shadowOf(Looper.getMainLooper()).idle()
            originalList.layout(0, 0, 800, 500)
            assertEquals(9, originalList.firstVisiblePosition)
            val expectedAnchorPath = checkNotNull(
                (originalList.adapter.getItem(9) as FileItem).file?.absolutePath
            )

            controller.recreate()

            val recreatedActivity = controller.get()
            assertTrue("configuration recreation must replace the Activity",
                originalActivity !== recreatedActivity)
            awaitDirectoryLoad(recreatedActivity)
            val recreatedList = recreatedActivity.findViewById<ListView>(R.id.listFiles)
            recreatedList.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY))
            recreatedList.layout(0, 0, 800, 500)
            shadowOf(Looper.getMainLooper()).idle()
            recreatedList.layout(0, 0, 800, 500)

            assertEquals(current.absolutePath,
                recreatedActivity.findViewById<EditText>(R.id.etPath).text.toString())
            assertEquals(9, recreatedList.firstVisiblePosition)
            assertEquals(expectedAnchorPath,
                (recreatedList.adapter.getItem(recreatedList.firstVisiblePosition) as FileItem)
                    .file?.absolutePath)
        } finally {
            if (!controller.get().isDestroyed) controller.pause().stop().destroy()
        }
    }

    private fun awaitDirectoryLoad(activity: MainActivity) {
        val barrier = CountDownLatch(1)
        activity.directoryLoadExecutorForTest.execute { barrier.countDown() }
        assertTrue(barrier.await(10, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun invokeLoadCurrentDirectory(activity: MainActivity) {
        activity.loadCurrentDirectoryForTest()
    }

    @Test
    fun topBackButtonReturnsToParentDirectory() {
        val storageRoot = temporaryFolder.newFolder("storage")
        val parentDirectory = File(storageRoot, "parent").apply { mkdir() }
        val currentDirectory = File(parentDirectory, "current").apply { mkdir() }
        ShadowEnvironment.setExternalStorageDirectory(storageRoot.toPath())

        val application = RuntimeEnvironment.getApplication()
        val preferences = application.getSharedPreferences("saf_prefs", Context.MODE_PRIVATE)
        preferences.edit()
            .putString("current_path", currentDirectory.absolutePath)
            .putBoolean("storage_permission_prompted", true)
            .apply()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        try {
            activity.findViewById<View>(R.id.btnBack).performClick()

            assertEquals(
                parentDirectory.absolutePath,
                preferences.getString("current_path", null)
            )
        } finally {
            controller.pause().stop().destroy()
        }
    }
}

@Implements(Environment::class)
class ShadowManageExternalStorage : ShadowEnvironment() {
    companion object {
        var allowed = true
        @Implementation
        @JvmStatic
        fun isExternalStorageManager(): Boolean = allowed
    }
}
