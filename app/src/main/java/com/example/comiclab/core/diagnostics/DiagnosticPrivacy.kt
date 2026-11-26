package com.example.comiclab.core.diagnostics

import android.content.Context
import com.example.comiclab.core.store.BookcaseFolderPaths
import com.example.comiclab.core.store.PrivacyStore
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap

internal class DiagnosticPrivacy(context: Context) {
    private val directories = runCatching { PrivacyStore.privacyDirectories(context) }.getOrNull()
    private val literalPaths by lazy {
        directories.orEmpty().map { directory ->
            Regex("""(?<![\p{L}\p{N}_./\\-])${Regex.escape(directory)}(?=$|[/\\\s,:"'\])])""")
        }
    }

    fun filePath(path: String): String {
        if (path == REDACTED || path.any { it.isISOControl() }) return REDACTED
        val canonical = PrivacyStore.resolveCanonicalPath(path) ?: return REDACTED
        return if (canonical.any { it.isISOControl() } || isPrivate(canonical)) REDACTED else canonical
    }

    private fun isPrivate(canonical: String): Boolean =
        directories?.any { BookcaseFolderPaths.isPathInDirectory(canonical, it) } ?: true

    data class Record(val text: String, val filePath: String) {
        val redacted: Boolean get() = filePath == REDACTED
    }

    fun record(value: String?, pdf: Boolean = false): Record? {
        val match = value?.let(recordLine::matchEntire) ?: return null
        val (prefix, path, metadata, details) = match.destructured
        val safePath = if (pdf && path.length >= LEGACY_PDF_PATH_LIMIT) REDACTED else filePath(path)
        return Record(prefix + safePath + metadata + safeFields(details), safePath)
    }

    fun pdfSnapshot(value: String): String {
        val lines = value.lines()
        val source = lines.firstOrNull { it.startsWith("app=") }?.let { record(it, pdf = true) }
        return lines.mapNotNull { line ->
            when {
                line.startsWith("app=") || line == "file=$REDACTED" -> source?.text ?: "file=$REDACTED"
                source?.redacted == false -> if (containsPrivatePath(line)) REDACTED else line
                else -> safePdfLine(line)
            }
        }.joinToString("\n")
    }

    fun isActivePrivatePdf(snapshot: String?): Boolean {
        if (snapshot?.startsWith("source=live\n") != true) return false
        return snapshot.lineSequence().any { pdfHeader.matches(it) && it.endsWith(" state=active") } &&
            snapshot.lineSequence().any { it == "file=$REDACTED" || record(it, pdf = true)?.redacted == true }
    }

    fun containsPrivatePath(text: String): Boolean {
        val marks = directories ?: return true
        if (marks.isEmpty()) return false
        val completePaths = mutableListOf<IntRange>()
        for (quoted in quotedPath.findAll(text)) {
            val candidate = quoted.value.substring(1, quoted.value.lastIndex)
            val canonical = PrivacyStore.resolveCanonicalPath(candidate) ?: continue
            if (referencesPrivatePath(candidate, canonical)) return true
            completePaths += quoted.range
        }
        for (start in absoluteStart.findAll(text)) {
            val offset = start.range.first
            if (completePaths.any { offset in it }) continue
            val tail = text.substring(offset)
            val end = unquotedPathEnd.find(tail)?.range?.first ?: tail.length
            val candidate = tail.substring(0, end).trimEnd()
            val paths = listOf(candidate) + pathSuffixStart.findAll(candidate)
                .map { candidate.substring(0, it.range.first).trimEnd() }.toList().asReversed()
            // 优先用真实存在的最长路径，保留目录名里的空格、括号和箭头。
            val existing = paths.firstOrNull { File(it).exists() }
            for (path in existing?.let(::listOf) ?: paths) {
                val canonical = PrivacyStore.resolveCanonicalPath(path) ?: continue
                if (referencesPrivatePath(path, canonical)) return true
                // 缺失文件只确认已存在的父目录，错误尾注仍可继续做字面匹配。
                val confirmedLength = if (path == existing) path.length else existingParentEnd(path)
                if (confirmedLength > 0) completePaths += offset until offset + confirmedLength
            }
        }
        return literalPaths.any { pattern ->
            pattern.findAll(text).any { match ->
                completePaths.none { match.range.first in it && match.range.last in it }
            }
        }
    }

    private fun existingParentEnd(path: String): Int {
        var end = path.lastIndexOfAny(pathSeparators)
        while (end > 0) {
            if (File(path.substring(0, end)).isDirectory) return end
            end = path.lastIndexOfAny(pathSeparators, end - 1)
        }
        return 0
    }

    private fun referencesPrivatePath(path: String, canonical: String): Boolean =
        isPrivate(canonical) || (path != canonical && generateSequence(File(path).parentFile) { it.parentFile }
            .any { PrivacyStore.resolveCanonicalPath(it)?.let(::isPrivate) == true })

    fun throwableText(throwable: Throwable, redactMessages: Boolean): String = buildString {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val pending = ArrayDeque<Pair<String, Throwable>>()
        pending.addLast("Throwable: " to throwable)
        while (pending.isNotEmpty()) {
            val (prefix, current) = pending.removeLast()
            if (!seen.add(current)) continue
            append(prefix).append(current.javaClass.name)
            if (!redactMessages) current.message?.let { message ->
                append(": ").append(if (containsPrivatePath(message)) REDACTED else message)
            }
            appendLine()
            current.stackTrace.forEach { frame ->
                val text = "\tat $frame"
                if (stackFrame.matches(text)) appendLine(text)
            }
            current.cause?.let { pending.addLast("Caused by: " to it) }
            current.suppressed.reversed().forEach { pending.addLast("Suppressed: " to it) }
        }
    }

    fun historicalLog(text: Sequence<String>): String = buildString {
        var crashLog = false
        var ordinaryReader = false
        var inStack = false
        var keepMessages = false
        text.forEachIndexed { index, line ->
            if (index == 0) crashLog = line == "ComicLab Crash Log"
            val label = readerLabels.firstOrNull { line.startsWith(it) }
            val safe = when {
                !inStack && crashLog && line in stackHeaders -> {
                    inStack = true
                    keepMessages = ordinaryReader && line == READER_STACK_HEADER
                    "Stack trace:"
                }
                inStack -> if (stackFrame.matches(line)) line else exceptionHeader(line, keepMessages, typed = true)
                label != null -> {
                    val details = record(line.removePrefix(label))
                    if (label == "Reader session: ") ordinaryReader =
                        details?.redacted == false && isReaderSessionMarkerActive(details.text)
                    label + (details?.text ?: "unknown")
                }
                line.startsWith("app=") -> record(line, pdf = true)?.text ?: "file=$REDACTED"
                line in logHeaders || timeLine.matches(line) || appLine.matches(line) ||
                    androidLine.matches(line) || phaseLine.matches(line) ||
                    stackFrame.matches(line) -> line
                else -> exceptionHeader(line, keepMessage = false) ?: safePdfLine(line)
            }
            if (safe != null) appendLine(safe)
        }
    }

    private fun exceptionHeader(line: String, keepMessage: Boolean, typed: Boolean = false): String? {
        val match = (if (typed) typedExceptionLine else exceptionLine).matchEntire(line) ?: return null
        val (type, message) = match.destructured
        return if (keepMessage && message.isNotEmpty()) {
            "$type: ${if (containsPrivatePath(message)) REDACTED else message}"
        } else type
    }

    companion object {
        const val REDACTED = "[privacy-redacted]"
        const val READER_STACK_HEADER = "Stack trace: active ordinary reader"
        const val STACK_HEADER = "Stack trace: unverified source"
        private const val LEGACY_PDF_PATH_LIMIT = 512
        private val stackHeaders = setOf(READER_STACK_HEADER, STACK_HEADER)
        private val recordLine = Regex(
            """(file=|state=active file=|app=[\w.-]+ code=\d+ build=[\w.-]+ file=)(.+)( size=\d+ modified=-?\d+)(.*)"""
        )
        private const val ABSOLUTE_PREFIX = """(?:[A-Za-z]:[/\\]|/)"""
        private val pathSeparators = if (File.separatorChar == '\\') charArrayOf('/', '\\') else charArrayOf('/')
        private val absoluteStart = Regex("""(?<![\p{L}\p{N}_./\\])$ABSOLUTE_PREFIX""")
        // 保留相对目标前的箭头供候选路径切分，仍用单独的 > 结束路径。
        private val unquotedPathEnd = Regex(""" -> (?=["']?$ABSOLUTE_PREFIX)|[\r\n\t"'<]|(?<! -)>|>(?! )""")
        private val quotedPath =
            Regex(""""$ABSOLUTE_PREFIX[^"\r\n]*"|'$ABSOLUTE_PREFIX[^'\r\n]*'""")
        private val pathSuffixStart = Regex(""" ->(?= |$)|:\s+|\s+\((?=[^/\\]*\)\s*$)""")
        private val stackFrame = Regex(
            """\s*at [\w.$/<>-]+\((?:[\w.$-]+\.(?:kt|java|cpp|c)(?::-?\d+)?|Native Method|Unknown Source)\)"""
        )
        private val exceptionLine =
            Regex("""\s*((?:Caused by: |Suppressed: )?[\w.$]*(?:Exception|Error|Throwable))(?:: (.*))?""")
        private val typedExceptionLine =
            Regex("""\s*((?:Throwable: |Caused by: |Suppressed: )[\w.$]+)(?:: (.*))?""")
        private val readerLabels = listOf("Reader session: ", "Reader checkpoint: ", "Reader render: ")
        private val logHeaders = setOf("ComicLab Crash Log", "ComicLab Suspected Reader Interruption",
            "Reason: reader session was still active when the process started")
        private val timeLine = Regex("""Time: \d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}""")
        private val appLine = Regex("""App: v?[\w.-]+ \(\d+\)""")
        private val androidLine = Regex("""Android: [\w.-]+ \(API \d+\)""")
        private val phaseLine = Regex("Reader render phase: (session_started|started|completed|recovered|unknown)")
        private val numeric = Regex("""-?\d+(?:\.\d+)?|(?:\d+|\?)x(?:\d+|\?)|\?|true|false|unknown""")
        private val token = Regex("[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")
        private val pdfHeader = Regex("""PDF reader timing v1 session=${token.pattern} startedAtEpochMs=\d+ state=(active|closed)""")
        private val kinds = setOf("FULL", "ZOOM_FULL", "PREVIEW", "TILE", "unknown")
        private val events = setOf(
            "queued", "started", "native_wait", "native_open", "native_render", "native_done", "native_close",
            "native_closed", "bounds_wait", "bounds_open", "bounds_done", "session_open", "session_ready",
            "legacy_diagnostic", "legacy_diagnostic_begin", "legacy_diagnostic_finish", "legacy_diagnostic_finish_begin",
            "closed", "cache_accepted", "draw", "bound", "viewport", "placeholder", "bind_cpu", "abandoned",
            "cancelled", "task_returned", "stale", "discard", "decode_failure", "decode_empty", "bitmap_ready",
            "awaiting_bitmap", "awaiting_draw"
        )
        private val metrics = setOf(
            "page", "position", "offset", "target", "bitmapBytes", "usedHeapKb", "maxHeapKb", "task", "view", "tMs",
            "ageMs", "phaseAgeMs", "queueMaxMs", "nativeMaxMs", "placeholderMaxMs", "bindMaxMs", "drawAfterBindMaxMs",
            "legacyDiagnosticMaxMs", "activeTasks", "activePlaceholders", "dropped", "queueWaitMs", "gateWaitMs",
            "renderMs", "elapsedMs", "afterBindMs", "placeholderMs", "lastCacheToBindMs", "preload", "pending",
            "first", "last", "direction", "fast", "ok", "writeMs", "savedAtEpochMs", "process"
        )
        private val pdfLineStarts = listOf(
            "task=", "view=", "slow-active ", "slow ", "tMs=", "queueMaxMs=", "activeTasks=", "Latest viewport: ",
            "savedAtEpochMs="
        )

        fun renderKind(kind: String): String = kind.takeIf { it in kinds } ?: "unknown"

        private fun safeFields(details: String): String = details.split(' ').filter { field ->
            val key = field.substringBefore('=')
            val value = field.substringAfter('=', "")
            when (key) {
                "kind" -> value in kinds
                "phase", "event" -> value in events
                "token" -> token.matches(value)
                else -> key in metrics && numeric.matches(value)
            }
        }.joinToString("") { " $it" }

        private fun safePdfLine(line: String): String? {
            if (pdfHeader.matches(line)) return line
            if (!pdfLineStarts.any { line.startsWith(it) }) return null
            val fields = safeFields(line)
            return fields.trimStart().takeIf { it.isNotEmpty() }
        }
    }
}
