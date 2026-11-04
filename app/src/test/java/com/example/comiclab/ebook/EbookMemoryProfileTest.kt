package com.example.comiclab.ebook

import com.example.comiclab.ebook.html.EbookHtmlRenderer
import com.example.comiclab.ebook.html.EbookStyle
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 手动启用的同源样本比较；JVM堆数据不能代替Android/WebView进程内存。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EbookMemoryProfileTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun profilePreparationStages() {
        assumeTrue(System.getProperty("comiclab.ebook.profile") == "true")
        EbookHtmlRenderer().render(EbookBook("warmup", null, emptyList(), emptyList()), EbookStyle())
        listOf(txt(), epub(), mobi()).forEach { file ->
            System.gc()
            val baseline = heapUsed()
            val parsing = measure { EbookSessionFactory.open(file, "book", "chapter") }
            val session = parsing.value
            System.gc()
            val modelBytes = heapUsed() - baseline
            val modelChars = session.book.chapters.sumOf { it.html.length.toLong() }
            val rendering = measure { PreparedEbookDocument.prepare(session, folderCache()) }
            rendering.value.use { document ->
                System.gc()
                val retained = heapUsed() - baseline
                println("EBOOK_PROFILE format=${file.extension} fileBytes=${file.length()} chapters=${session.book.chapters.size} " +
                    "modelChars=$modelChars htmlChars=${document.htmlChars} retainedModelChars=${session.book.chapters.sumOf { it.html.length.toLong() }} " +
                    "parseMs=${parsing.millis} parseHeapDelta=${parsing.peak - baseline} modelHeapDelta=$modelBytes " +
                    "renderMs=${rendering.millis} renderHeapDelta=${rendering.peak - baseline} retainedHeapDelta=$retained")
                java.lang.ref.Reference.reachabilityFence(session)
                java.lang.ref.Reference.reachabilityFence(rendering.value)
            }
        }
    }

    private fun folderCache() = java.io.File(temporaryFolder.root, "cache").apply { mkdirs() }

    private data class Measurement<T>(val value: T, val peak: Long, val millis: Long)
    private fun <T> measure(action: () -> T): Measurement<T> {
        val running = AtomicBoolean(true)
        val peak = AtomicLong(heapUsed())
        val sampler = Thread {
            while (running.get()) {
                peak.accumulateAndGet(heapUsed(), ::maxOf)
                Thread.sleep(2)
            }
        }.apply { isDaemon = true; start() }
        val started = System.nanoTime()
        try {
            val value = action()
            peak.accumulateAndGet(heapUsed(), ::maxOf)
            return Measurement(value, peak.get(), (System.nanoTime() - started) / 1_000_000)
        } finally { running.set(false); sampler.join() }
    }
    private fun heapUsed() = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
    private fun txt() = temporaryFolder.newFile("large.txt").apply {
        bufferedWriter().use { out -> repeat(140000) { out.write("正文内容<&\"。This is a long paragraph.\n") } }
    }
    private fun epub() = temporaryFolder.newFile("many-chapters.epub").apply {
        ZipOutputStream(outputStream()).use { zip ->
            fun entry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip")
            entry("META-INF/container.xml", """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            val manifest = (0 until 240).joinToString("") { "<item id='c$it' href='c$it.xhtml' media-type='application/xhtml+xml'/>" }
            val spine = (0 until 240).joinToString("") { "<itemref idref='c$it'/>" }
            entry("book.opf", """<package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Profile</dc:title></metadata><manifest>$manifest</manifest><spine>$spine</spine></package>""")
            val chapter = "<html><body><p>" + "正文内容。This is a long paragraph. ".repeat(1000) + "</p></body></html>"
            repeat(240) { entry("c$it.xhtml", chapter) }
        }
    }
    private fun mobi() = temporaryFolder.newFile("large.mobi").apply {
        val unit = "<p>正文内容。Text.</p>\n".toByteArray()
        val record = ByteArray(unit.size * 256) { unit[it % unit.size] }
        val records = 800
        val header = java.nio.ByteBuffer.allocate(280).apply {
            putShort(0, 1); putInt(4, record.size * records); putShort(8, records.toShort())
            position(16); put("MOBI".toByteArray()); putInt(20, 264); putInt(28, 65001); putInt(36, 5); putInt(108, -1)
        }.array()
        DataOutputStream(outputStream().buffered()).use { out ->
            val palm = java.nio.ByteBuffer.allocate(78).apply { putShort(76, (records + 1).toShort()) }.array()
            out.write(palm)
            var offset = 78 + (records + 1) * 8
            out.writeInt(offset); out.writeInt(0); offset += header.size
            repeat(records) { out.writeInt(offset); out.writeInt(0); offset += record.size }
            out.write(header); repeat(records) { out.write(record) }
        }
    }
}
