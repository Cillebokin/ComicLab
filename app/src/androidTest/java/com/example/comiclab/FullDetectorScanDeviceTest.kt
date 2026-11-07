package com.example.comiclab

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import li.joye.yakuyomi.engine.Detection
import li.joye.yakuyomi.engine.Detector
import li.joye.yakuyomi.engine.LightTextDetector
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.max

/** Explicit local batch probe; reports are private to the isolated test package. */
@RunWith(AndroidJUnit4::class)
class FullDetectorScanDeviceTest {
    @Test fun scanBatch() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val batch = requireNotNull(args.getString("batchId"))
        require(batch.matches(Regex("[a-f0-9]{32}")))
        val slots = requireNotNull(args.getString("samples")).split(',')
        val includeShort = args.getString("includeShort") == "true"
        val includeDedup = args.getString("includeDedup") == "true"
        val skipReference = args.getString("skipReference") == "true"
        val includeStrict = args.getString("includeStrict") == "true"
        require(slots.isNotEmpty() && slots.size <= 96 && slots.distinct().size == slots.size)
        require(slots.all { it.matches(Regex("sample-[0-9]{2}\\.jpg")) })
        val dir = File(instrumentation.targetContext.filesDir, "detector-comparison")
        val model = File(dir, "dbnet_detect.ncnn.param")
        require(model.isFile && File(dir, "dbnet_detect.ncnn.bin").isFile)
        val output = File(dir, "full-scan-result.jsonl")
        Detector(model.path).use { dbnet ->
            output.outputStream().bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.flush()
                for (slot in slots) {
                    val row = JSONObject().put("batchId", batch).put("slot", slot)
                    try {
                        val source = File(dir, slot)
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(source.path, bounds)
                        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Invalid image bounds" }
                        var sample = 1
                        while (max(bounds.outWidth, bounds.outHeight) / sample > 1536) sample *= 2
                        val image = requireNotNull(BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample }))
                        try {
                            row.put("sourceWidth", bounds.outWidth).put("sourceHeight", bounds.outHeight)
                                .put("width", image.width).put("height", image.height)
                            val reference = if (skipReference) null else {
                                val started = SystemClock.elapsedRealtime()
                                val ref = dbnet.detect(image)
                                try {
                                    val result = describe(ref, null, capped = false)
                                    row.put("dbnet", result.first.put("milliseconds", SystemClock.elapsedRealtime() - started))
                                    requireNotNull(result.second)
                                } finally { ref.textMask.recycle() }
                            }
                            for (allowMidtones in listOf(false, true)) {
                                val t = SystemClock.elapsedRealtime()
                                val detection = LightTextDetector.detect(image, allowMidtones)
                                try {
                                    val result = describe(detection, reference, capped = true).first
                                    row.put(if (allowMidtones) "midtones" else "light", result.put("milliseconds", SystemClock.elapsedRealtime() - t))
                                } finally { detection.textMask.recycle() }
                            }
                            if (includeStrict) {
                                val t = SystemClock.elapsedRealtime()
                                val detection = LightTextDetector.detect(image, strictBackground = true)
                                try {
                                    row.put("strict", describe(detection, reference, capped = true).first.put("milliseconds", SystemClock.elapsedRealtime() - t))
                                } finally { detection.textMask.recycle() }
                            }
                            if (includeShort) {
                                val t = SystemClock.elapsedRealtime()
                                val detection = LightTextDetector.detect(image, allowShort = true)
                                try {
                                    row.put("short", describe(detection, reference, capped = true).first.put("milliseconds", SystemClock.elapsedRealtime() - t))
                                } finally { detection.textMask.recycle() }
                            }
                            if (includeDedup) {
                                val t = SystemClock.elapsedRealtime()
                                val detection = LightTextDetector.detect(image, allowShort = true, suppressContainedShort = true)
                                try {
                                    row.put("dedup", describe(detection, reference, capped = true).first.put("milliseconds", SystemClock.elapsedRealtime() - t))
                                } finally { detection.textMask.recycle() }
                            }
                            row.put("status", "ok")
                        } finally { image.recycle() }
                    } catch (failure: Exception) {
                        row.put("status", "error").put("error", failure.javaClass.simpleName + ": " + (failure.message ?: "").take(240))
                    }
                    writer.write(row.toString()); writer.newLine(); writer.flush()
                }
            }
        }
    }

    private fun describe(detection: Detection, reference: BooleanArray?, capped: Boolean): Pair<JSONObject, BooleanArray?> {
        val mask = detection.textMask
        val pixels = IntArray(mask.width * mask.height)
        mask.getPixels(pixels, 0, mask.width, 0, 0, mask.width, mask.height)
        if (reference != null) require(reference.size == pixels.size)
        val own = if (reference == null) BooleanArray(pixels.size) else null
        var white = 0; var intersection = 0; var union = 0
        for (i in pixels.indices) {
            val on = (pixels[i] and 255) > 127
            if (on) white++
            if (own != null) own[i] = on
            if (reference != null) {
                if (on && reference[i]) intersection++
                if (on || reference[i]) union++
            }
        }
        val boxes = JSONArray()
        detection.lines.forEach { line -> boxes.put(JSONArray().apply { line.quad.forEach { put(JSONArray().put(it.x.toDouble()).put(it.y.toDouble())) } }) }
        return JSONObject().put("lineCount", detection.lines.size).put("capReached", capped && detection.lines.size >= 400)
            .put("maskWhite", white).put("maskIntersection", intersection).put("maskUnion", union).put("boxes", boxes) to own
    }
}
