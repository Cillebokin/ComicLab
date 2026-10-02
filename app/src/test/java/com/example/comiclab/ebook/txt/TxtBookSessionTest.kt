package com.example.comiclab.ebook.txt

import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import com.example.comiclab.ebook.EbookSessionFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TxtBookSessionTest {

    @Test
    fun openReadsUtf8AndEscapesHtmlCharactersAndLineEndings() {
        val root = Files.createTempDirectory("comiclab-txt-utf8").toFile()
        try {
            val file = File(root, "sample.txt")
            file.writeBytes(
                "A&B <C>\r\nD\rE\n\"'".toByteArray(StandardCharsets.UTF_8)
            )

            val session = TxtBookSession.open(file, "untitled book", "untitled chapter")

            assertEquals("sample", session.book.title)
            assertEquals(
                "A&amp;B &lt;C&gt;<br>\nD<br>\nE<br>\n&quot;&#39;",
                session.book.chapters.single().html
            )
            assertEquals(0, session.book.chapters.single().index)
            assertNull(session.openResource("anything"))
            session.close()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun openDetectsUtf8AndUtf16ByteOrderMarks() {
        val root = Files.createTempDirectory("comiclab-txt-bom").toFile()
        try {
            val utf8 = File(root, "utf8.txt").apply {
                writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "本文".toByteArray())
            }
            val utf16le = File(root, "utf16le.txt").apply {
                writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "左".toByteArray(StandardCharsets.UTF_16LE))
            }
            val utf16be = File(root, "utf16be.txt").apply {
                writeBytes(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "右".toByteArray(StandardCharsets.UTF_16BE))
            }

            assertEquals("本文", TxtBookSession.open(utf8, "book", "chapter").book.chapters.single().html)
            assertEquals("左", TxtBookSession.open(utf16le, "book", "chapter").book.chapters.single().html)
            assertEquals("右", TxtBookSession.open(utf16be, "book", "chapter").book.chapters.single().html)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun openFallsBackToGb18030WhenUtf8IsMalformed() {
        val root = Files.createTempDirectory("comiclab-txt-gb18030").toFile()
        try {
            val file = File(root, "gb.txt")
            file.writeBytes("中文内容".toByteArray(Charset.forName("GB18030")))

            val session = TxtBookSession.open(file, "book", "chapter")

            assertEquals("中文内容", session.book.chapters.single().html)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun openReturnsNoChapterForWhitespaceOnlyText() {
        val root = Files.createTempDirectory("comiclab-txt-empty").toFile()
        try {
            val file = File(root, "blank.txt").apply { writeText(" \r\n\t") }

            val session = TxtBookSession.open(file, "book", "chapter")

            assertTrue(session.book.chapters.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sessionFactoryRoutesTxtFilesToTxtSession() {
        val root = Files.createTempDirectory("comiclab-txt-session-factory").toFile()
        try {
            val file = File(root, "factory.txt").apply { writeText("factory content") }

            val session = EbookSessionFactory.open(file, "book", "chapter")

            assertEquals("factory", session.book.title)
            assertEquals("factory content", session.book.chapters.single().html)
        } finally {
            root.deleteRecursively()
        }
    }
}
