package com.example.comiclab

import java.util.Locale
import java.util.TimeZone
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

    @Test
    fun formatDateUsesDefaultLocaleAndTimeZoneForFixedInstants() {
        val originalLocale = Locale.getDefault()
        val originalTimeZone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.US)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            assertEquals("1970/01/01", CommonFunc.formatDate(0L))
            assertEquals("1969/12/31", CommonFunc.formatDate(-1L))
            assertEquals("1970/01/01", CommonFunc.formatDate(72_000_000L))

            TimeZone.setDefault(TimeZone.getTimeZone("GMT+08:00"))
            assertEquals("1970/01/01", CommonFunc.formatDate(0L))
            assertEquals("1970/01/01", CommonFunc.formatDate(-1L))
            assertEquals("1970/01/02", CommonFunc.formatDate(72_000_000L))
        } finally {
            Locale.setDefault(originalLocale)
            TimeZone.setDefault(originalTimeZone)
        }
    }

    @Test
    fun extractStartMarkerReturnsEmptyForEmptyAndBlankSource() {
        assertEquals("", CommonFunc.extractStartMarker("", "excluded"))
        assertEquals("", CommonFunc.extractStartMarker(" \t\r\n", "excluded"))
    }

    @Test
    fun extractStartMarkerReturnsEmptyForUnclosedBracket() {
        assertEquals("", CommonFunc.extractStartMarker("prefix[unfinished", "excluded"))
        assertEquals("", CommonFunc.extractStartMarker("[excluded][unfinished", "excluded"))
    }
}
