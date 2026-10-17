package com.example.comiclab

import android.content.Context
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.widget.EditText
import android.widget.ListView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchActivityR12Test {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @get:Rule
    val temporaryFolder = TemporaryFolder(context.cacheDir)

    @Test
    fun matchingSearchResultIsDisplayedAndOpensItsDirectory() {
        val root = temporaryFolder.newFolder("search-root")
        val matchingFile = File(root, "r12-target.cbz").apply { writeText("fixture") }
        File(root, "unrelated.zip").writeText("fixture")

        val preferences = context.getSharedPreferences("saf_prefs", Context.MODE_PRIVATE)
        val hadCurrentPath = preferences.contains("current_path")
        val previousCurrentPath = preferences.getString("current_path", null)
        val hadPromptValue = preferences.contains("storage_permission_prompted")
        val previousPromptValue = preferences.getBoolean("storage_permission_prompted", false)

        var searchActivity: SearchActivity? = null
        var mainActivity: MainActivity? = null
        var storedPathSandbox: StoredPathPreferenceSandbox? = null

        try {
            storedPathSandbox = StoredPathPreferenceSandbox(context)
            executeShellCommand(
                "appops set com.example.comiclab MANAGE_EXTERNAL_STORAGE allow"
            )
            assertTrue(Environment.isExternalStorageManager())
            check(
                preferences.edit()
                    .putBoolean("storage_permission_prompted", true)
                    .commit()
            )

            executeShellCommand(
                "am start -n com.example.comiclab/.MainActivity -f 0x10008000 " +
                    "--es center_target_path ${matchingFile.absolutePath}"
            )
            mainActivity = awaitActivity<MainActivity>()
            onMain { mainActivity!!.findViewById<View>(R.id.btnSearch).performClick() }
            searchActivity = awaitActivity<SearchActivity>()

            val resultList = searchActivity!!.findViewById<ListView>(R.id.listSearchResults)
            onMain {
                searchActivity!!.findViewById<EditText>(R.id.inputSearchKeyword)
                    .setText("target")
                searchActivity!!.findViewById<View>(R.id.btnRunSearch).performClick()
            }
            awaitCondition { onMain { resultList.adapter?.count == 1 } }
            assertTrue(onMain { resultList.isShown })
            assertEquals(
                matchingFile.absolutePath,
                onMain {
                    (resultList.adapter.getItem(0) as? File)?.absolutePath
                }
            )

            onMain {
                val firstRow = checkNotNull(resultList.getChildAt(0))
                resultList.performItemClick(firstRow, 0, resultList.adapter.getItemId(0))
            }
            mainActivity = awaitActivity<MainActivity>()
            assertEquals(root.absolutePath, onMain { mainActivity!!.currentPathForTest })
        } finally {
            listOfNotNull(searchActivity, mainActivity).forEach { activity ->
                if (!activity.isFinishing && !activity.isDestroyed) {
                    instrumentation.runOnMainSync { activity.finish() }
                }
            }
            instrumentation.waitForIdleSync()
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
                storedPathSandbox?.restore()
            }
        }
    }

    private inline fun <reified T : android.app.Activity> awaitActivity(): T {
        val deadline = SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(20)
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            val activity = onMain {
                ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<T>()
                    .firstOrNull { !it.isFinishing && !it.isDestroyed }
            }
            if (activity != null) return activity
            Thread.sleep(50L)
        }
        error("${T::class.java.simpleName} did not resume")
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(10)
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            Thread.sleep(50L)
        }
        assertTrue("Condition did not become true within 10000ms", condition())
    }

    private fun <T> onMain(action: () -> T): T {
        val value = AtomicReference<T>()
        instrumentation.runOnMainSync { value.set(action()) }
        return value.get()
    }

    private fun executeShellCommand(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use {
            it.readText()
        }
    }
}
