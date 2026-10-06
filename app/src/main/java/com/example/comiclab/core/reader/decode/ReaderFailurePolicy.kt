package com.example.comiclab.core.reader.decode

internal fun shouldSkipPdfPreloadAfterMemoryTrim(
    isPdfSource: Boolean,
    lowMemoryMode: Boolean,
    isPreload: Boolean
): Boolean {
    return isPdfSource && lowMemoryMode && isPreload
}

internal fun shouldSkipTiledPdfPreviewDecodeAfterMemoryTrim(
    isPdfSource: Boolean,
    lowMemoryMode: Boolean,
    isPreload: Boolean
): Boolean {
    return shouldSkipPdfPreloadAfterMemoryTrim(
        isPdfSource = isPdfSource,
        lowMemoryMode = lowMemoryMode,
        isPreload = isPreload
    )
}

internal fun shouldRetryPdfPageAfterFailure(
    isPdfSource: Boolean,
    isPreload: Boolean,
    retryAlreadyUsed: Boolean
): Boolean {
    return isPdfSource && !isPreload && !retryAlreadyUsed
}

internal fun shouldSkipReaderPreviewDecodeAfterMemoryTrim(lowMemoryMode: Boolean): Boolean {
    return lowMemoryMode
}

internal fun shouldRetryFullPageAfterFailure(
    isPreload: Boolean,
    alreadyRetried: Boolean
): Boolean {
    return !isPreload && !alreadyRetried
}

internal fun reducedDecodeDimensionForRetry(
    currentDimension: Int,
    minimumDimension: Int
): Int {
    val safeCurrentDimension = currentDimension.coerceAtLeast(1)
    val safeMinimumDimension = minimumDimension.coerceAtLeast(1)
    return if (safeCurrentDimension <= safeMinimumDimension) {
        safeCurrentDimension
    } else {
        maxOf(safeMinimumDimension, safeCurrentDimension / 2)
    }
}

internal fun shouldCancelPreloadAfterOutOfMemory(
    isCancelable: Boolean,
    isVisible: Boolean
): Boolean {
    return isCancelable && !isVisible
}
