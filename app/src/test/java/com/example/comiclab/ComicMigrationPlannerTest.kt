package com.example.comiclab

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComicMigrationPlannerTest {

    @Test
    fun targetDirectoryChoicesAlwaysAppendDefaultAfterMatches() {
        withTemporaryDirectory { root ->
            val firstMatch = File(root, "first")
            val secondMatch = File(root, "second")
            val defaultDirectory = File(root, "default")
            val matches = listOf(
                ComicMigrationDirectoryMatch(firstMatch, ComicMigrationMatchReason.EXACT_NAME),
                ComicMigrationDirectoryMatch(secondMatch, ComicMigrationMatchReason.CONTAINS_MARKER)
            )

            val result = ComicMigrationPlanner { false }
                .targetDirectoryChoices(matches, defaultDirectory)

            assertEquals(listOf(firstMatch, secondMatch, defaultDirectory), result)
        }
    }

    @Test
    fun targetDirectoryChoicesContainsDefaultWhenThereAreNoMatches() {
        withTemporaryDirectory { root ->
            val defaultDirectory = File(root, "default")

            val result = ComicMigrationPlanner { false }
                .targetDirectoryChoices(emptyList(), defaultDirectory)

            assertEquals(listOf(defaultDirectory), result)
        }
    }

    @Test
    fun prepareTargetPlansBuildsChoicesForEveryComicBeforeSelectionStarts() {
        withTemporaryDirectory { root ->
            val source = File(root, "source").apply { mkdirs() }
            val destinationRoot = File(root, "destination").apply { mkdirs() }
            val aaaDirectory = File(destinationRoot, "AAA circle").apply { mkdirs() }
            val otherDirectory = File(destinationRoot, "other").apply { mkdirs() }
            val bbbDirectory = File(otherDirectory, "BBB circle").apply { mkdirs() }
            val defaultDirectory = File(root, "default").apply { mkdirs() }
            val comics = listOf(
                File(source, "one.zip"),
                File(source, "two.zip"),
                File(source, "three.zip")
            )
            val markers = mapOf(
                "one.zip" to "AAA",
                "two.zip" to "BBB",
                "three.zip" to ""
            )
            val preparationProgress = mutableListOf<Pair<Int, Int>>()

            val plans = ComicMigrationPlanner { false }.prepareTargetPlans(
                comicFiles = comics,
                rootDirectory = destinationRoot,
                defaultDirectory = defaultDirectory,
                excludedDirectory = source,
                markerForFile = { markers.getValue(it.name) },
                onProgress = { completed, total ->
                    preparationProgress.add(completed to total)
                }
            )

            assertEquals(comics, plans.map { it.comicFile })
            assertEquals(listOf("AAA", "BBB", ""), plans.map { it.startMarker })
            assertEquals(listOf(aaaDirectory, defaultDirectory), plans[0].directories)
            assertEquals(listOf(bbbDirectory, defaultDirectory), plans[1].directories)
            assertEquals(listOf(defaultDirectory), plans[2].directories)
            assertEquals(listOf(0 to 3, 1 to 3, 2 to 3, 3 to 3), preparationProgress)
        }
    }

    @Test
    fun prepareTargetPlansUsesWorkerPoolAndPreservesComicOrder() {
        withTemporaryDirectory { root ->
            val destinationRoot = File(root, "destination").apply { mkdirs() }
            val matchingDirectory = File(destinationRoot, "AAA circle").apply { mkdirs() }
            val defaultDirectory = File(root, "default").apply { mkdirs() }
            val comics = (1..4).map { File(root, "comic-$it.zip") }
            val executor = Executors.newFixedThreadPool(2)
            val twoMatchesCompleted = CountDownLatch(2)

            try {
                val plans = ComicMigrationPlanner { false }.prepareTargetPlans(
                    comicFiles = comics,
                    rootDirectory = destinationRoot,
                    defaultDirectory = defaultDirectory,
                    markerForFile = { "AAA" },
                    workerExecutor = executor,
                    onProgress = { completed, _ ->
                        if (completed in 1..2) {
                            twoMatchesCompleted.countDown()
                            assertTrue(
                                "Target matching did not run concurrently",
                                twoMatchesCompleted.await(5, TimeUnit.SECONDS)
                            )
                        }
                    }
                )

                assertEquals(comics, plans.map { it.comicFile })
                assertEquals(
                    List(comics.size) { listOf(matchingDirectory, defaultDirectory) },
                    plans.map { it.directories }
                )
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun prepareTargetPlansStopsWhenThePreparationThreadIsInterrupted() {
        withTemporaryDirectory { root ->
            val destinationRoot = File(root, "destination").apply { mkdirs() }
            File(destinationRoot, "AAA circle").mkdirs()
            val defaultDirectory = File(root, "default").apply { mkdirs() }
            val comics = listOf(File(root, "one.zip"), File(root, "two.zip"))
            val wasInterrupted = Thread.interrupted()

            try {
                val error = runCatching {
                    ComicMigrationPlanner { false }.prepareTargetPlans(
                        comicFiles = comics,
                        rootDirectory = destinationRoot,
                        defaultDirectory = defaultDirectory,
                        markerForFile = { "AAA" },
                        onProgress = { completed, _ ->
                            if (completed == 1) {
                                Thread.currentThread().interrupt()
                            }
                        }
                    )
                }.exceptionOrNull()

                assertTrue(error is InterruptedException)
            } finally {
                Thread.interrupted()
                if (wasInterrupted) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    @Test
    fun scanComicFilesRecursivelyFindsSupportedFilesInNestedFolders() {
        withTemporaryDirectory { root ->
            val directComic = File(root, "direct.comic").apply { writeText("direct") }
            File(root, "ignored.txt").writeText("text")
            val childDirectory = File(root, "child").apply { mkdirs() }
            val nestedComic = File(childDirectory, "nested.comic").apply { writeText("nested") }
            val siblingDirectory = File(root, "sibling").apply { mkdirs() }
            val siblingComic = File(siblingDirectory, "sibling.comic").apply { writeText("sibling") }
            val executor = Executors.newFixedThreadPool(2)

            val result = try {
                ComicMigrationPlanner { it.extension == "comic" }
                    .scanComicFilesRecursively(root, workerExecutor = executor)
            } finally {
                executor.shutdownNow()
            }

            assertEquals(
                listOf(directComic, nestedComic, siblingComic).map { it.absolutePath }.sorted(),
                result.map { it.absolutePath }.sorted()
            )
        }
    }

    @Test
    fun scanComicFilesReadsSiblingDirectoriesConcurrently() {
        withTemporaryDirectory { root ->
            val firstDirectory = File(root, "first").apply { mkdirs() }
            val firstComic = File(firstDirectory, "first.comic").apply { writeText("first") }
            val secondDirectory = File(root, "second").apply { mkdirs() }
            val secondComic = File(secondDirectory, "second.comic").apply { writeText("second") }
            val twoDirectoryReadsStarted = CountDownLatch(2)
            val scanRoot = object : File(root.path) {
                override fun listFiles(): Array<File>? = arrayOf(
                    blockingDirectory(firstDirectory, twoDirectoryReadsStarted),
                    blockingDirectory(secondDirectory, twoDirectoryReadsStarted)
                )
            }
            val executor = Executors.newFixedThreadPool(2)

            val result = try {
                ComicMigrationPlanner { it.extension == "comic" }
                    .scanComicFilesRecursively(scanRoot, workerExecutor = executor)
            } finally {
                executor.shutdownNow()
            }

            assertEquals(
                setOf(firstComic.absolutePath, secondComic.absolutePath),
                result.map { it.absolutePath }.toSet()
            )
        }
    }

    @Test
    fun scanComicFilesChecksFilesConcurrently() {
        withTemporaryDirectory { root ->
            val comics = (1..4).map { File(root, "comic-$it.comic").apply { writeText("comic") } }
            val twoFileChecksStarted = CountDownLatch(2)
            val executor = Executors.newFixedThreadPool(2)

            val result = try {
                ComicMigrationPlanner { file ->
                    check(twoFileChecksStarted.await(5, TimeUnit.SECONDS)) {
                        "Comic file checks did not run concurrently"
                    }
                    file.extension == "comic"
                }.scanComicFilesRecursively(root, workerExecutor = executor)
            } finally {
                executor.shutdownNow()
            }

            assertEquals(comics.map { it.absolutePath }.toSet(), result.map { it.absolutePath }.toSet())
        }
    }

    @Test
    fun missingSourceDirectoryIsNotReportedAsAnEmptyScan() {
        withTemporaryDirectory { root ->
            val missingSource = File(root, "missing-source")

            val error = runCatching {
                ComicMigrationPlanner { false }.scanComicFilesRecursively(missingSource)
            }.exceptionOrNull()

            assertTrue(error is IOException)
        }
    }

    @Test
    fun matchingFoldersUsesTheWholeMarkerInsteadOfParentheticalComponents() {
        withTemporaryDirectory { root ->
            val fullMarkerMatch = File(root, "Shelf AAA(BBB) Collection").apply { mkdirs() }
            val unrelatedParts = listOf("AAA", "BBB", "AAA (CCC)", "BBB(AAA)")
                .map { File(root, it).apply { mkdirs() } }
            val ignored = File(root, "Unrelated").apply { mkdirs() }
            File(ignored, "[AAA(BBB)] comic.cbz").writeText("not a folder-name match")
            val nestedMatch = File(File(root, "Branch").apply { mkdirs() }, "AAA Extra")
                .apply { mkdirs() }

            val result = ComicMigrationPlanner { false }
                .findMatchingDirectories(root, "AAA(BBB)")

            assertEquals(
                setOf(fullMarkerMatch.absolutePath),
                result.map { it.directory.absolutePath }.toSet()
            )
            assertEquals(ComicMigrationMatchReason.CONTAINS_MARKER, result.single().reason)
            assertTrue(result.none { it.directory in unrelatedParts || it.directory == nestedMatch })
            assertTrue(result.none { it.directory == root })
            assertTrue(result.none { it.directory == ignored })
        }
    }

    @Test
    fun matchingFoldersDoesNotMatchShortKanaOrRomajiFragmentsFromMixedMarkers() {
        withTemporaryDirectory { root ->
            val unrelatedFolderNames = listOf(
                "2no",
                "5年目の放課後 (カントク)",
                "Aino_Chie",
                "Alkaloid no Baketu",
                "Kokonoki_Nao",
                "Yamakon-ya",
                "きのこのみ (konomi)",
                "ぐらちゃんとこのほん",
                "しゅらの工房 (しゅら)",
                "汁まみれ定食 (汁助)",
                "とろとろ夢ばなな",
                "ぶた小屋 (ケミガワ)"
            ).map { File(root, it).apply { mkdirs() } }
            val planner = ComicMigrationPlanner { false }

            val markers = listOf(
                "軒下の猫屋 (アルデヒド)",
                "MeltdoWN COmet (雪雨こん)",
                "URAN-FACTORY (URAN)",
                "Shinsen Gokuraku (Mami)",
                "ばな奈工房 (青ばなな)",
                "とらいあんぐる (あまま紗由, 三上ミカ, キチロク, 他)"
            )

            markers.forEach { marker ->
                assertTrue(
                    "Unexpected match for marker: $marker",
                    planner.findMatchingDirectories(root, marker).none { it.directory in unrelatedFolderNames }
                )
            }
        }
    }

    @Test
    fun matchingKanaMarkerRequiresAWholeRomajiNameOrToken() {
        withTemporaryDirectory { root ->
            val kanaEquivalent = File(root, "まみ").apply { mkdirs() }
            val romajiEquivalent = File(root, "mami shelf").apply { mkdirs() }
            val partialRomaji = File(root, "mamire").apply { mkdirs() }

            val result = ComicMigrationPlanner { false }
                .findMatchingDirectories(root, "マミ")

            assertEquals(
                setOf(kanaEquivalent.absolutePath, romajiEquivalent.absolutePath),
                result.map { it.directory.absolutePath }.toSet()
            )
            assertTrue(result.none { it.directory == partialRomaji })
        }
    }

    @Test
    fun matchingRomajiMarkerCanMatchACompleteKanaEquivalent() {
        withTemporaryDirectory { root ->
            val literalRomaji = File(root, "airu shelf").apply { mkdirs() }
            val kanaEquivalent = File(root, "あいる").apply { mkdirs() }
            val kanaWithExtraSyllable = File(root, "あいるか").apply { mkdirs() }

            val result = ComicMigrationPlanner { false }
                .findMatchingDirectories(root, "airu")

            assertEquals(
                setOf(literalRomaji.absolutePath, kanaEquivalent.absolutePath),
                result.map { it.directory.absolutePath }.toSet()
            )
            assertTrue(result.none { it.directory == kanaWithExtraSyllable })
        }
    }

    @Test
    fun matchingFoldersFindsKanaAndRomajiVariantsWithInternalRules() {
        withTemporaryDirectory { root ->
            val expected = listOf(
                File(root, "アイル").apply { mkdirs() },
                File(root, "あいる").apply { mkdirs() },
                File(root, "airu").apply { mkdirs() }
            )

            val result = ComicMigrationPlanner { false }
                .findMatchingDirectories(root, "アイル")

            assertEquals(expected.map { it.absolutePath }.toSet(), result.map { it.directory.absolutePath }.toSet())
            assertEquals(
                ComicMigrationMatchReason.KANA_ROMAJI,
                result.first { it.directory == expected[1] }.reason
            )
            assertEquals(
                ComicMigrationMatchReason.KANA_ROMAJI,
                result.first { it.directory == expected[2] }.reason
            )
        }
    }

    @Test
    fun matchingFoldersCanonicalizesCommonRomanizationAliases() {
        withTemporaryDirectory { root ->
            val shi = File(root, "shi").apply { mkdirs() }
            val si = File(root, "si").apply { mkdirs() }
            val chi = File(root, "chi").apply { mkdirs() }
            val ti = File(root, "ti").apply { mkdirs() }
            val sister = File(root, "sister").apply { mkdirs() }
            val music = File(root, "music").apply { mkdirs() }
            val practice = File(root, "practice").apply { mkdirs() }
            val shirt = File(root, "shirt").apply { mkdirs() }
            val fishing = File(root, "fishing").apply { mkdirs() }

            val result = ComicMigrationPlanner { false }
                .findMatchingDirectories(root, "シ")
            val chiResult = ComicMigrationPlanner { false }
                .findMatchingDirectories(root, "チ")

            assertEquals(setOf(shi.absolutePath, si.absolutePath), result.map { it.directory.absolutePath }.toSet())
            assertEquals(setOf(chi.absolutePath, ti.absolutePath), chiResult.map { it.directory.absolutePath }.toSet())
            assertTrue(result.none {
                it.directory == sister || it.directory == music ||
                    it.directory == shirt || it.directory == fishing
            })
            assertTrue(chiResult.none { it.directory == practice })
        }
    }

    @Test
    fun matchingFoldersIgnoresSpacesAndHyphensBetweenRomajiParts() {
        withTemporaryDirectory { root ->
            val shiro = File(root, "shirogane").apply { mkdirs() }
            val hyphenated = File(root, "shiro-gane").apply { mkdirs() }
            val spaced = File(root, "shiro gane").apply { mkdirs() }

            val result = ComicMigrationPlanner { false }
                .findMatchingDirectories(root, "シロガネ")

            assertEquals(
                setOf(shiro.absolutePath, hyphenated.absolutePath, spaced.absolutePath),
                result.map { it.directory.absolutePath }.toSet()
            )
        }
    }

    @Test
    fun matchingFoldersHandlesDigraphsAndSmallTsu() {
        withTemporaryDirectory { root ->
            val matcha = File(root, "matcha").apply { mkdirs() }
            val kyou = File(root, "kyou").apply { mkdirs() }
            File(root, "macha").mkdirs()

            val matchaResult = ComicMigrationPlanner { false }
                .findMatchingDirectories(root, "マッチャ")
            val kyouResult = ComicMigrationPlanner { false }
                .findMatchingDirectories(root, "きょう")

            assertEquals(listOf(matcha.absolutePath), matchaResult.map { it.directory.absolutePath })
            assertEquals(listOf(kyou.absolutePath), kyouResult.map { it.directory.absolutePath })
        }
    }

    @Test
    fun matchingFoldersCanonicalizesKanaLongVowelsMacronsAndRomajiAliases() {
        withTemporaryDirectory { root ->
            val expected = listOf(
                File(root, "こうひい").apply { mkdirs() },
                File(root, "kōhī").apply { mkdirs() },
                File(root, "koohii").apply { mkdirs() }
            )
            File(root, "coffee").mkdirs()

            val result = ComicMigrationPlanner { false }
                .findMatchingDirectories(root, "コーヒー")

            assertEquals(expected.map { it.absolutePath }.toSet(), result.map { it.directory.absolutePath }.toSet())
        }
    }

    @Test
    fun matchingFoldersCanonicalizesOuAndEiLongVowelSpellings() {
        withTemporaryDirectory { root ->
            val ou = File(root, "kou").apply { mkdirs() }
            val macron = File(root, "kō").apply { mkdirs() }
            val ei = File(root, "sei").apply { mkdirs() }
            val eMacron = File(root, "sē").apply { mkdirs() }

            val ouResult = ComicMigrationPlanner { false }
                .findMatchingDirectories(root, "こう")
            val eiResult = ComicMigrationPlanner { false }
                .findMatchingDirectories(root, "せい")

            assertEquals(setOf(ou.absolutePath, macron.absolutePath), ouResult.map { it.directory.absolutePath }.toSet())
            assertEquals(setOf(ei.absolutePath, eMacron.absolutePath), eiResult.map { it.directory.absolutePath }.toSet())
        }
    }

    @Test
    fun syllabicNBeforeVowelKeepsItsDisambiguatingApostrophe() {
        withTemporaryDirectory { root ->
            val romanized = File(root, "n'a").apply { mkdirs() }
            File(root, "na").mkdirs()

            val result = ComicMigrationPlanner { false }
                .findMatchingDirectories(root, "ンア")

            assertEquals(listOf(romanized.absolutePath), result.map { it.directory.absolutePath })
        }
    }

    @Test
    fun matchingFoldersDoesNotReturnSourceDirectoryOrItsDescendants() {
        withTemporaryDirectory { root ->
            val source = File(root, "source").apply { mkdirs() }
            val sourceChild = File(source, "AAA").apply { mkdirs() }
            val valid = File(root, "AAA").apply { mkdirs() }

            val result = ComicMigrationPlanner { false }
                .findMatchingDirectories(root, "AAA", excludedDirectory = source)

            assertEquals(listOf(valid.absolutePath), result.map { it.directory.absolutePath })
            assertFalse(result.any { it.directory == sourceChild })
        }
    }

    @Test
    fun missingDestinationTreeIsNotTreatedAsNoMatch() {
        withTemporaryDirectory { root ->
            val missingTree = File(root, "missing-tree")

            val error = runCatching {
                ComicMigrationPlanner { false }.findMatchingDirectories(missingTree, "AAA")
            }.exceptionOrNull()

            assertTrue(error is IOException)
        }
    }

    @Test
    fun destinationMustNotBeTheSourceOrOneOfItsDescendants() {
        withTemporaryDirectory { root ->
            val source = File(root, "source").apply { mkdirs() }
            val child = File(source, "child").apply { mkdirs() }
            val sibling = File(root, "destination").apply { mkdirs() }
            val parent = root

            assertFalse(ComicMigrationPlanner.isSafeDestinationDirectory(source, source))
            assertFalse(ComicMigrationPlanner.isSafeDestinationDirectory(source, child))
            assertTrue(ComicMigrationPlanner.isSafeDestinationDirectory(source, sibling))
            assertTrue(ComicMigrationPlanner.isSafeDestinationDirectory(source, parent))
        }
    }

    @Test
    fun copyDisabledByDefaultPreviewsWithoutWritingDestination() {
        withTemporaryDirectory { root ->
            val source = File(root, "source.cbz").apply { writeText("archive content") }
            val destination = File(root, "destination").apply { mkdirs() }

            val result = ComicMigrationPlanner { false }
                .copyComicFile(source, destination)

            assertEquals("PREVIEW_ONLY", result.status.name)
            assertEquals("archive content", source.readText())
            assertFalse(File(destination, source.name).exists())
            assertEquals(0, destination.listFiles()?.size ?: 0)
        }
    }

    @Test
    fun copyCreatesDestinationAndLeavesSourceIntact() {
        withTemporaryDirectory { root ->
            val source = File(root, "source.cbz").apply { writeText("archive content") }
            val destination = File(root, "destination").apply { mkdirs() }

            val result = ComicMigrationPlanner(allowFileCopy = true) { false }
                .copyComicFile(source, destination)

            assertEquals(ComicMigrationCopyStatus.COPIED, result.status)
            assertEquals("archive content", File(destination, source.name).readText())
            assertEquals("archive content", source.readText())
        }
    }

    @Test
    fun copyDoesNotOverwriteAnExistingSameNameFile() {
        withTemporaryDirectory { root ->
            val source = File(root, "source.cbz").apply { writeText("new content") }
            val destination = File(root, "destination").apply { mkdirs() }
            val existing = File(destination, source.name).apply { writeText("keep existing") }

            val result = ComicMigrationPlanner(allowFileCopy = true) { false }
                .copyComicFile(source, destination)

            assertEquals(ComicMigrationCopyStatus.ALREADY_EXISTS, result.status)
            assertEquals("keep existing", existing.readText())
            assertEquals("new content", source.readText())
        }
    }

    @Test
    fun copyCancellationKeepsSourceAndRemovesPartialDestination() {
        withTemporaryDirectory { root ->
            val source = File(root, "large.cbz").apply {
                writeBytes(ByteArray(2 * 1024 * 1024) { 7 })
            }
            val destination = File(root, "destination").apply { mkdirs() }
            var cancellationChecks = 0

            val result = ComicMigrationPlanner(allowFileCopy = true) { false }.copyComicFile(
                source,
                destination,
                shouldCancel = { ++cancellationChecks >= 3 }
            )

            assertEquals(ComicMigrationCopyStatus.CANCELLED, result.status)
            assertTrue(source.exists())
            assertFalse(File(destination, source.name).exists())
            assertEquals(0, destination.listFiles()?.size ?: 0)
        }
    }

    private fun withTemporaryDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("comic-migration-test").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun blockingDirectory(
        directory: File,
        twoDirectoryReadsStarted: CountDownLatch
    ): File = object : File(directory.path) {
        override fun listFiles(): Array<File>? {
            check(twoDirectoryReadsStarted.await(5, TimeUnit.SECONDS)) {
                "Sibling directory reads did not run concurrently"
            }
            return super.listFiles()
        }
    }
}
