package com.example.comiclab.ebook

import kotlin.math.roundToInt

object EbookReaderProgressMapper {

    const val SLIDER_MAX = 1_000

    fun toSlider(progress: EbookProgress?, chapterCount: Int): Int {
        if (chapterCount <= 0) {
            return 0
        }

        val safeProgress = clampToBook(progress ?: EbookProgress(0, 0f), chapterCount)
        val bookPosition = (safeProgress.chapterIndex + safeProgress.scrollFraction) / chapterCount
        return (bookPosition * SLIDER_MAX).roundToInt().coerceIn(0, SLIDER_MAX)
    }

    fun fromSlider(value: Int, chapterCount: Int): EbookProgress {
        if (chapterCount <= 0) {
            return EbookProgress(0, 0f)
        }

        val normalized = value.coerceIn(0, SLIDER_MAX).toFloat() / SLIDER_MAX
        val bookPosition = normalized * chapterCount
        if (bookPosition >= chapterCount) {
            return EbookProgress(chapterCount - 1, 1f)
        }

        val chapterIndex = bookPosition.toInt().coerceIn(0, chapterCount - 1)
        return EbookProgress(
            chapterIndex = chapterIndex,
            scrollFraction = (bookPosition - chapterIndex).coerceIn(0f, 1f)
        )
    }

    fun clampToBook(progress: EbookProgress, chapterCount: Int): EbookProgress {
        if (chapterCount <= 0) {
            return EbookProgress(0, 0f)
        }

        return EbookProgress(
            chapterIndex = progress.chapterIndex.coerceIn(0, chapterCount - 1),
            scrollFraction = progress.scrollFraction.coerceIn(0f, 1f)
        )
    }
}
