package com.example.comiclab.ui.reader.manga

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderImageEnhancementTest {

    @Test
    fun enhancementIsRestrictedToEnabledArchiveImages() {
        assertTrue(ReaderImageEnhancement.shouldApply(enabled = true, isPdfSource = false))
        assertFalse(ReaderImageEnhancement.shouldApply(enabled = false, isPdfSource = false))
        assertFalse(ReaderImageEnhancement.shouldApply(enabled = true, isPdfSource = true))
    }
}
