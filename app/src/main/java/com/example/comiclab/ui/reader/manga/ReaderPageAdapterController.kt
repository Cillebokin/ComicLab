package com.example.comiclab.ui.reader.manga

import androidx.recyclerview.widget.RecyclerView

internal interface ReaderPageAdapterController {
    fun readerItemCount(): Int

    fun preloadAround(
        firstVisiblePosition: Int,
        visibleItemCount: Int,
        scrollDirection: Int = 1,
        isFastScroll: Boolean = false,
        isIdle: Boolean = false,
        isDiscreteJump: Boolean = false
    )

    fun applyDebugZoomToVisiblePages(
        recyclerView: RecyclerView,
        scale: Float,
        panX: Float,
        panY: Float
    )

    fun trimMemory(level: Int)

    fun flushPendingRefreshesIfIdle()

    fun close()
}
