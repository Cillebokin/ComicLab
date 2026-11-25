package com.example.comiclab

import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import com.example.comiclab.core.store.PrivacyStore
import com.example.comiclab.ui.privacy.PrivacyAccessGuard.PRIVACY_VIRTUAL_ROOT_PATH
import com.example.comiclab.ui.privacy.PrivacySessionManager
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
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
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [ShadowManageExternalStorage::class])
class SearchPrivacyRobolectricTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @After fun lock() {
        PrivacySessionManager.lock()
        PrivacySessionManager.consumePendingNormalRestorePath()
    }

    @Test fun ordinarySearchSkipsPrivateBranchesAndRejectsAHiddenRoot() {
        val root = storageRoot()
        val ordinary = File(root, "chapter ordinary.cbz").apply { writeText("ordinary") }
        val hidden = markedDirectory(root, "chapter hidden")
        File(hidden, "chapter hidden.cbz").writeText("private")

        val controller = Robolectric.buildActivity(SearchActivity::class.java, intent(root.path, "NORMAL")).setup()
        try {
            runSearch(controller.get())
            assertEquals(listOf(ordinary), results(controller.get()))
        } finally { controller.pause().stop().destroy() }

        val rejected = Robolectric.buildActivity(SearchActivity::class.java, intent(hidden.path, "NORMAL")).setup()
        try { assertTrue(rejected.get().isFinishing) } finally { rejected.pause().stop().destroy() }
    }

    @Test fun virtualRootSearchIncludesTopLevelMatchAndDiscardsResultsOnLock() {
        val root = storageRoot()
        val top = markedDirectory(root, "chapter top")
        val privateFile = File(top, "chapter private.cbz").apply { writeText("private") }
        File(root, "chapter ordinary.cbz").writeText("ordinary")
        assertTrue(PrivacySessionManager.enterPrivacySpace(root.path, PrivacySessionManager.sessionEpoch))

        val controller = Robolectric.buildActivity(SearchActivity::class.java,
            intent(PRIVACY_VIRTUAL_ROOT_PATH, "PRIVACY_VIRTUAL_ROOT")).setup()
        try {
            val activity = controller.get()
            assertTrue(activity.findViewById<TextView>(R.id.tvSearchRoot).text.contains(activity.getString(R.string.privacy_browser_title)))
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            runSearch(activity)
            assertEquals(listOf(top, privateFile), results(activity))
            runSearch(activity, drainUi = false)
            PrivacySessionManager.lock()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(results(activity).isEmpty())
            assertTrue(activity.isFinishing)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun subtreeSearchStaysInsideItsRootAndRejectsAnOldSessionIntent() {
        val root = storageRoot()
        val left = markedDirectory(root, "left")
        val right = markedDirectory(root, "right")
        val expected = File(left, "chapter left.cbz").apply { writeText("left") }
        File(right, "chapter right.cbz").writeText("right")
        assertTrue(PrivacySessionManager.enterPrivacySpace(root.path, PrivacySessionManager.sessionEpoch))
        val request = intent(left.path, "PRIVACY_SUBTREE")
        val controller = Robolectric.buildActivity(SearchActivity::class.java, request).setup()
        try {
            runSearch(controller.get())
            assertEquals(listOf(expected), results(controller.get()))
            assertTrue(controller.get().findViewById<TextView>(R.id.tvSearchRoot).text.contains(left.path))
            val activity = controller.get()
            val list = activity.findViewById<ListView>(R.id.listSearchResults)
            assertTrue(list.performItemClick(null, 0, list.getItemIdAtPosition(0)))
            val returned = shadowOf(activity).nextStartedActivity
            assertEquals(expected.path, returned.getStringExtra(MainActivity.EXTRA_CENTER_TARGET_PATH))
        } finally { controller.pause().stop().destroy() }
        lock()
        assertTrue(PrivacySessionManager.enterPrivacySpace(root.path, PrivacySessionManager.sessionEpoch))
        val stale = Robolectric.buildActivity(SearchActivity::class.java, request).setup()
        try { assertTrue(stale.get().isFinishing) } finally { stale.pause().stop().destroy() }
    }

    @Test fun toolbarVirtualSearchReturnsTopLevelMatchToMainVirtualRoot() {
        val root = storageRoot()
        val top = markedDirectory(root, "chapter top")
        val main = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val browser = main.get()
            awaitWorker(browser.directoryLoadExecutorForTest)
            assertTrue(browser.enterPrivacyBrowser(PrivacySessionManager.sessionEpoch))
            awaitWorker(browser.directoryLoadExecutorForTest)
            val searchButton = browser.findViewById<View>(R.id.btnSearch)
            assertTrue(searchButton.isEnabled && searchButton.performClick())
            val request = shadowOf(browser).nextStartedActivity
            assertEquals("PRIVACY_VIRTUAL_ROOT", request.getStringExtra(SearchActivity.EXTRA_PRIVACY_SEARCH_MODE))
            val search = Robolectric.buildActivity(SearchActivity::class.java, request).setup()
            try {
                val activity = search.get()
                runSearch(activity)
                val list = activity.findViewById<ListView>(R.id.listSearchResults)
                assertEquals(listOf(top), results(activity))
                assertTrue(list.performItemClick(null, 0, list.getItemIdAtPosition(0)))
                val returned = shadowOf(activity).nextStartedActivity
                assertEquals(top.path, returned.getStringExtra(MainActivity.EXTRA_CENTER_TARGET_PATH))
                assertEquals(PrivacySessionManager.sessionEpoch,
                    returned.getLongExtra(MainActivity.EXTRA_CENTER_TARGET_PRIVACY_EPOCH, -1))
                browser.currentPathForTest = top.path
                MainActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java).apply {
                    isAccessible = true
                }.invoke(browser, returned)
                awaitWorker(browser.directoryLoadExecutorForTest)
                assertEquals(PRIVACY_VIRTUAL_ROOT_PATH, browser.currentPathForTest)
            } finally { search.pause().stop().destroy() }
        } finally { main.pause().stop().destroy() }
    }

    @Test fun ordinaryQueuedSearchResultUsesMarksAtPresentation() {
        val root = storageRoot()
        val chapter = File(root, "chapter folder").apply { mkdir() }
        File(chapter, "chapter file.cbz").writeText("fixture")
        val controller = Robolectric.buildActivity(SearchActivity::class.java, intent(root.path, "NORMAL")).setup()
        try {
            val activity = controller.get()
            runSearch(activity, drainUi = false)
            assertTrue(PrivacyStore.addPrivacyDirectory(RuntimeEnvironment.getApplication(), chapter))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(results(activity).isEmpty())
        } finally { controller.pause().stop().destroy() }
    }

    private fun storageRoot(): File = temporaryFolder.newFolder("storage").also {
        ShadowEnvironment.setExternalStorageDirectory(it.toPath())
    }

    private fun markedDirectory(root: File, name: String): File = File(root, name).apply {
        check(mkdir())
        check(PrivacyStore.addPrivacyDirectory(RuntimeEnvironment.getApplication(), this))
    }

    private fun intent(path: String, mode: String): Intent =
        Intent(RuntimeEnvironment.getApplication(), SearchActivity::class.java)
            .putExtra(SearchActivity.EXTRA_SEARCH_ROOT_PATH, path)
            .putExtra("privacy_search_mode", mode)
            .putExtra("privacy_search_epoch", PrivacySessionManager.sessionEpoch)

    private fun runSearch(activity: SearchActivity, drainUi: Boolean = true) {
        activity.findViewById<EditText>(R.id.inputSearchKeyword).setText("chapter")
        activity.findViewById<View>(R.id.btnRunSearch).performClick()
        val worker = ReflectionHelpers.getField<ExecutorService>(activity, "searchExecutor")
        awaitWorker(worker, drainUi)
    }

    private fun awaitWorker(worker: ExecutorService, drainUi: Boolean = true) {
        worker.submit {}.get(10, TimeUnit.SECONDS)
        if (drainUi) shadowOf(Looper.getMainLooper()).idle()
    }

    private fun results(activity: SearchActivity): List<File> {
        val list = activity.findViewById<ListView>(R.id.listSearchResults)
        return (0 until (list.adapter?.count ?: 0)).map { list.getItemAtPosition(it) as File }
    }
}
