package com.example.comiclab.ebook.epub

import android.util.Xml
import com.example.comiclab.ebook.EbookBook
import com.example.comiclab.ebook.EbookChapter
import com.example.comiclab.ebook.EbookResource
import com.example.comiclab.ebook.EbookResourceRecord
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.StringReader
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.zip.ZipException
import java.util.zip.ZipFile
import org.xmlpull.v1.XmlPullParser

class EpubParser {

    fun parse(file: File): ParsedEpub {
        if (!file.isFile || !file.canRead() || file.length() <= 0L) {
            throw EpubParseException(EpubParseError.INVALID_FILE, "EPUB file is not readable")
        }

        return try {
            ZipFile(file).use { zipFile ->
                parseZip(file, zipFile)
            }
        } catch (error: EpubParseException) {
            throw error
        } catch (error: ZipException) {
            throw EpubParseException(EpubParseError.INVALID_ARCHIVE, "EPUB archive is invalid", error)
        } catch (error: IOException) {
            throw EpubParseException(EpubParseError.INVALID_ARCHIVE, "Unable to read EPUB archive", error)
        } catch (error: RuntimeException) {
            throw EpubParseException(EpubParseError.INVALID_PACKAGE, "Unable to parse EPUB package", error)
        }
    }

    private fun parseZip(file: File, zipFile: ZipFile): ParsedEpub {
        if (zipFile.getEntry(ENCRYPTION_ENTRY) != null) {
            throw EpubParseException(
                EpubParseError.ENCRYPTED,
                "Encrypted EPUB resources are not supported"
            )
        }

        val mimetypeEntry = zipFile.getEntry(MIMETYPE_ENTRY)
            ?: throw EpubParseException(EpubParseError.MISSING_MIMETYPE, "EPUB mimetype is missing")
        val mimetype = zipFile.getInputStream(mimetypeEntry).use { input ->
            String(input.readBytes(), StandardCharsets.US_ASCII).trim()
        }
        if (mimetype != EPUB_MIMETYPE) {
            throw EpubParseException(EpubParseError.MISSING_MIMETYPE, "EPUB mimetype is invalid")
        }

        val containerEntry = zipFile.getEntry(CONTAINER_ENTRY)
            ?: throw EpubParseException(EpubParseError.INVALID_CONTAINER, "EPUB container.xml is missing")
        val opfPath = parseContainer(zipFile.getInputStream(containerEntry))
        val opfEntry = zipFile.getEntry(opfPath)
            ?: throw EpubParseException(EpubParseError.INVALID_PACKAGE, "EPUB package document is missing")
        val packageData = parsePackage(zipFile.getInputStream(opfEntry))

        val resolvedManifest = packageData.manifest.mapNotNull { item ->
            val path = EpubPath.resolve(opfPath, item.href) ?: return@mapNotNull null
            item.copy(path = path)
        }
        if (resolvedManifest.isEmpty()) {
            throw EpubParseException(EpubParseError.INVALID_PACKAGE, "EPUB manifest is empty")
        }

        val manifestById = resolvedManifest.associateBy { it.id }
        val navigationTitles = parseNavigationTitles(zipFile, packageData, manifestById)
        val chapters = parseChapters(zipFile, packageData, resolvedManifest, navigationTitles)
        if (chapters.isEmpty()) {
            throw EpubParseException(EpubParseError.EMPTY_BOOK, "EPUB does not contain readable chapters")
        }

        val resources = mutableListOf<EbookResource>()
        val resourceRecords = linkedMapOf<String, EbookResourceRecord>()
        var coverResourceId: String? = null
        var resourceNumber = 0
        val css = StringBuilder()

        resolvedManifest.forEach { item ->
            if (item.mediaType == CSS_MIME_TYPE) {
                readZipEntry(zipFile, item.path)?.let { css.append(it).append('\n') }
            }

            if (!item.mediaType.startsWith("image/")) {
                return@forEach
            }
            if (zipFile.getEntry(item.path) == null) {
                return@forEach
            }

            resourceNumber++
            val resourceId = "epub-resource-${resourceNumber.toString().padStart(4, '0')}"
            resources += EbookResource(
                id = resourceId,
                mimeType = item.mediaType,
                path = item.path
            )
            resourceRecords[resourceId] = EbookResourceRecord(
                id = resourceId,
                mimeType = item.mediaType,
                path = item.path
            )
            if (item.id == packageData.coverId || item.properties.split(Regex("\\s+")).contains(COVER_IMAGE_PROPERTY)) {
                coverResourceId = resourceId
            }
        }
        if (coverResourceId == null) {
            coverResourceId = resources.firstOrNull()?.id
        }

        return ParsedEpub(
            book = EbookBook(
                title = packageData.title?.takeIf(String::isNotBlank) ?: file.nameWithoutExtension,
                author = packageData.author?.takeIf(String::isNotBlank),
                chapters = chapters,
                resources = resources,
                coverResourceId = coverResourceId,
                stylesheet = css.toString().takeIf(String::isNotBlank)
            ),
            resourceRecords = resourceRecords,
            sourceFile = file
        )
    }

    private fun parseContainer(input: InputStream): String {
        return try {
            val parser = newParser(input)
            var rootFilePath: String? = null
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "rootfile") {
                    val candidate = parser.getAttributeValue(null, "full-path")
                    if (candidate != null &&
                        parser.getAttributeValue(null, "media-type") == OPF_MIME_TYPE
                    ) {
                        rootFilePath = EpubPath.normalize(candidate)
                        break
                    }
                }
            }
            rootFilePath
                ?: throw EpubParseException(EpubParseError.INVALID_CONTAINER, "EPUB rootfile is missing")
        } finally {
            input.close()
        }
    }

    private fun parsePackage(input: InputStream): PackageData {
        return try {
            val parser = newParser(input)
            val manifest = mutableListOf<ManifestItem>()
            val spine = mutableListOf<SpineItem>()
            var title: String? = null
            var author: String? = null
            var coverId: String? = null
            var spineTocId: String? = null

            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) {
                    continue
                }

                when (parser.name.substringAfterLast(':')) {
                    "title" -> title = parser.nextText().trim()
                    "creator" -> author = parser.nextText().trim()
                    "meta" -> {
                        val name = parser.getAttributeValue(null, "name")
                        val content = parser.getAttributeValue(null, "content")
                        if (name.equals("cover", ignoreCase = true)) {
                            coverId = content?.trim()
                        }
                    }
                    "item" -> {
                        val id = parser.getAttributeValue(null, "id")?.trim()
                        val href = parser.getAttributeValue(null, "href")?.trim()
                        val mediaType = parser.getAttributeValue(null, "media-type")?.trim()
                        if (!id.isNullOrBlank() && !href.isNullOrBlank() && !mediaType.isNullOrBlank()) {
                            manifest += ManifestItem(
                                id = id,
                                href = href,
                                mediaType = mediaType.lowercase(),
                                properties = parser.getAttributeValue(null, "properties")?.trim().orEmpty()
                            )
                        }
                    }
                    "spine" -> {
                        spineTocId = parser.getAttributeValue(null, "toc")?.trim()
                    }
                    "itemref" -> {
                        val idref = parser.getAttributeValue(null, "idref")?.trim()
                        if (!idref.isNullOrBlank()) {
                            spine += SpineItem(
                                idref = idref,
                                linear = parser.getAttributeValue(null, "linear")
                                    ?.equals("no", ignoreCase = true) != true
                            )
                        }
                    }
                }
            }

            if (manifest.isEmpty() || spine.isEmpty()) {
                throw EpubParseException(EpubParseError.INVALID_PACKAGE, "EPUB package is incomplete")
            }
            PackageData(title, author, manifest, spine, coverId, spineTocId)
        } finally {
            input.close()
        }
    }

    private fun parseNavigationTitles(
        zipFile: ZipFile,
        packageData: PackageData,
        manifestById: Map<String, ManifestItem>
    ): Map<String, String> {
        val navItem = manifestById.values
            .firstOrNull { it.properties.split(Regex("\\s+")).contains(NAV_PROPERTY) }
            ?: packageData.spineTocId?.let(manifestById::get)
            ?: manifestById.values.firstOrNull { it.mediaType == NCX_MIME_TYPE }
            ?: return emptyMap()
        val navPath = navItem.path.ifBlank { return emptyMap() }
        val source = readZipEntry(zipFile, navPath) ?: return emptyMap()

        return if (navItem.mediaType == NCX_MIME_TYPE) {
            parseNcxTitles(source, navPath)
        } else {
            parseNavDocumentTitles(source, navPath)
        }
    }

    private fun parseChapters(
        zipFile: ZipFile,
        packageData: PackageData,
        manifest: List<ManifestItem>,
        navigationTitles: Map<String, String>
    ): List<EbookChapter> {
        val manifestById = manifest.associateBy { it.id }
        val spineItems = packageData.spine
            .filter { it.linear }
            .ifEmpty { packageData.spine }
        return spineItems.mapNotNull { spineItem ->
            val item = manifestById[spineItem.idref] ?: return@mapNotNull null
            if (item.mediaType != XHTML_MIME_TYPE && item.mediaType != HTML_MIME_TYPE) {
                return@mapNotNull null
            }
            val source = readZipEntry(zipFile, item.path) ?: return@mapNotNull null
            val body = extractBody(source).trim()
            if (body.isBlank()) {
                return@mapNotNull null
            }
            val title = navigationTitles[item.path]
                ?: extractHeading(source)
                ?: extractDocumentTitle(source)
                ?: item.path.substringAfterLast('/').substringBeforeLast('.').ifBlank { "未命名" }
            EbookChapter(
                index = 0,
                title = title,
                html = body,
                sourcePath = item.path
            )
        }.mapIndexed { index, chapter -> chapter.copy(index = index) }
    }

    private fun parseNcxTitles(source: String, navPath: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(StringReader(source))
        }
        val points = ArrayDeque<NcxPoint>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name.substringAfterLast(':')) {
                    "navPoint" -> points.addLast(NcxPoint())
                    "text" -> if (points.isNotEmpty()) {
                        points.last().title = parser.nextText().trim()
                    }
                    "content" -> if (points.isNotEmpty()) {
                        points.last().href = parser.getAttributeValue(null, "src")?.trim()
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name.substringAfterLast(':') == "navPoint" && points.isNotEmpty()) {
                    val point = points.removeLast()
                    val path = point.href?.let { EpubPath.resolve(navPath, it) }
                    if (path != null && !point.title.isNullOrBlank()) {
                        result[path] = stripMarkup(point.title.orEmpty()).trim()
                    }
                }
            }
        }
        return result
    }

    private fun parseNavDocumentTitles(source: String, navPath: String): Map<String, String> {
        return NAV_ANCHOR_REGEX.findAll(source).mapNotNull { match ->
            val href = attribute(match.groupValues[1], "href") ?: return@mapNotNull null
            val title = stripMarkup(match.groupValues[2]).trim()
            val path = EpubPath.resolve(navPath, href) ?: return@mapNotNull null
            path to title
        }.filter { it.second.isNotBlank() }.toMap()
    }

    private fun readZipEntry(zipFile: ZipFile, path: String): String? {
        val entry = zipFile.getEntry(path) ?: return null
        return zipFile.getInputStream(entry).use { input -> decodeXml(input.readBytes()) }
    }

    private fun extractBody(source: String): String {
        return BODY_REGEX.find(source)?.groupValues?.getOrNull(1)
            ?: source
                .replace(XML_DECLARATION_REGEX, "")
                .replace(HEAD_BLOCK_REGEX, "")
                .replace(DOCUMENT_TAG_REGEX, "")
    }

    private fun extractHeading(source: String): String? {
        return HEADING_REGEX.find(source)?.groupValues?.getOrNull(1)
            ?.let(::stripMarkup)
            ?.trim()
            ?.takeIf(String::isNotBlank)
    }

    private fun extractDocumentTitle(source: String): String? {
        return TITLE_REGEX.find(source)?.groupValues?.getOrNull(1)
            ?.let(::stripMarkup)
            ?.trim()
            ?.takeIf(String::isNotBlank)
    }

    private fun stripMarkup(value: String): String {
        return value
            .replace(TAG_REGEX, "")
            .replace("&nbsp;", " ", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&lt;", "<", ignoreCase = true)
            .replace("&gt;", ">", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#39;", "'", ignoreCase = true)
            .replace(WHITESPACE_REGEX, " ")
    }

    private fun attribute(attributes: String, name: String): String? {
        val match = ATTRIBUTE_REGEX(name).find(attributes) ?: return null
        return match.groupValues.drop(1).firstOrNull(String::isNotEmpty)
    }

    private fun newParser(input: InputStream): XmlPullParser {
        return Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(InputStreamReader(input, StandardCharsets.UTF_8))
        }
    }

    private fun decodeXml(bytes: ByteArray): String {
        val prefix = bytes.copyOfRange(0, bytes.size.coerceAtMost(256)).toString(StandardCharsets.US_ASCII)
        val encoding = XML_ENCODING_REGEX.find(prefix)?.groupValues?.getOrNull(1)
            ?.let { runCatching { Charset.forName(it) }.getOrNull() }
            ?: StandardCharsets.UTF_8
        return String(bytes, encoding)
    }

    private data class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: String,
        val path: String = ""
    )

    private data class SpineItem(
        val idref: String,
        val linear: Boolean
    )

    private data class PackageData(
        val title: String?,
        val author: String?,
        val manifest: List<ManifestItem>,
        val spine: List<SpineItem>,
        val coverId: String?,
        val spineTocId: String?
    )

    private companion object {
        const val MIMETYPE_ENTRY = "mimetype"
        const val CONTAINER_ENTRY = "META-INF/container.xml"
        const val ENCRYPTION_ENTRY = "META-INF/encryption.xml"
        const val EPUB_MIMETYPE = "application/epub+zip"
        const val OPF_MIME_TYPE = "application/oebps-package+xml"
        const val XHTML_MIME_TYPE = "application/xhtml+xml"
        const val HTML_MIME_TYPE = "text/html"
        const val NCX_MIME_TYPE = "application/x-dtbncx+xml"
        const val CSS_MIME_TYPE = "text/css"
        const val NAV_PROPERTY = "nav"
        const val COVER_IMAGE_PROPERTY = "cover-image"
        val XML_ENCODING_REGEX = Regex("(?is)encoding\\s*=\\s*[\"']([^\"']+)[\"']")
        val XML_DECLARATION_REGEX = Regex("(?is)<\\?xml[^>]*>")
        val HEAD_BLOCK_REGEX = Regex("(?is)<head\\b[^>]*>.*?</head\\s*>")
        val DOCUMENT_TAG_REGEX = Regex("(?is)</?(?:html|body)\\b[^>]*>")
        val BODY_REGEX = Regex("(?is)<body\\b[^>]*>(.*?)</body\\s*>")
        val HEADING_REGEX = Regex("(?is)<h[1-6]\\b[^>]*>(.*?)</h[1-6]\\s*>")
        val TITLE_REGEX = Regex("(?is)<title\\b[^>]*>(.*?)</title\\s*>")
        val TAG_REGEX = Regex("(?is)<[^>]+>")
        val WHITESPACE_REGEX = Regex("\\s+")
        val NAV_ANCHOR_REGEX = Regex("(?is)<a\\b([^>]*)>(.*?)</a\\s*>")
        fun ATTRIBUTE_REGEX(name: String): Regex {
            return Regex("(?is)(?:^|\\s)$name\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")
        }
    }

    private data class NcxPoint(
        var title: String? = null,
        var href: String? = null
    )
}
