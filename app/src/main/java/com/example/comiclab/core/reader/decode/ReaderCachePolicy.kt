package com.example.comiclab.core.reader.decode

internal fun shouldReleasePreviewAfterFullDecode(isTiled: Boolean): Boolean {
    return !isTiled
}

internal fun pdfFullBitmapCacheSizeKb(
    maxMemoryKb: Int,
    doublePageReading: Boolean = false
): Int {
    if (doublePageReading) {
        return (maxMemoryKb / 10)
            .coerceAtLeast(12 * 1024)
            .coerceAtMost(16 * 1024)
    }

    return (maxMemoryKb / 8)
        .coerceAtLeast(16 * 1024)
        .coerceAtMost(24 * 1024)
}

internal fun pdfTileBitmapCacheSizeKb(maxMemoryKb: Int): Int {
    return (maxMemoryKb / 16)
        .coerceAtLeast(8 * 1024)
        .coerceAtMost(16 * 1024)
}
