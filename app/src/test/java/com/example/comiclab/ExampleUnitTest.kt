package com.example.comiclab

import org.junit.Test

import org.junit.Assert.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun extractStartMarker_returnsFirstValidBracketContent() {
        val marker = CommonFunc.extractStartMarker("[作者][作品名].zip", "中;汉;翻;翻译")

        assertEquals("作者", marker)
    }

    @Test
    fun extractStartMarker_skipsEmptyAndErrorMarkers() {
        val marker = CommonFunc.extractStartMarker("[][中文翻译][社团][标题].zip", "中;汉;翻;翻译")

        assertEquals("社团", marker)
    }

    @Test
    fun extractStartMarker_returnsEmptyWhenNoValidMarkerExists() {
        val marker = CommonFunc.extractStartMarker("漫画[翻译][汉化].zip", "中;汉;翻;翻译")

        assertEquals("", marker)
    }

    @Test
    fun extractStartMarker_allowsEmptyErrorMarkers() {
        val marker = CommonFunc.extractStartMarker("[翻译][标题].zip", "")

        assertEquals("翻译", marker)
    }
}
