package com.example.comiclab

import org.junit.Test

import org.junit.Assert.*
import java.io.File
import java.nio.file.Files

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

    @Test
    fun classify_createsNextOutputDirectoryAndRenamesDuplicates() {
        val root = Files.createTempDirectory("comiclab-classify").toFile()
        try {
            File(root, "ClassifyNList000").mkdirs()
            File(root, "[社团]漫画.zip").writeText("one")
            File(root, "sub").mkdirs()
            File(root, "sub/[社团]漫画.zip").writeText("two")
            File(root, "[翻译]无标签.zip").writeText("three")

            val result = ComicClassifier.classify(root, "翻译") {}
            val output = File(root, "ClassifyNList001")

            assertEquals(3, result.totalCount)
            assertEquals(3, result.copiedCount)
            assertEquals(0, result.failedCount)
            assertEquals(output.absolutePath, result.outputDirectory?.absolutePath)
            assertTrue(File(output, "社团/[社团]漫画.zip").isFile)
            assertTrue(File(output, "社团/[社团]漫画000.zip").isFile)
            assertTrue(File(output, "ClassifyNListNoTag/[翻译]无标签.zip").isFile)
        } finally {
            root.deleteRecursively()
        }
    }
}
