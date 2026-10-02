package com.example.comiclab.ebook

import org.junit.Assert.assertEquals
import org.junit.Test

class EbookReaderProgressMapperTest {

    @Test
    fun toSliderMapsChapterAndScrollPositionAcrossWholeBook() {
        assertEquals(0, EbookReaderProgressMapper.toSlider(EbookProgress(0, 0f), 4))
        assertEquals(375, EbookReaderProgressMapper.toSlider(EbookProgress(1, 0.5f), 4))
        assertEquals(1_000, EbookReaderProgressMapper.toSlider(EbookProgress(3, 1f), 4))
    }

    @Test
    fun toSliderUsesStartForMissingProgressAndEmptyBooks() {
        assertEquals(0, EbookReaderProgressMapper.toSlider(null, 3))
        assertEquals(0, EbookReaderProgressMapper.toSlider(EbookProgress(1, 0.5f), 0))
        assertEquals(0, EbookReaderProgressMapper.toSlider(EbookProgress(1, 0.5f), -2))
    }

    @Test
    fun toSliderClampsProgressToBookBoundaries() {
        assertEquals(0, EbookReaderProgressMapper.toSlider(EbookProgress(-2, -0.5f), 3))
        assertEquals(1_000, EbookReaderProgressMapper.toSlider(EbookProgress(8, 1.5f), 3))
    }

    @Test
    fun fromSliderClampsSliderAndMapsToChapterFraction() {
        assertEquals(EbookProgress(0, 0f), EbookReaderProgressMapper.fromSlider(-5, 4))
        assertEquals(EbookProgress(1, 0.5f), EbookReaderProgressMapper.fromSlider(375, 4))
        assertEquals(EbookProgress(3, 1f), EbookReaderProgressMapper.fromSlider(1_005, 4))
    }

    @Test
    fun fromSliderReturnsOriginForEmptyBooks() {
        assertEquals(EbookProgress(0, 0f), EbookReaderProgressMapper.fromSlider(500, 0))
        assertEquals(EbookProgress(0, 0f), EbookReaderProgressMapper.fromSlider(500, -1))
    }

    @Test
    fun clampToBookClampsBothChapterAndFraction() {
        assertEquals(
            EbookProgress(0, 0f),
            EbookReaderProgressMapper.clampToBook(EbookProgress(-1, -0.25f), 3)
        )
        assertEquals(
            EbookProgress(2, 1f),
            EbookReaderProgressMapper.clampToBook(EbookProgress(8, 1.25f), 3)
        )
        assertEquals(
            EbookProgress(0, 0f),
            EbookReaderProgressMapper.clampToBook(EbookProgress(8, 1.25f), 0)
        )
    }
}
