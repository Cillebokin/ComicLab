package com.example.comiclab

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class InsetDividerItemDecoration(
    context: Context,
    insetDp: Int = DEFAULT_INSET_DP,
    private val skipAdjacentViewTypes: Set<Int> = emptySet()
) : RecyclerView.ItemDecoration() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.comiclab_divider_subtle)
        strokeWidth = context.resources.displayMetrics.density
    }
    private val inset = (insetDp * context.resources.displayMetrics.density).toInt()

    override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val itemCount = parent.adapter?.itemCount ?: return
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            val position = parent.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION || position >= itemCount - 1) {
                continue
            }
            val adapter = parent.adapter ?: continue
            val nextPosition = position + 1
            if (adapter.getItemViewType(position) in skipAdjacentViewTypes ||
                adapter.getItemViewType(nextPosition) in skipAdjacentViewTypes
            ) {
                continue
            }

            val y = child.bottom.toFloat()
            canvas.drawLine(
                parent.paddingLeft + inset.toFloat(),
                y,
                parent.width - parent.paddingRight - inset.toFloat(),
                y,
                paint
            )
        }
    }

    private companion object {
        const val DEFAULT_INSET_DP = 12
    }
}
