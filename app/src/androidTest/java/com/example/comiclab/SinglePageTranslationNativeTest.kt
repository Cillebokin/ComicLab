package com.example.comiclab

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import li.joye.yakuyomi.engine.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import com.example.comiclab.core.reader.translation.*

/** 可选真实模型验证：仅使用测试专属目录，不下载模型、不调用付费AI。 */
@RunWith(AndroidJUnit4::class)
class SinglePageTranslationNativeTest {
    @Test fun fullLocalPipelinePreservesArchiveAndStreamsTranslationWithoutPaidApi() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installed = File(context.filesDir, "translation-validation-models")
        assumeTrue(File(installed, "dbnet_detect.ncnn.bin").isFile)
        val models = File(context.noBackupFilesDir, "translation-models").apply { mkdirs() }
        installed.listFiles()!!.forEach { it.copyTo(File(models, it.name), overwrite = true) }
        val directory = File(context.cacheDir, "translation-native-validation-${System.nanoTime()}").apply { mkdirs() }
        try {
            val source = File(directory, "sample.png")
            val bitmap = Bitmap.createBitmap(1000, 800, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                drawText("こんにちは", 120f, 380f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 90f })
            }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height,
                android.graphics.Matrix().apply { setRotate(-90f) }, false)
            source.outputStream().use { rotated.compress(Bitmap.CompressFormat.PNG, 100, it) }; rotated.recycle(); bitmap.recycle()
            android.media.ExifInterface(source.path).apply {
                setAttribute(android.media.ExifInterface.TAG_ORIENTATION, "6"); saveAttributes()
            }
            val archive = File(directory, "source.cbz")
            java.util.zip.ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("001.png")); source.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
            val original = archive.readBytes()
            val work = File(directory, "work").apply { mkdirs() }
            val stages = mutableListOf<String>()
            val cancelled = AtomicBoolean()
            var requests = 0
            val config = TranslationSettings("https://example.invalid/v1", "synthetic-test", "not-a-real-key")
            val first = SinglePageTranslation(context, cancelled) { if (it.startsWith("inpaint|")) cancelled.set(true) }
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                first.process(archive, "001.png", work, config, "Simplified Chinese") { _, texts, _, _ ->
                    requests++; assertTrue(texts.any { it.contains("こんにちは") }); texts.map { "你好" }
                }
            }
            assertEquals(1, requests)
            assertTrue(File(work, "translations.json").isFile)
            cancelled.set(false)
            val pipeline = SinglePageTranslation(context, cancelled) { stages += it }
            val analysis = pipeline.process(archive, "001.png", work, TranslationSettings("https://example.invalid/v1", "synthetic-test", "not-a-real-key"), "Simplified Chinese") { _, texts, _, _ ->
                error("Retry must use verified paid-translation cache")
            }
            assertEquals(1, requests)
            assertEquals(6, analysis.orientation)
            assertTrue(analysis.patches.isNotEmpty())
            val rendered = pipeline.render(analysis, work, TranslationStyle())
            val result = android.graphics.BitmapFactory.decodeFile(rendered.path)
            try { assertEquals(1000, result.width); assertEquals(800, result.height); assertEquals(Color.WHITE, result.getPixel(10, 10)) }
            finally { result.recycle() }
            assertArrayEquals(original, archive.readBytes())
            assertTrue(stages.contains("detect")); assertTrue(stages.any { it.startsWith("ocr|") }); assertTrue(stages.contains("encode"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun serialModelsRecognizeJapaneseAndRepairBoundedCrop() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "translation-validation-models")
        assumeTrue("Supply pinned model files in test-only directory", File(root, "dbnet_detect.ncnn.bin").isFile)
        assertTrue(NcnnBackend.available)
        val active = AtomicBoolean(true); val peak = AtomicLong()
        val sampler = Thread {
            while (active.get()) { peak.updateAndGet { maxOf(it, Debug.getPss()) }; Thread.sleep(50) }
        }.apply { start() }
        try {
            val page = Bitmap.createBitmap(1000, 800, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(page); canvas.drawColor(Color.WHITE)
                canvas.drawText("こんにちは", 120f, 380f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 90f })
                val detection = Detector(File(root, "dbnet_detect.ncnn.param").path).use { it.detect(page) }
                try {
                    assertTrue("DBNet must find synthetic Japanese text", detection.lines.isNotEmpty())
                    val alphabet = context.assets.open("translation/alphabet-all-v5.txt").bufferedReader().use { it.readLines() }
                    Ocr(File(root, "ocr_48px_ctc.ncnn.param").path, alphabet, OcrConfig(concurrent = false)).use { ocr ->
                        runBlocking { ocr.recognize(page, detection.lines) }
                    }
                    val text = detection.lines.joinToString("") { it.text }
                    assertTrue("OCR must recognize Japanese greeting", text.contains("こんにちは"))
                } finally { detection.textMask.recycle() }
            } finally { page.recycle() }
            val handle = NcnnBackend.createNet(File(root, "mit_aot_fixed512.ncnn.param").path, File(root, "mit_aot_fixed512.ncnn.bin").path)
            assertTrue(handle != 0L)
            try {
                val size = 512; val area = size * size
                assertTrue("Reject undersized arrays instead of crashing", NcnnBackend.inpaintAot(handle, FloatArray(1), FloatArray(1), size, FloatArray(1)) < 0)
                val input = FloatArray(3 * area) { 1f }; val mask = FloatArray(area)
                for (y in 230..260) for (x in 230..280) {
                    val i = y * size + x; mask[i] = 1f
                    for (c in 0..2) input[c * area + i] = 0f
                }
                val output = FloatArray(3 * area)
                assertEquals(0, NcnnBackend.inpaintAot(handle, input, mask, size, output))
                assertTrue(output.all { it.isFinite() })
                assertTrue("Flat white context should remain bright", output[245 * size + 255] > 0.3f)
            } finally { NcnnBackend.releaseNet(handle) }
        } finally {
            active.set(false); sampler.join()
            android.util.Log.i("TranslationValidation", "Serial synthetic test peak PSS KiB=${peak.get()} fp16=${NcnnBackend.cpuSupportsFp16}")
        }
    }
}
