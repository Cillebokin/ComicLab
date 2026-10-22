package com.example.comiclab.core.reader.enhance

import java.io.File
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

internal data class EnhancementTile(val left: Int, val top: Int, val right: Int, val bottom: Int,
    val inputLeft: Int, val inputTop: Int, val inputRight: Int, val inputBottom: Int)

internal class EnhancementPlan(private val width: Int, private val height: Int, private val tileSize: Int = 256, val scale: Int = 2) {
    init {
        require(scale in 2..4)
        require(width in 1..16384 && height in 1..16384 &&
            width.toLong() * scale <= 32768 && height.toLong() * scale <= 32768 &&
            width.toLong() * height * scale * scale <= 40_000_000L) {
            "Page exceeds the single-page enhancement limit"
        }
        require(tileSize in 32..256)
    }
    val outputWidth = width * scale
    val outputHeight = height * scale
    val tileCount = ((width + tileSize - 1) / tileSize) * ((height + tileSize - 1) / tileSize)
    val requiredFreeBytes = outputWidth.toLong() * outputHeight * 6 + 16L * 1024 * 1024

    fun tiles(): Sequence<EnhancementTile> = sequence {
        for (y in 0 until height step tileSize) for (x in 0 until width step tileSize) {
            val right = minOf(x + tileSize, width)
            val bottom = minOf(y + tileSize, height)
            // 保留32像素上下文，裁掉分块边缘，避免把独立分块的填充边界拼入结果
            yield(EnhancementTile(x, y, right, bottom,
                maxOf(0, x - 32), maxOf(0, y - 32),
                minOf(width, right + 32), minOf(height, bottom + 32)))
        }
    }
}

internal object StreamingRgbPng {
    fun write(raw: File, output: File, width: Int, height: Int, checkCancelled: () -> Unit) {
        require(width > 0 && height > 0 && width <= 32768 && height <= 32768)
        require(raw.length() == width.toLong() * height * 3)
        DataOutputStream(output.outputStream().buffered()).use { png ->
            png.writeLong(-8552249625308161526L)
            val header = ByteArrayOutputStream().apply {
                DataOutputStream(this).apply {
                    writeInt(width); writeInt(height)
                    write(byteArrayOf(8, 2, 0, 0, 0))
                }
            }.toByteArray()
            chunk(png, "IHDR", header)
            val deflater = Deflater(3)
            try {
                // 每次压缩输出直接成为一个IDAT块，不积累整幅图片的压缩数据
                val chunks = object : OutputStream() {
                    override fun write(value: Int) = write(byteArrayOf(value.toByte()))
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        if (length > 0) chunk(png, "IDAT", bytes, offset, length)
                    }
                }
                DeflaterOutputStream(chunks, deflater, 64 * 1024).use { compressed ->
                    raw.inputStream().buffered().use { input ->
                        val row = ByteArray(width * 3)
                        val data = java.io.DataInputStream(input)
                        repeat(height) {
                            checkCancelled()
                            data.readFully(row)
                            compressed.write(0) // PNG filter: None
                            compressed.write(row)
                        }
                    }
                }
            } finally {
                deflater.end()
            }
            checkCancelled()
            chunk(png, "IEND", byteArrayOf())
        }
    }

    private fun chunk(out: DataOutputStream, name: String, bytes: ByteArray,
        offset: Int = 0, length: Int = bytes.size) {
        val type = name.toByteArray(Charsets.US_ASCII)
        out.writeInt(length)
        out.write(type)
        out.write(bytes, offset, length)
        out.writeInt(CRC32().apply { update(type); update(bytes, offset, length) }.value.toInt())
    }
}
