package com.example.comiclab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookcaseRowViewTypesTest {

    @Test
    fun regularAndBookcaseRowsUseDistinctReusableViewTypes() {
        val regularType = BookcaseRowViewTypes.forBookcase(isBookcase = false)
        val bookcaseType = BookcaseRowViewTypes.forBookcase(isBookcase = true)

        assertTrue(regularType != bookcaseType)
        assertTrue(regularType in 0 until BookcaseRowViewTypes.COUNT)
        assertTrue(bookcaseType in 0 until BookcaseRowViewTypes.COUNT)
        assertEquals(2, BookcaseRowViewTypes.COUNT)
    }
}
