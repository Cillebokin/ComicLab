package com.example.comiclab.core.reader.enhance

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException
import java.util.zip.InflaterInputStream
import java.util.zip.CRC32
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SuperResolutionFilesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun chosenScaleControlsDimensionsAndOutputLimit() {
        for (scale in 2..4) {
            val plan = EnhancementPlan(259, 131, scale = scale)
            assertEquals(259 * scale, plan.outputWidth)
            assertEquals(131 * scale, plan.outputHeight)
            assertThrows(IllegalArgumentException::class.java) { EnhancementPlan(16384, 2, scale = scale + 2) }
        }
        assertThrows(IllegalArgumentException::class.java) { EnhancementPlan(2000, 2000, scale = 4) }
        assertThrows(IllegalArgumentException::class.java) { EnhancementPlan(12000, 1, scale = 3) }
    }

    @Test fun tilesCoverOddSizedPageExactlyOnceAndKeepContextInsideSource() {
        val coverage = IntArray(259 * 131)
        val plan = EnhancementPlan(259, 131)
        for (tile in plan.tiles()) {
            assertTrue(tile.inputLeft >= 0 && tile.inputTop >= 0)
            assertTrue(tile.inputRight <= 259 && tile.inputBottom <= 131)
            assertTrue(tile.inputLeft <= tile.left && tile.inputTop <= tile.top)
            for (y in tile.top until tile.bottom) for (x in tile.left until tile.right) {
                coverage[y * 259 + x]++
            }
        }
        assertTrue(coverage.all { it == 1 })
        assertEquals(518, plan.outputWidth)
        assertEquals(262, plan.outputHeight)
    }

    @Test fun excessiveOrInvalidDimensionsAreRejectedBeforeAllocatingFiles() {
        for ((w, h) in listOf(0 to 5, -1 to 2, Int.MAX_VALUE to 2, 10000 to 10000)) {
            assertThrows(IllegalArgumentException::class.java) { EnhancementPlan(w, h) }
        }
    }

    @Test fun pngStreamsExactRgbRowsWithValidChunkChecksums() {
        val raw = temp.newFile()
        val rgb = byteArrayOf(-1, 0, 0, 0, -1, 0, 0, 0, -1, -1, -1, -1)
        raw.writeBytes(rgb)
        val png = temp.newFile()
        StreamingRgbPng.write(raw, png, 2, 2) {}
        val compressed = ByteArrayOutputStream()
        DataInputStream(png.inputStream()).use { input ->
            assertEquals(-8552249625308161526L, input.readLong())
            while (input.available() > 0) {
                val size = input.readInt()
                val type = ByteArray(4).also(input::readFully)
                val bytes = ByteArray(size).also(input::readFully)
                val crc = CRC32().apply { update(type); update(bytes) }.value.toInt()
                assertEquals(crc, input.readInt())
                if (String(type, Charsets.US_ASCII) == "IDAT") compressed.write(bytes)
            }
        }
        val rows = InflaterInputStream(compressed.toByteArray().inputStream()).readBytes()
        assertArrayEquals(byteArrayOf(0, -1, 0, 0, 0, -1, 0, 0, 0, 0, -1, -1, -1, -1), rows)
    }

    @Test fun cancellationDuringPngEncodingStopsBeforePublishing() {
        val raw = temp.newFile().apply { writeBytes(ByteArray(30)) }
        val png = temp.newFile()
        assertThrows(CancellationException::class.java) {
            StreamingRgbPng.write(raw, png, 2, 5) { throw CancellationException() }
        }
    }
}
