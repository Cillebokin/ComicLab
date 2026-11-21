package com.example.comiclab.ui.browser

import android.content.Context
import android.view.View
import android.widget.AbsListView
import android.widget.ImageView
import android.widget.ProgressBar
import com.example.comiclab.core.store.EbookProgressStore
import com.example.comiclab.core.store.ReaderProgressStore
import com.example.comiclab.ebook.EbookReaderProgressMapper
import com.example.comiclab.ebook.ReaderFileDetector
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

internal fun bindReadingProgressBar(
    context: Context,
    file: File,
    progressBar: ProgressBar,
    fallbackMangaTotalCount: Int = 0
) {
    if (ReaderFileDetector.isEbook(file)) {
        progressBar.max = EbookReaderProgressMapper.SLIDER_MAX
        progressBar.progress = runCatching {
            EbookProgressStore.savedSliderProgress(context, file)
        }.getOrDefault(0)
        return
    }

    val total = runCatching {
        ReaderProgressStore.savedTotalPageCount(context, file)
    }.getOrNull() ?: fallbackMangaTotalCount
    progressBar.max = total.coerceAtLeast(1)
    progressBar.progress = runCatching {
        ReaderProgressStore.savedReadingPageCount(context, file, total)
    }.getOrDefault(0)
}

internal fun hideFileReadingProgressBar(progressBar: ProgressBar?) {
    if (progressBar == null) return
    progressBar.tag = null
    progressBar.max = 1
    progressBar.progress = 0
    progressBar.visibility = View.GONE
}
