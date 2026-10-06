package com.example.comiclab.core.archive

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import com.example.comiclab.core.archive.cache.ArchiveEntryFileCache
import net.sf.sevenzipjbinding.IInArchive
import net.sf.sevenzipjbinding.PropID
import android.graphics.Rect
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.zip.ZipFile

internal object ArchiveImageDecoder {
    private const val ZIP_PREVIEW_DECODE_THREAD_COUNT = 2

    data class Bounds(val width: Int, val height: Int)

    fun decodeImage(file: File, entryName: String, maxSize: Int): Bitmap? =
        ZipFile(file).use { zipFile ->
            decodeZipBitmap(zipFile, entryName, Bitmap.Config.RGB_565) { width, height ->
                calculateInSampleSize(width, height, maxSize)
            }?.scaleToFitMaxSize(maxSize)
        }

    fun decodeImageForWidth(file: File, entryName: String, targetWidth: Int): Bitmap? =
        ZipFile(file).use { zipFile ->
            decodeZipBitmap(zipFile, entryName, Bitmap.Config.ARGB_8888) { width, _ ->
                calculateInSampleSizeForWidth(width, targetWidth)
            }?.scaleToWidthIfLarger(targetWidth)
        }

    fun decodeZipImages(
        file: File,
        entryNames: List<String>,
        maxSize: Int,
        onDecoded: (entryName: String, bitmap: Bitmap?) -> Boolean
    ) {
        if (entryNames.isEmpty()) {
            return
        }

        val workerCount = minOf(ZIP_PREVIEW_DECODE_THREAD_COUNT, entryNames.size)
        val pendingEntries = ConcurrentLinkedQueue(entryNames)
        val keepDecoding = java.util.concurrent.atomic.AtomicBoolean(true)
        val executor = Executors.newFixedThreadPool(workerCount)

        try {
            val futures = (0 until workerCount).map {
                executor.submit {
                    ZipFile(file).use { zipFile ->
                        while (keepDecoding.get()) {
                            val entryName = pendingEntries.poll() ?: break
                            val bitmap = runCatching {
                                decodeZipBitmap(
                                    zipFile = zipFile,
                                    entryName = entryName,
                                    preferredConfig = Bitmap.Config.RGB_565,
                                    sampleSize = { width, height ->
                                        calculateInSampleSize(width, height, maxSize)
                                    }
                                )?.scaleToFitMaxSize(maxSize)
                            }.getOrNull()

                            if (keepDecoding.get() && !onDecoded(entryName, bitmap)) {
                                keepDecoding.set(false)
                            }
                        }
                    }
                }
            }

            futures.forEach { future ->
                runCatching { future.get() }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    fun decodeZipBitmap(
        zipFile: ZipFile,
        entryName: String,
        preferredConfig: Bitmap.Config,
        sampleSize: (width: Int, height: Int) -> Int
    ): Bitmap? {
        val entry = zipFile.getEntry(entryName) ?: return null
        return decodeImageFromStreamFactory(
            openInputStream = { zipFile.getInputStream(entry) },
            preferredConfig = preferredConfig,
            sampleSize = sampleSize
        )
    }

    fun decodeImageFromStreamFactory(
        openInputStream: () -> InputStream,
        preferredConfig: Bitmap.Config,
        sampleSize: (width: Int, height: Int) -> Int
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        openInputStream().use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = preferredConfig
        }
        return openInputStream().use { input ->
            BitmapFactory.decodeStream(input, null, decodeOptions)
        }
    }

    fun decodeFileBitmap(
        file: File,
        preferredConfig: Bitmap.Config,
        sampleSize: (width: Int, height: Int) -> Int
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, bounds)

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = preferredConfig
        }

        return BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
    }

    fun decodeFileRegionForWidth(
        file: File,
        sourceRect: Rect,
        targetWidth: Int
    ): Bitmap? {
        val decoder = runCatching {
            BitmapRegionDecoder.newInstance(file.absolutePath, false)
        }.getOrNull() ?: return null

        return try {
            val boundedRect = Rect(sourceRect).apply {
                left = left.coerceIn(0, decoder.width)
                top = top.coerceIn(0, decoder.height)
                right = right.coerceIn(left, decoder.width)
                bottom = bottom.coerceIn(top, decoder.height)
            }
            if (boundedRect.width() <= 0 || boundedRect.height() <= 0) return null

            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSizeForWidth(boundedRect.width(), targetWidth)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            decoder.decodeRegion(boundedRect, options)?.scaleToWidthIfLarger(targetWidth)
        } finally {
            decoder.recycle()
        }
    }

    fun decodeBounds(bytes: ByteArray): Bounds? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }
        return Bounds(bounds.outWidth, bounds.outHeight)
    }

    fun decodeBounds(stream: InputStream): Bounds? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeStream(stream, null, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }
        return Bounds(bounds.outWidth, bounds.outHeight)
    }

    fun decodeFileBounds(file: File): Bounds? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }
        return Bounds(bounds.outWidth, bounds.outHeight)
    }

    fun decodeRegionForWidth(
        stream: InputStream,
        sourceRect: Rect,
        targetWidth: Int
    ): Bitmap? {
        val decoder = runCatching {
            BitmapRegionDecoder.newInstance(stream, false)
        }.getOrNull() ?: return null

        return try {
            val boundedRect = Rect(sourceRect).apply {
                left = left.coerceIn(0, decoder.width)
                top = top.coerceIn(0, decoder.height)
                right = right.coerceIn(left, decoder.width)
                bottom = bottom.coerceIn(top, decoder.height)
            }
            if (boundedRect.width() <= 0 || boundedRect.height() <= 0) {
                return null
            }

            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSizeForWidth(boundedRect.width(), targetWidth)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            decoder.decodeRegion(boundedRect, options)?.scaleToWidthIfLarger(targetWidth)
        } finally {
            decoder.recycle()
        }
    }

    fun calculateInSampleSize(width: Int, height: Int, maxSize: Int): Int {
        if (width <= 0 || height <= 0 || maxSize <= 0) {
            return 1
        }

        var sampleSize = 1
        var sampledWidth = width
        var sampledHeight = height

        while (sampledWidth / 2 >= maxSize || sampledHeight / 2 >= maxSize) {
            sampleSize *= 2
            sampledWidth /= 2
            sampledHeight /= 2
        }

        return sampleSize
    }

    fun calculateInSampleSizeForWidth(width: Int, targetWidth: Int): Int {
        if (width <= 0 || targetWidth <= 0) {
            return 1
        }

        var sampleSize = 1
        while (width / (sampleSize * 2) >= targetWidth) {
            sampleSize *= 2
        }
        return sampleSize
    }

    fun calculateInSampleSizeForHeight(height: Int, targetHeight: Int): Int {
        if (height <= 0 || targetHeight <= 0) {
            return 1
        }

        var sampleSize = 1
        while (height / (sampleSize * 2) >= targetHeight) {
            sampleSize *= 2
        }
        return sampleSize
    }

    fun Bitmap.scaleToFitMaxSize(maxSize: Int): Bitmap {
        if (maxSize <= 0 || width <= 0 || height <= 0) {
            return this
        }

        val maxDimension = maxOf(width, height)
        if (maxDimension <= maxSize) {
            return this
        }

        val scale = maxSize.toFloat() / maxDimension.toFloat()
        val targetWidth = (width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (height * scale).toInt().coerceAtLeast(1)
        return createScaledBitmapSafely(targetWidth, targetHeight)
    }

    fun Bitmap.scaleToWidthIfLarger(targetWidth: Int): Bitmap {
        if (targetWidth <= 0 || width <= 0 || height <= 0 || width <= targetWidth) {
            return this
        }

        val targetHeight = (targetWidth.toFloat() / width.toFloat() * height.toFloat())
            .toInt()
            .coerceAtLeast(1)
        return createScaledBitmapSafely(targetWidth, targetHeight)
    }

    fun Bitmap.scaleToFitBoundsIfLarger(targetWidth: Int, targetHeight: Int): Bitmap {
        if (targetWidth <= 0 || targetHeight <= 0 || width <= 0 || height <= 0) {
            return this
        }

        if (width <= targetWidth && height <= targetHeight) {
            return this
        }

        val scale = minOf(
            targetWidth.toFloat() / width.toFloat(),
            targetHeight.toFloat() / height.toFloat()
        )
        val scaledWidth = (width * scale).toInt().coerceAtLeast(1)
        val scaledHeight = (height * scale).toInt().coerceAtLeast(1)
        return createScaledBitmapSafely(scaledWidth, scaledHeight)
    }

    fun Bitmap.createScaledBitmapSafely(targetWidth: Int, targetHeight: Int): Bitmap {
        if (targetWidth == width && targetHeight == height) {
            return this
        }

        val scaledBitmap = runCatching {
            Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
        }.getOrNull() ?: return this
        if (scaledBitmap != this) {
            recycle()
        }
        return scaledBitmap
    }

    fun decodeSevenZipImages(
        archive: IInArchive,
        cache: ArchiveEntryFileCache,
        entryNames: List<String>,
        maxSize: Int,
        extractEntry: (entryIndex: Int, target: File) -> Boolean,
        onDecoded: (entryName: String, bitmap: Bitmap?) -> Boolean
    ) {
        val entryIndexesByPath = (0 until archive.numberOfItems)
            .asSequence()
            .filter { !(archive.getProperty(it, PropID.IS_FOLDER) as? Boolean ?: false) }
            .mapNotNull { index -> (archive.getProperty(index, PropID.PATH) as? String)?.let { path -> path to index } }
            .toMap()

        for (entryName in entryNames) {
            val bitmap = runCatching {
                val entryIndex = entryIndexesByPath[entryName] ?: return@runCatching null
                cache.withEntry(
                    entryName = entryName,
                    extract = { target -> extractEntry(entryIndex, target) },
                    use = { imageFile ->
                        decodeFileBitmap(
                            file = imageFile,
                            preferredConfig = Bitmap.Config.RGB_565,
                            sampleSize = { width, height ->
                                calculateInSampleSize(width, height, maxSize)
                            }
                        )?.scaleToFitMaxSize(maxSize)
                    }
                )
            }.getOrNull()

            if (!onDecoded(entryName, bitmap)) {
                return
            }
        }
    }
}
