package com.example.comiclab

import android.content.Context
import android.content.DialogInterface
import android.os.Looper
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [ShadowManageExternalStorage::class])
class MainActivityNasCacheDialogTest {

    @Test
    fun selectingCachedArchiveDefersConfirmationUntilPickerDismissalFinishes() {
        val application = RuntimeEnvironment.getApplication()
        application.getSharedPreferences("saf_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("storage_permission_prompted", true)
            .apply()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val archive = File(activity.cacheDir, "nas-comics/${"a".repeat(64)}/comic.zip")
            .apply {
                parentFile?.mkdirs()
                writeText("comic")
            }

        try {
            MainActivity::class.java.getDeclaredMethod("showNasCachedArchivePickerDialog")
                .apply { isAccessible = true }
                .invoke(activity)
            val picker = checkNotNull(ShadowAlertDialog.getLatestAlertDialog())

            picker.listView.performItemClick(
                picker.listView.getChildAt(0),
                0,
                picker.listView.adapter.getItemId(0)
            )

            assertFalse(picker.isShowing)
            assertFalse(ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true)
            assertTrue(archive.isFile)

            shadowOf(Looper.getMainLooper()).idle()

            val confirmation = checkNotNull(ShadowAlertDialog.getLatestAlertDialog())
            assertTrue(confirmation.isShowing)
            assertTrue(archive.isFile)
            confirmation.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            assertTrue(archive.isFile)
        } finally {
            controller.pause().stop().destroy()
            File(activity.cacheDir, "nas-comics").deleteRecursively()
        }
    }
}
