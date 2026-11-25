package com.example.comiclab

import com.example.comiclab.core.transfer.DirectoryTransferOperations
import com.example.comiclab.core.transfer.FileOperationProgress
import com.example.comiclab.core.store.PrivacyStore
import com.example.comiclab.ui.privacy.PrivacySessionManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ListView
import com.example.comiclab.ui.browser.model.FileItem
import com.example.comiclab.ui.browser.transfer.FileTransferMode
import com.example.comiclab.ui.browser.transfer.PendingFileTransfer
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.core.content.ContextCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowEnvironment
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [ShadowManageExternalStorage::class])
class MainActivityFileTransferToolbarTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @After fun resetPrivacy() {
        PrivacySessionManager.lock()
        PrivacySessionManager.consumePendingNormalRestorePath()
    }

    @Test fun ordinaryParentWithPrivateDescendantCannotBeCopiedCutRenamedOrDeleted() {
        val root = temporaryFolder.newFolder("nested-privacy-transfer")
        val parent = File(root, "ordinary parent").apply { mkdir() }
        val hidden = File(parent, "inner/private").apply { mkdirs() }
        ShadowEnvironment.setExternalStorageDirectory(root.toPath())
        prepareBrowserPreferences(root)
        assertTrue(PrivacyStore.addPrivacyDirectory(RuntimeEnvironment.getApplication(), hidden))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            for (mode in FileTransferMode.entries) {
                activity.beginFileTransferForTest(parent, mode)
                assertNull(activity.fileTransferControllerForTest.pendingTransfer)
            }
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "renameDirectory",
                from(File::class.java, parent), from(String::class.java, "renamed"))
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "deleteDirectoryFromBrowser", from(File::class.java, parent))
            activity.directoryLoadExecutorForTest.submit {}.get(5, TimeUnit.SECONDS)
            assertTrue(hidden.isDirectory)
            assertFalse(File(root, "renamed").exists())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun privateTopLevelAndIndependentlyMarkedSubtreeBlockMovesButDeletionCleansItsMarks() {
        val root = temporaryFolder.newFolder("private-directory-operations")
        val top = File(root, "private top").apply { mkdir() }
        val subtree = File(top, "subtree").apply { mkdir() }
        val nestedMark = File(subtree, "inner/independent").apply { mkdirs() }
        val separate = File(root, "another private root").apply { mkdir() }
        File(nestedMark, "private.cbz").writeText("fixture")
        ShadowEnvironment.setExternalStorageDirectory(root.toPath())
        prepareBrowserPreferences(root)
        val context = RuntimeEnvironment.getApplication()
        listOf(top, nestedMark, separate).forEach { assertTrue(PrivacyStore.addPrivacyDirectory(context, it)) }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            assertTrue(activity.enterPrivacyBrowser(PrivacySessionManager.sessionEpoch))
            for (directory in listOf(top, subtree)) {
                activity.beginFileTransferForTest(directory, FileTransferMode.COPY)
                assertNull(activity.fileTransferControllerForTest.pendingTransfer)
                activity.beginFileTransferForTest(directory, FileTransferMode.CUT)
                assertNull(activity.fileTransferControllerForTest.pendingTransfer)
                ReflectionHelpers.callInstanceMethod<Unit>(activity, "renameDirectory",
                    from(File::class.java, directory), from(String::class.java, "renamed"))
                assertTrue(directory.isDirectory)
            }
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "deleteDirectoryFromBrowser", from(File::class.java, subtree))
            activity.directoryLoadExecutorForTest.submit {}.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(subtree.exists())
            assertEquals(setOf(top.canonicalPath, separate.canonicalPath), PrivacyStore.privacyDirectories(context))
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "deleteDirectoryFromBrowser", from(File::class.java, top))
            activity.directoryLoadExecutorForTest.submit {}.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(top.exists())
            assertEquals(setOf(separate.canonicalPath), PrivacyStore.privacyDirectories(context))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun privatePendingTransferCannotPasteOutsideItsAreaOrBePersisted() {
        withPrivateTransfer { activity, source, destination ->
            val ordinary = File(destination.parentFile!!.parentFile, "ordinary destination").apply { mkdir() }
            val state = Bundle()
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "onSaveInstanceState", from(Bundle::class.java, state))
            assertFalse(state.containsKey("pending_file_transfer_path"))
            val transfer = checkNotNull(activity.fileTransferControllerForTest.pendingTransfer)
            invokeStartFileTransfer(activity, transfer, ordinary)
            awaitFileTransfer(activity)
            assertFalse(File(ordinary, source.name).exists())
            assertFalse(activity.fileTransferControllerForTest.isTransferInProgress)
            assertEquals(transfer, activity.fileTransferControllerForTest.pendingTransfer)
        }
    }

    @Test fun oldBundleCannotRestoreTransferFromPrivateSource() {
        val root = temporaryFolder.newFolder("restore-private-transfer")
        val top = File(root, "private").apply { mkdir() }
        val source = File(top, "private.cbz").apply { writeText("fixture") }
        ShadowEnvironment.setExternalStorageDirectory(root.toPath())
        prepareBrowserPreferences(root)
        assertTrue(PrivacyStore.addPrivacyDirectory(RuntimeEnvironment.getApplication(), top))
        val state = Bundle().apply {
            putString("pending_file_transfer_path", source.path)
            putString("pending_file_transfer_mode", FileTransferMode.CUT.name)
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java).create(state).start().resume().visible()
        try { assertNull(controller.get().fileTransferControllerForTest.pendingTransfer) }
        finally { controller.pause().stop().destroy() }
    }

    @Test fun ordinaryPendingTransferCannotPasteIntoPrivateDirectory() {
        val root = temporaryFolder.newFolder("normal-to-private-transfer")
        val hidden = File(root, "private target").apply { mkdir() }
        val source = File(root, "public.cbz").apply { writeText("fixture") }
        ShadowEnvironment.setExternalStorageDirectory(root.toPath())
        prepareBrowserPreferences(root)
        assertTrue(PrivacyStore.addPrivacyDirectory(RuntimeEnvironment.getApplication(), hidden))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.beginFileTransferForTest(source, FileTransferMode.CUT)
            val transfer = checkNotNull(activity.fileTransferControllerForTest.pendingTransfer)
            assertTrue(activity.enterPrivacyBrowser(PrivacySessionManager.sessionEpoch))
            activity.currentPathForTest = hidden.path
            activity.updateFileTransferToolbarForTest()
            assertEquals(View.GONE, activity.findViewById<View>(R.id.btnPasteFile).visibility)
            invokeStartFileTransfer(activity, transfer, hidden)
            awaitFileTransfer(activity)
            assertTrue(source.isFile)
            assertTrue(hidden.listFiles()!!.isEmpty())
            assertEquals(transfer, activity.fileTransferControllerForTest.pendingTransfer)
            assertFalse(activity.fileTransferControllerForTest.isTransferInProgress)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun lockedPrivateDirectoryPasteWithLateConflictClearsPendingAndKeepsBothDirectories() {
        withPrivateTransfer { activity, _, destination ->
            activity.findViewById<View>(R.id.btnExitFileTransfer).performClick()
            val source = File(destination.parentFile, "protected folder").apply { mkdir() }
            File(source, "private.cbz").writeText("fixture")
            activity.beginFileTransferForTest(source, FileTransferMode.CUT)
            val release = CountDownLatch(1)
            activity.fileTransferExecutorForTest.execute { check(release.await(5, TimeUnit.SECONDS)) }
            try {
                activity.findViewById<View>(R.id.btnPasteFile).performClick()
                val progress = checkNotNull(ShadowAlertDialog.getLatestAlertDialog())
                assertTrue(progress.isShowing)
                assertTrue(activity.fileTransferControllerForTest.isTransferInProgress)
                PrivacySessionManager.lock()
                assertFalse(progress.isShowing)
                val conflict = File(destination, source.name).apply { mkdir() }
                File(conflict, "existing.cbz").writeText("existing")
                release.countDown()
                awaitFileTransfer(activity)
                assertEquals("fixture", File(source, "private.cbz").readText())
                assertEquals("existing", conflict.listFiles()!!.single().readText())
                assertFalse(ShadowAlertDialog.getLatestAlertDialog().isShowing)
                assertFalse(activity.fileTransferControllerForTest.isTransferInProgress)
                assertNull(activity.fileTransferControllerForTest.pendingTransfer)
            } finally { release.countDown() }
        }
    }

    @Test fun queuedNormalTransferRejectsNewlyPrivateDestinationAndRetainsSourceForRetry() {
        val root = temporaryFolder.newFolder("late-transfer-privacy-mark")
        val destination = File(root, "destination").apply { mkdir() }
        val source = File(root, "source.cbz").apply { writeText("fixture") }
        val retryDestination = File(root, "ordinary retry").apply { mkdir() }
        ShadowEnvironment.setExternalStorageDirectory(root.toPath())
        prepareBrowserPreferences(destination)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val release = CountDownLatch(1)
        try {
            val activity = controller.get()
            activity.fileTransferExecutorForTest.execute { check(release.await(5, TimeUnit.SECONDS)) }
            startFilePaste(activity, source, FileTransferMode.CUT, destination)
            val transfer = checkNotNull(activity.fileTransferControllerForTest.pendingTransfer)
            val dialog = checkNotNull(ShadowAlertDialog.getLatestAlertDialog())
            assertTrue(dialog.isShowing)
            assertTrue(PrivacyStore.addPrivacyDirectory(RuntimeEnvironment.getApplication(), destination))
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(dialog.isShowing)
            release.countDown()
            awaitFileTransfer(activity)
            assertTrue(source.isFile)
            assertTrue(destination.listFiles()!!.isEmpty())
            assertFalse(activity.fileTransferControllerForTest.isTransferInProgress)
            assertEquals(transfer, activity.fileTransferControllerForTest.pendingTransfer)
            invokeStartFileTransfer(activity, transfer, retryDestination)
            awaitFileTransfer(activity)
            assertFalse(source.exists())
            assertEquals("fixture", File(retryDestination, source.name).readText())
            assertNull(activity.fileTransferControllerForTest.pendingTransfer)
        } finally {
            release.countDown()
            controller.pause().stop().destroy()
        }
    }

    @Test fun ordinaryDirectoryCanBeBrowsedWhileAnOldPrivatePasteFinishes() {
        val root = temporaryFolder.newFolder("ordinary-browsing-during-private-transfer")
        val ordinary = File(root, "ordinary").apply { mkdir() }
        val child = File(ordinary, "subfolder").apply { mkdir() }
        val hidden = File(root, "private").apply { mkdir() }
        val source = File(hidden, "private.cbz").apply { writeText("private bytes") }
        val target = File(hidden, "destination").apply { mkdir() }
        ShadowEnvironment.setExternalStorageDirectory(root.toPath())
        prepareBrowserPreferences(ordinary)
        assertTrue(PrivacyStore.addPrivacyDirectory(RuntimeEnvironment.getApplication(), hidden))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val release = CountDownLatch(1)
        try {
            val activity = controller.get()
            fun awaitDirectory() {
                activity.directoryLoadExecutorForTest.submit {}.get(5, TimeUnit.SECONDS)
                shadowOf(Looper.getMainLooper()).idle()
            }
            awaitDirectory()
            assertTrue(activity.enterPrivacyBrowser(PrivacySessionManager.sessionEpoch))
            awaitDirectory()
            activity.fileTransferExecutorForTest.execute { check(release.await(10, TimeUnit.SECONDS)) }
            activity.beginFileTransferForTest(source, FileTransferMode.COPY)
            val transfer = checkNotNull(activity.fileTransferControllerForTest.pendingTransfer)
            invokeStartFileTransfer(activity, transfer, target)
            controller.pause()
            PrivacySessionManager.lock()
            controller.resume()
            awaitDirectory()

            assertEquals(ordinary.path, activity.currentPathForTest)
            assertTrue(activity.fileTransferControllerForTest.isTransferInProgress)
            val list = activity.findViewById<android.widget.ListView>(R.id.listFiles)
            assertTrue(list.isEnabled)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.btnBack).visibility)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.btnPasteFile).visibility)
            val row = (0 until list.count).single {
                (list.getItemAtPosition(it) as com.example.comiclab.ui.browser.model.FileItem).file == child
            }
            assertTrue(list.performItemClick(View(activity), row, list.getItemIdAtPosition(row)))
            awaitDirectory()
            assertEquals(child.path, activity.currentPathForTest)
            activity.onBackPressedDispatcher.onBackPressed()
            awaitDirectory()
            assertEquals(ordinary.path, activity.currentPathForTest)
            val otherSource = File(ordinary, "other.cbz").apply { writeText("new") }
            activity.beginFileTransferForTest(otherSource, FileTransferMode.CUT)
            assertEquals(transfer, activity.fileTransferControllerForTest.pendingTransfer)

            release.countDown()
            awaitFileTransfer(activity)
            assertEquals(ordinary.path, activity.currentPathForTest)
            assertEquals("private bytes", File(target, source.name).readText())
            assertFalse(activity.fileTransferControllerForTest.isTransferInProgress)
            assertNull(activity.fileTransferControllerForTest.pendingTransfer)
        } finally {
            release.countDown()
            controller.pause().stop().destroy()
        }
    }

    @Test fun acceptedPrivatePasteStaysExclusiveAfterBrowserReopens() {
        val root = temporaryFolder.newFolder("paste-across-browser-instances")
        val ordinary = File(root, "ordinary").apply { mkdir() }
        val hidden = File(root, "private").apply { mkdir() }
        val privateSource = File(hidden, "private.cbz").apply { writeText("private bytes") }
        val privateTarget = File(hidden, "destination").apply { mkdir() }
        val ordinarySource = File(root, "ordinary source").apply { mkdir() }
        File(ordinarySource, "ordinary.cbz").writeText("ordinary bytes")
        val ordinaryCopy = File(ordinary, "${ordinarySource.name}/ordinary.cbz")
        ShadowEnvironment.setExternalStorageDirectory(root.toPath())
        prepareBrowserPreferences(ordinary)
        assertTrue(PrivacyStore.addPrivacyDirectory(RuntimeEnvironment.getApplication(), hidden))
        val first = Robolectric.buildActivity(MainActivity::class.java).setup()
        val oldActivity = first.get()
        val oldExecutor = oldActivity.fileTransferExecutorForTest
        val release = CountDownLatch(1)
        val mainLooper = shadowOf(Looper.getMainLooper())
        try {
            oldActivity.directoryLoadExecutorForTest.submit {}.get(5, TimeUnit.SECONDS)
            mainLooper.idle()
            assertTrue(oldActivity.enterPrivacyBrowser(PrivacySessionManager.sessionEpoch))
            oldActivity.directoryLoadExecutorForTest.submit {}.get(5, TimeUnit.SECONDS)
            mainLooper.idle()
            oldExecutor.execute { check(release.await(10, TimeUnit.SECONDS)) }
            oldActivity.beginFileTransferForTest(privateSource, FileTransferMode.COPY)
            val firstPaste = checkNotNull(oldActivity.fileTransferControllerForTest.pendingTransfer)
            invokeStartFileTransfer(oldActivity, firstPaste, privateTarget)
            assertTrue(oldActivity.fileTransferControllerForTest.isTransferInProgress)
            first.pause()
            PrivacySessionManager.lock()
            first.stop().destroy()
            assertTrue(oldExecutor.isShutdown)

            Robolectric.buildActivity(MainActivity::class.java).setup().use { reopened ->
                val activity = reopened.get()
                activity.directoryLoadExecutorForTest.submit {}.get(5, TimeUnit.SECONDS)
                mainLooper.idle()
                assertEquals(ordinary.path, activity.currentPathForTest)
                assertTrue(activity.findViewById<View>(R.id.listFiles).isEnabled)
                activity.beginFileTransferForTest(ordinarySource, FileTransferMode.COPY)
                val secondPaste = checkNotNull(activity.fileTransferControllerForTest.pendingTransfer)
                activity.findViewById<View>(R.id.btnPasteFile).performClick()

                assertFalse(activity.fileTransferControllerForTest.isTransferInProgress)
                assertFalse(ordinaryCopy.exists())
                assertEquals(secondPaste, activity.fileTransferControllerForTest.pendingTransfer)
                assertEquals(activity.getString(R.string.file_transfer_in_progress), ShadowToast.getTextOfLatestToast())

                release.countDown()
                assertTrue(oldExecutor.awaitTermination(5, TimeUnit.SECONDS))
                assertTrue(oldActivity.fileTransferControllerForTest.isTransferInProgress)
                activity.findViewById<View>(R.id.btnPasteFile).performClick()
                assertFalse(activity.fileTransferControllerForTest.isTransferInProgress)
                assertFalse(ordinaryCopy.exists())
                val loadGeneration = activity.directoryLoadGenerationForTest
                val toast = ShadowToast.getTextOfLatestToast()
                mainLooper.idle()
                assertEquals("private bytes", File(privateTarget, privateSource.name).readText())
                assertFalse(oldActivity.fileTransferControllerForTest.isTransferInProgress)
                assertNull(oldActivity.fileTransferControllerForTest.pendingTransfer)
                assertEquals(loadGeneration, activity.directoryLoadGenerationForTest)
                assertEquals(toast, ShadowToast.getTextOfLatestToast())

                activity.findViewById<View>(R.id.btnPasteFile).performClick()
                awaitFileTransfer(activity)
                assertEquals("ordinary bytes", ordinaryCopy.readText())
                assertFalse(activity.fileTransferControllerForTest.isTransferInProgress)
            }
        } finally {
            release.countDown()
            if (!oldActivity.isDestroyed) first.pause().stop().destroy()
            oldExecutor.awaitTermination(5, TimeUnit.SECONDS)
            mainLooper.idle()
        }
    }

    @Test fun ordinaryFileCopyRefreshesDestinationAfterBrowserRecreation() {
        verifyRecreatedBrowserRefreshesAfterPaste(FileTransferMode.COPY, isDirectory = false)
    }

    @Test fun ordinaryDirectoryCutRefreshesSourceAfterBrowserRecreation() {
        verifyRecreatedBrowserRefreshesAfterPaste(FileTransferMode.CUT, isDirectory = true)
    }

    @Test fun ordinaryPasteRefreshKeepsSearchTargetWhileDirectoryIsLoading() {
        verifyPasteRefreshKeepsSearchTarget(SearchTargetPhase.LOADING)
    }

    @Test fun ordinaryPasteRefreshKeepsSearchTargetWhileCenteringIsQueued() {
        verifyPasteRefreshKeepsSearchTarget(SearchTargetPhase.QUEUED)
    }

    @Test fun ordinaryPasteRefreshKeepsSearchTargetUntilSelectionIsLaidOut() {
        verifyPasteRefreshKeepsSearchTarget(SearchTargetPhase.AWAITING_LAYOUT)
    }

    @Test fun ordinaryPasteRefreshDoesNotReplaySearchTargetAfterLayout() {
        verifyPasteRefreshKeepsSearchTarget(SearchTargetPhase.LAID_OUT)
    }

    private enum class SearchTargetPhase { LOADING, QUEUED, AWAITING_LAYOUT, LAID_OUT }

    private fun verifyPasteRefreshKeepsSearchTarget(phase: SearchTargetPhase) {
        val root = temporaryFolder.newFolder("paste-refresh-search-target")
        val destination = File(root, "destination").apply { mkdir() }
        val source = File(root, "copy.cbz").apply { writeText("comic bytes") }
        val copied = File(destination, source.name)
        val searchTargets = List(40) { index ->
            File(destination, "archive-${index.toString().padStart(2, '0')}.txt").apply { writeText("book") }
        }
        val releasePaste = CountDownLatch(1)
        val releaseDirectoryLoad = CountDownLatch(1)
        val mainLooper = shadowOf(Looper.getMainLooper())
        ShadowEnvironment.setExternalStorageDirectory(root.toPath())
        prepareBrowserPreferences(destination)

        Robolectric.buildActivity(MainActivity::class.java).setup().use { browser ->
            val previous = browser.get()
            val pasteExecutor = previous.fileTransferExecutorForTest
            fun awaitDirectory(activity: MainActivity) {
                activity.directoryLoadExecutorForTest.submit {}.get(5, TimeUnit.SECONDS)
                mainLooper.idle()
            }
            try {
                awaitDirectory(previous)
                pasteExecutor.execute { check(releasePaste.await(10, TimeUnit.SECONDS)) }
                previous.beginFileTransferForTest(source, FileTransferMode.COPY)
                previous.findViewById<View>(R.id.btnPasteFile).performClick()
                assertTrue(previous.fileTransferControllerForTest.isTransferInProgress)

                browser.recreate()
                assertTrue(previous.isDestroyed)
                val activity = browser.get()
                awaitDirectory(activity)
                val list = SelectionRecordingListView(activity).apply {
                    adapter = activity.findViewById<ListView>(R.id.listFiles).adapter
                    layout(0, 0, 800, 500)
                    holdPosts = phase == SearchTargetPhase.QUEUED
                }
                ReflectionHelpers.setField(activity, "listView", list)
                if (phase == SearchTargetPhase.LOADING) {
                    activity.directoryLoadExecutorForTest.execute { check(releaseDirectoryLoad.await(10, TimeUnit.SECONDS)) }
                }

                ReflectionHelpers.callInstanceMethod<Unit>(activity, "onNewIntent", from(Intent::class.java,
                    Intent().putExtra(MainActivity.EXTRA_CENTER_TARGET_PATH, searchTargets[20].absolutePath)))
                if (phase != SearchTargetPhase.LOADING) awaitDirectory(activity)
                when (phase) {
                    SearchTargetPhase.LOADING -> assertTrue(list.requestedPositions.isEmpty())
                    SearchTargetPhase.QUEUED -> {
                        assertTrue(list.queuedPosts.isNotEmpty())
                        assertTrue(list.requestedPositions.isEmpty())
                    }
                    SearchTargetPhase.AWAITING_LAYOUT, SearchTargetPhase.LAID_OUT -> {
                        assertTrue(list.requestedPositions.single() > 4)
                        if (phase == SearchTargetPhase.LAID_OUT) list.viewTreeObserver.dispatchOnPreDraw()
                        assertEquals(0, list.firstVisiblePosition)
                        list.requestedPositions.clear()
                    }
                }

                releasePaste.countDown()
                assertTrue(pasteExecutor.awaitTermination(5, TimeUnit.SECONDS))
                mainLooper.idle()
                releaseDirectoryLoad.countDown()
                awaitDirectory(activity)
                list.queuedPosts.forEach(Runnable::run)

                assertEquals("comic bytes", copied.readText())
                assertTrue((0 until list.count).any { (list.getItemAtPosition(it) as FileItem).file == copied })
                assertEquals(1, list.requestedPositions.size)
                if (phase == SearchTargetPhase.LAID_OUT) {
                    assertEquals(0, list.requestedPositions.single())
                } else {
                    assertTrue("An unfinished search target should be centered", list.requestedPositions.single() > 4)
                }
            } finally {
                releasePaste.countDown()
                releaseDirectoryLoad.countDown()
                if (!pasteExecutor.isShutdown) awaitFileTransfer(previous)
                else pasteExecutor.awaitTermination(5, TimeUnit.SECONDS)
                mainLooper.idle()
            }
        }
    }

    private class SelectionRecordingListView(context: Context) : ListView(context) {
        val requestedPositions = mutableListOf<Int>()
        val queuedPosts = mutableListOf<Runnable>()
        var holdPosts = false

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) = Unit

        override fun post(action: Runnable): Boolean {
            if (holdPosts) queuedPosts.add(action) else action.run()
            return true
        }

        override fun setSelectionFromTop(position: Int, offset: Int) {
            requestedPositions.add(position)
        }
    }

    private fun verifyRecreatedBrowserRefreshesAfterPaste(mode: FileTransferMode, isDirectory: Boolean) {
        val root = temporaryFolder.newFolder("ordinary-paste-recreation")
        val sourceParent = File(root, "source").apply { mkdir() }
        val destination = File(root, "destination").apply { mkdir() }
        val source = if (isDirectory) File(sourceParent, "series").apply { mkdir() }
        else File(sourceParent, "comic.cbz")
        val archive = if (isDirectory) File(source, "comic.cbz") else source
        archive.writeText("comic bytes")
        val target = File(destination, source.name)
        val targetArchive = if (isDirectory) File(target, archive.name) else target
        val release = CountDownLatch(1)
        val mainLooper = shadowOf(Looper.getMainLooper())
        ShadowEnvironment.setExternalStorageDirectory(root.toPath())
        prepareBrowserPreferences(destination)

        Robolectric.buildActivity(MainActivity::class.java).setup().use { browser ->
            val oldActivity = browser.get()
            val oldExecutor = oldActivity.fileTransferExecutorForTest
            fun awaitDirectory(activity: MainActivity) {
                activity.directoryLoadExecutorForTest.submit {}.get(5, TimeUnit.SECONDS)
                mainLooper.idle()
            }
            try {
                awaitDirectory(oldActivity)
                oldExecutor.execute { check(release.await(10, TimeUnit.SECONDS)) }
                oldActivity.beginFileTransferForTest(source, mode)
                oldActivity.findViewById<View>(R.id.btnPasteFile).performClick()
                assertTrue(oldActivity.fileTransferControllerForTest.isTransferInProgress)

                browser.recreate()
                assertTrue(oldActivity.isDestroyed)
                val activity = browser.get()
                if (mode == FileTransferMode.CUT) {
                    activity.currentPathForTest = sourceParent.path
                    activity.loadCurrentDirectoryForTest()
                }
                awaitDirectory(activity)
                val list = activity.findViewById<ListView>(R.id.listFiles)
                val displayedFile = if (mode == FileTransferMode.CUT) source else target
                fun showsFile() = (0 until list.count).any {
                    (list.getItemAtPosition(it) as FileItem).file == displayedFile
                }
                assertEquals(mode == FileTransferMode.CUT, showsFile())
                val currentPath = activity.currentPathForTest
                val toast = ShadowToast.getTextOfLatestToast()

                release.countDown()
                assertTrue(oldExecutor.awaitTermination(5, TimeUnit.SECONDS))
                mainLooper.idle()
                awaitDirectory(activity)

                assertEquals("comic bytes", targetArchive.readText())
                assertEquals(mode == FileTransferMode.COPY, source.exists())
                assertFalse(oldActivity.fileTransferControllerForTest.isTransferInProgress)
                assertEquals(currentPath, activity.currentPathForTest)
                assertEquals(toast, ShadowToast.getTextOfLatestToast())
                assertEquals(mode == FileTransferMode.COPY, showsFile())
            } finally {
                release.countDown()
                if (!oldExecutor.isShutdown) awaitFileTransfer(oldActivity)
                else oldExecutor.awaitTermination(5, TimeUnit.SECONDS)
                mainLooper.idle()
            }
        }
    }

    @Test fun rejectedPasteSubmissionReleasesSlotForAnotherBrowser() {
        val root = temporaryFolder.newFolder("rejected-paste")
        val destination = File(root, "destination").apply { mkdir() }
        val source = File(root, "ordinary.cbz").apply { writeText("ordinary bytes") }
        val target = File(destination, source.name)
        ShadowEnvironment.setExternalStorageDirectory(root.toPath())
        prepareBrowserPreferences(destination)

        Robolectric.buildActivity(MainActivity::class.java).setup().use { rejected ->
            val activity = rejected.get()
            activity.fileTransferExecutorForTest.shutdown()
            activity.beginFileTransferForTest(source, FileTransferMode.COPY)
            val paste = checkNotNull(activity.fileTransferControllerForTest.pendingTransfer)
            invokeStartFileTransfer(activity, paste, destination)
            assertFalse(activity.fileTransferControllerForTest.isTransferInProgress)
            assertFalse(target.exists())
            assertEquals(paste, activity.fileTransferControllerForTest.pendingTransfer)

            Robolectric.buildActivity(MainActivity::class.java).setup().use { reopened ->
                val other = reopened.get()
                other.beginFileTransferForTest(source, FileTransferMode.COPY)
                invokeStartFileTransfer(other, checkNotNull(other.fileTransferControllerForTest.pendingTransfer), destination)
                awaitFileTransfer(other)
                assertEquals("ordinary bytes", target.readText())
                assertFalse(other.fileTransferControllerForTest.isTransferInProgress)
            }
        }
    }

    @Test fun privateConflictDialogClosesOnLockAndOldConfirmationCannotStartPaste() {
        withPrivateTransfer { activity, source, destination ->
            val conflict = File(destination, source.name).apply { writeText("existing") }
            val transfer = checkNotNull(activity.fileTransferControllerForTest.pendingTransfer)
            invokeStartFileTransfer(activity, transfer, destination)
            val dialog = checkNotNull(ShadowAlertDialog.getLatestAlertDialog())
            val confirm = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            assertTrue(dialog.isShowing)

            PrivacySessionManager.lock()
            assertFalse(dialog.isShowing)
            assertNull(activity.fileTransferControllerForTest.pendingTransfer)
            confirm.performClick()
            awaitFileTransfer(activity)
            assertEquals(listOf(conflict), destination.listFiles()!!.toList())
            assertEquals("existing", conflict.readText())
            assertTrue(source.isFile)
        }
    }

    @Test fun lockedPrivatePasteFinishesSilentlyOnSuccessFailureAndConflict() {
        for (outcome in listOf("success", "failure", "conflict")) {
            withPrivateTransfer { activity, source, destination ->
                val release = CountDownLatch(1)
                val running = CountDownLatch(1)
                activity.fileTransferExecutorForTest.execute {
                    running.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                assertTrue(running.await(5, TimeUnit.SECONDS))
                try {
                    val transfer = checkNotNull(activity.fileTransferControllerForTest.pendingTransfer)
                    invokeStartFileTransfer(activity, transfer, destination)
                    val progress = checkNotNull(ShadowAlertDialog.getLatestAlertDialog())
                    assertTrue(progress.isShowing)
                    PrivacySessionManager.lock()
                    assertFalse(progress.isShowing)
                    assertTrue(activity.fileTransferControllerForTest.isTransferInProgress)
                    val target = File(destination, source.name)
                    if (outcome == "failure") assertTrue(source.delete())
                    if (outcome == "conflict") target.writeText("existing")
                    val toast = ShadowToast.getTextOfLatestToast()
                    release.countDown()
                    awaitFileTransfer(activity)

                    assertEquals(toast, ShadowToast.getTextOfLatestToast())
                    assertFalse(ShadowAlertDialog.getLatestAlertDialog().isShowing)
                    assertFalse(activity.fileTransferControllerForTest.isTransferInProgress)
                    assertNull(activity.fileTransferControllerForTest.pendingTransfer)
                    when (outcome) {
                        "success" -> assertEquals("private bytes", target.readText())
                        "conflict" -> assertEquals("existing", target.readText())
                        else -> assertFalse(target.exists())
                    }
                } finally { release.countDown() }
            }
        }
    }

    private fun withPrivateTransfer(test: (MainActivity, File, File) -> Unit) {
        val root = temporaryFolder.newFolder()
        val hidden = File(root, "private shelf").apply { mkdir() }
        val destination = File(hidden, "destination").apply { mkdir() }
        val source = File(hidden, "secret.cbz").apply { writeText("private bytes") }
        ShadowEnvironment.setExternalStorageDirectory(root.toPath())
        prepareBrowserPreferences(root)
        assertTrue(PrivacyStore.addPrivacyDirectory(RuntimeEnvironment.getApplication(), hidden))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            assertTrue(activity.enterPrivacyBrowser(PrivacySessionManager.sessionEpoch))
            activity.currentPathForTest = destination.path
            activity.loadCurrentDirectoryForTest()
            activity.directoryLoadExecutorForTest.submit {}.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            activity.beginFileTransferForTest(source, FileTransferMode.COPY)
            test(activity, source, destination)
        } finally {
            controller.pause().stop().destroy()
            resetPrivacy()
        }
    }

    @Test
    fun newFolderButtonCreatesFolderAndKeepsPendingTransfer() {
        val storageRoot = temporaryFolder.newFolder("storage")
        val destination = File(storageRoot, "destination").apply { mkdir() }
        val sourceFile = File(storageRoot, "source.zip").apply { writeText("comic") }
        ShadowEnvironment.setExternalStorageDirectory(storageRoot.toPath())

        val application = RuntimeEnvironment.getApplication()
        application.getSharedPreferences("saf_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("current_path", destination.absolutePath)
            .putBoolean("storage_permission_prompted", true)
            .apply()

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        try {
            val newFolderButton = activity.findViewById<ImageButton>(R.id.btnCreateTransferDirectory)
            val pasteButton = activity.findViewById<View>(R.id.btnPasteFile)
            assertTrue(newFolderButton.drawable is BitmapDrawable)
            assertEquals(
                ContextCompat.getColor(activity, R.color.comiclab_icon),
                newFolderButton.imageTintList?.defaultColor
            )
            assertEquals(View.GONE, newFolderButton.visibility)

            val copyMode = FileTransferMode.COPY
            activity.beginFileTransferForTest(sourceFile, copyMode)

            assertEquals(View.VISIBLE, newFolderButton.visibility)
            val toolbar = newFolderButton.parent as ViewGroup
            assertTrue(toolbar.indexOfChild(newFolderButton) < toolbar.indexOfChild(pasteButton))

            newFolderButton.performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertNotNull(dialog)
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()

            assertTrue(File(destination, "New Folder").isDirectory)
            assertEquals(View.VISIBLE, newFolderButton.visibility)
            assertEquals(View.VISIBLE, pasteButton.visibility)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun pendingTransferIsSavedAndRestoredUsingExistingBundleKeys() {
        val storageRoot = temporaryFolder.newFolder("storage")
        val destination = File(storageRoot, "destination").apply { mkdir() }
        val sourceFile = File(storageRoot, "source.zip").apply { writeText("comic") }
        ShadowEnvironment.setExternalStorageDirectory(storageRoot.toPath())

        val application = RuntimeEnvironment.getApplication()
        application.getSharedPreferences("saf_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("current_path", destination.absolutePath)
            .putBoolean("storage_permission_prompted", true)
            .apply()

        val originalController = Robolectric.buildActivity(MainActivity::class.java).setup()
        val originalActivity = originalController.get()
        var restoredController: org.robolectric.android.controller.ActivityController<MainActivity>? = null
        try {
            originalActivity.beginFileTransferForTest(sourceFile, FileTransferMode.CUT)

            val state = Bundle()
            originalController.saveInstanceState(state)
            assertEquals(sourceFile.absolutePath, state.getString("pending_file_transfer_path"))
            assertEquals("CUT", state.getString("pending_file_transfer_mode"))
            assertTrue(state.getBoolean("pending_file_transfer_is_directory") == false)

            originalController.pause().stop().destroy()
            restoredController = Robolectric.buildActivity(MainActivity::class.java)
                .create(state)
                .start()
                .resume()
                .visible()

            val restoredActivity = restoredController.get()
            assertEquals(
                View.VISIBLE,
                restoredActivity.findViewById<View>(R.id.btnPasteFile).visibility
            )
            assertTrue(restoredActivity.findViewById<View>(R.id.btnPasteFile).isEnabled)
            assertTrue(restoredActivity.findViewById<View>(R.id.btnExitFileTransfer).isEnabled)
        } finally {
            restoredController?.pause()?.stop()?.destroy()
            if (originalActivity.isDestroyed.not()) {
                originalController.pause().stop().destroy()
            }
        }
    }

    @Test
    fun successfulFilePasteClearsPendingToolbarAfterActivityCallback() {
        val storageRoot = temporaryFolder.newFolder("successful-transfer-storage")
        val destination = File(storageRoot, "destination").apply { mkdir() }
        val sourceFile = File(storageRoot, "source.zip").apply { writeText("comic bytes") }
        ShadowEnvironment.setExternalStorageDirectory(storageRoot.toPath())
        prepareBrowserPreferences(destination)

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        try {
            startFilePaste(activity, sourceFile, FileTransferMode.COPY, destination)
            val progressDialog = checkNotNull(ShadowAlertDialog.getLatestAlertDialog())
            assertTrue(progressDialog.isShowing)
            awaitFileTransfer(activity)

            assertTrue(sourceFile.isFile)
            assertEquals("comic bytes", File(destination, sourceFile.name).readText())
            assertFalse(progressDialog.isShowing)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.btnPasteFile).visibility)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.btnBack).visibility)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun cutFallbackCompletesCleanMoveAndFinishesPaste() {
        val storageRoot = temporaryFolder.newFolder("cut-fallback-storage")
        val destination = File(storageRoot, "destination").apply { mkdir() }
        val source = File(storageRoot, "source.cbz").apply { writeText("comic bytes") }
        ShadowEnvironment.setExternalStorageDirectory(storageRoot.toPath())
        prepareBrowserPreferences(destination)

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        try {
            startFilePaste(activity, source, FileTransferMode.CUT, destination)
            awaitFileTransfer(activity)

            assertFalse(source.exists())
            assertEquals("comic bytes", File(destination, source.name).readText())
            assertEquals(
                activity.getString(R.string.file_cut_success),
                ShadowToast.getTextOfLatestToast()
            )
            assertEquals(View.GONE, activity.findViewById<View>(R.id.btnPasteFile).visibility)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun retainedSourceDirectoryCallbackShowsMessageAndFinishesPaste() {
        val storageRoot = temporaryFolder.newFolder("retained-directory-transfer-storage")
        val destination = File(storageRoot, "destination").apply { mkdir() }
        val sourceDirectory = File(storageRoot, "source").apply { mkdir() }
        ShadowEnvironment.setExternalStorageDirectory(storageRoot.toPath())
        prepareBrowserPreferences(destination)

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        try {
            beginFileTransfer(activity, sourceDirectory, FileTransferMode.CUT)
            val transferController = activity.fileTransferControllerForTest
            val transfer = checkNotNull(transferController.pendingTransfer)
            assertTrue(transferController.startPaste(transfer))
            activity.updateFileTransferToolbarForTest()
            activity.showFileOperationProgressForTest()

            activity.handleDirectoryTransferFailureForTest(
                transfer,
                DirectoryTransferOperations.Result.COPIED_SOURCE_RETAINED,
                destination
            )

            assertNull(transferController.pendingTransfer)
            assertFalse(transferController.isTransferInProgress)
            assertEquals(
                activity.getString(R.string.file_transfer_copied_source_retained),
                ShadowToast.getTextOfLatestToast()
            )
            assertEquals(View.GONE, activity.findViewById<View>(R.id.btnPasteFile).visibility)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun failedFilePasteRetainsPendingToolbarAfterActivityCallback() {
        val storageRoot = temporaryFolder.newFolder("failed-transfer-storage")
        val destination = File(storageRoot, "destination").apply { mkdir() }
        val sourceFile = File(storageRoot, "source.zip").apply { writeText("comic bytes") }
        ShadowEnvironment.setExternalStorageDirectory(storageRoot.toPath())
        prepareBrowserPreferences(destination)

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        try {
            val transfer = PendingFileTransfer(
                sourceFile.absolutePath,
                FileTransferMode.COPY,
                isDirectory = false
            )
            beginFileTransfer(activity, sourceFile, transfer.mode)
            assertTrue(sourceFile.delete())
            invokeStartFileTransfer(activity, transfer, destination)
            val progressDialog = checkNotNull(ShadowAlertDialog.getLatestAlertDialog())
            assertTrue(progressDialog.isShowing)
            awaitFileTransfer(activity)

            assertFalse(progressDialog.isShowing)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.btnPasteFile).visibility)
            assertTrue(activity.findViewById<View>(R.id.btnPasteFile).isEnabled)
            assertTrue(activity.findViewById<View>(R.id.btnExitFileTransfer).isEnabled)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun incompleteDirectoryCopyClosesProgressRetainsPendingAndReloadsDirectory() {
        val storageRoot = temporaryFolder.newFolder("failed-directory-transfer-storage")
        val destination = File(storageRoot, "destination").apply { mkdir() }
        val sourceDirectory = File(storageRoot, "source").apply { mkdir() }
        File(sourceDirectory, "page.jpg").writeText("comic bytes")
        ShadowEnvironment.setExternalStorageDirectory(storageRoot.toPath())
        prepareBrowserPreferences(destination)

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        try {
            beginFileTransfer(activity, sourceDirectory, FileTransferMode.CUT)
            val transferController = activity.fileTransferControllerForTest
            val transfer = checkNotNull(transferController.pendingTransfer)
            assertTrue(transfer.isDirectory)
            assertTrue(transferController.startPaste(transfer))

            activity.updateFileTransferToolbarForTest()
            activity.showFileOperationProgressForTest()
            val progressDialog = checkNotNull(ShadowAlertDialog.getLatestAlertDialog())
            assertTrue(progressDialog.isShowing)

            val generationBeforeCallback = activity.directoryLoadGenerationForTest
            activity.handleDirectoryTransferFailureForTest(
                transfer,
                DirectoryTransferOperations.Result.COPY_INCOMPLETE,
                destination
            )

            assertFalse(progressDialog.isShowing)
            assertEquals(transfer, transferController.pendingTransfer)
            assertFalse(transferController.isTransferInProgress)
            assertTrue(activity.directoryLoadGenerationForTest > generationBeforeCallback)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.btnPasteFile).visibility)
            assertTrue(activity.findViewById<View>(R.id.btnPasteFile).isEnabled)
            assertTrue(activity.findViewById<View>(R.id.btnExitFileTransfer).isEnabled)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun prepareBrowserPreferences(destination: File) {
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("saf_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("current_path", destination.absolutePath)
            .putBoolean("storage_permission_prompted", true)
            .apply()
    }

    private fun startFilePaste(
        activity: MainActivity,
        sourceFile: File,
        mode: FileTransferMode,
        destination: File
    ) {
        val transfer = PendingFileTransfer(sourceFile.absolutePath, mode, isDirectory = false)
        beginFileTransfer(activity, sourceFile, mode)
        invokeStartFileTransfer(activity, transfer, destination)
    }

    private fun beginFileTransfer(activity: MainActivity, sourceFile: File, mode: FileTransferMode) {
        activity.beginFileTransferForTest(sourceFile, mode)
    }

    private fun invokeStartFileTransfer(
        activity: MainActivity,
        transfer: PendingFileTransfer,
        destination: File
    ) {
        activity.startFileTransferForTest(
            transfer,
            destination,
            transfer.sourcePath.substringAfterLast(File.separator)
        )
    }

    private fun awaitFileTransfer(activity: MainActivity) {
        activity.fileTransferExecutorForTest.submit {}.get(5, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }
}
