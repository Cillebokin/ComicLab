package com.example.comiclab

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class CommonFuncTest {

    @Test
    fun formatFileSizeHandlesNonPositiveValuesAndUnitBoundaries() {
        val originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            assertEquals("0 B", CommonFunc.formatFileSize(-1))
            assertEquals("0 B", CommonFunc.formatFileSize(0))
            assertEquals("1023 B", CommonFunc.formatFileSize(1023))
            assertEquals("1.0 KB", CommonFunc.formatFileSize(1024))
            assertEquals("1.0 MB", CommonFunc.formatFileSize(1024L * 1024))
            assertEquals("1.0 GB", CommonFunc.formatFileSize(1024L * 1024 * 1024))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }
}
