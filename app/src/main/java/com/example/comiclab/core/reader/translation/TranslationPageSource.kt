package com.example.comiclab.core.reader.translation

import android.graphics.*
import android.media.ExifInterface
import java.io.File

/** Coordinates always refer to the upright page. Only the requested crop is rotated. */
internal class TranslationPageSource(file: File) : AutoCloseable {
    val orientation = runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }
        .getOrDefault(1).let { if (it == 0) 1 else it }
    private val decoder: BitmapRegionDecoder
    val width: Int
    val height: Int
    init {
        require(orientation in listOf(1, 3, 6, 8)) { "Mirrored EXIF pages are not supported yet" }
        decoder = BitmapRegionDecoder.newInstance(file.path, false) ?: error("Cannot region-decode page")
        width = if (orientation == 6 || orientation == 8) decoder.height else decoder.width
        height = if (orientation == 6 || orientation == 8) decoder.width else decoder.height
    }
    fun decodeRegion(rect: Rect, sample: Int = 1): Bitmap {
        require(rect.left >= 0 && rect.top >= 0 && rect.right <= width && rect.bottom <= height && !rect.isEmpty)
        val rw = decoder.width; val rh = decoder.height
        val rawRect = when (orientation) {
            3 -> Rect(rw - rect.right, rh - rect.bottom, rw - rect.left, rh - rect.top)
            6 -> Rect(rect.top, rh - rect.right, rect.bottom, rh - rect.left)
            8 -> Rect(rw - rect.bottom, rect.left, rw - rect.top, rect.right)
            else -> rect
        }
        val raw = decoder.decodeRegion(rawRect, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
            ?: error("Cannot decode page crop")
        if (orientation == 1) return raw
        try {
            val degrees = when (orientation) { 3 -> 180f; 6 -> 90f; else -> 270f }
            return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { setRotate(degrees) }, false)
        } finally { raw.recycle() }
    }
    override fun close() = decoder.recycle()
}
