package com.example.comiclab

import android.content.Context
import android.content.DialogInterface
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import java.io.File
import org.junit.Assert.assertEquals
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
            val newFolderButton = activity.findViewById<View>(R.id.btnCreateTransferDirectory)
            val pasteButton = activity.findViewById<View>(R.id.btnPasteFile)
            assertEquals(View.GONE, newFolderButton.visibility)

            val modeType = MainActivity::class.java.declaredClasses
                .single { it.simpleName == "FileTransferMode" }
            val copyMode = checkNotNull(modeType.enumConstants)
                .single { (it as Enum<*>).name == "COPY" }
            MainActivity::class.java
                .getDeclaredMethod("beginFileTransfer", File::class.java, modeType)
                .apply { isAccessible = true }
                .invoke(activity, sourceFile, copyMode)

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
}
