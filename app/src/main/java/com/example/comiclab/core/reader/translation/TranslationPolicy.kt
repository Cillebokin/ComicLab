package com.example.comiclab.core.reader.translation

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

internal object TranslationPolicy {
    fun endpoint(address: String): String {
        val uri = URI(address.trim())
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "Use an HTTPS API base URL without credentials, query or fragment"
        }
        val base = "https:" + uri.toASCIIString().substringAfter(':').trimEnd('/')
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    fun parseTranslations(raw: String, count: Int): List<String> {
        val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val array = try { if (clean.startsWith("{")) JSONObject(clean).getJSONArray("translations") else JSONArray(clean) }
            catch (_: Exception) { throw IllegalArgumentException("Invalid translation JSON") }
        require(array.length() == count) { "Missing translation blocks" }
        val result = arrayOfNulls<String>(count)
        for (i in 0 until count) {
            val item = array.getJSONObject(i)
            val number = item.get("id")
            require(number is Number && number.toDouble().isFinite() && number.toDouble() == number.toInt().toDouble()) { "Invalid translation id" }
            val id = number.toInt() - 1
            val value = (item.get("text") as? String)?.trim() ?: throw IllegalArgumentException("Invalid translation text")
            require(id in result.indices && result[id] == null && value.isNotBlank() && value.length <= 6000) { "Invalid translation block" }
            result[id] = value
        }
        return result.map { requireNotNull(it) }
    }

    fun parseResponse(raw: String, count: Int): List<String> {
        val choice = JSONObject(raw).getJSONArray("choices").getJSONObject(0)
        val reason = choice.optString("finish_reason", "stop")
        require(reason == "stop" || reason.isBlank() || reason == "null") { "Incomplete AI response" }
        val message = choice.getJSONObject("message")
        require(message.isNull("refusal") || message.optString("refusal").isBlank()) { "AI refused translation" }
        val content = when (val value = message.get("content")) {
            is String -> value
            is JSONArray -> buildString {
                for (i in 0 until value.length()) {
                    val part = value.getJSONObject(i)
                    require(part.optString("type") == "text") { "Unsupported AI content" }
                    append(part.get("text") as? String ?: throw IllegalArgumentException("Invalid AI text"))
                }
            }
            else -> throw IllegalArgumentException("Missing AI text")
        }
        return parseTranslations(content, count)
    }

    fun verticalColumns(glyphs: List<String>, maxRows: Int): List<List<String>> {
        require(maxRows > 0)
        val closing = "，。、：；？！,.!?;:）》」』】〕〉”’…"
        val opening = "（《「『【〔〈“‘"
        val planned = (glyphs.size + maxRows - 1) / maxRows
        val columns = mutableListOf<List<String>>()
        var start = 0
        while (start < glyphs.size) {
            val remainingColumns = maxOf(1, planned - columns.size)
            val rows = (glyphs.size - start + remainingColumns - 1) / remainingColumns
            val limit = minOf(glyphs.size, start + maxRows)
            var end = minOf(limit, start + rows)
            while (end < limit && glyphs[end] in closing) end++
            if (end < glyphs.size && glyphs[end] in closing) {
                do { end-- } while (end > start + 1 && glyphs[end] in closing)
            }
            while (end > start + 1 && glyphs[end - 1] in opening) end--
            end = end.coerceAtLeast(start + 1)
            columns.add(glyphs.subList(start, end)); start = end
        }
        return columns
    }

    data class Window(val left: Int, val top: Int, val right: Int, val bottom: Int,
        val coreLeft: Int, val coreTop: Int, val coreRight: Int, val coreBottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
    }

    // 固定512输入，中心至多320，余下空间保留上下文；边缘只合成中心掩码。
    fun windows(width: Int, height: Int, left: Int, top: Int, right: Int, bottom: Int): List<Window> {
        require(width > 0 && height > 0 && left in 0 until right && right <= width && top in 0 until bottom && bottom <= height)
        return buildList {
            for (y in top until bottom step 320) for (x in left until right step 320) {
                val cr = minOf(x + 320, right); val cb = minOf(y + 320, bottom)
                val l = ((x + cr - 512) / 2).coerceIn(0, maxOf(0, width - 512))
                val t = ((y + cb - 512) / 2).coerceIn(0, maxOf(0, height - 512))
                add(Window(l, t, minOf(width, l + 512), minOf(height, t + 512), x, y, cr, cb))
            }
        }
    }

    fun stripHeight(width: Int): Int = if (width <= 8192) 128 else 64
}
