package com.example.comiclab

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainPanelScrimTest {

    @Test
    fun openingEachSidePanelShowsScrimDuringPanelEntrance() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = context.getSharedPreferences("saf_prefs", Context.MODE_PRIVATE)
        val hadPromptValue = preferences.contains("storage_permission_prompted")
        val previousPromptValue = preferences.getBoolean("storage_permission_prompted", false)
        var activity: MainActivity? = null

        try {
            check(
                preferences.edit()
                    .putBoolean("storage_permission_prompted", true)
                    .commit()
            )
            activity = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity

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
            val editor = preferences.edit()
            if (hadPromptValue) {
                editor.putBoolean("storage_permission_prompted", previousPromptValue)
            } else {
                editor.remove("storage_permission_prompted")
            }
            check(editor.commit())
        }
    }
}
