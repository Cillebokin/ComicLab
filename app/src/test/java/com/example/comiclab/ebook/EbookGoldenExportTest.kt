package com.example.comiclab.ebook

import com.example.comiclab.ebook.epub.EpubParseException
import com.example.comiclab.ebook.epub.EpubParser
import com.example.comiclab.ebook.mobi.MobiParseException
import com.example.comiclab.ebook.mobi.MobiParser
import com.example.comiclab.ebook.txt.TxtBookSession
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Explicitly enabled exporter for the checked-in Kotlin parser golden file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EbookGoldenExportTest {

    @Test
    fun exportsAndroidParserResultsForFixtureCorpus() {
        val fixtureRoot = System.getenv(FIXTURE_ROOT_ENV)
        val outputPath = System.getenv(OUTPUT_PATH_ENV)
        assumeTrue(
            "Set $FIXTURE_ROOT_ENV and $OUTPUT_PATH_ENV to explicitly export the golden file",
            !fixtureRoot.isNullOrBlank() && !outputPath.isNullOrBlank()
        )

        val root = File(requireNotNull(fixtureRoot)).canonicalFile
        val outputFile = File(requireNotNull(outputPath)).canonicalFile
        val specs = loadSpecs(root)
        assertTrue("Fixture manifest contains no samples", specs.isNotEmpty())

        val snapshots = specs.sortedBy(SampleSpec::id).map { spec ->
            val file = File(root, spec.path).canonicalFile
            assertTrue("Missing fixture ${spec.path}", file.isFile)
            val snapshot = parseSample(file, spec)
            assertEquals(
                "Unexpected parser result for ${spec.id}",
                spec.expectedErrorCode,
                snapshot.errorCode
            )
            snapshot
        }

        outputFile.parentFile?.mkdirs()
        outputFile.writeText(
            renderJson(
                linkedMapOf(
                    "schemaVersion" to 1,
                    "producer" to "Android Kotlin ebook parsers",
                    "samples" to snapshots.map(::toJsonValue)
                )
            ) + "\n",
            StandardCharsets.UTF_8
        )
        println("Exported ${snapshots.size} Android ebook baselines to ${outputFile.absolutePath}")
    }

    private fun loadSpecs(root: File): List<SampleSpec> {
        val manifest = JSONObject(File(root, "manifest.json").readText(StandardCharsets.UTF_8))
        val samples = manifest.getJSONArray("samples")
        return (0 until samples.length()).map { index ->
            val value = samples.getJSONObject(index)
            SampleSpec(
                id = value.getString("id"),
                path = value.getString("path"),
                format = value.getString("format"),
                variant = value.getString("variant"),
                expectedErrorCode = value.opt("expectedErrorCode")
                    ?.takeUnless { it === JSONObject.NULL }
                    ?.toString()
            )
        }
    }

    private fun parseSample(file: File, spec: SampleSpec): SampleSnapshot {
        var book: EbookBook? = null
        var resources = emptyList<EbookResource>()
        var resourceRecords = emptyMap<String, EbookResourceRecord>()
        var errorCode: String? = null

        try {
            when (spec.format) {
                "EPUB" -> EpubParser().parse(file).also { parsed ->
                    book = parsed.book
                    resources = parsed.book.resources
                    resourceRecords = parsed.resourceRecords
                }
                "MOBI", "AZW3" -> MobiParser().parse(file).also { parsed ->
                    book = parsed.book
                    resources = parsed.book.resources
                    resourceRecords = parsed.resourceRecords
                }
                "TXT" -> TxtBookSession.open(file, "未命名电子书", "未命名章节").use { session ->
                    book = session.book
                    resources = session.book.resources
                }
                else -> error("Unsupported fixture format ${spec.format}")
            }
        } catch (error: EpubParseException) {
            errorCode = error.reason.name
        } catch (error: MobiParseException) {
            errorCode = error.reason.name
        }

        return SampleSnapshot(
            spec = spec,
            sizeBytes = file.length(),
            inputSha256 = sha256(file),
            errorCode = errorCode,
            book = book,
            resources = resources,
            resourceRecords = resourceRecords
        )
    }

    private fun toJsonValue(snapshot: SampleSnapshot): Map<String, Any?> {
        val book = snapshot.book
        return linkedMapOf(
            "id" to snapshot.spec.id,
            "path" to snapshot.spec.path,
            "format" to snapshot.spec.format,
            "variant" to snapshot.spec.variant,
            "sizeBytes" to snapshot.sizeBytes,
            "inputSha256" to snapshot.inputSha256,
            "status" to if (snapshot.errorCode == null) "ok" else "error",
            "errorCode" to snapshot.errorCode,
            "book" to book?.let {
                linkedMapOf(
                    "title" to it.title,
                    "author" to it.author,
                    "coverResourceId" to it.coverResourceId,
                    "stylesheetSha256" to it.stylesheet?.let(::sha256),
                    "chapters" to it.chapters.map { chapter ->
                        linkedMapOf(
                            "index" to chapter.index,
                            "title" to chapter.title,
                            "sourcePath" to chapter.sourcePath,
                            "htmlBytes" to chapter.html.toByteArray(StandardCharsets.UTF_8).size,
                            "htmlSha256" to sha256(chapter.html)
                        )
                    },
                    "resources" to snapshot.resources.map { resource ->
                        val record = snapshot.resourceRecords[resource.id]
                        linkedMapOf(
                            "id" to resource.id,
                            "mimeType" to resource.mimeType,
                            "path" to resource.path,
                            "recordIndex" to resource.recordIndex,
                            "resourceIndex" to resource.resourceIndex,
                            "recordOffset" to record?.offset,
                            "recordLength" to record?.length
                        )
                    }
                )
            }
        )
    }

    private fun sha256(value: String): String = sha256(value.toByteArray(StandardCharsets.UTF_8))

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().toHex()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }

    private fun renderJson(value: Any?, depth: Int = 0): String = when (value) {
        null -> "null"
        is String -> quoteJson(value)
        is Number, is Boolean -> value.toString()
        is Map<*, *> -> {
            if (value.isEmpty()) {
                "{}"
            } else {
                val indent = "  ".repeat(depth)
                val childIndent = "  ".repeat(depth + 1)
                value.entries.joinToString(",\n", "{\n", "\n$indent}") { (key, child) ->
                    "$childIndent${quoteJson(requireNotNull(key).toString())}: ${renderJson(child, depth + 1)}"
                }
            }
        }
        is Iterable<*> -> {
            val items = value.toList()
            if (items.isEmpty()) "[]" else {
                val indent = "  ".repeat(depth)
                val childIndent = "  ".repeat(depth + 1)
                items.joinToString(",\n", "[\n", "\n$indent]") { item ->
                    childIndent + renderJson(item, depth + 1)
                }
            }
        }
        else -> error("Cannot encode ${value::class.java.name} as JSON")
    }

    private fun quoteJson(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u${character.code.toString(16).padStart(4, '0')}")
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    private data class SampleSpec(
        val id: String,
        val path: String,
        val format: String,
        val variant: String,
        val expectedErrorCode: String?
    )

    private data class SampleSnapshot(
        val spec: SampleSpec,
        val sizeBytes: Long,
        val inputSha256: String,
        val errorCode: String?,
        val book: EbookBook?,
        val resources: List<EbookResource>,
        val resourceRecords: Map<String, EbookResourceRecord>
    )

    private companion object {
        const val FIXTURE_ROOT_ENV = "COMICLAB_EBOOK_SAMPLES"
        const val OUTPUT_PATH_ENV = "COMICLAB_EBOOK_GOLDEN_OUT"
    }
}
