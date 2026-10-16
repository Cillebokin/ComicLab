package com.example.comiclab

import android.content.Context
import android.os.Environment
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.ListView
import com.example.comiclab.ui.browser.model.FileItem
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
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

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [ShadowManageExternalStorage::class])
class MainActivityNavigationTest {

    companion object {
        // MainActivity's storage-root constant survives between tests in the same sandbox.
        @ClassRule @JvmField val temporaryFolder = TemporaryFolder()
    }

    @Before
    fun cleanFixture() {
        File(temporaryFolder.root, "storage").deleteRecursively()
    }

    @After
    fun restorePermission() { ShadowManageExternalStorage.allowed = true }

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
