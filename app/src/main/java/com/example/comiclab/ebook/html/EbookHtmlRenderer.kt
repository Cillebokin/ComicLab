package com.example.comiclab.ebook.html

import android.net.Uri
import com.example.comiclab.ebook.EbookBook
import com.example.comiclab.ebook.EbookChapter

data class EbookStyle(
    val fontScale: Float = 1.0f,
    val lineHeight: Float = 1.6f,
    val backgroundColor: String = "#101010",
    val foregroundColor: String = "#E8E8E8"
)

class EbookHtmlRenderer {

    fun render(book: EbookBook, style: EbookStyle): String {
        val safeStyle = style.normalized()
        val output = StringBuilder()
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
            .append((safeStyle.fontScale * 100f).toInt())
            .append("%;line-height:")
            .append(safeStyle.lineHeight)
            .append(";font-family:sans-serif;padding:24px 18px 48px;word-wrap:break-word;}")
        output.append("article{max-width:760px;margin:0 auto;}")
        output.append("h1,h2,h3,h4,h5,h6{line-height:1.25;}img{max-width:100%;height:auto;}")
        output.append("a{color:")
            .append(safeStyle.foregroundColor)
            .append(";}")
        output.append(".book-header{border-bottom:1px solid rgba(128,128,128,.35);margin-bottom:28px;padding-bottom:18px;}")
        output.append(".book-author{opacity:.72;margin-top:8px;}")
        output.append("</style></head><body><article>")
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
            appendChapter(output, book, chapter)
        }

        output.append(
            """
            </article><script>
            (function() {
                var reportTimer = null;
                function reportProgress() {
                    var sections = document.querySelectorAll('[data-chapter-index]');
                    var position = window.scrollY;
                    var current = 0;
                    for (var i = 0; i < sections.length; i++) {
                        if (sections[i].offsetTop <= position) current = i;
                    }
                    var section = sections[current];
                    var sectionTop = section ? section.offsetTop : 0;
                    var sectionHeight = section ? Math.max(section.offsetHeight - window.innerHeight, 1) : 1;
                    var fraction = Math.max(0, Math.min(1, (position - sectionTop) / sectionHeight));
                    if (window.ComicLabBridge) window.ComicLabBridge.reportProgress(current, fraction);
                }
                window.addEventListener('scroll', function() {
                    if (reportTimer) clearTimeout(reportTimer);
                    reportTimer = setTimeout(reportProgress, 180);
                });
                window.restoreComicLabProgress = function(chapterIndex, fraction) {
                    var section = document.getElementById('chapter-' + chapterIndex);
                    if (!section) return;
                    var target = section.offsetTop + Math.max(0, Math.min(1, fraction)) * Math.max(section.offsetHeight - window.innerHeight, 0);
                    window.scrollTo(0, target);
                    setTimeout(reportProgress, 200);
                };
                window.jumpToComicLabChapter = function(chapterIndex) {
                    var section = document.getElementById('chapter-' + chapterIndex);
                    if (section) window.scrollTo(0, section.offsetTop);
                    setTimeout(reportProgress, 200);
                };
                setTimeout(reportProgress, 200);
            })();
            </script></body></html>
            """.trimIndent()
        )
        return output.toString()
    }

    private fun appendChapter(output: StringBuilder, book: EbookBook, chapter: EbookChapter) {
        output.append("<section id=\"chapter-")
            .append(chapter.index)
            .append("\" data-chapter-index=\"")
            .append(chapter.index)
            .append("\"><h2>")
            .append(escapeHtml(chapter.title))
            .append("</h2>")
            .append(sanitizeFragment(chapter.html, book, chapter.sourcePath))
            .append("</section>")
    }

    private fun sanitizeFragment(
        fragment: String,
        book: EbookBook,
        sourcePath: String?
    ): String {
        val withoutUnsafeBlocks = fragment
            .replace(UNSAFE_BLOCK_REGEX, "")
            .replace(COMMENT_REGEX, "")
        val output = StringBuilder()
        var cursor = 0

        TAG_REGEX.findAll(withoutUnsafeBlocks).forEach { match ->
            output.append(withoutUnsafeBlocks, cursor, match.range.first)
            val closing = match.groupValues[1].isNotEmpty()
            val tagName = match.groupValues[2].lowercase()
            val attributes = match.groupValues[3]
            if (tagName in SAFE_TEXT_TAGS) {
                if (closing) {
                    output.append("</$tagName>")
                } else {
                    output.append("<$tagName")
                        .append(safeAttributes(attributes))
                        .append(">")
                }
            } else if (tagName == "a" && !closing) {
                val href = attribute(attributes, "href")
                    ?.takeIf { it.startsWith("#") }
                output.append("<a")
                if (href != null) {
                    output.append(" href=\"").append(escapeHtmlAttribute(href)).append("\"")
                }
                output.append(">")
            } else if (tagName == "a" && closing) {
                output.append("</a>")
            } else if (tagName == "img" && !closing) {
                val reference = attribute(attributes, "recindex") ?: attribute(attributes, "src")
                val resourceId = reference?.let { resolveResourceId(it, book, sourcePath) }
                if (resourceId != null) {
                    output.append("<img src=\"ebook-resource://")
                        .append(escapeHtmlAttribute(resourceId))
                        .append("\" alt=\"\">")
                }
            } else if (tagName == "br" && !closing) {
                output.append("<br>")
            }
            cursor = match.range.last + 1
        }
        output.append(withoutUnsafeBlocks, cursor, withoutUnsafeBlocks.length)
        return output.toString()
    }

    private fun resolveResourceId(
        reference: String,
        book: EbookBook,
        sourcePath: String?
    ): String? {
        val normalized = reference.trim().removeSurrounding("\"").removeSurrounding("'")
        val mobiResourceIndex = if (sourcePath == null) {
            MOBI_RESOURCE_INDEX_REGEX.find(normalized)
                ?.groupValues
                ?.getOrNull(1)
                ?.let(::decodeMobiResourceIndex)
        } else {
            null
        }
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

    private fun safeAttributes(attributes: String): String {
        val builder = StringBuilder()
        attribute(attributes, "class")
            ?.let(::safeCssTokens)
            ?.takeIf(String::isNotEmpty)
            ?.let { builder.append(" class=\"").append(escapeHtmlAttribute(it)).append("\"") }
        attribute(attributes, "id")
            ?.let(::safeCssToken)
            ?.takeIf(String::isNotEmpty)
            ?.let { builder.append(" id=\"").append(escapeHtmlAttribute(it)).append("\"") }
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
    }

    private fun attribute(attributes: String, name: String): String? {
        val match = Regex(
            "(?is)(?:^|\\s)$name\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))"
        ).find(attributes) ?: return null
        return match.groupValues.drop(1).firstOrNull(String::isNotEmpty)
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
        val SAFE_TEXT_TAGS = setOf(
            "p", "div", "span", "h1", "h2", "h3", "h4", "h5", "h6",
            "em", "strong", "blockquote", "ul", "ol", "li", "table", "tr", "td", "th"
        )
        val TAG_REGEX = Regex("(?is)<(/?)([a-z][a-z0-9:-]*)([^>]*)>")
        val UNSAFE_BLOCK_REGEX = Regex("(?is)<(script|style|iframe|object|embed)\\b.*?</\\1\\s*>")
        val COMMENT_REGEX = Regex("(?is)<!--.*?-->")
        val CSS_IMPORT_REGEX = Regex("(?is)@import[^;]*;")
        val CSS_UNSAFE_DECLARATION_REGEX = Regex("(?is)(?:behavior|binding|expression)\\s*:[^;{}]+;?")
        val CSS_URL_REGEX = Regex("(?is)url\\s*\\([^)]*\\)")
        val MOBI_RESOURCE_INDEX_REGEX = Regex("(?i)^(?:kindle:embed:)?([0-9A-Z]+)(?:\\?.*)?$")
        const val MOBI_RESOURCE_RADIX = 32L
        const val MOBI_RESOURCE_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    }
}
