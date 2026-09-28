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
        )
    )

    fun find(id: String?): HelpArticle? {
        return all.firstOrNull { it.id == id }
    }
}
