package com.example.comiclab.core.organize

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DirectorySimilarityScannerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun normalizesCaseWhitespaceAndSymbolsAndIgnoresHiddenOrBlankNames() {
        val root = temporaryFolder.newFolder("normalized-root")
        directory(root, "Manga_A-01 (Special)")
        directory(root, "manga a 01 special")
        directory(root, " _-·[]()")
        val hiddenDirectory = directory(root, ".hidden")
        directory(hiddenDirectory, "Manga_A-01 (Special)")

        val report = createReport(root)
        val contents = readReport(report.outputFile)

        assertEquals(3, report.directoryCount)
        assertEquals(1, report.pairCount)
        assertTrue(contents.contains("Name A: Manga_A-01 (Special)") || contents.contains("Name B: Manga_A-01 (Special)"))
        assertFalse(contents.contains(".hidden"))
        assertFalse(contents.contains(" _-·[]()"))
    }

    @Test
    fun emptyRootReportsZeroCandidatesAndWritesNoPairReport() {
        val root = temporaryFolder.newFolder("empty-root")
        val progress = mutableListOf<DirectorySimilarityProgress>()

        val report = createReport(root, onProgress = progress::add)

        assertEquals(0, report.directoryCount)
        assertEquals(0, report.pairCount)
        assertTrue(readReport(report.outputFile).contains("No similar directory pairs"))
        assertEquals(
            listOf(
                DirectorySimilarityProgress(DirectorySimilarityProgress.Stage.SCANNING_DIRECTORIES),
                DirectorySimilarityProgress(
                    DirectorySimilarityProgress.Stage.COMPARING_DIRECTORY_NAMES,
                    completed = 0,
                    total = 0
                ),
                DirectorySimilarityProgress(
                    DirectorySimilarityProgress.Stage.COMPARING_DIRECTORY_NAMES,
                    completed = 0,
                    total = 0
                )
            ),
            progress
        )
    }

    @Test
    fun keepsOnlyPairsAtOrAboveTheSimilarityThreshold() {
        val root = temporaryFolder.newFolder("threshold-root")
        val atThresholdA = "a".repeat(50)
        val atThresholdB = "b".repeat(11) + "a".repeat(39)
        val belowThresholdA = "x".repeat(50)
        val belowThresholdB = "y".repeat(12) + "x".repeat(38)
        directory(root, atThresholdA)
        directory(root, atThresholdB)
        directory(root, belowThresholdA)
        directory(root, belowThresholdB)

        val report = createReport(root)
        val contents = readReport(report.outputFile)

        assertEquals(1, report.pairCount)
        assertTrue(contents.contains(atThresholdA))
        assertTrue(contents.contains(atThresholdB))
        assertFalse(contents.contains(belowThresholdA))
        assertFalse(contents.contains(belowThresholdB))
    }

    @Test
    fun findsRepeatedNestedNamesAndSortsEqualSimilarityPairsByPath() {
        val root = temporaryFolder.newFolder("stable-order-root")
        val first = directory(directory(root, "AlphaCollection"), "Issue")
        val second = directory(directory(root, "GammaShelf"), "Issue")
        val third = directory(directory(root, "NorthRepository"), "Issue")

        val report = createReport(root)
        val pathA = readReport(report.outputFile).lineSequence()
            .filter { it.startsWith("Path A: ") }
            .map { it.removePrefix("Path A: ") }
            .toList()
        val pathB = readReport(report.outputFile).lineSequence()
            .filter { it.startsWith("Path B: ") }
            .map { it.removePrefix("Path B: ") }
            .toList()

        assertEquals(6, report.directoryCount)
        assertEquals(3, report.pairCount)
        assertEquals(listOf(first.absolutePath, first.absolutePath, second.absolutePath), pathA)
        assertEquals(listOf(second.absolutePath, third.absolutePath, third.absolutePath), pathB)
    }

    @Test
    fun unrelatedDirectoriesProduceNoPairs() {
        val root = temporaryFolder.newFolder("unrelated-root")
        directory(root, "Alpha")
        directory(root, "CompletelyDifferentShelf")

        val report = createReport(root)

        assertEquals(2, report.directoryCount)
        assertEquals(0, report.pairCount)
        assertTrue(readReport(report.outputFile).contains("No similar directory pairs"))
    }

    @Test
    fun writesUtf8ReportToRootUsingTimestampAndCollisionSuffix() {
        val root = temporaryFolder.newFolder("report-root")
        directory(root, "漫画收藏卷")
        directory(root, "漫画收藏册")
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT)
            .parse("20261005_123456")!!
        File(root, "ComicLab_similar_directory_names_20261005_123456.txt")
            .writeText("existing report")

        val report = createReport(root, timestamp)

        assertEquals(
            "ComicLab_similar_directory_names_20261005_123456_001.txt",
            report.outputFile.name
        )
        assertEquals(root.absolutePath, report.outputFile.parentFile?.absolutePath)
        val contents = readReport(report.outputFile)
        assertTrue(contents.contains("Generated at: 2026-10-05 12:34:56"))
        assertTrue(contents.contains("漫画收藏卷"))
        assertTrue(contents.contains("漫画收藏册"))
        assertEquals(contents, String(report.outputFile.readBytes(), Charsets.UTF_8))
    }

    private fun createReport(
        root: File,
        timestamp: Date = fixedTimestamp(),
        onProgress: (DirectorySimilarityProgress) -> Unit = {}
    ): DirectorySimilarityReport {
        val timestampMillis = timestamp.time
        return DirectorySimilarityScanner.createReport(
            rootDirectory = root,
            formatMessage = ::formatReportMessage,
            clock = { Date(timestampMillis) },
            onProgress = onProgress
        )
    }

    private fun formatReportMessage(
        message: DirectorySimilarityReportMessage,
        arguments: Array<out Any>
    ): String {
        return when (message) {
            DirectorySimilarityReportMessage.TITLE -> "Directory similarity report"
            DirectorySimilarityReportMessage.GENERATED_AT -> "Generated at: ${arguments[0]}"
            DirectorySimilarityReportMessage.ROOT_DIRECTORY -> "Root directory: ${arguments[0]}"
            DirectorySimilarityReportMessage.SIMILARITY_RULE -> "Similarity threshold: ${arguments[0]}"
            DirectorySimilarityReportMessage.DIRECTORY_COUNT -> "Directory count: ${arguments[0]}"
            DirectorySimilarityReportMessage.PAIR_COUNT -> "Pair count: ${arguments[0]}"
            DirectorySimilarityReportMessage.NO_PAIRS -> "No similar directory pairs"
            DirectorySimilarityReportMessage.PAIR_SIMILARITY -> "[${arguments[0]}] Similarity: ${arguments[1]}"
            DirectorySimilarityReportMessage.NAME_A -> "Name A: ${arguments[0]}"
            DirectorySimilarityReportMessage.PATH_A -> "Path A: ${arguments[0]}"
            DirectorySimilarityReportMessage.NAME_B -> "Name B: ${arguments[0]}"
            DirectorySimilarityReportMessage.PATH_B -> "Path B: ${arguments[0]}"
        }
    }

    private fun directory(parent: File, name: String): File {
        return File(parent, name).apply { assertTrue("Could not create $absolutePath", mkdir()) }
    }

    private fun readReport(file: File): String = String(file.readBytes(), Charsets.UTF_8)

    private fun fixedTimestamp(): Date = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT)
        .parse("20261005_123456")!!
}
