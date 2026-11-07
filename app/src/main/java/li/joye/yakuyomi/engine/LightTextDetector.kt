package li.joye.yakuyomi.engine

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Experimental, model-free detector. It is not used by the translation pipeline. */
internal object LightTextDetector {
    private data class Component(val left: Int, val top: Int, val right: Int, val bottom: Int, val label: Int, val pixels: Int) {
        val w get() = right - left + 1
        val h get() = bottom - top + 1
    }

    /** Experimental filter: retains sufficiently uniform midtone backgrounds too. */
    internal fun hasSimpleSurrounding(gray: IntArray, w: Int, h: Int, l: Int, t: Int, r: Int, b: Int, allowMidtones: Boolean = false,
        strictBackground: Boolean = false): Boolean {
        val pad = (min(r - l, b - t) * 0.3f).roundToInt().coerceIn(3, 12)
        val x0 = max(0, l - pad); val y0 = max(0, t - pad)
        val x1 = min(w, r + pad); val y1 = min(h, b + pad)
        var bright = 0; var dark = 0; var count = 0
        val histogram = IntArray(8)
        fun add(x: Int, y: Int) {
            val v = gray[y * w + x]
            if (v >= 235) bright++
            if (v <= 20) dark++
            histogram[v / 32]++
            count++
        }
        for (y in y0 until y1) {
            if (y < t || y >= b) {
                for (x in x0 until x1) add(x, y)
            } else {
                for (x in x0 until l) add(x, y)
                for (x in r until x1) add(x, y)
            }
        }
        val similar = (0 until 7).maxOf { histogram[it] + histogram[it + 1] }
        val required = if (strictBackground) 4 else 3
        return count > 0 && (max(bright, dark) * 10 >= count * required || (allowMidtones && similar * 4 >= count * 3))
    }

    fun detect(page: Bitmap, allowMidtones: Boolean = false, allowShort: Boolean = false,
        suppressContainedShort: Boolean = false, strictBackground: Boolean = false, checkCancelled: () -> Unit = {}): Detection {
        val base = detectPass(page, allowMidtones, false, emptyList(), 400, false, strictBackground, checkCancelled)
        if (!allowShort || base.lines.size >= 400) return base
        try {
            val extra = detectPass(page, allowMidtones, true, base.lines, 400 - base.lines.size, suppressContainedShort, strictBackground, checkCancelled)
            try {
                val w = page.width; val h = page.height
                val pixels = IntArray(w * h); val extraPixels = IntArray(w * h)
                base.textMask.getPixels(pixels, 0, w, 0, 0, w, h)
                extra.textMask.getPixels(extraPixels, 0, w, 0, 0, w, h)
                for (i in pixels.indices) if ((extraPixels[i] and 255) > 127) pixels[i] = -1
                return Detection(base.lines + extra.lines, Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888))
            } finally { extra.textMask.recycle() }
        } finally { base.textMask.recycle() }
    }

    private fun similarBox(lines: List<TextLine>, l: Float, t: Float, r: Float, b: Float): Boolean {
        val area = (r - l) * (b - t)
        return lines.any { line ->
            val al = line.quad.minOf { it.x }; val at = line.quad.minOf { it.y }
            val ar = line.quad.maxOf { it.x }; val ab = line.quad.maxOf { it.y }
            val overlap = max(0f, min(r, ar) - max(l, al)) * max(0f, min(b, ab) - max(t, at))
            val union = area + (ar - al) * (ab - at) - overlap
            union > 0 && overlap / union >= .6f
        }
    }

    private fun containedInComparableLine(lines: List<TextLine>, l: Float, t: Float, r: Float, b: Float): Boolean {
        val area = (r - l) * (b - t)
        return area > 0 && lines.any { line ->
            val al = line.quad.minOf { it.x }; val at = line.quad.minOf { it.y }
            val ar = line.quad.maxOf { it.x }; val ab = line.quad.maxOf { it.y }
            val lineArea = (ar - al) * (ab - at)
            val overlap = max(0f, min(r, ar) - max(l, al)) * max(0f, min(b, ab) - max(t, at))
            lineArea <= 4f * area && overlap >= .85f * area
        }
    }

    // A long, nearly solid edge across the page is usually a panel border, not a text line.
    private fun hasLongFrameEdge(gray: IntArray, w: Int, h: Int, l: Int, t: Int, r: Int, b: Int): Boolean {
        fun dark(x: Int, y: Int) = gray[y * w + x] <= 32
        if ((r - l) * 2 >= w) {
            for (y in intArrayOf(t, b - 1)) {
                var count = 0
                for (x in l until r) if (dark(x, y)) count++
                if (count * 4 >= (r - l) * 3) return true
            }
        }
        if ((b - t) * 2 >= h) {
            for (x in intArrayOf(l, r - 1)) {
                var count = 0
                for (y in t until b) if (dark(x, y)) count++
                if (count * 4 >= (b - t) * 3) return true
            }
        }
        return false
    }

    private fun detectPass(page: Bitmap, allowMidtones: Boolean, shortOnly: Boolean, exclude: List<TextLine>, maxLines: Int,
        suppressContainedShort: Boolean, strictBackground: Boolean, checkCancelled: () -> Unit): Detection {
        val w = page.width; val h = page.height
        require(w > 0 && h > 0 && w.toLong() * h <= 4_000_000)
        val n = w * h
        val pixels = IntArray(n); page.getPixels(pixels, 0, w, 0, 0, w, h)
        val gray = IntArray(n) { i ->
            val p = pixels[i]; val a = p ushr 24
            val lum = ((p shr 16 and 255) * 77 + (p shr 8 and 255) * 150 + (p and 255) * 29) shr 8
            (lum * a + 255 * (255 - a)) / 255
        }
        val integral = LongArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            checkCancelled()
            var row = 0L
            for (x in 0 until w) { row += gray[y * w + x]; integral[(y + 1) * (w + 1) + x + 1] = integral[y * (w + 1) + x + 1] + row }
        }
        val lines = ArrayList<TextLine>()
        val mask = IntArray(n) { 0xff000000.toInt() }
        for (polarity in intArrayOf(-1, 1)) {
            val binary = BooleanArray(n)
            val radius = max(12, min(w, h) / 60)
            for (y in 0 until h) {
                checkCancelled()
                val y0 = max(0, y - radius); val y1 = min(h, y + radius + 1)
                for (x in 0 until w) {
                    val x0 = max(0, x - radius); val x1 = min(w, x + radius + 1)
                    val sum = integral[y1 * (w + 1) + x1] - integral[y0 * (w + 1) + x1] - integral[y1 * (w + 1) + x0] + integral[y0 * (w + 1) + x0]
                    val mean = (sum / ((x1 - x0) * (y1 - y0))).toInt()
                    binary[y * w + x] = (gray[y * w + x] - mean) * polarity >= 24
                }
            }
            val labels = IntArray(n); val stack = IntArray(n); val components = ArrayList<Component>()
            var nextLabel = 0
            for (seed in 0 until n) {
                if (seed % w == 0) checkCancelled()
                if (!binary[seed] || labels[seed] != 0) continue
                val label = ++nextLabel; var count = 0; var sp = 0
                var left = w; var right = 0; var top = h; var bottom = 0
                stack[sp++] = seed; labels[seed] = -1
                while (sp > 0) {
                    val p = stack[--sp]; val x = p % w; val y = p / w
                    labels[p] = label; count++; if (count % 4096 == 0) checkCancelled()
                    left = min(left, x); right = max(right, x); top = min(top, y); bottom = max(bottom, y)
                    for (dy in -1..1) for (dx in -1..1) {
                        val xx = x + dx; val yy = y + dy
                        if (xx !in 0 until w || yy !in 0 until h) continue
                        val q = yy * w + xx
                        if (binary[q] && labels[q] == 0) { labels[q] = -1; stack[sp++] = q }
                    }
                }
                val cw = right - left + 1; val ch = bottom - top + 1
                if (count >= 3 && cw >= 2 && ch >= 3 && cw <= w / 6 && ch <= h / 6 && components.size < 2500) {
                    components.add(Component(left, top, right, bottom, label, count))
                }
            }
            val accepted = BooleanArray(nextLabel + 1)
            // Separate directions prevent a horizontal connection and a vertical one
            // from transitively joining a whole illustration into one text box.
            for (horizontal in listOf(true, false)) {
                val parent = IntArray(components.size) { it }
                fun root(i: Int): Int { var k = i; while (parent[k] != k) k = parent[k]; return k }
                for (i in components.indices) {
                    checkCancelled()
                    val a = components[i]
                    for (j in 0 until i) {
                        val b = components[j]
                        val aligned = if (horizontal)
                            abs((a.top + a.bottom) - (b.top + b.bottom)) <= max(a.h, b.h) && max(a.h, b.h) <= 2 * min(a.h, b.h) && max(a.left, b.left) - min(a.right, b.right) <= max(a.h, b.h) * 1.5
                        else
                            abs((a.left + a.right) - (b.left + b.right)) <= max(a.w, b.w) && max(a.w, b.w) <= 2 * min(a.w, b.w) && max(a.top, b.top) - min(a.bottom, b.bottom) <= max(a.w, b.w) * 2
                        if (aligned) parent[root(i)] = root(j)
                    }
                }
                for (members in components.indices.groupBy { root(it) }.values) {
                    if (members.size < (if (shortOnly) 1 else 3) || lines.size >= maxLines) continue
                    val group = members.map { components[it] }
                    val l = group.minOf { it.left }.toFloat(); val t = group.minOf { it.top }.toFloat()
                    val r = min(w, group.maxOf { it.right } + 1).toFloat(); val b = min(h, group.maxOf { it.bottom } + 1).toFloat()
                    if (shortOnly) {
                        val width = r - l; val height = b - t
                        if (min(width, height) < 6f || max(width, height) > max(24f, min(w, h) / 8f)) continue
                        if (max(width / height, height / width) >= 1.35f) continue
                        val fill = group.sumOf { it.pixels }.toFloat() / (width * height)
                        if (fill < .08f || fill > .75f || similarBox(exclude, l, t, r, b) || similarBox(lines, l, t, r, b)) continue
                        if (suppressContainedShort && containedInComparableLine(exclude, l, t, r, b)) continue
                    } else if (if (horizontal) r - l < (b - t) * 1.35f else b - t < (r - l) * 1.35f) continue
                    val largestCross = if (horizontal) group.maxOf { it.h } else group.maxOf { it.w }
                    val crossSpan = if (horizontal) b - t else r - l
                    if (crossSpan > largestCross * 2.5f) continue
                    if (!shortOnly && hasLongFrameEdge(gray, w, h, l.toInt(), t.toInt(), r.toInt(), b.toInt())) continue
                    if (!hasSimpleSurrounding(gray, w, h, l.toInt(), t.toInt(), r.toInt(), b.toInt(), allowMidtones, strictBackground)) continue
                    lines.add(TextLine(listOf(Pt(l, t), Pt(r, t), Pt(r, b), Pt(l, b)), 1f))
                    members.forEach { accepted[components[it].label] = true }
                }
            }
            for (i in 0 until n) if (labels[i] > 0 && accepted[labels[i]]) mask[i] = -1
        }
        return Detection(lines, Bitmap.createBitmap(mask, w, h, Bitmap.Config.ARGB_8888))
    }
}
