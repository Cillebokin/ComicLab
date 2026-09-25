package com.example.comiclab

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class RoundedPopupMenuPositionTest {

    @Test
    fun menuAnchoredAtBottomKeepsDeleteItemInsideVisibleWindow() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = context.getSharedPreferences("saf_prefs", Context.MODE_PRIVATE)
        val hadPromptValue = preferences.contains("storage_permission_prompted")
        val previousPromptValue = preferences.getBoolean("storage_permission_prompted", false)
        var activity: MainActivity? = null
        var anchor: View? = null
        val deleteClicked = AtomicBoolean(false)

        try {
            check(preferences.edit().putBoolean("storage_permission_prompted", true).commit())
            activity = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
            instrumentation.waitForIdleSync()

            instrumentation.runOnMainSync {
                val launchedActivity = activity!!
                val content = launchedActivity.findViewById<FrameLayout>(android.R.id.content)
                val contentLocation = IntArray(2)
                val visibleWindow = Rect()
                content.getLocationOnScreen(contentLocation)
                content.getWindowVisibleDisplayFrame(visibleWindow)
                val contentBottom = contentLocation[1] + content.height
                val bottomMargin = (contentBottom - visibleWindow.bottom + 1).coerceAtLeast(0)
                anchor = View(launchedActivity).also { view ->
                    content.addView(
                        view,
                        FrameLayout.LayoutParams(48, 1, Gravity.BOTTOM or Gravity.END).apply {
                            this.bottomMargin = bottomMargin
                        }
                    )
                }
                anchor!!.post {
                    RoundedPopupMenu.show(
                        context = launchedActivity,
                        anchor = anchor!!,
                        widthDp = 148,
                        items = listOf(
                            RoundedPopupMenu.Item("Jump") {},
                            RoundedPopupMenu.Item("Delete") { deleteClicked.set(true) }
                        )
                    )
                }
            }
            instrumentation.waitForIdleSync()

            listOf("Jump", "Delete").forEach { itemLabel ->
                onView(withText(itemLabel)).check { view, _ ->
                    val itemLocation = IntArray(2)
                    view.getLocationOnScreen(itemLocation)
                    val itemBounds = Rect(
                        itemLocation[0],
                        itemLocation[1],
                        itemLocation[0] + view.width,
                        itemLocation[1] + view.height
                    )

                    val visibleWindow = Rect()
                    anchor!!.getWindowVisibleDisplayFrame(visibleWindow)
                    assertTrue(
                        "$itemLabel menu item bounds $itemBounds exceed visible window $visibleWindow",
                        visibleWindow.contains(itemBounds)
                    )
                }
            }
            onView(withText("Delete")).perform(click())
            assertTrue("Delete menu action should remain clickable", deleteClicked.get())
        } finally {
            activity?.let { launchedActivity ->
                instrumentation.runOnMainSync {
                    anchor?.let { (launchedActivity.findViewById<FrameLayout>(android.R.id.content)).removeView(it) }
                    launchedActivity.finish()
                }
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
