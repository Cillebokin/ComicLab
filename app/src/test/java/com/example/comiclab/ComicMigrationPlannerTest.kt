package com.example.comiclab

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComicMigrationPlannerTest {

    @Test
    fun scanComicFilesRecursivelyFindsSupportedFilesInNestedFolders() {
        withTemporaryDirectory { root ->
            val directComic = File(root, "direct.comic").apply { writeText("direct") }
            File(root, "ignored.txt").writeText("text")
            val childDirectory = File(root, "child").apply { mkdirs() }
            val nestedComic = File(childDirectory, "nested.comic").apply { writeText("nested") }

            val result = ComicMigrationPlanner { it.extension == "comic" }
                .scanComicFilesRecursively(root)

            assertEquals(
                listOf(directComic, nestedComic).map { it.absolutePath }.sorted(),
                result.map { it.absolutePath }.sorted()
            )
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
    fun matchingFoldersUsesOnlyNamesAndListsParentheticalComponentsAndReversedPairs() {
        withTemporaryDirectory { root ->
            val expected = listOf(
                File(root, "AAA").apply { mkdirs() },
                File(root, "BBB").apply { mkdirs() },
                File(root, "AAA (CCC)").apply { mkdirs() },
                File(root, "BBB(AAA)").apply { mkdirs() }
            )
            val ignored = File(root, "Unrelated").apply { mkdirs() }
            File(ignored, "[AAA(BBB)] comic.cbz").writeText("not a folder-name match")
            val nestedMatch = File(File(root, "Branch").apply { mkdirs() }, "AAA Extra")
                .apply { mkdirs() }

            val result = ComicMigrationPlanner { false }
                .findMatchingDirectories(root, "AAA(BBB)")

            assertEquals(
                (expected + nestedMatch).map { it.absolutePath }.toSet(),
                result.map { it.directory.absolutePath }.toSet()
            )
            assertTrue(
                result.first { it.directory == expected[2] }.reason ==
                    ComicMigrationMatchReason.COMPONENT_ALIAS
            )
            assertTrue(result.none { it.directory == root })
            assertTrue(result.none { it.directory == ignored })
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
}
