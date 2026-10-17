package com.example.comiclab.ui.browser

import android.content.Context
import android.view.View
import android.widget.AbsListView
import android.widget.ImageView
import java.io.File

// 文件列表与搜索结果共用的封面纯工具函数（W2-5）。
// 仅抽取无状态的辅助逻辑，不包含封面加载流程。

internal fun applyHalfSizeIconPadding(imgIcon: ImageView) {
    val drawable = imgIcon.drawable ?: return
    val params = imgIcon.layoutParams
    val targetWidth = drawable.intrinsicWidth / 2
    val targetHeight = drawable.intrinsicHeight / 2
    val horizontalPadding = ((params.width - targetWidth) / 2).coerceAtLeast(0)
    val verticalPadding = ((params.height - targetHeight) / 2).coerceAtLeast(0)
    imgIcon.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
}

internal fun updateIconSize(imgIcon: ImageView, width: Int, height: Int) {
    val layoutParams = imgIcon.layoutParams
    if (layoutParams.width == width && layoutParams.height == height) {
        return
    }

    layoutParams.width = width
    layoutParams.height = height
    imgIcon.layoutParams = layoutParams
}

internal fun Context.dpToPx(value: Int): Int {
    return (value * resources.displayMetrics.density).toInt()
}

internal fun archiveCoverCacheKey(file: File): String {
    return "${file.absolutePath}:${file.lastModified()}:${file.length()}"
}

internal fun View.findAncestorAbsListView(): AbsListView? {
    var current = parent
    while (current is View) {
        if (current is AbsListView) {
            return current
        }
        current = current.parent
    }
    return null
}
