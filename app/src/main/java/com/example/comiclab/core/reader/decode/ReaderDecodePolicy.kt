package com.example.comiclab.core.reader.decode

internal fun calculatePdfRenderWidthByPixelBudget(
    pageWidth: Int,
    pageHeight: Int,
    baseDisplayWidth: Int,
    renderScale: Int,
    maxRenderWidth: Int,
    maxRenderPixels: Long
): Int {
    val safePageWidth = pageWidth.coerceAtLeast(1)
    val safePageHeight = pageHeight.coerceAtLeast(1)
    val safeBaseDisplayWidth = baseDisplayWidth.coerceAtLeast(1).toLong()
    val safeRenderScale = renderScale.coerceAtLeast(1).toLong()
    val safeMaxRenderWidth = maxRenderWidth.coerceAtLeast(1)
    val safeMaxRenderPixels = maxRenderPixels.coerceAtLeast(1L)
    val maxWidthByPixels = kotlin.math.sqrt(
        safeMaxRenderPixels.toDouble() * safePageWidth.toDouble() / safePageHeight.toDouble()
    ).toInt().coerceAtLeast(1)
    val preferredWidth = (safeBaseDisplayWidth * safeRenderScale)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()

    return minOf(preferredWidth, safeMaxRenderWidth, maxWidthByPixels)
}

internal fun visiblePreviewPriorityForReader(
    isPdfSource: Boolean,
    previewPriority: Int,
    fullPriority: Int
): Int {
    return if (isPdfSource) {
        maxOf(previewPriority, fullPriority + 1)
    } else {
        previewPriority
    }
}

internal fun shouldScheduleImmediateFullDecodeForReader(
    isPdfSource: Boolean,
    isDiscreteJump: Boolean = false
): Boolean {
    return !isPdfSource || isDiscreteJump
}

internal fun calculatePdfPreviewWidth(
    baseDisplayWidth: Int,
    minimumWidth: Int,
    maximumWidth: Int
): Int {
    val safeMinimumWidth = minimumWidth.coerceAtLeast(1)
    val safeMaximumWidth = maximumWidth.coerceAtLeast(safeMinimumWidth)
    val preferredWidth = (baseDisplayWidth.coerceAtLeast(1).toLong() / 2L)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
    return preferredWidth.coerceIn(safeMinimumWidth, safeMaximumWidth)
}

internal fun preparedReaderNearbyPositions(
    firstVisible: Int,
    lastVisible: Int,
    entryCount: Int,
    movingForward: Boolean,
    beforeCount: Int,
    afterCount: Int
): List<Int> {
    if (entryCount <= 0 || firstVisible < 0 || lastVisible < firstVisible ||
        lastVisible >= entryCount
    ) {
        return emptyList()
    }

    val before = if (beforeCount <= 0 || firstVisible == 0) {
        emptyList()
    } else {
        (firstVisible - 1 downTo (firstVisible - beforeCount).coerceAtLeast(0)).toList()
    }
    val after = if (afterCount <= 0 || lastVisible == entryCount - 1) {
        emptyList()
    } else {
        ((lastVisible + 1)..(lastVisible + afterCount).coerceAtMost(entryCount - 1)).toList()
    }

    return if (movingForward) after + before else before + after
}

internal fun shouldPreloadBackgroundFullPagesForReader(
    isPdfSource: Boolean,
    isFastScroll: Boolean,
    isIdle: Boolean
): Boolean {
    return if (isPdfSource) {
        false
    } else {
        !isFastScroll || isIdle
    }
}

internal fun shouldRefreshBoundReaderPageImmediately(
    isBoundToPosition: Boolean,
    isComputingLayout: Boolean
): Boolean {
    return isBoundToPosition && !isComputingLayout
}

internal fun isReaderTileVisible(
    tileTop: Int,
    tileBottom: Int,
    viewportTop: Int,
    viewportBottom: Int
): Boolean {
    return isReaderTileVisible(
        tileTop = tileTop,
        tileBottom = tileBottom,
        viewportTop = viewportTop.toFloat(),
        viewportBottom = viewportBottom.toFloat()
    )
}

internal fun isReaderTileVisible(
    tileTop: Int,
    tileBottom: Int,
    viewportTop: Float,
    viewportBottom: Float
): Boolean {
    return tileBottom > tileTop &&
        viewportBottom > viewportTop &&
        tileBottom.toFloat() > viewportTop &&
        tileTop.toFloat() < viewportBottom
}

internal fun shouldUsePdfTiledRendering(
    pageWidth: Int,
    pageHeight: Int,
    displayWidth: Int,
    renderPixelThreshold: Long
): Boolean {
    if (pageWidth <= 0 || pageHeight <= 0 || displayWidth <= 0) {
        return false
    }

    val displayHeight = (displayWidth.toLong() * pageHeight / pageWidth).coerceAtLeast(1L)
    return displayWidth.toLong() * displayHeight >= renderPixelThreshold.coerceAtLeast(1L)
}
