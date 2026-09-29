package com.example.comiclab

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClassifyMenuVisibilityTest {

    @Test
    fun classifyMenuHidesSimilarityAndUsesUnqualifiedLabels() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = context.getSharedPreferences("saf_prefs", Context.MODE_PRIVATE)
        val hadPromptValue = preferences.contains("storage_permission_prompted")
        val previousPromptValue = preferences.getBoolean("storage_permission_prompted", false)
        var activity: MainActivity? = null

        try {
            assertTrue(
                preferences.edit()
                    .putBoolean("storage_permission_prompted", true)
                    .commit()
            )
            activity = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity

            instrumentation.runOnMainSync {
                activity!!.findViewById<View>(R.id.btnClassify).performClick()
            }
            instrumentation.waitForIdleSync()

            val classifyLabel = context.getString(R.string.classify_comics)
            val migrationLabel = context.getString(R.string.comic_migration)
            val mergeComicsLabel = context.getString(R.string.language_menu_merge_comics)
            val mergeFilesLabel = context.getString(R.string.language_menu_merge_files)
            val similarDirectoriesLabel = context.getString(
                R.string.language_menu_find_similar_directories
            )

            assertFalse(classifyLabel.contains("没做完") || classifyLabel.contains("unfinished"))
            assertFalse(migrationLabel.contains("没做完") || migrationLabel.contains("unfinished"))
            assertFalse(mergeComicsLabel.contains("不递归") || mergeComicsLabel.contains("non-recursive"))
            assertFalse(mergeFilesLabel.contains("不递归") || mergeFilesLabel.contains("non-recursive"))
            onView(withText(classifyLabel)).check(matches(isDisplayed()))
            onView(withText(migrationLabel)).check(matches(isDisplayed()))
            onView(withText(mergeComicsLabel)).check(matches(isDisplayed()))
            onView(withText(mergeFilesLabel)).check(matches(isDisplayed()))
            onView(withText(similarDirectoriesLabel)).check(doesNotExist())
        } finally {
            activity?.let { launchedActivity ->
                instrumentation.runOnMainSync { launchedActivity.finish() }
                instrumentation.waitForIdleSync()
            }
            val editor = preferences.edit()
            if (hadPromptValue) {
                editor.putBoolean("storage_permission_prompted", previousPromptValue)
            } else {
                editor.remove("storage_permission_prompted")
            }
            assertTrue(editor.commit())
        }
    }
}
