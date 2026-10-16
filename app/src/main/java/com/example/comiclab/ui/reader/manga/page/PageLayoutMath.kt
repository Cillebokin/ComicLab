package com.example.comiclab.ui.reader.manga.page

import kotlin.math.roundToInt

internal data class DisplaySize(
    val width: Int,
    val height: Int
)

internal fun calculateFitInsideSize(
    width: Int,
    height: Int,
    baseDisplayWidth: Int,
    baseDisplayHeight: Int
): DisplaySize {
    if (width <= 0 || height <= 0 || baseDisplayWidth <= 0 || baseDisplayHeight <= 0) {
        return DisplaySize(baseDisplayWidth, baseDisplayHeight)
    }

    val widthScale = baseDisplayWidth.toFloat() / width.toFloat()
    val heightScale = baseDisplayHeight.toFloat() / height.toFloat()
    val scale = minOf(widthScale, heightScale)
    return DisplaySize(
        width = (width * scale).roundToInt().coerceAtLeast(1),
        height = (height * scale).roundToInt().coerceAtLeast(1)
    )
}

internal fun sourceToDisplayY(sourceY: Int, sourceHeight: Int, imageHeight: Int): Int {
    if (sourceHeight <= 0 || imageHeight <= 0) {
        return 0
    }

    return (sourceY.toFloat() / sourceHeight.toFloat() * imageHeight.toFloat())
        .roundToInt()
        .coerceIn(0, imageHeight)
}

internal fun calculateSampleSizeForWidth(width: Int, targetWidth: Int): Int {
    if (width <= 0 || targetWidth <= 0) {
        return 1
    }

    var sampleSize = 1
    while (width / (sampleSize * 2) >= targetWidth) {
        sampleSize *= 2
    }
    return sampleSize
}
