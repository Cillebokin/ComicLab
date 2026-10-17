package com.example.comiclab

import com.example.comiclab.core.transfer.DirectoryTransferOperations
import com.example.comiclab.core.transfer.FileOperationProgress
import android.content.Context
import android.content.DialogInterface
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import com.example.comiclab.ui.browser.transfer.FileTransferMode
import com.example.comiclab.ui.browser.transfer.PendingFileTransfer
import java.io.File
import java.util.concurrent.TimeUnit
import androidx.core.content.ContextCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [ShadowManageExternalStorage::class])
class MainActivityFileTransferToolbarTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

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
    fun failedDirectoryCutCallbackClosesProgressRetainsPendingAndReloadsDirectory() {
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
                DirectoryTransferOperations.Result.SOURCE_DELETE_FAILED,
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
