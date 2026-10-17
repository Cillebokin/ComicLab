package com.example.comiclab.core.reader.layout

import com.example.comiclab.core.reader.layout.ReaderPagePositionMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderPagePositionMapperTest {

    @Test
    fun singlePageModeMapsSourceAndAdapterPositionsOneToOne() {
        val mapper = ReaderPagePositionMapper(
            sourceItemCount = 3,
            doublePageReading = false,
            coverSinglePage = true
        )

        assertEquals(3, mapper.adapterItemCount)
        assertEquals(0, mapper.sourcePositionForAdapterPosition(0))
        assertEquals(2, mapper.sourcePositionForAdapterPosition(2))
        assertNull(mapper.sourcePositionForAdapterPosition(3))
        assertEquals(2, mapper.adapterPositionForSourcePosition(2))
        assertEquals(listOf(1, 2), mapper.sourcePositionsInAdapterRange(1, 2))
    }

    @Test
    fun doublePageModeAddsBlankSlotAfterCoverAndMapsRemainingPages() {
        val mapper = ReaderPagePositionMapper(
            sourceItemCount = 5,
            doublePageReading = true,
            coverSinglePage = true
        )

        assertEquals(6, mapper.adapterItemCount)
        assertEquals(0, mapper.sourcePositionForAdapterPosition(0))
        assertNull(mapper.sourcePositionForAdapterPosition(1))
        assertEquals(1, mapper.sourcePositionForAdapterPosition(2))
        assertEquals(4, mapper.sourcePositionForAdapterPosition(5))
        assertEquals(2, mapper.adapterPositionForSourcePosition(1))
        assertEquals(listOf(0, 1, 2), mapper.sourcePositionsInAdapterRange(0, 4))
    }

    @Test
    fun doublePageModePadsAnOddUnpairedPageWhenCoverIsNotSingle() {
        val mapper = ReaderPagePositionMapper(
            sourceItemCount = 3,
            doublePageReading = true,
            coverSinglePage = false
        )

        assertEquals(4, mapper.adapterItemCount)
        assertEquals(2, mapper.sourcePositionForAdapterPosition(2))
        assertNull(mapper.sourcePositionForAdapterPosition(3))
    }

    @Test
    fun spreadStartMethodsKeepSourceProgressOnVisiblePageInCoverMode() {
        val mapper = ReaderPagePositionMapper(
            sourceItemCount = 5,
            doublePageReading = true,
            coverSinglePage = true
        )

        assertEquals(2, mapper.spreadStartAdapterPosition(3))
        assertEquals(4, mapper.spreadStartAdapterPosition(5))
        assertEquals(2, mapper.spreadStartAdapterPositionForSourcePosition(2))
        assertEquals(1, mapper.spreadStartSourcePositionForSourcePosition(2))
        assertEquals(3, mapper.spreadStartSourcePositionForSourcePosition(3))
    }

    @Test
    fun visibleDoublePageSpreadKeepsItsSourcePageBeforeLayoutModeChanges() {
        val mapper = ReaderPagePositionMapper(
            sourceItemCount = 5,
            doublePageReading = true,
            coverSinglePage = true
        )

        assertEquals(
            1,
            mapper.displayedSourcePositionForAdapterPosition(
                adapterPosition = 2,
                fallbackSourcePosition = 2
            )
        )
    }

    @Test
    fun doublePageSpreadCanStartAtTheSelectedSinglePage() {
        val expectedSpreadPages = listOf<Pair<Int, Int?>>(
            0 to 1,
            1 to 2,
            2 to 3,
            3 to 4,
            4 to 5,
            5 to null
        )

        for ((sourcePosition, nextSourcePosition) in expectedSpreadPages) {
            val mapper = ReaderPagePositionMapper(
                sourceItemCount = 6,
                doublePageReading = true,
                coverSinglePage = true,
                spreadAnchorSourcePosition = sourcePosition
            )
            val spreadStart = mapper.spreadStartAdapterPositionForSourcePosition(sourcePosition)

            assertEquals(sourcePosition, mapper.sourcePositionForAdapterPosition(spreadStart))
            assertEquals(nextSourcePosition, mapper.sourcePositionForAdapterPosition(spreadStart + 1))
            assertEquals(
                listOfNotNull(sourcePosition, nextSourcePosition),
                mapper.sourcePositionsInAdapterRange(spreadStart, 2)
            )
        }
    }

    @Test
    fun emptyAndOutOfRangePositionsAreSafe() {
        val emptyMapper = ReaderPagePositionMapper(
            sourceItemCount = -1,
            doublePageReading = true,
            coverSinglePage = true
        )
        val mapper = ReaderPagePositionMapper(
            sourceItemCount = 2,
            doublePageReading = false,
            coverSinglePage = false
        )

        assertEquals(0, emptyMapper.adapterItemCount)
        assertEquals(0, emptyMapper.spreadStartAdapterPosition(99))
        assertEquals(0, emptyMapper.spreadStartSourcePositionForSourcePosition(99))
        assertEquals(-1, mapper.adapterPositionForSourcePosition(-1))
        assertEquals(-1, mapper.adapterPositionForSourcePosition(2))
        assertEquals(emptyList<Int>(), mapper.sourcePositionsInAdapterRange(0, 0))
    }
}
