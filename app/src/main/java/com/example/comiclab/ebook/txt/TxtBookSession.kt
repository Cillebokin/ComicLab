package com.example.comiclab.ebook.txt

import com.example.comiclab.ebook.EbookBook
import com.example.comiclab.ebook.EbookChapter
import com.example.comiclab.ebook.EbookSession
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
    override val book: EbookBook
) : EbookSession {

    override fun openResource(resourceId: String): InputStream? = null

    override fun close() = Unit

    companion object {
        fun open(
            file: File,
            untitledBookTitle: String,
            untitledChapterTitle: String
        ): TxtBookSession {
            val title = file.nameWithoutExtension.takeIf(String::isNotBlank) ?: untitledBookTitle
            val text = readText(file)
            val chapterTitle = title.ifBlank { untitledChapterTitle }
            val chapters = if (text.isBlank()) {
                emptyList()
            } else {
                listOf(
                    EbookChapter(
                        index = 0,
                        title = chapterTitle,
                        html = textToHtml(text)
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

        private fun readText(file: File): String {
            val bom = detectBom(file)
            if (bom != null) {
                return decode(file, bom.charset, bom.length)
            }

            return try {
                decode(file, StandardCharsets.UTF_8, 0)
            } catch (_: CharacterCodingException) {
                decode(file, Charset.forName("GB18030"), 0)
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

        private fun decode(file: File, charset: Charset, bomLength: Int): String {
            FileInputStream(file).use { input ->
                repeat(bomLength) {
                    if (input.read() < 0) return ""
                }
                val decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                return BufferedReader(InputStreamReader(input, decoder)).use { it.readText() }
            }
        }

        private fun textToHtml(text: String): String {
            val html = StringBuilder(text.length)
            var index = 0
            while (index < text.length) {
                when (val character = text[index++]) {
                    '\r' -> {
                        if (text.getOrNull(index) == '\n') index++
                        html.append("<br>\n")
                    }
                    '\n' -> html.append("<br>\n")
                    '&' -> html.append("&amp;")
                    '<' -> html.append("&lt;")
                    '>' -> html.append("&gt;")
                    '"' -> html.append("&quot;")
                    '\'' -> html.append("&#39;")
                    else -> html.append(character)
                }
            }
            return html.toString()
        }

        private data class Bom(val charset: Charset, val length: Int)
    }
}
