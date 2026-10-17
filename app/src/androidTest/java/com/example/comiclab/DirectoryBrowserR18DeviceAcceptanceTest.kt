package com.example.comiclab

import android.app.Instrumentation
import android.content.Context
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.widget.ListView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.comiclab.ui.browser.model.FileItem
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectoryBrowserR18DeviceAcceptanceTest {

    @Test
    fun directoryNavigationSortingRefreshAndScrollRestoreRemainStable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = context.getSharedPreferences("saf_prefs", Context.MODE_PRIVATE)
        val preferenceKeys = listOf("current_path", "storage_permission_prompted", "sort_mode")
        val savedPreferenceKeys = preferenceKeys.filter(preferences::contains).toSet()
        val savedPreferences = savedPreferenceKeys.associateWith { key -> preferences.all[key] }
        val previousAppOpsMode = readManageStorageMode(instrumentation)
        val cacheRoot = context.cacheDir.canonicalFile
        cacheRoot.listFiles { file ->
            file.isDirectory && file.name.startsWith("r18-directory-")
        }.orEmpty().forEach { staleFixture ->
            check(staleFixture.canonicalFile.parentFile == cacheRoot)
            check(staleFixture.deleteRecursively())
        }
        val fixtureRoot = File(cacheRoot, "r18-directory-${UUID.randomUUID()}").canonicalFile
        var activity: MainActivity? = null

        try {
            check(fixtureRoot.parentFile == cacheRoot)
            check(!fixtureRoot.exists())
            check(fixtureRoot.mkdirs())
            repeat(25) { index ->
                val directory = File(fixtureRoot, "directory-%02d".format(index))
                check(directory.mkdir())
                File(directory, "inside.txt").writeText("fixture")
                check(File(directory, "inside.txt").length() > 0)
            }
            File(fixtureRoot, "alpha.txt").writeText("a")
            File(fixtureRoot, "zeta.txt").writeText("z")
            check(File(fixtureRoot, "alpha.txt").length() > 0)
            check(File(fixtureRoot, "zeta.txt").length() > 0)

            executeShellCommand(
                instrumentation,
                "appops set com.example.comiclab MANAGE_EXTERNAL_STORAGE allow"
            )
            assertTrue(Environment.isExternalStorageManager())
            check(
                preferences.edit()
                    .putString("current_path", fixtureRoot.absolutePath)
                    .putBoolean("storage_permission_prompted", true)
                    .putString("sort_mode", "NAME_ASC")
                    .commit()
            )
            executeShellCommand(
                instrumentation,
                "am start -n com.example.comiclab/.MainActivity -f 0x10008000"
            )
            activity = awaitMainActivity(instrumentation)
            assertEquals(
                fixtureRoot.absolutePath,
                onMain(instrumentation) { activity!!.currentPathForTest }
            )

            val fileList = activity!!.findViewById<ListView>(R.id.listFiles)
            awaitCondition(instrumentation) {
                onMain(instrumentation) {
                    activity!!.currentPathForTest == fixtureRoot.absolutePath &&
                        fileList.adapter?.count == 28
                }
            }

            check(preferences.edit().putString("sort_mode", "NAME_DESC").commit())
            onMain(instrumentation) { activity!!.loadCurrentDirectoryForTest() }
            awaitCondition(instrumentation) {
                onMain(instrumentation) {
                    preferences.getString("sort_mode", null) == "NAME_DESC" &&
                        adapterFile(fileList, 1)?.name == "directory-24"
                }
            }

            check(preferences.edit().putString("sort_mode", "NAME_ASC").commit())
            onMain(instrumentation) { activity!!.loadCurrentDirectoryForTest() }
            awaitCondition(instrumentation) {
                onMain(instrumentation) {
                    preferences.getString("sort_mode", null) == "NAME_ASC" &&
                        adapterFile(fileList, 1)?.name == "directory-00"
                }
            }

            File(fixtureRoot, "refresh-marker.txt").writeText("refreshed")
            check(File(fixtureRoot, "refresh-marker.txt").length() > 0)
            onMain(instrumentation) { activity!!.loadCurrentDirectoryForTest() }
            awaitCondition(instrumentation) {
                onMain(instrumentation) {
                    (0 until (fileList.adapter?.count ?: 0)).any { position ->
                        adapterFile(fileList, position)?.name == "refresh-marker.txt"
                    }
                }
            }

            val anchorPosition = 16 // Parent row followed by directory-00 through directory-24.
            val anchorTop = -17
            onMain(instrumentation) { fileList.setSelectionFromTop(anchorPosition, anchorTop) }
            awaitCondition(instrumentation) {
                onMain(instrumentation) {
                    fileList.firstVisiblePosition == anchorPosition &&
                        fileList.getChildAt(0)?.top == anchorTop
                }
            }
            val anchorPath = onMain(instrumentation) {
                (fileList.adapter.getItem(fileList.firstVisiblePosition) as FileItem)
                    .file!!.absolutePath
            }
            assertEquals("directory-15", File(anchorPath).name)

            onMain(instrumentation) {
                val row = checkNotNull(fileList.getChildAt(0))
                fileList.performItemClick(
                    row,
                    anchorPosition,
                    fileList.adapter.getItemId(anchorPosition)
                )
            }
            awaitCondition(instrumentation) {
                onMain(instrumentation) {
                    activity!!.currentPathForTest == anchorPath && fileList.adapter?.count == 2
                }
            }

            onMain(instrumentation) {
                activity!!.findViewById<android.view.View>(R.id.btnBack).performClick()
            }
            awaitCondition(instrumentation) {
                onMain(instrumentation) {
                    activity!!.currentPathForTest == fixtureRoot.absolutePath &&
                        fileList.adapter?.count == 29 &&
                        fileList.firstVisiblePosition == anchorPosition &&
                        fileList.getChildAt(0)?.top == anchorTop &&
                        adapterFile(fileList, fileList.firstVisiblePosition)?.absolutePath == anchorPath
                }
            }
        } finally {
            activity?.let { launchedActivity ->
                instrumentation.runOnMainSync { launchedActivity.finish() }
                instrumentation.waitForIdleSync()
                val deadline = SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(5)
                while (!launchedActivity.isDestroyed &&
                    SystemClock.elapsedRealtime() < deadline
                ) {
                    instrumentation.waitForIdleSync()
                    Thread.sleep(25L)
                }
            }
            try {
                val editor = preferences.edit()
                (preferenceKeys - savedPreferenceKeys).forEach(editor::remove)
                savedPreferences.forEach { (key, value) ->
                    when (value) {
                        is String -> editor.putString(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value)
                        null -> editor.remove(key)
                        else -> error("Unsupported saved preference type for $key")
                    }
                }
                check(editor.commit())
                savedPreferences.forEach { (key, value) ->
                    check(preferences.all[key] == value)
                }
            } finally {
                try {
                    executeShellCommand(
                        instrumentation,
                        "appops set com.example.comiclab MANAGE_EXTERNAL_STORAGE $previousAppOpsMode"
                    )
                } finally {
                    if (fixtureRoot.exists()) {
                        check(fixtureRoot.canonicalFile.toPath().startsWith(cacheRoot.toPath()))
                        check(fixtureRoot.deleteRecursively())
                    }
                }
            }
        }
    }

    private fun adapterFile(list: ListView, position: Int): File? {
        return (list.adapter?.getItem(position) as? FileItem)?.file
    }

    private fun readManageStorageMode(instrumentation: Instrumentation): String {
        val output = executeShellCommand(
            instrumentation,
            "appops get com.example.comiclab MANAGE_EXTERNAL_STORAGE"
        )
        val mode = Regex("MANAGE_EXTERNAL_STORAGE:\\s*([a-z_]+)")
            .find(output)
            ?.groupValues
            ?.get(1)
        return checkNotNull(mode) { "Could not read MANAGE_EXTERNAL_STORAGE mode: $output" }
    }

    private fun awaitMainActivity(instrumentation: Instrumentation): MainActivity {
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

    private fun awaitCondition(instrumentation: Instrumentation, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(15)
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            Thread.sleep(50L)
        }
        assertTrue("Condition did not become true within 15000ms", condition())
    }

    private fun <T> onMain(instrumentation: Instrumentation, action: () -> T): T {
        val value = AtomicReference<T>()
        instrumentation.runOnMainSync { value.set(action()) }
        return value.get()
    }

    private fun executeShellCommand(instrumentation: Instrumentation, command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use {
            it.readText()
        }
    }
}
