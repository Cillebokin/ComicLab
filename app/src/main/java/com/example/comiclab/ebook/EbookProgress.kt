package com.example.comiclab.ebook

data class EbookProgress(
    val chapterIndex: Int,
    val scrollFraction: Float
) {
    fun normalized(): EbookProgress {
        return copy(
            chapterIndex = chapterIndex.coerceAtLeast(0),
            scrollFraction = scrollFraction.coerceIn(0f, 1f)
        )
    }
}
