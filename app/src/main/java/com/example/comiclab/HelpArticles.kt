package com.example.comiclab

import androidx.annotation.RawRes
import androidx.annotation.StringRes

data class HelpArticle(
    val id: String,
    @StringRes val titleResId: Int,
    @RawRes val htmlResId: Int
)

object HelpArticles {
    const val START_MARKER_ID = "start_marker"
    const val START_MARKER_ERROR_TAGS_ID = "start_marker_error_tags"
    const val COMIC_CLASSIFICATION_ID = "comic_classification"
    const val COMIC_MIGRATION_ID = "comic_migration"
    const val COMIC_MERGE_ID = "comic_merge"
    const val FILE_MERGE_ID = "file_merge"

    val all: List<HelpArticle> = listOf(
        HelpArticle(
            id = START_MARKER_ID,
            titleResId = R.string.help_start_marker,
            htmlResId = R.raw.help_start_marker
        ),
        HelpArticle(
            id = START_MARKER_ERROR_TAGS_ID,
            titleResId = R.string.help_start_marker_error_tags,
            htmlResId = R.raw.help_start_marker_error_tags
        ),
        HelpArticle(
            id = COMIC_CLASSIFICATION_ID,
            titleResId = R.string.help_comic_classification,
            htmlResId = R.raw.help_comic_classification
        ),
        HelpArticle(
            id = COMIC_MIGRATION_ID,
            titleResId = R.string.help_comic_migration,
            htmlResId = R.raw.help_comic_migration
        ),
        HelpArticle(
            id = COMIC_MERGE_ID,
            titleResId = R.string.help_comic_merge,
            htmlResId = R.raw.help_comic_merge
        ),
        HelpArticle(
            id = FILE_MERGE_ID,
            titleResId = R.string.help_file_merge,
            htmlResId = R.raw.help_file_merge
        )
    )

    fun find(id: String?): HelpArticle? {
        return all.firstOrNull { it.id == id }
    }
}
