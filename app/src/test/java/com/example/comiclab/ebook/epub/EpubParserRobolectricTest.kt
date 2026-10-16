package com.example.comiclab.ebook.epub

import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EpubParserRobolectricTest {

    @Test
    fun parsesMetadataNavigationChaptersStylesheetAndImageResources() {
        val root = Files.createTempDirectory("comiclab-epub-valid").toFile()
        try {
            val file = File(root, "sample.epub")
            writeEpub(file)

            val parsed = EpubParser().parse(file, untitledChapterTitle = "Untitled")

            assertEquals("A & B", parsed.book.title)
            assertEquals("Author", parsed.book.author)
            assertEquals(2, parsed.book.chapters.size)
            assertEquals("Navigation title", parsed.book.chapters[0].title)
            assertEquals("EPUB/Text/chapter1.xhtml", parsed.book.chapters[0].sourcePath)
            assertTrue(parsed.book.chapters[0].html.contains("<p>First page</p>"))
            assertEquals("Chapter heading", parsed.book.chapters[1].title)
            assertEquals("EPUB/Text/chapter2.xhtml", parsed.book.chapters[1].sourcePath)
            assertEquals("p { color: red; }", parsed.book.stylesheet?.trim())
            assertEquals(2, parsed.book.resources.size)
            assertEquals(parsed.book.resources.first().id, parsed.book.coverResourceId)
            assertEquals("EPUB/Images/cover.png", parsed.resourceRecords[parsed.book.coverResourceId]?.path)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun readsNcxTitlesWhenPackageUsesSpineTocFallback() {
        val root = Files.createTempDirectory("comiclab-epub-ncx").toFile()
        try {
            val file = File(root, "ncx.epub")
            val opf = packageXml()
                .replace(" properties=\"nav\"", "")
                .replace("<spine>", "<spine toc=\"ncx\">")
                .replace(
                    "</manifest>",
                    "<item id=\"ncx\" href=\"Text/toc.ncx\" media-type=\"application/x-dtbncx+xml\"/></manifest>"
                )
            writeEpub(
                file,
                opf = opf,
                extraEntries = mapOf(
                    "EPUB/Text/toc.ncx" to """
                        <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/">
                          <navMap><navPoint id="one"><navLabel><text>NCX chapter title</text></navLabel>
                            <content src="chapter1.xhtml#start"/></navPoint></navMap>
                        </ncx>
                    """.trimIndent()
                )
            )

            val parsed = EpubParser().parse(file)

            assertEquals("NCX chapter title", parsed.book.chapters.first().title)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun excludesNonLinearSpineItemsWhenLinearChaptersExist() {
        val root = Files.createTempDirectory("comiclab-epub-linear").toFile()
        try {
            val file = File(root, "linear.epub")
            val opf = packageXml().replace(
                "<itemref idref=\"chapter1\"/>",
                "<itemref idref=\"chapter1\" linear=\"no\"/>"
            )
            writeEpub(file, opf = opf)

            val parsed = EpubParser().parse(file)

            assertEquals(listOf("EPUB/Text/chapter2.xhtml"), parsed.book.chapters.map { it.sourcePath })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun fallsBackToAllSpineItemsWhenEveryItemIsNonLinear() {
        val root = Files.createTempDirectory("comiclab-epub-all-nonlinear").toFile()
        try {
            val file = File(root, "all-nonlinear.epub")
            val opf = packageXml()
                .replace("<itemref idref=\"chapter1\"/>", "<itemref idref=\"chapter1\" linear=\"no\"/>")
                .replace("<itemref idref=\"chapter2\"/>", "<itemref idref=\"chapter2\" linear=\"no\"/>")
            writeEpub(file, opf = opf)

            val parsed = EpubParser().parse(file)

            assertEquals(2, parsed.book.chapters.size)
            assertEquals(listOf(0, 1), parsed.book.chapters.map { it.index })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sessionOpensOnlyKnownResourcesAndClosingIsIdempotent() {
        val root = Files.createTempDirectory("comiclab-epub-session").toFile()
        try {
            val file = File(root, "sample.epub")
            writeEpub(file)
            val session = EpubBookSession.open(file, "Untitled")
            val coverId = session.book.coverResourceId
                ?: throw AssertionError("Expected a cover resource")

            assertEquals(byteArrayOf(1, 2, 3).toList(), session.openResource(coverId)?.use { it.readBytes().toList() })
            assertNull(session.openResource("not-a-resource"))

            session.close()
            session.close()
            assertNull(session.openResource(coverId))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsManifestReferencesThatEscapeArchiveRoot() {
        val root = Files.createTempDirectory("comiclab-epub-path-safety").toFile()
        try {
            val file = File(root, "unsafe.epub")
            val unsafePackage = packageXml()
                .replace("Text/chapter2.xhtml", "../../outside.xhtml")
                .replace("Images/inline.png", "../../outside.png")
            writeEpub(file, opf = unsafePackage)

            val parsed = EpubParser().parse(file)

            assertEquals(1, parsed.book.chapters.size)
            assertEquals("EPUB/Text/chapter1.xhtml", parsed.book.chapters.single().sourcePath)
            assertEquals(listOf("EPUB/Images/cover.png"), parsed.book.resources.map { it.path })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun malformedContainerAndPackageXmlAreReportedAsTypedParseErrors() {
        val root = Files.createTempDirectory("comiclab-epub-malformed-xml").toFile()
        try {
            val malformedContainer = File(root, "container.epub")
            writeEpub(malformedContainer, container = "<container><rootfiles>")
            assertParseError(EpubParseError.INVALID_CONTAINER, malformedContainer)

            val malformedPackage = File(root, "package.epub")
            writeEpub(malformedPackage, opf = "<package><metadata>")
            assertParseError(EpubParseError.INVALID_PACKAGE, malformedPackage)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun assertParseError(expected: EpubParseError, file: File) {
        try {
            EpubParser().parse(file)
            fail("Expected ${expected.name} for ${file.name}")
        } catch (error: EpubParseException) {
            assertEquals(expected, error.reason)
        }
    }

    @Test
    fun opensBookWhenEncryptionDeclaresOnlyFontObfuscation() {
        val root = Files.createTempDirectory("comiclab-epub-font-obfuscation").toFile()
        try {
            val file = File(root, "obfuscated-font.epub")
            writeEpub(
                file,
                extraEntries = mapOf(
                    "META-INF/encryption.xml" to encryptionXml(
                        "http://www.idpf.org/2008/embedding",
                        "http://ns.adobe.com/pdf/enc#RC"
                    )
                )
            )

            val parsed = EpubParser().parse(file, untitledChapterTitle = "Untitled")

            assertEquals(2, parsed.book.chapters.size)
            assertTrue(parsed.book.chapters[0].html.contains("<p>First page</p>"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsBookWhenEncryptionUsesRealCipher() {
        val root = Files.createTempDirectory("comiclab-epub-real-encryption").toFile()
        try {
            val file = File(root, "drm.epub")
            writeEpub(
                file,
                extraEntries = mapOf(
                    "META-INF/encryption.xml" to encryptionXml("http://www.w3.org/2001/04/xmlenc#aes128-cbc")
                )
            )

            assertParseError(EpubParseError.ENCRYPTED, file)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsBookWhenFontObfuscationIsMixedWithRealCipher() {
        val root = Files.createTempDirectory("comiclab-epub-mixed-encryption").toFile()
        try {
            val file = File(root, "mixed.epub")
            writeEpub(
                file,
                extraEntries = mapOf(
                    "META-INF/encryption.xml" to encryptionXml(
                        "http://www.idpf.org/2008/embedding",
                        "http://www.w3.org/2001/04/xmlenc#aes256-cbc"
                    )
                )
            )

            assertParseError(EpubParseError.ENCRYPTED, file)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsBookWhenEncryptionXmlIsUnreadableOrDeclaresNothing() {
        val root = Files.createTempDirectory("comiclab-epub-unknown-encryption").toFile()
        try {
            val truncated = File(root, "truncated.epub")
            writeEpub(
                truncated,
                extraEntries = mapOf("META-INF/encryption.xml" to "<encryption><EncryptedData>")
            )
            val empty = File(root, "empty.epub")
            writeEpub(
                empty,
                extraEntries = mapOf("META-INF/encryption.xml" to "<encryption/>")
            )

            assertParseError(EpubParseError.ENCRYPTED, truncated)
            assertParseError(EpubParseError.ENCRYPTED, empty)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun encryptionXml(vararg algorithms: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        append("<encryption xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\" ")
        append("xmlns:enc=\"http://www.w3.org/2001/04/xmlenc#\">")
        algorithms.forEachIndexed { index, algorithm ->
            append("<enc:EncryptedData>")
            append("<enc:EncryptionMethod Algorithm=\"$algorithm\"/>")
            append("<enc:CipherData><enc:CipherReference URI=\"EPUB/Fonts/font$index.otf\"/></enc:CipherData>")
            append("</enc:EncryptedData>")
        }
        append("</encryption>")
    }

    private fun writeEpub(
        file: File,
        container: String = CONTAINER_XML,
        opf: String = packageXml(),
        extraEntries: Map<String, String> = emptyMap()
    ) {
        val entries = linkedMapOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to container,
            "EPUB/package.opf" to opf,
            "EPUB/Text/nav.xhtml" to """
                <html xmlns="http://www.w3.org/1999/xhtml"><body>
                  <nav><ol>
                    <li><a href="chapter1.xhtml#start">Navigation title</a></li>
                    <li><a href="https://example.invalid/external.xhtml">External</a></li>
                  </ol></nav>
                </body></html>
            """.trimIndent(),
            "EPUB/Text/chapter1.xhtml" to """
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Document title</title></head>
                  <body><h1>Ignored in favor of nav</h1><p>First page</p></body>
                </html>
            """.trimIndent(),
            "EPUB/Text/chapter2.xhtml" to """
                <html xmlns="http://www.w3.org/1999/xhtml"><body><h2>Chapter heading</h2><p>Second page</p></body></html>
            """.trimIndent(),
            "EPUB/Styles/book.css" to "p { color: red; }"
        )
        entries.putAll(extraEntries)
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, content) ->
                val bytes = content.toByteArray(Charsets.UTF_8)
                val entry = ZipEntry(name)
                if (name == "mimetype") {
                    val crc = CRC32().apply { update(bytes) }
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = crc.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("EPUB/Images/cover.png"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("EPUB/Images/inline.png"))
            zip.write(byteArrayOf(4, 5, 6))
            zip.closeEntry()
        }
    }

    private fun packageXml(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:title>A &amp; B</dc:title><dc:creator>Author</dc:creator>
          </metadata>
          <manifest>
            <item id="nav" href="Text/nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
            <item id="chapter1" href="Text/chapter1.xhtml" media-type="application/xhtml+xml"/>
            <item id="chapter2" href="Text/chapter2.xhtml" media-type="application/xhtml+xml"/>
            <item id="css" href="Styles/book.css" media-type="text/css"/>
            <item id="cover" href="Images/cover.png" media-type="image/png" properties="cover-image"/>
            <item id="inline" href="Images/inline.png" media-type="image/png"/>
          </manifest>
          <spine>
            <itemref idref="chapter1"/><itemref idref="chapter2"/>
          </spine>
        </package>
    """.trimIndent()

    private companion object {
        val CONTAINER_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
              <rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>
        """.trimIndent()
    }
}
