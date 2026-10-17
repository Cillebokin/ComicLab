package com.example.comiclab

import android.content.Context
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.widget.ListView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainPanelScrimTest {

    @Test
    fun openingEachSidePanelShowsScrimDuringPanelEntrance() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = context.getSharedPreferences("saf_prefs", Context.MODE_PRIVATE)
        val hadCurrentPath = preferences.contains("current_path")
        val previousCurrentPath = preferences.getString("current_path", null)
        val hadPromptValue = preferences.contains("storage_permission_prompted")
        val previousPromptValue = preferences.getBoolean("storage_permission_prompted", false)
        val cacheRoot = context.cacheDir.canonicalFile
        val testDirectory = File(
            cacheRoot,
            "task3-phase3-main-panel-${UUID.randomUUID()}"
        ).canonicalFile
        val sampleDirectory = File(testDirectory, "sample-directory")
        var activity: MainActivity? = null
        var storedPathSandbox: StoredPathPreferenceSandbox? = null

        try {
            check(testDirectory.parentFile == cacheRoot)
            check(!testDirectory.exists())
            check(testDirectory.mkdirs())
            check(sampleDirectory.mkdirs())
            storedPathSandbox = StoredPathPreferenceSandbox(context)
            executeShellCommand(
                instrumentation,
                "appops set com.example.comiclab MANAGE_EXTERNAL_STORAGE allow"
            )
            assertTrue(Environment.isExternalStorageManager())
            check(
                preferences.edit()
                    .putString("current_path", testDirectory.absolutePath)
                    .putBoolean("storage_permission_prompted", true)
                    .commit()
            )
            executeShellCommand(
                instrumentation,
                "am start -n com.example.comiclab/.MainActivity -f 0x10008000 " +
                    "--es center_target_path ${sampleDirectory.absolutePath}"
            )
            activity = awaitMainActivity(instrumentation)
            assertEquals(
                testDirectory.absolutePath,
                onMain(instrumentation) { activity!!.currentPathForTest }
            )

            val fileList = activity!!.findViewById<ListView>(R.id.listFiles)
            awaitCondition(instrumentation) {
                onMain(instrumentation) { fileList.adapter?.count == 2 }
            }
            assertTrue(onMain(instrumentation) { fileList.isShown })
            assertEquals(2, onMain(instrumentation) { fileList.adapter.count })
            onMain(instrumentation) {
                val childDirectoryRow = checkNotNull(fileList.getChildAt(1))
                fileList.performItemClick(
                    childDirectoryRow,
                    1,
                    fileList.adapter.getItemId(1)
                )
            }
            awaitCondition(instrumentation) {
                onMain(instrumentation) {
                    activity!!.currentPathForTest == sampleDirectory.absolutePath &&
                        fileList.adapter?.count == 1
                }
            }

            listOf(
                R.id.btnReadingHistory,
                R.id.btnFavoriteComics,
                R.id.btnFavoritePaths
            ).forEach { buttonId ->
                var scrimVisibility = View.GONE
                instrumentation.runOnMainSync {
                    activity!!.findViewById<View>(buttonId).performClick()
                    scrimVisibility = activity!!.findViewById<View>(R.id.readingHistoryScrim).visibility
                }
                assertEquals(
                    "Scrim should appear with panel button $buttonId, not after its entrance animation",
                    View.VISIBLE,
                    scrimVisibility
                )
            }
        } finally {
            activity?.let { launchedActivity ->
                instrumentation.runOnMainSync { launchedActivity.finish() }
                instrumentation.waitForIdleSync()
            }
            try {
                val editor = preferences.edit()
                if (hadCurrentPath) {
                    editor.putString("current_path", previousCurrentPath)
                } else {
                    editor.remove("current_path")
                }
                if (hadPromptValue) {
                    editor.putBoolean("storage_permission_prompted", previousPromptValue)
                } else {
                    editor.remove("storage_permission_prompted")
                }
                check(editor.commit())
            } finally {
                try {
                    storedPathSandbox?.restore()
                } finally {
                    if (testDirectory.exists()) {
                        check(testDirectory.canonicalFile.toPath().startsWith(cacheRoot.toPath()))
                        check(testDirectory.deleteRecursively())
                    }
                }
            }
        }
    }

    private fun awaitMainActivity(instrumentation: android.app.Instrumentation): MainActivity {
        val deadline = SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(20)
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            val activity = onMain(instrumentation) {
                ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>()
                    .firstOrNull { !it.isFinishing && !it.isDestroyed }
            }
            if (activity != null) return activity
            Thread.sleep(50L)
        }
        error("MainActivity did not resume")
    }

    private fun awaitCondition(
        instrumentation: android.app.Instrumentation,
        condition: () -> Boolean
    ) {
        val deadline = SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(10)
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            Thread.sleep(50L)
        }
        assertTrue("Condition did not become true within 10000ms", condition())
    }

    private fun <T> onMain(instrumentation: android.app.Instrumentation, action: () -> T): T {
        val value = AtomicReference<T>()
        instrumentation.runOnMainSync { value.set(action()) }
        return value.get()
    }

    private fun executeShellCommand(
        instrumentation: android.app.Instrumentation,
        command: String
    ): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use {
            it.readText()
        }
    }
}
