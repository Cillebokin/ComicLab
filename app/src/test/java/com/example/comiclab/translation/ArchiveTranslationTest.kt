package com.example.comiclab.translation

import com.example.comiclab.core.reader.translation.ArchiveTranslation
import com.example.comiclab.core.reader.translation.ArchiveTranslationPagesFailedException
import com.example.comiclab.core.reader.translation.NoReadableTranslationTextException
import com.example.comiclab.core.reader.translation.TranslationServiceException
import com.example.comiclab.core.reader.translation.TranslationServiceException.Reason
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchiveTranslationTest {
    @get:Rule val folder = TemporaryFolder()
    private val output get() = File(folder.root, "translated.cbz")
    private val cancelled = AtomicBoolean()
    private val attempts = mutableListOf<Int>()
    private val stages = mutableListOf<String>()

    @Test fun definitiveServiceFailuresStopAfterOneAttemptAndDiscardTheOutput() {
        for (reason in listOf(Reason.AUTHENTICATION, Reason.MODEL_OR_ENDPOINT)) {
            attempts.clear()
            val failure = assertThrows(TranslationServiceException::class.java) {
                runBook { throw TranslationServiceException(reason) }
            }
            assertEquals(reason, failure.reason)
            assertEquals(listOf(0), attempts)
            assertFalse(output.exists())
        }
    }

    @Test fun repeatedServiceFailuresStopWithoutAZeroPageExport() {
        val failure = assertThrows(TranslationServiceException::class.java) {
            runBook { throw TranslationServiceException(Reason.UNAVAILABLE) }
        }
        assertEquals(Reason.UNAVAILABLE, failure.reason)
        assertEquals(listOf(0, 1), attempts)
        assertFalse(output.exists())
    }

    @Test fun serviceOutageAfterPartialSuccessKeepsRemainingPagesWithoutMoreRequests() {
        val result = runBook { index ->
            if (index > 0) throw TranslationServiceException(Reason.UNAVAILABLE)
        }
        assertEquals(listOf(0, 1, 2), attempts)
        assertEquals(Reason.UNAVAILABLE, result.serviceStopped)
        assertEquals(1, result.translatedPages)
        assertEquals(3, result.keptOriginalPages)
        assertEquals(listOf("translated-0", "original-1", "original-2", "original-3"), savedPages())
    }

    @Test fun transientFailureAndLocalPageOutcomesDoNotPreventLaterTranslation() {
        val result = runBook { index ->
            when (index) {
                0 -> throw TranslationServiceException(Reason.UNAVAILABLE)
                1 -> throw NoReadableTranslationTextException()
                2 -> error("Processing failed for this page")
            }
        }
        assertEquals(listOf(0, 1, 2, 3), attempts)
        assertNull(result.serviceStopped)
        assertEquals(1, result.translatedPages)
        assertEquals(3, result.keptOriginalPages)
        assertEquals(1, result.pagesWithoutText)
        assertEquals(1, stages.count { it == "no_text" })
        assertEquals(listOf("original-0", "original-1", "original-2", "translated-3"), savedPages())
    }

    @Test fun allBlankAndAllFailedPagesProduceDifferentOutcomesAndNoExport() {
        assertThrows(NoReadableTranslationTextException::class.java) {
            runBook { throw NoReadableTranslationTextException() }
        }
        assertFalse(output.exists())
        assertThrows(ArchiveTranslationPagesFailedException::class.java) {
            runBook { error("Processing failed for this page") }
        }
        assertFalse(output.exists())
    }

    @Test fun cancellationAfterACompletedPageDiscardsThePartialArchive() {
        assertThrows(CancellationException::class.java) {
            runBook { index -> if (index == 1) cancelled.set(true) }
        }
        assertEquals(listOf(0, 1), attempts)
        assertFalse(output.exists())
    }

    private fun runBook(translate: (index: Int) -> Unit) = ArchiveTranslation.translatePages(
        output = output,
        entries = List(4) { "$it.png" },
        cancelled = cancelled,
        extractPage = { index, entry -> File(folder.root, entry).apply { writeText("original-$index") } },
        translatePage = { index, _, _, _ ->
            attempts += index
            translate(index)
            File(folder.root, "render-$index.png").apply { writeText("translated-$index") }
        },
        onProgress = { _, _, _, stage, _, _ -> stages += stage }
    )

    private fun savedPages() = ZipFile(output).use { zip ->
        zip.entries().asSequence().map { zip.getInputStream(it).bufferedReader().use { reader -> reader.readText() } }.toList()
    }
}
