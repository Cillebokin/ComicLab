package com.example.comiclab

import android.content.Context
import android.os.Environment
import android.view.View
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowEnvironment

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [ShadowManageExternalStorage::class])
class MainActivityNavigationTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

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
        @Implementation
        @JvmStatic
        fun isExternalStorageManager(): Boolean = true
    }
}
