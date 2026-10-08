package com.example.comiclab.core.reader.layout

internal enum class DoublePageModeUnavailableReason {
    VERTICAL_READING,
    LANDSCAPE_REQUIRED
}

internal fun doublePageModeUnavailableReason(
    isHorizontalReading: Boolean,
    isLandscape: Boolean
): DoublePageModeUnavailableReason? {
    return when {
        !isHorizontalReading -> DoublePageModeUnavailableReason.VERTICAL_READING
        !isLandscape -> DoublePageModeUnavailableReason.LANDSCAPE_REQUIRED
        else -> null
    }
}
