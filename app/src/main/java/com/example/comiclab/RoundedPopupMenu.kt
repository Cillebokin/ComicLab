package com.example.comiclab

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat

object RoundedPopupMenu {

    data class Item(
        val label: String,
        val isSelected: Boolean = false,
        val onClick: () -> Unit
    )

    fun show(
        context: Context,
        anchor: View,
        items: List<Item>,
        widthDp: Int
    ) {
        if (items.isEmpty()) {
            return
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_file_picker_popup_panel)
            setPadding(
                dpToPx(context, MENU_PADDING_HORIZONTAL_DP),
                dpToPx(context, MENU_PADDING_VERTICAL_DP),
                dpToPx(context, MENU_PADDING_HORIZONTAL_DP),
                dpToPx(context, MENU_PADDING_VERTICAL_DP)
            )
        }

        var popupWindow: PopupWindow? = null
        items.forEach { item ->
            val row = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dpToPx(context, MENU_ITEM_HEIGHT_DP)
                )
                background = ContextCompat.getDrawable(context, R.drawable.bg_file_picker_popup_item)
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = android.view.Gravity.CENTER_VERTICAL
                includeFontPadding = false
                maxLines = 1
                setPadding(
                    dpToPx(context, MENU_ITEM_PADDING_HORIZONTAL_DP),
                    0,
                    dpToPx(context, MENU_ITEM_PADDING_HORIZONTAL_DP),
                    0
                )
                text = item.label
                textSize = 14f
                setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (item.isSelected) {
                            R.color.comiclab_blue
                        } else {
                            R.color.comiclab_text_primary
                        }
                    )
                )
                if (item.isSelected) {
                    setTypeface(typeface, Typeface.BOLD)
                }
                setOnClickListener {
                    popupWindow?.dismiss()
                    item.onClick()
                }
            }
            content.addView(row)
        }

        val popupWidthPx = dpToPx(context, widthDp)
        popupWindow = PopupWindow(
            content,
            popupWidthPx,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            elevation = dpToPx(context, MENU_ELEVATION_DP).toFloat()
            showAsDropDown(
                anchor,
                anchor.width - popupWidthPx,
                dpToPx(context, MENU_VERTICAL_OFFSET_DP)
            )
        }
    }

    private fun dpToPx(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }

    private const val MENU_PADDING_HORIZONTAL_DP = 6
    private const val MENU_PADDING_VERTICAL_DP = 6
    private const val MENU_ITEM_HEIGHT_DP = 44
    private const val MENU_ITEM_PADDING_HORIZONTAL_DP = 14
    private const val MENU_VERTICAL_OFFSET_DP = 8
    private const val MENU_ELEVATION_DP = 8
}
