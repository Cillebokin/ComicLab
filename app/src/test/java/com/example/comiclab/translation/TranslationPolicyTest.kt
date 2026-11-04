package com.example.comiclab.translation

import com.example.comiclab.core.reader.translation.TranslationPolicy
import org.junit.Assert.*
import org.junit.Test

class TranslationPolicyTest {
    @Test fun endpointKeepsVersionPrefixWithoutDuplicatingSuffix() {
        assertEquals("https://example.com/v1/chat/completions", TranslationPolicy.endpoint("https://example.com/v1/"))
        assertEquals("https://example.com/v1/chat/completions", TranslationPolicy.endpoint("https://example.com/v1/chat/completions"))
    }
    @Test fun rejectsCredentialsQueriesAndCleartextServiceAddresses() {
        listOf("http://example.com/v1", "https://key@example.com/v1", "https://example.com/v1?key=secret").forEach {
            assertThrows(IllegalArgumentException::class.java) { TranslationPolicy.endpoint(it) }
        }
    }
    @Test fun translationIdsMustBeCompleteAndUnique() {
        assertEquals(listOf("甲", "乙"), TranslationPolicy.parseTranslations("```json\n[{\"id\":2,\"text\":\"乙\"},{\"id\":1,\"text\":\"甲\"}]\n```", 2))
        listOf("[{\"id\":1,\"text\":\"甲\"}]", "[{\"id\":1,\"text\":\"甲\"},{\"id\":1,\"text\":\"乙\"}]").forEach {
            assertThrows(IllegalArgumentException::class.java) { TranslationPolicy.parseTranslations(it, 2) }
        }
    }
    @Test fun localWindowsCoverLargeRegionsWithContextAndBoundedInputs() {
        val windows = TranslationPolicy.windows(1000, 1400, 80, 120, 900, 1300)
        assertTrue(windows.size > 1)
        assertTrue(windows.all { it.width <= 512 && it.height <= 512 && it.left >= 0 && it.top >= 0 && it.right <= 1000 && it.bottom <= 1400 })
        for (y in 120 until 1300 step 31) for (x in 80 until 900 step 31) {
            assertTrue("Uncovered $x,$y", windows.any { x in it.coreLeft until it.coreRight && y in it.coreTop until it.coreBottom })
        }
    }
    @Test fun stripBudgetDoesNotGrowWithPageHeight() {
        assertEquals(128, TranslationPolicy.stripHeight(4000))
        assertEquals(64, TranslationPolicy.stripHeight(16384))
    }

    @Test fun windowsCoverPageEdgesWithoutAllocatingLargerThan512() {
        for ((w, h) in listOf(32 to 32, 100 to 700, 700 to 100, 16384 to 64)) {
            val windows = TranslationPolicy.windows(w, h, 0, 0, w, h)
            assertTrue(windows.all { it.width in 1..512 && it.height in 1..512 })
            assertTrue(windows.any { it.coreRight == w && it.coreBottom == h })
        }
    }

    @Test fun compatibleResponsesAcceptTextPartsAndRejectTruncatedOutput() {
        val raw = "[{\"id\":1,\"text\":\"你好\"}]"
        val response = org.json.JSONObject().put("choices", org.json.JSONArray().put(org.json.JSONObject()
            .put("finish_reason", "stop").put("message", org.json.JSONObject().put("content", org.json.JSONArray()
                .put(org.json.JSONObject().put("type", "text").put("text", raw))))))
        assertEquals(listOf("你好"), TranslationPolicy.parseResponse(response.toString(), 1))
        response.getJSONArray("choices").getJSONObject(0).put("finish_reason", "length")
        assertThrows(IllegalArgumentException::class.java) { TranslationPolicy.parseResponse(response.toString(), 1) }
    }

    @Test fun nullableTextAndFractionalIdsNeverBecomePublishedTranslations() {
        listOf("[{\"id\":1,\"text\":null}]", "[{\"id\":1.8,\"text\":\"你好\"}]").forEach {
            assertThrows(IllegalArgumentException::class.java) { TranslationPolicy.parseTranslations(it, 1) }
        }
        assertEquals(listOf("你好"), TranslationPolicy.parseTranslations("{\"translations\":[{\"id\":1,\"text\":\"你好\"}]}", 1))
    }

    @Test fun verticalColumnsBalanceTextAndKeepClosingPunctuationWithItsPhrase() {
        val text = "娜娜莉，这个周末要不要去看电影？"
        val columns = TranslationPolicy.verticalColumns(text.map { it.toString() }, 6)
        assertEquals(text, columns.flatten().joinToString(""))
        assertTrue(columns.all { it.size <= 6 })
        assertTrue(columns.last().size > 1)
        assertTrue(columns.drop(1).none { it.first() in listOf("，", "？", "。", "”") })
        val plain = TranslationPolicy.verticalColumns("一二三四五六七八九十甲乙丙".map { it.toString() }, 6)
        assertTrue(plain.maxOf { it.size } - plain.minOf { it.size } <= 1)
    }
}
