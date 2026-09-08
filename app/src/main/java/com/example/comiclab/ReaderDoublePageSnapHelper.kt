package com.example.comiclab

import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * 横屏双页模式下，将两个连续页面作为一个分页单元吸附到阅读区域边缘。
 * 页面适配器仍保持单页粒度，因此图片、PDF 分块渲染和单页缩放可以复用现有逻辑。
 */
class ReaderDoublePageSnapHelper : PagerSnapHelper() {

    override fun calculateDistanceToFinalSnap(
        layoutManager: RecyclerView.LayoutManager,
        targetView: View
    ): IntArray {
        if (!layoutManager.canScrollHorizontally()) {
            return super.calculateDistanceToFinalSnap(layoutManager, targetView)
                ?: intArrayOf(0, 0)
        }

        val reverseLayout = isReverseLayout(layoutManager)
        return intArrayOf(leadingEdgeOffset(layoutManager, targetView, reverseLayout), 0)
    }

    override fun findSnapView(layoutManager: RecyclerView.LayoutManager): View? {
        if (!layoutManager.canScrollHorizontally()) {
            return super.findSnapView(layoutManager)
        }

        val reverseLayout = isReverseLayout(layoutManager)
        var closestView: View? = null
        var closestDistance = Int.MAX_VALUE

        for (index in 0 until layoutManager.childCount) {
            val child = layoutManager.getChildAt(index) ?: continue
            val position = layoutManager.getPosition(child)
            if (position == RecyclerView.NO_POSITION || position % 2 != 0) {
                continue
            }

            val distance = abs(leadingEdgeOffset(layoutManager, child, reverseLayout))
            if (distance < closestDistance) {
                closestDistance = distance
                closestView = child
            }
        }

        if (closestView != null) {
            return closestView
        }

        val firstVisiblePosition = (layoutManager as? LinearLayoutManager)
            ?.findFirstVisibleItemPosition()
            ?.takeIf { it != RecyclerView.NO_POSITION }
            ?: return null
        val spreadStart = spreadStartPosition(firstVisiblePosition)
        return layoutManager.findViewByPosition(spreadStart)
    }

    override fun findTargetSnapPosition(
        layoutManager: RecyclerView.LayoutManager,
        velocityX: Int,
        velocityY: Int
    ): Int {
        if (!layoutManager.canScrollHorizontally()) {
            return super.findTargetSnapPosition(layoutManager, velocityX, velocityY)
        }

        val itemCount = layoutManager.itemCount
        if (itemCount <= 0) {
            return RecyclerView.NO_POSITION
        }

        val currentView = findSnapView(layoutManager)
        val currentPosition = currentView?.let(layoutManager::getPosition)
            ?: (layoutManager as? LinearLayoutManager)
                ?.findFirstVisibleItemPosition()
                ?.takeIf { it != RecyclerView.NO_POSITION }
            ?: 0
        val lastSpreadStart = lastSpreadStartForItemCount(itemCount)
        val currentSpreadStart = spreadStartPosition(currentPosition)
            .coerceIn(0, lastSpreadStart)
        val reverseLayout = isReverseLayout(layoutManager)
        val forwardDirection = velocityX > 0
        val delta = if (reverseLayout == forwardDirection) -2 else 2
        if (velocityX == 0) {
            return currentSpreadStart
        }

        val targetPosition = if (currentView != null &&
            isAnchorAheadOfLeadingEdge(layoutManager, currentView, reverseLayout, delta)
        ) {
            currentSpreadStart
        } else {
            currentSpreadStart + delta
        }
        return targetPosition.coerceIn(0, lastSpreadStart)
    }

    private fun isAnchorAheadOfLeadingEdge(
        layoutManager: RecyclerView.LayoutManager,
        anchorView: View,
        reverseLayout: Boolean,
        adapterDelta: Int
    ): Boolean {
        val edgeOffset = leadingEdgeOffset(layoutManager, anchorView, reverseLayout)

        return if (adapterDelta > 0) {
            if (reverseLayout) edgeOffset < 0 else edgeOffset > 0
        } else {
            if (reverseLayout) edgeOffset > 0 else edgeOffset < 0
        }
    }

    private fun leadingEdgeOffset(
        layoutManager: RecyclerView.LayoutManager,
        view: View,
        reverseLayout: Boolean
    ): Int {
        val leadingEdge = if (reverseLayout) {
            layoutManager.width - layoutManager.paddingRight
        } else {
            layoutManager.paddingLeft
        }
        val decoratedEdge = if (reverseLayout) {
            layoutManager.getDecoratedRight(view)
        } else {
            layoutManager.getDecoratedLeft(view)
        }
        return decoratedEdge - leadingEdge
    }

    private fun spreadStartPosition(position: Int): Int {
        return position.coerceAtLeast(0) - (position.coerceAtLeast(0) % 2)
    }

    private fun lastSpreadStartForItemCount(itemCount: Int): Int {
        return ((itemCount - 1) / 2) * 2
    }

    private fun isReverseLayout(layoutManager: RecyclerView.LayoutManager): Boolean {
        val itemCount = layoutManager.itemCount
        if (itemCount <= 1) {
            return false
        }

        val vector = (layoutManager as? RecyclerView.SmoothScroller.ScrollVectorProvider)
            ?.computeScrollVectorForPosition(itemCount - 1)
        return vector?.x?.let { it < 0f } ?: false
    }
}
