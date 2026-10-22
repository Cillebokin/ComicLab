package com.example.comiclab.core.reader.enhance

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object EnhancedComicWriter {
    fun write(output: File, entries: List<String>, cancelled: AtomicBoolean,
        render: (Int, String) -> File, onPage: (Int, Int) -> Unit) {
        require(entries.isNotEmpty() && !output.exists())
        fun checkCancelled() { if (cancelled.get()) throw CancellationException() }
        try {
            checkCancelled()
            ZipOutputStream(output.outputStream().buffered(256 * 1024)).use { zip ->
                entries.forEachIndexed { index, name ->
                    checkCancelled()
                    val image = render(index, name)
                    try {
                        checkCancelled()
                        val crc = CRC32()
                        image.inputStream().buffered().use { input ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                checkCancelled()
                                val count = input.read(buffer)
                                if (count < 0) break
                                crc.update(buffer, 0, count)
                            }
                        }
                        zip.putNextEntry(ZipEntry("%06d.png".format(java.util.Locale.ROOT, index + 1)).apply {
                            method = ZipEntry.STORED
                            size = image.length()
                            compressedSize = size
                            this.crc = crc.value
                        })
                        image.inputStream().buffered().use { input ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                checkCancelled()
                                val count = input.read(buffer)
                                if (count < 0) break
                                zip.write(buffer, 0, count)
                            }
                        }
                        zip.closeEntry()
                    } finally { image.delete() }
                    onPage(index + 1, entries.size)
                }
                checkCancelled()
            }
        } catch (failure: Throwable) {
            output.delete()
            throw failure
        }
    }
}
