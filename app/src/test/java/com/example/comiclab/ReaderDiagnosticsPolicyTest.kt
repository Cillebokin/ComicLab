package com.example.comiclab

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderDiagnosticsPolicyTest {

    @Test
    fun checkpointSnapshotIncludesFileIdentityAndPosition() {
        assertEquals(
            "file=C:/books/01-05.pdf size=123456 modified=789 page=8 position=7 offset=42",
            formatReaderCheckpoint(
                filePath = "C:/books/01-05.pdf",
                fileSize = 123456L,
                fileModified = 789L,
                position = 7,
                offset = 42
            )
        )
    }

    @Test
    fun renderSnapshotIncludesDecodeAndHeapContext() {
        assertEquals(
            "file=C:/books/01-05.pdf size=123456 modified=789 page=8 position=7 " +
                "kind=FULL target=2160x3840 bitmapBytes=33177600 usedHeapKb=512000 maxHeapKb=1048576",
            formatReaderRender(
                filePath = "C:/books/01-05.pdf",
                fileSize = 123456L,
                fileModified = 789L,
                position = 7,
                kind = "FULL",
                targetWidth = 2160,
                targetHeight = 3840,
                bitmapBytes = 33177600,
                usedHeapKb = 512000,
                maxHeapKb = 1048576
            )
        )
    }

    @Test
    fun activeReaderSessionMarkerCanBeRecognizedAfterProcessDeath() {
        val marker = formatReaderSessionMarker(
            filePath = "C:/books/01-05.pdf",
            fileSize = 123456L,
            fileModified = 789L
        )

        assertEquals(
            "state=active file=C:/books/01-05.pdf size=123456 modified=789",
            marker
        )
        assertEquals(true, isReaderSessionMarkerActive(marker))
        assertEquals(false, isReaderSessionMarkerActive("state=closed"))
        assertEquals(false, isReaderSessionMarkerActive(null))
    }

    @Test
    fun oldReaderSessionCannotFinishNewReaderSessionMarker() {
        val oldMarker = "state=active file=C:/books/old.pdf size=1 modified=2 token=old"
        val newMarker = "state=active file=C:/books/new.pdf size=3 modified=4 token=new"

        assertEquals(false, isSameReaderSessionMarker(newMarker, oldMarker))
        assertEquals(true, isSameReaderSessionMarker(newMarker, newMarker))
    }
}
