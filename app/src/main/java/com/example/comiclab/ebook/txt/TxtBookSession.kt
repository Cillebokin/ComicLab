package com.example.comiclab.ebook.txt

import com.example.comiclab.ebook.EbookBook
import com.example.comiclab.ebook.EbookChapter
import com.example.comiclab.ebook.EbookSession
import com.example.comiclab.ebook.EbookContentLimits
import com.example.comiclab.ebook.withoutChapterContent
import com.example.comiclab.ebook.checkEbookCancellation
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

class TxtBookSession private constructor(
    book: EbookBook
) : EbookSession {

    @Volatile
    override var book = book
        private set

    override fun releaseChapterContent() { book = book.withoutChapterContent() }

    override fun openResource(resourceId: String): InputStream? = null

    override fun close() = Unit

    companion object {
        fun open(
            file: File,
            untitledBookTitle: String,
            untitledChapterTitle: String,
            limits: EbookContentLimits = EbookContentLimits.forHeap()
        ): TxtBookSession {
            checkEbookCancellation()
            val title = file.nameWithoutExtension.takeIf(String::isNotBlank) ?: untitledBookTitle
            val html = readHtml(file, limits)
            val chapterTitle = title.ifBlank { untitledChapterTitle }
            val chapters = if (html.isEmpty()) {
                emptyList()
            } else {
                listOf(
                    EbookChapter(
                        index = 0,
                        title = chapterTitle,
                        html = html
                    )
                )
            }
            return TxtBookSession(
                EbookBook(
                    title = title,
                    author = null,
                    chapters = chapters,
                    resources = emptyList()
                )
            )
        }

        private fun readHtml(file: File, limits: EbookContentLimits): String {
            val bom = detectBom(file)
            if (bom != null) {
                return decode(file, bom.charset, bom.length, limits)
            }

            return try {
                decode(file, StandardCharsets.UTF_8, 0, limits)
            } catch (_: CharacterCodingException) {
                decode(file, Charset.forName("GB18030"), 0, limits)
            }
        }

        private fun detectBom(file: File): Bom? {
            val bytes = ByteArray(3)
            var count = 0
            FileInputStream(file).use { input ->
                while (count < bytes.size) {
                    val read = input.read(bytes, count, bytes.size - count)
                    if (read < 0) break
                    count += read
                }
            }

            return when {
                count >= 3 &&
                    bytes[0] == 0xEF.toByte() &&
                    bytes[1] == 0xBB.toByte() &&
                    bytes[2] == 0xBF.toByte() -> Bom(StandardCharsets.UTF_8, 3)

                count >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                    Bom(StandardCharsets.UTF_16LE, 2)

                count >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                    Bom(StandardCharsets.UTF_16BE, 2)

                else -> null
            }
        }

        private fun decode(file: File, charset: Charset, bomLength: Int, limits: EbookContentLimits): String {
            FileInputStream(file).use { input ->
                repeat(bomLength) { if (input.read() < 0) return "" }
                val decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                return BufferedReader(InputStreamReader(input, decoder)).use { reader ->
                    val output = StringBuilder()
                    val buffer = CharArray(8192)
                    var rawChars = 0L
                    var hasText = false
                    var previousWasCr = false
                    fun appendPlain(start: Int, end: Int) {
                        limits.checkBookChars(output.length.toLong() + end - start)
                        output.append(buffer, start, end - start)
                    }
                    while (true) {
                        checkEbookCancellation()
                        val count = reader.read(buffer)
                        if (count < 0) break
                        rawChars += count
                        limits.checkBookChars(rawChars)
                        var plainStart = 0
                        for (index in 0 until count) {
                            val character = buffer[index]
                            if (!hasText && !character.isWhitespace()) hasText = true
                            val replacement = when (character) {
                                '\r' -> "<br>\n"
                                '\n' -> if (previousWasCr) "" else "<br>\n"
                                '&' -> "&amp;"
                                '<' -> "&lt;"
                                '>' -> "&gt;"
                                '"' -> "&quot;"
                                '\'' -> "&#39;"
                                else -> null
                            }
                            if (replacement != null) {
                                appendPlain(plainStart, index)
                                limits.checkBookChars(output.length.toLong() + replacement.length)
                                output.append(replacement)
                                plainStart = index + 1
                            }
                            previousWasCr = character == '\r'
                        }
                        appendPlain(plainStart, count)
                    }
                    if (hasText) output.toString() else ""
                }
            }
        }

        private data class Bom(val charset: Charset, val length: Int)
    }
}
