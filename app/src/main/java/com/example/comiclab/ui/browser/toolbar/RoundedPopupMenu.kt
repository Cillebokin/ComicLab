package com.example.comiclab.ui.browser.toolbar

import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.comiclab.R
import com.example.comiclab.ui.browser.dpToPx

internal data class RoundedPopupMenuItem(
    val label: String,
    val isSelected: Boolean = false,
    val onClick: () -> Unit
)

// Moved out of MainActivity without behaviour change; shared by the sort and classify menus.
internal fun AppCompatActivity.showRoundedPopupMenu(
    anchor: View,
    items: List<RoundedPopupMenuItem>,
    widthDp: Int
) {
    if (items.isEmpty()) {
        return
    }

    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_file_picker_popup_panel)
        setPadding(
            dpToPx(ROUNDED_MENU_PADDING_HORIZONTAL_DP),
            dpToPx(ROUNDED_MENU_PADDING_VERTICAL_DP),
            dpToPx(ROUNDED_MENU_PADDING_HORIZONTAL_DP),
            dpToPx(ROUNDED_MENU_PADDING_VERTICAL_DP)
        )
    }

    var popupWindow: PopupWindow? = null
    items.forEach { item ->
        val row = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(ROUNDED_MENU_ITEM_HEIGHT_DP)
            )
            background = getDrawable(R.drawable.bg_file_picker_popup_item)
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            maxLines = 1
            setPadding(
                dpToPx(ROUNDED_MENU_ITEM_PADDING_HORIZONTAL_DP),
                0,
                dpToPx(ROUNDED_MENU_ITEM_PADDING_HORIZONTAL_DP),
                0
            )
            text = item.label
            textSize = 14f
            setTextColor(
                getColor(
                    if (item.isSelected) {
                        R.color.comiclab_accent
                    } else {
                        R.color.comiclab_text_primary
                    }
                )
            )
            if (item.isSelected) {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            setOnClickListener {
                popupWindow?.dismiss()
                item.onClick()
            }
        }
        content.addView(row)
    }

    val popupWidthPx = dpToPx(widthDp)
    popupWindow = PopupWindow(
        content,
        popupWidthPx,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        true
    ).apply {
        isOutsideTouchable = true
        setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        elevation = dpToPx(ROUNDED_MENU_ELEVATION_DP).toFloat()
        showAsDropDown(
            anchor,
            anchor.width - popupWidthPx,
            dpToPx(ROUNDED_MENU_VERTICAL_OFFSET_DP)
        )
    }
}

private const val ROUNDED_MENU_PADDING_HORIZONTAL_DP = 6
private const val ROUNDED_MENU_PADDING_VERTICAL_DP = 6
private const val ROUNDED_MENU_ITEM_HEIGHT_DP = 44
private const val ROUNDED_MENU_ITEM_PADDING_HORIZONTAL_DP = 14
private const val ROUNDED_MENU_VERTICAL_OFFSET_DP = 8
private const val ROUNDED_MENU_ELEVATION_DP = 8
