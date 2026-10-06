package com.example.comiclab.ui.browser.panels

import android.app.Activity
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertNotNull
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

internal fun <VH : RecyclerView.ViewHolder> withVisibleRecyclerViewHolder(
    adapter: RecyclerView.Adapter<VH>,
    position: Int,
    block: (VH) -> Unit
) {
    val controller = Robolectric.buildActivity(Activity::class.java).setup()
    try {
        val activity = controller.get()
        val recyclerView = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            this.adapter = adapter
        }
        activity.setContentView(recyclerView)

        val width = 480
        val height = 800
        recyclerView.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        recyclerView.layout(0, 0, width, height)
        shadowOf(android.os.Looper.getMainLooper()).idle()

        val holder = recyclerView.findViewHolderForAdapterPosition(position)
        assertNotNull("RecyclerView should create the requested row", holder)
        @Suppress("UNCHECKED_CAST")
        block(holder as VH)
    } finally {
        controller.pause().stop().destroy()
    }
}
