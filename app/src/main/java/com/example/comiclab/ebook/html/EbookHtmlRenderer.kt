package com.example.comiclab.ebook.html

import android.net.Uri
import java.io.Writer
import com.example.comiclab.ebook.EbookBook
import com.example.comiclab.ebook.EbookChapter
import com.example.comiclab.ebook.EbookResource
import com.example.comiclab.ebook.epub.EpubPath
import com.example.comiclab.ebook.checkEbookCancellation

data class EbookStyle(
    val fontScale: Float = 1.0f,
    val lineHeight: Float = 1.6f,
    val backgroundColor: String = "#101010",
    val foregroundColor: String = "#E8E8E8",
    val previousChapterLabel: String = "上一章",
    val nextChapterLabel: String = "下一章"
)

class EbookHtmlRenderer {

    fun render(book: EbookBook, style: EbookStyle, svgSource: ((EbookResource) -> String?)? = null): String {
        val output = StringBuilder()
        writeTo(book, style, output, svgSource)
        return output.toString()
    }

    fun writeTo(book: EbookBook, style: EbookStyle, output: Appendable,
                svgSource: ((EbookResource) -> String?)? = null) {
        writeDocuments(book, style, output, svgSource, null)
    }

    /** 共用全书锚点索引，但各章独立落盘，运行时仅装载相邻章节。 */
    internal fun writeChapterDocuments(book: EbookBook, style: EbookStyle, shell: Appendable,
        svgSource: ((EbookResource) -> String?)?,
        windowCharBudget: Long, windowTagBudget: Long,
        chapterWriter: (EbookChapter, (Appendable) -> Unit) -> Unit) {
        writeDocuments(book, style, shell, svgSource, chapterWriter, windowCharBudget, windowTagBudget)
    }

    private fun writeDocuments(book: EbookBook, style: EbookStyle, output: Appendable,
        svgSource: ((EbookResource) -> String?)?,
        chapterWriter: ((EbookChapter, (Appendable) -> Unit) -> Unit)?,
        windowCharBudget: Long = 0, windowTagBudget: Long = 0) {
        checkEbookCancellation()
        val safeStyle = style.normalized()
        var svgIndex = -1
        val standaloneVectors = mutableSetOf<String>()
        val loadSvg: ((EbookResource) -> Pair<Int, String>?)? = svgSource?.let { source ->
            { resource ->
                if (resource.id in standaloneVectors) null else source(resource)?.let { svg ->
                    if (needsSvgInlining(svg)) svgIndex-- to svg
                    else { standaloneVectors += resource.id; null }
                }
            }
        }
        val anchors = book.chapters.associate { it.index to collectAnchors(it.html, it.index) }
        output.append("<!doctype html><html><head>")
        output.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        output.append("<meta charset=\"utf-8\">")
        book.stylesheet?.takeIf(String::isNotBlank)?.let { stylesheet ->
            output.append("<style>")
                .append(sanitizeStylesheet(stylesheet))
                .append("</style>")
        }
        output.append("<style>")
        output.append("html,body{margin:0;padding:0;background:")
            .append(safeStyle.backgroundColor)
            .append(";color:")
            .append(safeStyle.foregroundColor)
            .append(";}")
        output.append("body{font-size:")
            .append((safeStyle.fontScale * 100f).toInt().toString())
            .append("%;line-height:")
            .append(safeStyle.lineHeight.toString())
            .append(";font-family:sans-serif;padding:24px 18px 48px;word-wrap:break-word;}")
        output.append("article{max-width:760px;margin:0 auto;}")
        output.append("h1,h2,h3,h4,h5,h6{line-height:1.25;}img,svg{max-width:100%;height:auto;}")
        output.append("a{color:")
            .append(safeStyle.foregroundColor)
            .append(";}")
        output.append(".book-header{border-bottom:1px solid rgba(128,128,128,.35);margin-bottom:28px;padding-bottom:18px;}")
        output.append(".book-author{opacity:.72;margin-top:8px;}")
        output.append("</style></head><body><article data-chapter-count=\"")
            .append(book.chapters.size.toString()).append("\"")
        if (chapterWriter != null) output.append(" data-window-char-budget=\"")
            .append(windowCharBudget.toString()).append("\" data-window-tag-budget=\"")
            .append(windowTagBudget.toString()).append("\"")
        output.append(">")
        output.append("<header class=\"book-header\"><h1>")
            .append(escapeHtml(book.title))
            .append("</h1>")
        if (!book.author.isNullOrBlank()) {
            output.append("<div class=\"book-author\">")
                .append(escapeHtml(book.author))
                .append("</div>")
        }
        output.append("</header>")

        book.chapters.forEach { chapter ->
            checkEbookCancellation()
            if (chapterWriter == null) appendChapter(output, book, chapter, anchors, loadSvg)
            else chapterWriter(chapter) { target -> appendChapter(target, book, chapter, anchors, loadSvg) }
        }

        if (chapterWriter != null) {
            output.append("</article><nav id=\"chapter-navigation\" style=\"position:fixed;bottom:8px;left:18px;right:18px;display:flex;justify-content:space-between;max-width:760px;margin:auto\">")
                .append("<button id=\"chapter-previous\" aria-label=\"").append(escapeHtmlAttribute(style.previousChapterLabel)).append("\">‹</button>")
                .append("<button id=\"chapter-next\" aria-label=\"").append(escapeHtmlAttribute(style.nextChapterLabel)).append("\">›</button></nav>")
                .append("<script src=\"/ebook-chapters.js\"></script></body></html>")
            return
        }

        output.append(
            """
            </article><script>
            (function() {
                var reportTimer = null;
                function sectionTop(section) {
                    return section.getBoundingClientRect().top + window.scrollY;
                }
                function maxScroll() {
                    return Math.max(document.documentElement.scrollHeight - window.innerHeight, 0);
                }
                function chapterSpan(sections, index) {
                    var end = index + 1 < sections.length ? sectionTop(sections[index + 1]) : maxScroll();
                    return Math.max(end - sectionTop(sections[index]), 1);
                }
                function reportProgress() {
                    var sections = document.querySelectorAll('[data-chapter-index]');
                    var position = window.scrollY;
                    var current = 0;
                    for (var i = 0; i < sections.length; i++) {
                        if (sectionTop(sections[i]) <= position) current = i;
                    }
                    if (sections.length && maxScroll() > 0 && position >= maxScroll() - 0.5) current = sections.length - 1;
                    var section = sections[current];
                    var top = section ? sectionTop(section) : 0;
                    var fraction = Math.max(0, Math.min(1, (position - top) / (section ? chapterSpan(sections, current) : 1)));
                    if (maxScroll() > 0 && position >= maxScroll() - 0.5) fraction = 1;
                    if (window.ComicLabBridge) window.ComicLabBridge.reportProgress(current, fraction);
                }
                window.addEventListener('scroll', function() {
                    if (reportTimer) clearTimeout(reportTimer);
                    reportTimer = setTimeout(reportProgress, 180);
                });
                window.restoreComicLabProgress = function(chapterIndex, fraction, coordinateVersion) {
                    var section = document.getElementById('chapter-' + chapterIndex);
                    if (!section) return;
                    var sections = document.querySelectorAll('[data-chapter-index]');
                    var span = coordinateVersion === 1 ? Math.max(section.offsetHeight - window.innerHeight, 0) : chapterSpan(sections, chapterIndex);
                    var target = sectionTop(section) + Math.max(0, Math.min(1, fraction)) * span;
                    if (coordinateVersion !== 1 && chapterIndex === sections.length - 1 && fraction >= 1) target = maxScroll();
                    window.scrollTo(0, target);
                    setTimeout(reportProgress, 200);
                };
                window.jumpToComicLabAnchor = function(anchorId) {
                    var target = document.getElementById(anchorId);
                    if (!target) return false;
                    window.scrollTo(0, sectionTop(target));
                    reportProgress();
                    return true;
                };
                document.addEventListener('click', function(event) {
                    if (event.defaultPrevented) return;
                    var link = event.target.closest ? event.target.closest('a[href]') : null;
                    var href = link ? link.getAttribute('href') : null;
                    if (href && /^#chapter-\d+(?:-a-[0-9a-f]+)?$/.test(href)) {
                        event.preventDefault();
                        window.jumpToComicLabAnchor(href.substring(1));
                    }
                });
                window.jumpToComicLabChapter = function(chapterIndex) {
                    var section = document.getElementById('chapter-' + chapterIndex);
                    if (section) window.scrollTo(0, sectionTop(section));
                    setTimeout(reportProgress, 200);
                };
                setTimeout(reportProgress, 200);
            })();
            </script></body></html>
            """.trimIndent()
        )
    }

    private fun appendChapter(output: Appendable, book: EbookBook, chapter: EbookChapter, anchors: Map<Int, Map<String, String>>, loadSvg: ((EbookResource) -> Pair<Int, String>?)?) {
        output.append("<section id=\"chapter-")
            .append(chapter.index.toString())
            .append("\" data-chapter-index=\"")
            .append(chapter.index.toString())
            .append("\"><h2>")
            .append(escapeHtml(chapter.title))
            .append("</h2>")
        appendSanitizedFragment(output, chapter.html, book, chapter.sourcePath, chapter.index, anchors, loadSvg)
        output.append("</section>")
    }

    private fun Appendable.appendSource(source: String, start: Int, end: Int) {
        if (this is Writer) write(source, start, end - start)
        else append(source, start, end)
    }

    private fun cleanFragment(fragment: String): String = fragment
        .replace(UNSAFE_BLOCK_REGEX, "")
        .replace(COMMENT_REGEX, "")

    private fun needsSvgInlining(source: String): Boolean = TAG_REGEX.findAll(source).any { match ->
        checkEbookCancellation()
        match.groupValues[1].isEmpty() && match.groupValues[2].substringAfter(':').equals("image", true) &&
            (attribute(match.groupValues[3], "href") ?: attribute(match.groupValues[3], "xlink:href"))
                ?.let { !it.startsWith("data:", ignoreCase = true) } == true
    }

    private fun svgImageUrl(reference: String, book: EbookBook, sourcePath: String?): String? {
        val value = reference.trim()
        if (DATA_BITMAP.matches(value)) return value.replace("\r", "").replace("\n", "")
        return resolveResourceId(value, book, sourcePath)?.let { "ebook-resource://$it" }
    }

    private fun anchorId(index: Int, name: String): String = "chapter-$index-a-" +
        name.toByteArray(Charsets.UTF_8).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    private fun collectAnchors(fragment: String, index: Int): Map<String, String> {
        val anchors = linkedMapOf<String, String>()
        val match = TAG_REGEX.toPattern().matcher(cleanFragment(fragment))
        while (match.find()) {
            checkEbookCancellation()
            if (match.start(1) != match.end(1) || match.start(3) == match.end(3)) continue
            val tag = match.group(2).lowercase().removePrefix("svg:")
            if (tag !in SAFE_TEXT_TAGS && tag != "a" && tag != "img" && tag != "br" && tag != "svg" && tag !in SVG_TAGS) continue
            val attributes = match.group(3)
            if (attributes.isBlank()) continue
            val id = attribute(attributes, "id")?.takeIf(String::isNotBlank)
            val name = if (tag == "a") attribute(attributes, "name")?.takeIf(String::isNotBlank) else null
            val target = (id ?: name)?.let { anchorId(index, it) } ?: continue
            id?.let { anchors.putIfAbsent(it, target) }
            name?.let { anchors.putIfAbsent(it, target) }
        }
        return anchors
    }

    private fun internalLink(reference: String, book: EbookBook, sourcePath: String?, index: Int,
                             anchors: Map<Int, Map<String, String>>): String? {
        val value = reference.trim()
        if (value.isBlank() || EXTERNAL_REFERENCE.containsMatchIn(value) || value.startsWith("//")) return null
        val path = value.substringBefore('#').substringBefore('?')
        val chapter = if (path.isBlank()) book.chapters.firstOrNull { it.index == index }
            else sourcePath?.let { base -> EpubPath.resolve(base, path) }
                ?.let { resolved -> book.chapters.firstOrNull { it.sourcePath == resolved } }
        chapter ?: return null
        val fragment = Uri.decode(value.substringAfter('#', ""))
        return if (fragment.isEmpty()) "chapter-${chapter.index}" else anchors[chapter.index]?.get(fragment)
    }

    private fun appendSanitizedFragment(output: Appendable, fragment: String, book: EbookBook, sourcePath: String?, index: Int,
                                 anchors: Map<Int, Map<String, String>>, loadSvg: ((EbookResource) -> Pair<Int, String>?)? = null) {
        val source = cleanFragment(fragment)
        var cursor = 0
        var svgDepth = 0
        val localAnchors = anchors[index].orEmpty()
        val match = TAG_REGEX.toPattern().matcher(source)
        while (match.find()) {
            checkEbookCancellation()
            output.appendSource(source, cursor, match.start())
            val closing = match.start(1) != match.end(1)
            val tag = match.group(2).lowercase().removePrefix("svg:")
            val attributes = match.group(3)
            val selfClosing = attributes.trimEnd().endsWith("/")
            when {
                tag == "svg" -> {
                    if (closing) {
                        if (svgDepth > 0) { output.append("</svg>"); svgDepth-- }
                    } else {
                        output.append("<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\"")
                            .append(svgAttributes(attributes, localAnchors)).append(if (selfClosing) "/>" else ">")
                        if (!selfClosing) svgDepth++
                    }
                }
                svgDepth > 0 && tag in SVG_TAGS -> {
                    val svgTag = SVG_TAGS.getValue(tag)
                    if (closing) { if (tag != "image") output.append("</").append(svgTag).append(">") } else {
                        val href = if (tag == "image") {
                            (attribute(attributes, "href") ?: attribute(attributes, "xlink:href"))
                                ?.let { svgImageUrl(it, book, sourcePath) }
                        } else {
                            (attribute(attributes, "href") ?: attribute(attributes, "xlink:href"))
                                ?.takeIf { it.startsWith("#") }?.removePrefix("#")
                                ?.let { localAnchors[Uri.decode(it)] }?.let { "#$it" }
                        }
                        if (tag != "image" || href != null) {
                            output.append("<").append(svgTag).append(svgAttributes(attributes, localAnchors))
                            if (href != null) output.append(" href=\"").append(escapeHtmlAttribute(href)).append("\" xlink:href=\"").append(escapeHtmlAttribute(href)).append("\"")
                            output.append(if (selfClosing || tag == "image") "/>" else ">")
                        }
                    }
                }
                svgDepth == 0 && tag in SAFE_TEXT_TAGS -> {
                    if (closing) output.append("</").append(tag).append(">") else {
                        output.append("<").append(tag).append(safeAttributes(attributes, localAnchors)).append(">")
                        if (selfClosing) output.append("</").append(tag).append(">")
                    }
                }
                svgDepth == 0 && tag == "a" -> {
                    if (closing) output.append("</a>") else {
                        val href = attribute(attributes, "href")?.let { internalLink(it, book, sourcePath, index, anchors) }
                        output.append("<a").append(safeAttributes(attributes, localAnchors, namedAnchor = true))
                        if (href != null) output.append(" href=\"#").append(href).append("\"")
                        output.append(">")
                        if (selfClosing) output.append("</a>")
                    }
                }
                svgDepth == 0 && tag == "img" && !closing -> {
                    val reference = attribute(attributes, "recindex") ?: attribute(attributes, "src")
                    val id = reference?.let { resolveResourceId(it, book, sourcePath) }
                    val resource = book.resources.firstOrNull { it.id == id }
                    val loadedSvg = resource?.takeIf { it.mimeType == "image/svg+xml" }?.let { loadSvg?.invoke(it) }
                    if (loadedSvg != null) {
                        output.append("<span").append(safeAttributes(attributes, localAnchors)).append(">")
                        appendSvgResource(output, book, resource, loadedSvg.second, loadedSvg.first)
                        output.append("</span>")
                    } else if (id != null) output.append("<img").append(safeAttributes(attributes, localAnchors))
                        .append(" src=\"ebook-resource://").append(escapeHtmlAttribute(id)).append("\" alt=\"\">")
                }
                svgDepth == 0 && tag == "br" && !closing -> output.append("<br").append(safeAttributes(attributes, localAnchors)).append(">")
            }
            cursor = match.end()
        }
        output.appendSource(source, cursor, source.length)
    }

    /** SVG 内联后，内部图片仍通过受控资源会话读取，局部 ID 与其他插图隔离。 */
    private fun appendSvgResource(output: Appendable, book: EbookBook, resource: EbookResource, source: String, index: Int) {
        checkEbookCancellation()
        val svg = SVG_DOCUMENT.find(source)?.value ?: return
        val anchors = mapOf(index to collectAnchors(svg, index))
        appendSanitizedFragment(output, svg, book, resource.path, index, anchors)
    }

    private fun svgAttributes(attributes: String, anchors: Map<String, String>): String = buildString {
        append(safeAttributes(attributes, anchors))
        val values = parseAttributes(attributes).toMutableMap()
        values["style"]?.split(';')?.forEach { declaration ->
            val name = declaration.substringBefore(':').trim().lowercase()
            if (name in SVG_STYLE_ATTRIBUTES && ':' in declaration) values[name] = declaration.substringAfter(':').trim()
        }
        SVG_ATTRIBUTES.forEach { name ->
            values[name.lowercase()]?.let { value ->
                val localUrl = LOCAL_SVG_URL.matchEntire(value)
                val safeValue = when {
                    localUrl != null -> anchors[localUrl.groupValues[1]]?.let { "url(#$it)" }
                    value.contains("url", ignoreCase = true) -> null
                    else -> value
                }
                if (safeValue != null) append(" $name=\"").append(escapeHtmlAttribute(safeValue)).append("\"")
            }
        }
    }

    private fun resolveResourceId(
        reference: String,
        book: EbookBook,
        sourcePath: String?
    ): String? {
        val normalized = reference.trim().removeSurrounding("\"").removeSurrounding("'")
        if (sourcePath != null) {
            if (EXTERNAL_REFERENCE.containsMatchIn(normalized) || normalized.startsWith("//")) return null
            val path = EpubPath.resolve(sourcePath, normalized) ?: return null
            return book.resources.firstOrNull { it.path == path }?.id
        }
        val mobiResourceIndex = MOBI_RESOURCE_INDEX_REGEX.find(normalized)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::decodeMobiResourceIndex)
        return book.resources.firstOrNull { it.id == normalized }?.id
            ?: normalized.removePrefix("ebook-resource://")
                .removePrefix("mobi-resource://")
                .takeIf { value -> book.resources.any { it.id == value } }
            ?: mobiResourceIndex?.let { index ->
                book.resources.firstOrNull { it.resourceIndex == index - 1 }?.id
                    ?: book.resources.getOrNull(index - 1)?.id
            }
            ?: normalizeResourcePath(sourcePath, normalized)?.let { resourcePath ->
                book.resources.firstOrNull { it.path == resourcePath }?.id
            }
            ?: normalized.toIntOrNull()?.let { recordIndex ->
                book.resources.firstOrNull { it.recordIndex == recordIndex }?.id
                    ?: book.resources.getOrNull(recordIndex)?.id
            }
            ?: Regex("(\\d+)").find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?.let { index -> book.resources.getOrNull(index)?.id }
    }

    private fun decodeMobiResourceIndex(value: String): Int? {
        var decoded = 0L
        for (character in value.uppercase()) {
            val digit = MOBI_RESOURCE_ALPHABET.indexOf(character)
            if (digit < 0) {
                return null
            }
            decoded = decoded * MOBI_RESOURCE_RADIX + digit
            if (decoded > Int.MAX_VALUE) {
                return null
            }
        }
        return decoded.toInt().takeIf { it > 0 }
    }

    private fun normalizeResourcePath(sourcePath: String?, reference: String): String? {
        if (reference.startsWith("#") || reference.startsWith("data:", ignoreCase = true)) {
            return null
        }

        val decodedReference = Uri.decode(reference)
            .substringBefore('#')
            .substringBefore('?')
            .replace('\\', '/')
        if (decodedReference.isBlank()) {
            return null
        }

        val base = sourcePath?.substringBeforeLast('/', "")?.takeIf(String::isNotEmpty)
        val combined = if (decodedReference.startsWith('/')) {
            decodedReference.removePrefix("/")
        } else if (base == null) {
            decodedReference
        } else {
            "$base/$decodedReference"
        }

        val parts = ArrayDeque<String>()
        combined.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(part)
            }
        }
        return parts.joinToString("/").takeIf(String::isNotEmpty)
    }

    private fun safeAttributes(attributes: String, anchors: Map<String, String>, namedAnchor: Boolean = false): String {
        if (attributes.isBlank()) return ""
        val builder = StringBuilder()
        attribute(attributes, "class")?.let(::safeCssTokens)?.takeIf(String::isNotEmpty)
            ?.let { builder.append(" class=\"").append(escapeHtmlAttribute(it)).append("\"") }
        val original = attribute(attributes, "id")?.takeIf(String::isNotBlank)
            ?: if (namedAnchor) attribute(attributes, "name")?.takeIf(String::isNotBlank) else null
        original?.let { name -> anchors[name]?.let { target ->
            builder.append(" id=\"").append(target).append("\" data-ebook-id=\"")
                .append(anchorId(0, name).substringAfter("-a-")).append("\"")
        } }
        return builder.toString()
    }

    private fun safeCssTokens(value: String): String {
        return value.split(Regex("\\s+"))
            .mapNotNull(::safeCssToken)
            .joinToString(" ")
    }

    private fun safeCssToken(value: String): String? {
        return value.takeIf { it.matches(Regex("[A-Za-z_][A-Za-z0-9_-]*")) }
    }

    private fun sanitizeStylesheet(stylesheet: String): String {
        return stylesheet
            .replace(CSS_IMPORT_REGEX, "")
            .replace(CSS_UNSAFE_DECLARATION_REGEX, "")
            .replace(CSS_URL_REGEX, "none")
            .replace(CSS_SELECTOR_REGEX) { rule ->
                rule.groupValues[1].replace(CSS_ID_SELECTOR_REGEX) { selector ->
                    "[data-ebook-id=\"${anchorId(0, selector.groupValues[1]).substringAfter("-a-")}\"]"
                } + "{"
            }
    }

    private fun attribute(attributes: String, name: String): String? =
        ATTRIBUTE_REGEX.findAll(attributes).firstOrNull { it.groupValues[1].equals(name, ignoreCase = true) }
            ?.let { match -> attributeValue(match) }

    private fun parseAttributes(attributes: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        ATTRIBUTE_REGEX.findAll(attributes).forEach { match ->
            attributeValue(match)?.let { result.putIfAbsent(match.groupValues[1].lowercase(), it) }
        }
        return result
    }

    private fun attributeValue(match: MatchResult): String? =
        match.groupValues.drop(2).firstOrNull(String::isNotEmpty)?.let { value ->
            XML_ENTITY.replace(value) { entity ->
                when (val name = entity.groupValues[1]) {
                    "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
                    else -> {
                        val number = if (name.startsWith("#x", true)) name.drop(2).toIntOrNull(16) else name.drop(1).toIntOrNull()
                        number?.takeIf { Character.isValidCodePoint(it) && it !in 0xD800..0xDFFF }
                            ?.let { String(Character.toChars(it)) } ?: entity.value
                    }
                }
            }
        }

    private fun escapeHtml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    private fun escapeHtmlAttribute(value: String): String = escapeHtml(value)

    private fun EbookStyle.normalized(): EbookStyle {
        val safeColor = Regex("^#[0-9a-fA-F]{6}$")
        return copy(
            fontScale = fontScale.coerceIn(0.8f, 1.8f),
            lineHeight = lineHeight.coerceIn(1.2f, 2.4f),
            backgroundColor = backgroundColor.takeIf(safeColor::matches) ?: "#101010",
            foregroundColor = foregroundColor.takeIf(safeColor::matches) ?: "#E8E8E8"
        )
    }

    private companion object {
        val ATTRIBUTE_REGEX = Regex("(?is)(?:^|\\s)([a-z_:][a-z0-9_:.-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")
        val EXTERNAL_REFERENCE = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
        val XML_ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);")
        val CSS_SELECTOR_REGEX = Regex("([^{}]+)\\{")
        val CSS_ID_SELECTOR_REGEX = Regex("#([A-Za-z_][A-Za-z0-9_-]*)")
        val SVG_DOCUMENT = Regex("(?is)<(?:svg:)?svg\\b.*</(?:svg:)?svg\\s*>|<(?:svg:)?svg\\b[^>]*/>")
        val LOCAL_SVG_URL = Regex("url\\(\\s*#([^\\s)]+)\\s*\\)", RegexOption.IGNORE_CASE)
        val DATA_BITMAP = Regex("data:image/(?:png|jpeg|gif|webp|bmp|avif);base64,[A-Za-z0-9+/=\\r\\n]+", RegexOption.IGNORE_CASE)
        val SVG_TAGS = listOf("g", "defs", "path", "rect", "circle", "ellipse", "line", "polyline", "polygon",
            "text", "tspan", "title", "desc", "clipPath", "mask", "linearGradient", "radialGradient", "stop", "use", "symbol", "image")
            .associateBy { it.lowercase() }
        val SVG_STYLE_ATTRIBUTES = setOf("fill", "stroke", "stroke-width", "fill-opacity", "stroke-opacity", "opacity", "font-size", "font-family", "text-anchor")
        val SVG_ATTRIBUTES = listOf("viewBox", "width", "height", "x", "y", "x1", "y1", "x2", "y2", "dx", "dy",
            "cx", "cy", "r", "rx", "ry", "d", "points", "transform", "preserveAspectRatio", "fill", "stroke",
            "stroke-width", "stroke-linecap", "stroke-linejoin", "stroke-dasharray", "fill-rule", "clip-rule",
            "opacity", "fill-opacity", "stroke-opacity", "clip-path", "mask", "offset", "stop-color", "stop-opacity",
            "gradientUnits", "gradientTransform", "text-anchor", "font-size", "font-family")
        val SAFE_TEXT_TAGS = setOf(
            "p", "div", "span", "h1", "h2", "h3", "h4", "h5", "h6",
            "em", "strong", "blockquote", "ul", "ol", "li", "table", "tr", "td", "th"
        )
        val TAG_REGEX = Regex("(?is)<(/?)([a-z][a-z0-9:-]*)([^>]*)>")
        val UNSAFE_BLOCK_REGEX = Regex("(?is)<(script|style|iframe|object|embed|foreignObject)\\b.*?</\\1\\s*>")
        val COMMENT_REGEX = Regex("(?is)<!--.*?-->")
        val CSS_IMPORT_REGEX = Regex("(?is)@import[^;]*;")
        val CSS_UNSAFE_DECLARATION_REGEX = Regex("(?is)(?:behavior|binding|expression)\\s*:[^;{}]+;?")
        val CSS_URL_REGEX = Regex("(?is)url\\s*\\([^)]*\\)")
        val MOBI_RESOURCE_INDEX_REGEX = Regex("(?i)^(?:kindle:embed:)?([0-9A-Z]+)(?:\\?.*)?$")
        const val MOBI_RESOURCE_RADIX = 32L
        const val MOBI_RESOURCE_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    }
}
