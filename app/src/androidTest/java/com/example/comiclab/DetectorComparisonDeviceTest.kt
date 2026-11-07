package com.example.comiclab

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import li.joye.yakuyomi.engine.Detector
import li.joye.yakuyomi.engine.Detection
import li.joye.yakuyomi.engine.LightTextDetector
import li.joye.yakuyomi.engine.Ocr
import li.joye.yakuyomi.engine.OcrConfig
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.TextLine
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.floor
import kotlin.math.ceil

/** Explicit local experiment; input and outputs stay in this test app's files. */
@RunWith(AndroidJUnit4::class)
class DetectorComparisonDeviceTest {
    @Test fun compareSamePages() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.filesDir, "detector-comparison")
        val model = File(dir, "dbnet_detect.ncnn.param")
        require(model.isFile && File(dir, "dbnet_detect.ncnn.bin").isFile)
        val selected = InstrumentationRegistry.getArguments().getString("samples")?.split(',')?.toSet()
        val inputs = dir.listFiles { f -> f.name.matches(Regex("(?:input-(?:[0-9]{2}|dialogue|manga)\\.png|sample-[0-9]{2}\\.jpg)")) && (selected == null || f.name in selected) }!!.sortedBy { it.name }
        require(inputs.isNotEmpty())
        val skipOcr = InstrumentationRegistry.getArguments().getString("skipOcr") == "true"
        val saveVisuals = InstrumentationRegistry.getArguments().getString("saveVisuals") != "false"
        val allowMidtones = InstrumentationRegistry.getArguments().getString("allowMidtones") == "true"
        val allowShort = InstrumentationRegistry.getArguments().getString("allowShort") == "true"
        val suppressContainedShort = InstrumentationRegistry.getArguments().getString("suppressContainedShort") == "true"
        val report = JSONArray()
        val alphabet = context.assets.open("translation/alphabet-all-v5.txt").bufferedReader().use { it.readLines() }
        Detector(model.path).use { dbnet ->
          Ocr(File(dir, "ocr_48px_ctc.ncnn.param").path, alphabet, OcrConfig(concurrent = false)).use { ocr ->
            for (file in inputs) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / sample > 1536) sample *= 2
                val image = requireNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }))
                try {
                    val row = JSONObject().put("page", file.name).put("width", image.width).put("height", image.height)
                    for (kind in listOf("dbnet", "light")) {
                        val started = SystemClock.elapsedRealtime()
                        val result = if (kind == "dbnet") dbnet.detect(image) else LightTextDetector.detect(image, allowMidtones, allowShort, suppressContainedShort)
                        val elapsed = SystemClock.elapsedRealtime() - started
                        try {
                            if (saveVisuals) {
                                val overlay = image.copy(Bitmap.Config.ARGB_8888, true)
                                try {
                                    val canvas = Canvas(overlay)
                                    val paint = Paint().apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 2f }
                                    for (line in result.lines) {
                                        val path = Path(); path.moveTo(line.quad[0].x, line.quad[0].y)
                                        line.quad.drop(1).forEach { path.lineTo(it.x, it.y) }; path.close(); canvas.drawPath(path, paint)
                                    }
                                    File(dir, "${file.nameWithoutExtension}-$kind-boxes.png").outputStream().use { overlay.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                } finally { overlay.recycle() }
                                File(dir, "${file.nameWithoutExtension}-$kind-mask.png").outputStream().use { result.textMask.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            }
                            val boxes = JSONArray()
                            result.lines.forEach { line -> boxes.put(JSONArray().apply { line.quad.forEach { put(JSONArray().put(it.x.toDouble()).put(it.y.toDouble())) } }) }
                            val texts = JSONArray()
                            val ocrStart = SystemClock.elapsedRealtime()
                            (if (skipOcr) emptyList() else result.lines).forEach { line ->
                                val l = floor(line.quad.minOf { it.x } - 24).toInt().coerceIn(0, image.width - 1)
                                val t = floor(line.quad.minOf { it.y } - 24).toInt().coerceIn(0, image.height - 1)
                                val r = ceil(line.quad.maxOf { it.x } + 24).toInt().coerceIn(l + 1, image.width)
                                val b = ceil(line.quad.maxOf { it.y } + 24).toInt().coerceIn(t + 1, image.height)
                                val crop = Bitmap.createBitmap(image, l, t, r - l, b - t)
                                try {
                                    val local = TextLine(line.quad.map { Pt(it.x - l, it.y - t) }, line.score)
                                    runBlocking { ocr.recognize(crop, listOf(local)) }
                                    texts.put(local.text)
                                } finally { if (crop !== image) crop.recycle() }
                            }
                            row.put(kind, JSONObject().put("milliseconds", elapsed).put("ocrSkipped", skipOcr).put("ocrMilliseconds", SystemClock.elapsedRealtime() - ocrStart).put("lineCount", result.lines.size).put("boxes", boxes).put("texts", texts))
                        } finally { result.textMask.recycle() }
                    }
                    report.put(row)
                    File(dir, "report.json").writeText(report.toString(2))
                } finally { image.recycle() }
            }
          }
        }
    }
}
