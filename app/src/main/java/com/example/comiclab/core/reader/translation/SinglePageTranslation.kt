package com.example.comiclab.core.reader.translation

import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.example.comiclab.ComicArchive
import com.example.comiclab.core.reader.enhance.StreamingRgbPng
import kotlinx.coroutines.runBlocking
import li.joye.yakuyomi.engine.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

internal data class TranslationBlock(val region: TextRegion, var text: String, var darkBackground: Boolean = false)
internal data class TranslationAnalysis(val source: File, val width: Int, val height: Int,
    val orientation: Int, val blocks: List<TranslationBlock>, val patches: List<TranslationPatch>)
internal data class TranslationPatch(val file: File, val left: Int, val top: Int, val width: Int, val height: Int)
internal data class TranslationStyle(val font: String = "sans-serif", val scale: Float = 1f)

/** 一个工作线程、一页、一个阶段模型；任何时候均不创建原尺寸整页Bitmap。 */
internal class SinglePageTranslation(private val context: Context, private val cancelled: AtomicBoolean,
    private val status: (String) -> Unit) {
    private fun checkCancelled() = TranslationModels.checkCancelled(cancelled)

    fun process(archive: File, entry: String, directory: File, settings: TranslationSettings, language: String,
        translate: (TranslationSettings, List<String>, String, AtomicBoolean) -> List<String> = TranslationApi::translate,
        modelDirectory: File? = null, preparedSource: File? = null): TranslationAnalysis {
        checkCancelled()
        require(!ComicArchive.isPdf(archive)) { "PDF translation is not supported yet" }
        val models = modelDirectory ?: TranslationModels.ensure(context, cancelled) { status("models|$it") }
        check(NcnnBackend.available) { "Native translation runtime unavailable" }
        status("extract")
        val source = preparedSource ?: File(directory, "source.${entry.substringAfterLast('.', "img")}").also {
            check(ComicArchive.extractImageEntryToFile(archive, entry, it)) { "Could not extract source page" }
        }
        check(source.isFile && source.length() > 0L) { "Could not extract source page" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        val rawW = bounds.outWidth; val rawH = bounds.outHeight
        require(rawW in 32..16384 && rawH in 32..16384 && rawW.toLong() * rawH <= 80_000_000) { "Page dimensions exceed translation limit" }
        require(directory.usableSpace > rawW.toLong() * rawH * 6 + 64L * 1024 * 1024) { "Insufficient working storage" }
        val decoder = TranslationPageSource(source)
        val w = decoder.width; val h = decoder.height; val orientation = decoder.orientation
        var smallMask: Bitmap? = null
        try {
            status("detect"); checkCancelled()
            var sample = 1
            while (max(w, h) / sample > 1536) sample *= 2
            val preview = decoder.decodeRegion(Rect(0, 0, w, h), sample)
            val lines: List<TextLine>
            try {
                val det = Detector(File(models, "dbnet_detect.ncnn.param").path).use { it.detect(preview) }
                smallMask = det.textMask
                val sx = w.toFloat() / preview.width; val sy = h.toFloat() / preview.height
                lines = det.lines.map { line -> TextLine(line.quad.map { Pt(it.x * sx, it.y * sy) }, line.score) }
            } finally { preview.recycle() }
            checkCancelled() // Do not load the next model after an in-flight detection was cancelled.
            require(lines.isNotEmpty()) { "No text detected" }
            require(lines.size <= 400) { "Too many text regions for a single page" }
            val alphabet = context.assets.open("translation/alphabet-all-v5.txt").bufferedReader().use { it.readLines() }
            Ocr(File(models, "ocr_48px_ctc.ncnn.param").path, alphabet, OcrConfig(concurrent = false)).use { ocr ->
                lines.forEachIndexed { index, line ->
                    checkCancelled(); status("ocr|${index + 1}/${lines.size}")
                    val box = Rect(floor(line.quad.minOf { it.x } - 24).toInt().coerceAtLeast(0), floor(line.quad.minOf { it.y } - 24).toInt().coerceAtLeast(0),
                        ceil(line.quad.maxOf { it.x } + 24).toInt().coerceAtMost(w), ceil(line.quad.maxOf { it.y } + 24).toInt().coerceAtMost(h))
                    require(box.width() > 0 && box.height() > 0 && box.width().toLong() * box.height() <= 4_000_000) { "OCR region exceeds memory budget" }
                    val crop = decoder.decodeRegion(box)
                    try {
                        val local = TextLine(line.quad.map { Pt(it.x - box.left, it.y - box.top) }, line.score)
                        runBlocking { ocr.recognize(crop, listOf(local)) }
                        line.text = local.text; line.direction = local.direction
                    } finally { crop.recycle() }
                }
            }
            val regions = Grouping.group(lines.filter { it.text.isNotBlank() })
            require(regions.isNotEmpty() && regions.size <= 200) { "No readable text, or too many dialogue blocks" }
            status("translate"); checkCancelled()
            // 修补或编码失败后重试可复用已付费译文；只有识别内容、语言、模型或地址变化才重新请求。
            val sourceTexts = regions.map { it.sourceText }
            val signature = java.security.MessageDigest.getInstance("SHA-256").digest(
                org.json.JSONArray(listOf(settings.address, settings.model, language) + sourceTexts).toString().toByteArray(Charsets.UTF_8)
            ).joinToString("") { "%02x".format(it) }
            val cached = File(directory, "translations.json")
            val saved = runCatching { org.json.JSONObject(cached.readText()) }.getOrNull()
            val cachedTexts = if (saved?.optString("signature") == signature)
                runCatching { TranslationPolicy.parseTranslations(saved.getString("blocks"), regions.size) }.getOrNull() else null
            val texts = cachedTexts ?: run {
                translate(settings, sourceTexts, language, cancelled).also { result ->
                    val rows = org.json.JSONArray().apply { result.forEachIndexed { i, text -> put(org.json.JSONObject().put("id", i + 1).put("text", text)) } }
                    val atomic = android.util.AtomicFile(cached); val stream = atomic.startWrite()
                    try {
                        stream.write(org.json.JSONObject().put("signature", signature).put("blocks", rows.toString()).toString().toByteArray(Charsets.UTF_8))
                        atomic.finishWrite(stream)
                    } catch (error: Throwable) { atomic.failWrite(stream); throw error }
                }
            }
            checkCancelled()
            val blocks = regions.mapIndexed { i, r -> TranslationBlock(r, texts[i]) }
            directory.listFiles { _, name -> name.matches(Regex("patch-[0-9]+\\.png")) }?.forEach { it.delete() }
            val patches = mutableListOf<TranslationPatch>()
            var aot = 0L
            try {
                for ((index, block) in blocks.withIndex()) {
                    status("inpaint|${index + 1}/${blocks.size}"); checkCancelled()
                    val r = block.region
                    val left = floor(r.x0 - 28).toInt().coerceIn(0, w - 1); val top = floor(r.y0 - 28).toInt().coerceIn(0, h - 1)
                    val right = ceil(r.x1 + 28).toInt().coerceIn(left + 1, w); val bottom = ceil(r.y1 + 28).toInt().coerceIn(top + 1, h)
                    for (window in TranslationPolicy.windows(w, h, left, top, right, bottom)) {
                        checkCancelled()
                        val crop = decoder.decodeRegion(Rect(window.left, window.top, window.right, window.bottom))
                        try {
                            val mask = localMask(checkNotNull(smallMask), w, h, window, r)
                            if (mask.none { it }) continue
                            val pixels = IntArray(crop.width * crop.height)
                            crop.getPixels(pixels, 0, crop.width, 0, 0, crop.width, crop.height)
                            val color = flatBackground(pixels, mask, crop.width, crop.height, window, r)
                            if (color != null && Color.red(color) < 64) block.darkBackground = true
                            val repaired = if (color != null) IntArray(pixels.size) { color } else {
                                if (aot == 0L) aot = NcnnBackend.createNet(File(models, "mit_aot_fixed512.ncnn.param").path, File(models, "mit_aot_fixed512.ncnn.bin").path)
                                check(aot != 0L) { "Cannot load inpaint model" }
                                inpaint(aot, pixels, mask, crop.width, crop.height)
                            }
                            val cw = window.coreRight - window.coreLeft; val ch = window.coreBottom - window.coreTop
                            val patch = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
                            try {
                                val out = IntArray(cw * ch)
                                for (y in 0 until ch) for (x in 0 until cw) {
                                    val src = (y + window.coreTop - window.top) * crop.width + x + window.coreLeft - window.left
                                    if (mask[src]) out[y * cw + x] = repaired[src] or (0xff shl 24)
                                }
                                patch.setPixels(out, 0, cw, 0, 0, cw, ch)
                                val file = File(directory, "patch-${patches.size}.png")
                                file.outputStream().use { check(patch.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                                patches += TranslationPatch(file, window.coreLeft, window.coreTop, cw, ch)
                            } finally { patch.recycle() }
                        } finally { crop.recycle() }
                    }
                }
            } finally { if (aot != 0L) NcnnBackend.releaseNet(aot) }
            return TranslationAnalysis(source, w, h, orientation, blocks, patches)
        } finally { smallMask?.recycle(); decoder.close() }
    }

    private fun localMask(small: Bitmap, w: Int, h: Int, win: TranslationPolicy.Window, region: TextRegion): BooleanArray {
        val bmp = Bitmap.createBitmap(win.width, win.height, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bmp).drawBitmap(small, null, RectF(-win.left.toFloat(), -win.top.toFloat(), (w - win.left).toFloat(), (h - win.top).toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
            val px = IntArray(win.width * win.height); bmp.getPixels(px, 0, win.width, 0, 0, win.width, win.height)
            val initial = BooleanArray(px.size)
            for (y in 0 until win.height) for (x in 0 until win.width) {
                val gx = x + win.left; val gy = y + win.top
                initial[y * win.width + x] = (px[y * win.width + x] and 255) > 127 && gx >= region.x0 - 16 && gx <= region.x1 + 16 && gy >= region.y0 - 16 && gy <= region.y1 + 16
            }
            val horizontal = BooleanArray(px.size); val result = BooleanArray(px.size)
            for (y in 0 until win.height) {
                var count = 0
                for (x in 0 until win.width + 12) {
                    if (x < win.width && initial[y * win.width + x]) count++
                    if (x >= 25 && initial[y * win.width + x - 25]) count--
                    if (x >= 12) horizontal[y * win.width + x - 12] = count > 0
                }
            }
            for (x in 0 until win.width) {
                var count = 0
                for (y in 0 until win.height + 12) {
                    if (y < win.height && horizontal[y * win.width + x]) count++
                    if (y >= 25 && horizontal[(y - 25) * win.width + x]) count--
                    if (y >= 12) result[(y - 12) * win.width + x] = count > 0
                }
            }
            return result
        } finally { bmp.recycle() }
    }

    private fun flatBackground(px: IntArray, mask: BooleanArray, w: Int, h: Int, win: TranslationPolicy.Window, r: TextRegion): Int? {
        var n = 0; var sum = 0.0; var sum2 = 0.0
        val l = (r.x0 - win.left - 8).toInt().coerceIn(0, w); val t = (r.y0 - win.top - 8).toInt().coerceIn(0, h)
        val right = (r.x1 - win.left + 8).toInt().coerceIn(0, w); val bottom = (r.y1 - win.top + 8).toInt().coerceIn(0, h)
        for (y in t until bottom) for (x in l until right) {
            val i = y * w + x
            if (mask[i]) continue
            val c = px[i]; val red = Color.red(c); val green = Color.green(c); val blue = Color.blue(c)
            if (maxOf(red, green, blue) - minOf(red, green, blue) > 5) return null
            val v = (red + green + blue) / 3.0; n++; sum += v; sum2 += v * v
        }
        if (n < 64) return null
        val mean = sum / n
        if (sqrt(max(0.0, sum2 / n - mean * mean)) > 2.5) return null
        return when { mean > 248 -> Color.rgb(mean.roundToInt(), mean.roundToInt(), mean.roundToInt()); mean < 7 -> Color.rgb(mean.roundToInt(), mean.roundToInt(), mean.roundToInt()); else -> null }
    }

    private fun inpaint(handle: Long, pixels: IntArray, mask: BooleanArray, w: Int, h: Int): IntArray {
        val size = 512; val area = size * size
        val input = FloatArray(3 * area); val m = FloatArray(area); val output = FloatArray(3 * area)
        for (y in 0 until size) for (x in 0 until size) {
            val sy = y.coerceAtMost(h - 1); val sx = x.coerceAtMost(w - 1); val src = sy * w + sx; val i = y * size + x
            if (mask[src]) { m[i] = 1f; continue }
            val c = pixels[src]; input[i] = Color.red(c) / 127.5f - 1; input[area + i] = Color.green(c) / 127.5f - 1; input[2 * area + i] = Color.blue(c) / 127.5f - 1
        }
        checkCancelled()
        check(NcnnBackend.inpaintAot(handle, input, m, size, output) == 0) { "Inpainting failed" }
        checkCancelled()
        return IntArray(w * h) { i ->
            val at = (i / w) * size + i % w
            fun channel(offset: Int): Int { val v = output[offset + at]; check(v.isFinite()) { "Invalid inpaint output" }; return ((v + 1) * 127.5f).roundToInt().coerceIn(0, 255) }
            Color.rgb(channel(0), channel(area), channel(2 * area))
        }
    }

    fun render(analysis: TranslationAnalysis, directory: File, style: TranslationStyle): File {
        status("render"); checkCancelled()
        val raw = File(directory, "render.rgb"); val pending = File(directory, "render.pending.png")
        val output = File(directory, "translated-${System.nanoTime()}.png")
        require(directory.usableSpace > analysis.width.toLong() * analysis.height * 6 + 32L * 1024 * 1024) { "Insufficient render storage" }
        val decoder = TranslationPageSource(analysis.source)
        try {
            val layouts = analysis.blocks.map { block -> layout(block, style) }
            raw.outputStream().buffered(128 * 1024).use { stream ->
                val stripHeight = TranslationPolicy.stripHeight(analysis.width)
                for (top in 0 until analysis.height step stripHeight) {
                    checkCancelled()
                    val height = min(stripHeight, analysis.height - top)
                    val decoded = decoder.decodeRegion(Rect(0, top, analysis.width, top + height))
                    var strip: Bitmap? = null
                    try {
                        strip = Bitmap.createBitmap(analysis.width, height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(strip); canvas.drawColor(Color.WHITE); canvas.drawBitmap(decoded, 0f, 0f, null)
                        for (patch in analysis.patches) if (patch.top < top + height && patch.top + patch.height > top) {
                            checkCancelled()
                            val bmp = BitmapFactory.decodeFile(patch.file.path) ?: error("Missing repair patch")
                            try { canvas.drawBitmap(bmp, patch.left.toFloat(), (patch.top - top).toFloat(), null) } finally { bmp.recycle() }
                        }
                        for (layout in layouts) {
                            if (layout.bounds.bottom < top || layout.bounds.top > top + height) continue
                            canvas.save(); canvas.translate(0f, -top.toFloat())
                            layout.draw(canvas); canvas.restore()
                        }
                        val pixels = IntArray(analysis.width); val row = ByteArray(analysis.width * 3)
                        for (y in 0 until height) {
                            strip.getPixels(pixels, 0, analysis.width, 0, y, analysis.width, 1)
                            for (x in pixels.indices) { row[x * 3] = Color.red(pixels[x]).toByte(); row[x * 3 + 1] = Color.green(pixels[x]).toByte(); row[x * 3 + 2] = Color.blue(pixels[x]).toByte() }
                            stream.write(row)
                        }
                    } finally { decoded.recycle(); strip?.recycle() }
                }
            }
            status("encode")
            StreamingRgbPng.write(raw, pending, analysis.width, analysis.height) { checkCancelled() }
            checkCancelled(); check(pending.renameTo(output))
            return output
        } finally { decoder.close(); raw.delete(); pending.delete() }
    }

    private class TextDrawing(val bounds: RectF, val draw: (Canvas) -> Unit)

    private fun layout(block: TranslationBlock, style: TranslationStyle): TextDrawing {
        val r = block.region
        val oriented = r.boxW > 0 && r.boxH > 0
        val angle = if (oriented) r.angle else 0f
        // The mean of line corners is not the centre of an unequal-length multiline box.
        val points = r.lines.flatMap { line -> line.quad.flatMap { listOf(it.x, it.y) } }.toFloatArray()
        Matrix().apply { setRotate(-angle) }.mapPoints(points)
        val xs = points.indices.filter { it % 2 == 0 }.map { points[it] }
        val ys = points.indices.filter { it % 2 == 1 }.map { points[it] }
        val width = (xs.max() - xs.min()).toInt().coerceAtLeast(8)
        val height = (ys.max() - ys.min()).toInt().coerceAtLeast(8)
        val center = floatArrayOf((xs.min() + xs.max()) / 2, (ys.min() + ys.max()) / 2)
        Matrix().apply { setRotate(angle) }.mapPoints(center)
        val cx = center[0]; val cy = center[1]
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (block.darkBackground) Color.WHITE else Color.BLACK
            typeface = Typeface.create(style.font, Typeface.NORMAL)
            setShadowLayer(2f, 0f, 0f, if (block.darkBackground) Color.BLACK else Color.WHITE)
        }
        // Latin stays horizontal; CJK text uses right-to-left columns in vertical source bubbles.
        val vertical = r.direction == "v" && block.text.codePoints().anyMatch {
            Character.UnicodeScript.of(it) in setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
                Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL)
        }
        val drawLocal: (Canvas) -> Unit
        if (vertical) {
            val glyphs = block.text.codePoints().toArray().filter { !Character.isWhitespace(it) }.map { String(Character.toChars(it)) }
            require(glyphs.isNotEmpty()) { "Empty translation" }
            fun rows(size: Float) = max(1, floor(height / (size * 1.12f)).toInt())
            fun fits(size: Float) = TranslationPolicy.verticalColumns(glyphs, rows(size)).size * size * 1.12 <= width
            var size = min(140f, min(width.toFloat(), height.toFloat()))
            while (size > 8f && !fits(size)) size -= 1
            require(fits(size)) { "Translation does not fit its text box; shorten the text" }
            paint.textSize = max(8f, size * style.scale.coerceIn(0.5f, 1f))
            val step = paint.textSize * 1.12f
            val columns = TranslationPolicy.verticalColumns(glyphs, rows(paint.textSize))
            drawLocal = { canvas ->
                columns.forEachIndexed { columnIndex, column ->
                    val x = (width + columns.size * step) / 2 - step * (columnIndex + 0.5f)
                    column.forEachIndexed { rowIndex, glyph ->
                        val y = (height - column.size * step) / 2 + step * (rowIndex + 0.5f)
                        canvas.save(); canvas.translate(x, y)
                        if (glyph in listOf("ー", "―", "—", "（", "）", "(", ")", "「", "」", "『", "』")) canvas.rotate(90f)
                        canvas.drawText(glyph, -paint.measureText(glyph) / 2, -(paint.ascent() + paint.descent()) / 2, paint)
                        canvas.restore()
                    }
                }
            }
        } else {
            fun build(size: Float): StaticLayout {
                paint.textSize = size
                return StaticLayout.Builder.obtain(block.text, 0, block.text.length, TextPaint(paint), width)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).setLineSpacing(0f, 1.05f).build()
            }
            var size = min(140f, max(10f, height.toFloat()))
            var result = build(size)
            while (result.height > height && size > 8f) { size -= 1; result = build(size) }
            require(result.height <= height) { "Translation does not fit its text box; shorten the text" }
            val final = build(max(8f, size * style.scale.coerceIn(0.5f, 1f)))
            drawLocal = { canvas -> canvas.translate(0f, (height - final.height) / 2f); final.draw(canvas) }
        }
        val transform = Matrix().apply { setRotate(angle); postTranslate(cx, cy) }
        val bounds = RectF(-width / 2f, -height / 2f, width / 2f, height / 2f)
        transform.mapRect(bounds); bounds.inset(-3f, -3f)
        return TextDrawing(bounds) { canvas ->
            canvas.translate(cx, cy); canvas.rotate(angle); canvas.translate(-width / 2f, -height / 2f)
            drawLocal(canvas)
        }
    }
}
