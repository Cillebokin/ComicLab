package com.example.comiclab.core.reader.enhance

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.media.ExifInterface
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/** reuseModel用于整本任务：跨页保留模型和GPU流水线，use结束后在推理线程释放 */
internal class PageSuperResolution(private val context: Context, private val reuseModel: Boolean = false, private val scale: Int = 2) : Closeable {
    private var handle = 0L
    private var gpu = false
    private val lowMemory = context.getSystemService(ActivityManager::class.java).isLowRamDevice
    private val threads = if (lowMemory) 2 else Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    fun enhance(source: File, directory: File, cancelled: AtomicBoolean,
        preferGpu: Boolean = true, onProgress: (percent: Int, gpu: Boolean) -> Unit): File {
        fun checkCancelled() {
            if (cancelled.get() || Thread.currentThread().isInterrupted) throw CancellationException()
        }
        checkCancelled()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        val plan = EnhancementPlan(bounds.outWidth, bounds.outHeight, if (lowMemory) 128 else 256, scale)
        require(directory.usableSpace >= plan.requiredFreeBytes) { "Insufficient storage" }
        val raw = File(directory, "enhanced.rgb")
        val pending = File(directory, "enhanced.pending.png")
        val result = File(directory, "enhanced.png")
        require(!result.exists()) { "Result already exists" }
        var decoder: BitmapRegionDecoder? = null
        var next: Future<Pair<EnhancementTile, Bitmap>>? = null
        try {
            if (handle == 0L) {
                gpu = preferGpu
                if (gpu) {
                    handle = try { NativeSuperResolution.create(context.assets, true, threads, scale) }
                        catch (_: IllegalStateException) { 0L }
                    gpu = handle != 0L
                }
                if (handle == 0L) handle = NativeSuperResolution.create(context.assets, false, threads, scale)
            }
            check(handle != 0L) { "Could not initialize enhancement engine" }
            onProgress(0, gpu)
            checkCancelled()
            val regionDecoder = BitmapRegionDecoder.newInstance(source.absolutePath, false)
                ?: error("Cannot decode original image")
            decoder = regionDecoder
            val tiles = plan.tiles().iterator()
            fun prefetch(): Future<Pair<EnhancementTile, Bitmap>>? {
                if (!tiles.hasNext()) return null
                val tile = tiles.next()
                return EnhancementRuntime.decoder.submit<Pair<EnhancementTile, Bitmap>> {
                    checkCancelled()
                    val decoded = regionDecoder.decodeRegion(
                        Rect(tile.inputLeft, tile.inputTop, tile.inputRight, tile.inputBottom),
                        BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                    ) ?: error("Cannot decode source tile")
                    // 不透明图片直接使用解码结果；透明页再合成白底，避免一次无用Bitmap复制
                    if (!decoded.hasAlpha()) tile to decoded else {
                        var input: Bitmap? = null
                        try {
                            input = Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
                            Canvas(input).apply { drawColor(Color.WHITE); drawBitmap(decoded, 0f, 0f, null) }
                            tile to input
                        } catch (failure: Throwable) {
                            input?.recycle()
                            throw failure
                        } finally { decoded.recycle() }
                    }
                }
            }
            RandomAccessFile(raw, "rw").use { output ->
                output.setLength(plan.outputWidth.toLong() * plan.outputHeight * 3)
                next = prefetch()
                var completed = 0
                while (next != null) {
                    checkCancelled()
                    val (tile, input) = next!!.get()
                    next = null
                    try {
                        // 至多预取一块；GPU计算当前块时，解码线程准备下一块
                        next = prefetch()
                        checkCancelled()
                        fun infer() = NativeSuperResolution.infer(handle, input,
                            tile.left - tile.inputLeft, tile.top - tile.inputTop,
                            tile.right - tile.left, tile.bottom - tile.top)
                        val rgb = try { infer() } catch (failure: IllegalStateException) {
                            if (!gpu) throw failure
                            checkCancelled()
                            close()
                            gpu = false
                            handle = NativeSuperResolution.create(context.assets, false, threads, scale)
                            check(handle != 0L) { "CPU fallback failed" }
                            infer()
                        }
                        checkCancelled()
                        val rowBytes = (tile.right - tile.left) * scale * 3
                        repeat((tile.bottom - tile.top) * scale) { row ->
                            output.seek(((tile.top.toLong() * scale + row) * plan.outputWidth + tile.left.toLong() * scale) * 3)
                            output.write(rgb, row * rowBytes, rowBytes)
                        }
                        completed++
                        onProgress(completed * 90 / plan.tileCount, gpu)
                    } finally { input.recycle() }
                }
            }
            var encodedRows = 0
            StreamingRgbPng.write(raw, pending, plan.outputWidth, plan.outputHeight) {
                checkCancelled()
                encodedRows++
                if (encodedRows % 64 == 0) onProgress(90 + minOf(9, encodedRows * 9 / plan.outputHeight), gpu)
            }
            val orientation = runCatching {
                ExifInterface(source.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            if (orientation in 2..8) {
                ExifInterface(pending.absolutePath).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                    saveAttributes()
                }
            }
            checkCancelled()
            check(pending.renameTo(result)) { "Could not publish enhanced image" }
            onProgress(100, gpu)
            return result
        } finally {
            // 不能在预取仍使用regionDecoder时recycle；Future不强制取消，等待释放Bitmap
            try { next?.get()?.second?.recycle() } catch (_: Exception) { }
            decoder?.recycle()
            if (!reuseModel) close()
            raw.delete()
            pending.delete()
            if (cancelled.get()) result.delete()
        }
    }

    override fun close() {
        if (handle != 0L) NativeSuperResolution.release(handle)
        handle = 0L
    }
}
