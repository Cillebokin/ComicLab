package com.example.comiclab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComicLabConfigurationTest {

    @Test
    fun jsonRoundTripPreservesAllConfigurationSections() {
        val configuration = sampleConfiguration()

        val decoded = ComicLabConfigurationJson.decode(
            ComicLabConfigurationJson.encode(configuration)
        )

        assertEquals(configuration, decoded.configuration)
        assertEquals(0, decoded.skippedEntries)
    }

    @Test
    fun jsonDecodeSkipsMalformedEntriesAndKeepsValidEntries() {
        val decoded = ComicLabConfigurationJson.decode(
            """
            {
              "schemaVersion": 1,
              "exportedAt": 10,
              "favoriteComics": [
                {"path":"/valid.cbz","addedAt":1,"fileSize":2,"modifiedAt":3},
                {"path":"","addedAt":1,"fileSize":2,"modifiedAt":3}
              ],
              "favoritePaths": [],
              "readingHistory": [],
              "bookcasePaths": [],
              "mangaReadingProgress": [],
              "ebookReadingProgress": [],
              "appSettings": {}
            }
            """.trimIndent()
        )

        assertEquals(
            listOf(
                ComicLabFavoriteComic(
                    path = "/valid.cbz",
                    addedAt = 1L,
                    fileSize = 2L,
                    modifiedAt = 3L
                )
            ),
            decoded.configuration.favoriteComics
        )
        assertEquals(1, decoded.skippedEntries)
    }

    @Test
    fun mergerUnionsCollectionsAndImportedSettingsOverrideCurrentValues() {
        val current = sampleConfiguration().copy(
            favoritePaths = listOf(ComicLabFavoritePath("/current", 1L)),
            appSettings = ComicLabAppSettings(
                readingDirection = AppSettings.READING_DIRECTION_TOP_TO_BOTTOM,
                volumeKeyPageTurn = false
            )
        )
        val imported = sampleConfiguration().copy(
            favoritePaths = listOf(
                ComicLabFavoritePath("/current", 9L),
                ComicLabFavoritePath("/imported", 10L)
            ),
            appSettings = ComicLabAppSettings(
                readingDirection = AppSettings.READING_DIRECTION_RIGHT_TO_LEFT,
                volumeKeyPageTurn = true
            )
        )

        val merged = ComicLabConfigurationMerger.merge(current, imported)

        assertEquals(
            listOf("/current", "/imported"),
            merged.favoritePaths.map { it.path }
        )
        assertEquals(
            AppSettings.READING_DIRECTION_RIGHT_TO_LEFT,
            merged.appSettings.readingDirection
        )
        assertTrue(merged.appSettings.volumeKeyPageTurn == true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun jsonDecodeRejectsUnsupportedSchemaVersion() {
        ComicLabConfigurationJson.decode("{\"schemaVersion\": 99}")
    }

    private fun sampleConfiguration(): ComicLabConfiguration {
        return ComicLabConfiguration(
            schemaVersion = ComicLabConfigurationJson.CURRENT_SCHEMA_VERSION,
            exportedAt = 123L,
            favoriteComics = listOf(
                ComicLabFavoriteComic("/comic.cbz", 1L, 2L, 3L)
            ),
            favoritePaths = listOf(
                ComicLabFavoritePath("/library", 4L)
            ),
            readingHistory = listOf(
                ComicLabHistoryEntry("/history.cbz", 5L, 6L, 7L)
            ),
            bookcasePaths = listOf("/bookcase"),
            mangaReadingProgress = listOf(
                ComicLabMangaProgress("/comic.cbz", 8, 9)
            ),
            ebookReadingProgress = listOf(
                ComicLabEbookProgress("/book.epub", 2, 0.5f)
            ),
            appSettings = ComicLabAppSettings(
                darkModeEnabled = true,
                languageTag = "zh-CN",
                readingDirection = AppSettings.READING_DIRECTION_LEFT_TO_RIGHT,
                doublePageCoverSingle = true,
                volumeKeyPageTurn = true,
                autoHideSystemBars = false,
                startMarkerErrorTags = "中;汉",
                customReaderBrightnessEnabled = true,
                customReaderBrightness = 180
            )
        )
    }
}
