package com.example.comiclab.ebook.html

import com.example.comiclab.ebook.mobi.MobiBook
import com.example.comiclab.ebook.mobi.MobiChapter

data class EbookStyle(
    val fontScale: Float = 1.0f,
    val lineHeight: Float = 1.6f,
    val backgroundColor: String = "#101010",
    val foregroundColor: String = "#E8E8E8"
)

class EbookHtmlRenderer {

    fun render(book: MobiBook, style: EbookStyle): String {
        val safeStyle = style.normalized()
        val output = StringBuilder()
        output.append("<!doctype html><html><head>")
        output.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        output.append("<meta charset=\"utf-8\">")
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
                    var position = window.scrollY + 40;
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

    private fun appendChapter(output: StringBuilder, book: MobiBook, chapter: MobiChapter) {
        output.append("<section id=\"chapter-")
            .append(chapter.index)
            .append("\" data-chapter-index=\"")
            .append(chapter.index)
            .append("\"><h2>")
            .append(escapeHtml(chapter.title))
            .append("</h2>")
            .append(sanitizeFragment(chapter.html, book))
            .append("</section>")
    }

    private fun sanitizeFragment(fragment: String, book: MobiBook): String {
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
                output.append(if (closing) "</$tagName>" else "<$tagName>")
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
                val resourceId = reference?.let { resolveResourceId(it, book) }
                if (resourceId != null) {
                    output.append("<img src=\"mobi-resource://")
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

    private fun resolveResourceId(reference: String, book: MobiBook): String? {
        val normalized = reference.trim().removeSurrounding("\"").removeSurrounding("'")
        return book.resources.firstOrNull { it.id == normalized }?.id
            ?: normalized.removePrefix("mobi-resource://")
                .takeIf { value -> book.resources.any { it.id == value } }
            ?: normalized.toIntOrNull()?.let { recordIndex ->
                book.resources.firstOrNull { it.recordIndex == recordIndex }?.id
                    ?: book.resources.getOrNull(recordIndex)?.id
            }
            ?: Regex("(\\d+)").find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?.let { index -> book.resources.getOrNull(index)?.id }
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
    }
}
