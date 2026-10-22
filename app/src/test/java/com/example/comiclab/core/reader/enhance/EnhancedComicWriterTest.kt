package com.example.comiclab.core.reader.enhance

import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EnhancedComicWriterTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun writesEveryPageOnceInReadingOrderWithoutRecompressingPng() {
        val output = File(temp.root, "result.cbz")
        val calls = mutableListOf<String>()
        EnhancedComicWriter.write(output, listOf("cover.jpg", "page2.webp", "page10.png"), AtomicBoolean(),
            render = { index, name ->
                calls += name
                temp.newFile("page$index.png").apply { writeText("enhanced-$name") }
            }, onPage = { _, _ -> })
        assertEquals(listOf("cover.jpg", "page2.webp", "page10.png"), calls)
        ZipFile(output).use { zip ->
            val entries = zip.entries().toList()
            assertEquals(listOf("000001.png", "000002.png", "000003.png"), entries.map { it.name })
            assertTrue(entries.all { it.method == java.util.zip.ZipEntry.STORED })
            assertEquals("enhanced-page10.png", zip.getInputStream(entries[2]).bufferedReader().readText())
        }
    }

    @Test fun cancelledBookDoesNotLeaveAPartialArchive() {
        val output = File(temp.root, "result.cbz")
        val cancelled = AtomicBoolean()
        assertThrows(CancellationException::class.java) {
            EnhancedComicWriter.write(output, listOf("a", "b"), cancelled,
                render = { _, _ -> temp.newFile().apply { writeText("page") } },
                onPage = { _, _ -> cancelled.set(true) })
        }
        assertFalse(output.exists())
    }

    @Test fun failedPageCannotPublishAnIncompleteBook() {
        val output = File(temp.root, "result.cbz")
        assertThrows(IllegalStateException::class.java) {
            EnhancedComicWriter.write(output, listOf("a", "b"), AtomicBoolean(),
                render = { index, _ ->
                    if (index == 1) error("unreadable page")
                    temp.newFile().apply { writeText("page") }
                }, onPage = { _, _ -> })
        }
        assertFalse(output.exists())
    }

    @Test fun existingTargetIsNeverTruncated() {
        val output = temp.newFile().apply { writeText("keep") }
        assertThrows(IllegalArgumentException::class.java) {
            EnhancedComicWriter.write(output, listOf("a"), AtomicBoolean(),
                render = { _, _ -> error("must not render") }, onPage = { _, _ -> })
        }
        assertEquals("keep", output.readText())
    }
}
