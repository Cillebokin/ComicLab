package com.example.comiclab

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComicClassifierTest {

    @Test
    fun classifyDoesNotCreateOutputWhenRootIsUnavailableOrContainsNoComics() {
        val root = Files.createTempDirectory("comiclab-classifier-empty").toFile()
        try {
            val missingRoot = File(root, "missing")
            val missingResult = ComicClassifier.classify(missingRoot, "") {}
            val emptyResult = ComicClassifier.classify(root, "") {}

            assertNull(missingResult.outputDirectory)
            assertEquals(0, missingResult.totalCount)
            assertNull(emptyResult.outputDirectory)
            assertEquals(0, emptyResult.totalCount)
            assertFalse(File(root, "ClassifyNList000").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun classifySkipsHiddenAndPreviousOutputDirectoriesAndPreservesSources() {
        val root = Files.createTempDirectory("comiclab-classifier-scan").toFile()
        try {
            val hiddenComic = File(root, ".hidden/[hidden]comic.cbz").apply {
                parentFile?.mkdirs()
                writeText("hidden")
            }
            val oldOutputComic = File(root, "ClassifyNList000/[old]comic.cbz").apply {
                parentFile?.mkdirs()
                writeText("old output")
            }
            val sourceComic = File(root, "sub/[visible]comic.cbz").apply {
                parentFile?.mkdirs()
                writeText("source bytes")
            }
            File(root, "notes.docx").writeText("not a supported reader file")

            val progress = mutableListOf<ComicClassifier.Progress>()
            val result = ComicClassifier.classify(root, "") { progress += it }
            val output = result.outputDirectory ?: error("Expected a classification output")

            assertEquals(
                "Only the visible reader-supported file should be classified; progress: $progress",
                1,
                result.totalCount
            )
            assertEquals(1, result.copiedCount)
            assertEquals(0, result.failedCount)
            assertTrue(File(output, "visible/[visible]comic.cbz").readText() == "source bytes")
            assertTrue(sourceComic.isFile)
            assertEquals("source bytes", sourceComic.readText())
            assertTrue(hiddenComic.isFile)
            assertTrue(oldOutputComic.isFile)
            assertFalse(File(output, "hidden").exists())
            assertFalse(File(output, "old").exists())
            assertEquals(
                listOf(
                    ComicClassifier.Progress(stage = ComicClassifier.Stage.SCANNING),
                    ComicClassifier.Progress(
                        stage = ComicClassifier.Stage.COPYING,
                        completed = 0,
                        total = 1,
                        currentFileName = "[visible]comic.cbz"
                    ),
                    ComicClassifier.Progress(
                        stage = ComicClassifier.Stage.COPYING,
                        completed = 1,
                        total = 1
                    )
                ),
                progress
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
