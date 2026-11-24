package com.example.comiclab.core.diagnostics

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.example.comiclab.core.store.PrefsGateway
import com.example.comiclab.ui.privacy.PrivacySessionManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.system.exitProcess

object CrashLogManager {

    internal const val CRASH_LOG_DIR_NAME = "crash_logs"
    private const val CRASH_EXPORT_DIR_NAME = "crash_exports"
    private const val READER_DIAGNOSTIC_PREFS_NAME = "reader_diagnostics"
    private const val READER_CHECKPOINT_KEY = "latest_checkpoint"
    private const val READER_RENDER_KEY = "latest_render"
    private const val READER_RENDER_PHASE_KEY = "latest_render_phase"
    private const val READER_SESSION_KEY = "active_reader_session"
    private const val READER_RENDER_PERSIST_INTERVAL_MS = 1_000L
    private const val MAX_CRASH_LOG_COUNT = 10
    private val timestampFormatter = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT)
    private val displayTimeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)

    @Volatile
    private var installed = false

    private val readerRenderPersistenceLock = Any()
    private var lastReaderRenderPersistElapsedRealtime = Long.MIN_VALUE
    private val readerCleanupSource = ThreadLocal<String>()

    fun install(context: Context) {
        if (installed) {
            return
        }

        installed = true
        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                writeCrashLog(appContext, thread, throwable)
            }
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable)
            } else {
                exitProcess(2)
            }
        }
        recoverInterruptedReaderSession(appContext)
    }

    fun latestCrashLog(context: Context): File? {
        return crashLogDir(context)
            .listFiles()
            ?.filter { it.isFile && it.name.endsWith(".txt") }
            ?.maxByOrNull { it.lastModified() }
    }

    fun createLatestCrashLogExport(context: Context): File? {
        val latest = latestCrashLog(context)
        val prefs = diagnosticPrefs(context)
        val privacy = DiagnosticPrivacy(context)
        val pdf = PdfReaderDiagnostics.snapshot(context, privacy)
        val render = prefs.getString(READER_RENDER_KEY, null)
        val checkpoint = prefs.getString(READER_CHECKPOINT_KEY, null)
        if (latest == null && pdf == null && render == null && checkpoint == null) return null
        val exportDir = File(context.cacheDir, CRASH_EXPORT_DIR_NAME).apply {
            mkdirs()
        }
        val exportFile = File(exportDir, "ComicLab_crash_latest.txt")
        exportFile.bufferedWriter().use { writer ->
            writer.appendLine("ComicLab Reader Diagnostics")
            writer.appendLine("App: ${appVersionText(context)}")
            writer.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            writer.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            writer.appendLine("Reader render: ${privacy.record(render)?.text ?: "none"}")
            writer.appendLine("Reader checkpoint: ${privacy.record(checkpoint)?.text ?: "none"}")
            writer.appendLine(pdf ?: "No PDF timing snapshot available")
            writer.appendLine("Latest crash/interruption log (may be from another session):")
            if (latest != null) latest.bufferedReader().use { writer.write(privacy.historicalLog(it.lineSequence())) }
            else writer.appendLine("No crash log available")
        }
        return exportFile
    }

    fun recordReaderCheckpoint(
        context: Context,
        file: File,
        position: Int,
        offset: Int,
        durable: Boolean = false
    ) {
        val snapshot = formatReaderCheckpoint(
            filePath = DiagnosticPrivacy(context).filePath(file.path),
            fileSize = file.length(),
            fileModified = file.lastModified(),
            position = position,
            offset = offset
        )
        val editor = diagnosticPrefs(context).edit()
            .putString(READER_CHECKPOINT_KEY, snapshot)
        if (durable) {
            editor.commit()
        } else {
            editor.apply()
        }
    }

    fun recordReaderSessionStarted(context: Context, file: File): String {
        val marker = formatReaderSessionMarker(
            filePath = DiagnosticPrivacy(context).filePath(file.path),
            fileSize = file.length(),
            fileModified = file.lastModified()
        ) + " token=${UUID.randomUUID()}"
        synchronized(readerRenderPersistenceLock) {
            lastReaderRenderPersistElapsedRealtime = Long.MIN_VALUE
        }
        diagnosticPrefs(context).edit()
            .putString(READER_SESSION_KEY, marker)
            .remove(READER_RENDER_KEY)
            .putString(READER_RENDER_PHASE_KEY, "session_started")
            .commit()
        return marker
    }

    fun finishReaderSession(context: Context, marker: String?, cleanup: () -> Unit) {
        val source = marker?.let { DiagnosticPrivacy(context).record(it)?.filePath ?: DiagnosticPrivacy.REDACTED }
        withReaderCleanupSource(source, cleanup)
        val prefs = diagnosticPrefs(context)
        if (!isSameReaderSessionMarker(prefs.getString(READER_SESSION_KEY, null), marker)) {
            return
        }
        prefs.edit()
            .remove(READER_SESSION_KEY)
            .remove(READER_RENDER_PHASE_KEY)
            .commit()
    }

    internal fun readerCleanupAction(context: Context, file: File, action: () -> Unit): Runnable {
        val source = readerCleanupSource.get()?.takeIf { it == DiagnosticPrivacy.REDACTED }
            ?: DiagnosticPrivacy(context).filePath(file.path)
        return Runnable { withReaderCleanupSource(source, action) }
    }

    private fun withReaderCleanupSource(source: String?, action: () -> Unit) {
        val previous = readerCleanupSource.get()
        if (source != null) readerCleanupSource.set(source)
        // 清理中的异常应继续交给原有未捕获异常处理器，此时来源仍绑定在出错线程。
        action()
        if (previous == null) readerCleanupSource.remove() else readerCleanupSource.set(previous)
    }

    fun recordReaderRenderStarted(
        context: Context,
        file: File,
        position: Int,
        kind: String,
        targetWidth: Int?,
        targetHeight: Int?
    ) {
        val runtime = Runtime.getRuntime()
        val snapshot = formatReaderRender(
            filePath = DiagnosticPrivacy(context).filePath(file.path),
            fileSize = file.length(),
            fileModified = file.lastModified(),
            position = position,
            kind = DiagnosticPrivacy.renderKind(kind),
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            bitmapBytes = null,
            usedHeapKb = (runtime.totalMemory() - runtime.freeMemory()) / 1024L,
            maxHeapKb = runtime.maxMemory() / 1024L
        )
        val nowElapsedRealtime = SystemClock.elapsedRealtime()
        val persistSynchronously = synchronized(readerRenderPersistenceLock) {
            val shouldPersist = shouldPersistReaderRenderStart(
                lastPersistElapsedRealtime = lastReaderRenderPersistElapsedRealtime,
                nowElapsedRealtime = nowElapsedRealtime,
                minimumIntervalMillis = READER_RENDER_PERSIST_INTERVAL_MS
            )
            if (shouldPersist) {
                lastReaderRenderPersistElapsedRealtime = nowElapsedRealtime
            }
            shouldPersist
        }
        val editor = diagnosticPrefs(context).edit()
            .putString(READER_RENDER_KEY, snapshot)
            .putString(READER_RENDER_PHASE_KEY, "started")
        if (persistSynchronously) {
            editor.commit()
        } else {
            editor.apply()
        }
    }

    fun recordReaderRenderCompleted(context: Context) {
        diagnosticPrefs(context).edit()
            .putString(READER_RENDER_PHASE_KEY, "completed")
            .apply()
    }

    fun recordReaderRender(
        context: Context,
        file: File,
        position: Int,
        kind: String,
        targetWidth: Int?,
        targetHeight: Int?,
        bitmapBytes: Int? = null,
        usedHeapKb: Long? = null,
        maxHeapKb: Long? = null
    ) {
        val runtime = Runtime.getRuntime()
        val snapshot = formatReaderRender(
            filePath = DiagnosticPrivacy(context).filePath(file.path),
            fileSize = file.length(),
            fileModified = file.lastModified(),
            position = position,
            kind = DiagnosticPrivacy.renderKind(kind),
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            bitmapBytes = bitmapBytes,
            usedHeapKb = usedHeapKb ?: (runtime.totalMemory() - runtime.freeMemory()) / 1024L,
            maxHeapKb = maxHeapKb ?: runtime.maxMemory() / 1024L
        )
        diagnosticPrefs(context).edit()
            .putString(READER_RENDER_KEY, snapshot)
            .apply()
    }

    private fun writeCrashLog(context: Context, thread: Thread, throwable: Throwable) {
        val logDir = crashLogDir(context).apply {
            mkdirs()
        }
        val now = Date()
        val logFile = File(logDir, "crash_${timestampFormatter.format(now)}.txt")
        logFile.writeText(buildCrashText(context, thread, throwable, now))
        trimOldCrashLogs(logDir)
    }

    private fun buildCrashText(
        context: Context,
        thread: Thread,
        throwable: Throwable,
        time: Date
    ): String {
        val privacy = DiagnosticPrivacy(context)
        val diagnostics = diagnosticPrefs(context)
        val marker = diagnostics.getString(READER_SESSION_KEY, null)
        val activeReader = isReaderSessionMarkerActive(marker)
        val session = privacy.record(marker)
        val checkpoint = privacy.record(diagnostics.getString(READER_CHECKPOINT_KEY, null))
        val renderPhase = safeRenderPhase(diagnostics.getString(READER_RENDER_PHASE_KEY, null))
        val render = privacy.record(diagnostics.getString(READER_RENDER_KEY, null))
        val pdf = PdfReaderDiagnostics.snapshot(context, privacy)
        val cleanupSource = if (thread === Thread.currentThread()) readerCleanupSource.get() else null
        val redactMessages = PrivacySessionManager.isInPrivacySpace ||
            (cleanupSource != null && privacy.filePath(cleanupSource) == DiagnosticPrivacy.REDACTED) ||
            (activeReader && session?.redacted != false) ||
            (renderPhase == "started" && render?.redacted != false) ||
            (!activeReader && privacy.isActivePrivatePdf(pdf))
        val verifiedOrdinaryReader = !redactMessages && activeReader && cleanupSource == null

        return buildString {
            appendLine("ComicLab Crash Log")
            appendLine("Time: ${displayTimeFormatter.format(time)}")
            appendLine("Thread: ${if (redactMessages || privacy.containsPrivatePath(thread.name)) DiagnosticPrivacy.REDACTED else thread.name}")
            appendLine("App: ${appVersionText(context)}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Reader session: ${session?.text ?: "none"}")
            appendLine("Reader checkpoint: ${checkpoint?.text ?: "unknown"}")
            appendLine("Reader render phase: $renderPhase")
            appendLine("Reader render: ${render?.text ?: "unknown"}")
            appendLine(pdf ?: "No PDF timing snapshot available")
            appendLine()
            appendLine(if (verifiedOrdinaryReader) DiagnosticPrivacy.READER_STACK_HEADER else DiagnosticPrivacy.STACK_HEADER)
            append(privacy.throwableText(throwable, redactMessages))
        }
    }

    private fun appVersionText(context: Context): String {
        return runCatching {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val versionCode = packageInfo.longVersionCode
            "${packageInfo.versionName} ($versionCode)"
        }.getOrDefault("unknown")
    }

    private fun trimOldCrashLogs(logDir: File) {
        val logs = logDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        logs.drop(MAX_CRASH_LOG_COUNT).forEach { oldLog ->
            oldLog.delete()
        }
    }

    private fun recoverInterruptedReaderSession(context: Context) {
        val prefs = diagnosticPrefs(context)
        val marker = prefs.getString(READER_SESSION_KEY, null) ?: return
        if (!isReaderSessionMarkerActive(marker)) {
            prefs.edit().remove(READER_SESSION_KEY).apply()
            return
        }

        val logDir = crashLogDir(context).apply {
            mkdirs()
        }
        val now = Date()
        val logFile = File(logDir, "reader_interrupted_${timestampFormatter.format(now)}.txt")
        val privacy = DiagnosticPrivacy(context)
        val recovered = runCatching {
            logFile.writeText(
                buildString {
                    appendLine("ComicLab Suspected Reader Interruption")
                    appendLine("Time: ${displayTimeFormatter.format(now)}")
                    appendLine("Reason: reader session was still active when the process started")
                    appendLine("Reader session: ${privacy.record(marker)?.text ?: "unknown"}")
                    appendLine("Reader checkpoint: ${privacy.record(prefs.getString(READER_CHECKPOINT_KEY, null))?.text ?: "unknown"}")
                    appendLine("Reader render phase: ${safeRenderPhase(prefs.getString(READER_RENDER_PHASE_KEY, null))}")
                    appendLine("Reader render: ${privacy.record(prefs.getString(READER_RENDER_KEY, null))?.text ?: "unknown"}")
                    appendLine(PdfReaderDiagnostics.snapshot(context, privacy) ?: "No PDF timing snapshot available")
                }
            )
            trimOldCrashLogs(logDir)
            true
        }.getOrDefault(false)
        if (recovered) {
            prefs.edit()
                .remove(READER_SESSION_KEY)
                .putString(READER_RENDER_PHASE_KEY, "recovered")
                .apply()
        }
    }

    private fun crashLogDir(context: Context): File {
        return File(context.filesDir, CRASH_LOG_DIR_NAME)
    }

    private fun diagnosticPrefs(context: Context) =
        PrefsGateway.open(context.applicationContext, READER_DIAGNOSTIC_PREFS_NAME)

    private val renderPhases = setOf("session_started", "started", "completed", "recovered")

    private fun safeRenderPhase(value: String?): String = value?.takeIf { it in renderPhases } ?: "unknown"
}

internal fun formatReaderCheckpoint(
    filePath: String,
    fileSize: Long,
    fileModified: Long,
    position: Int,
    offset: Int
): String {
    return "file=$filePath size=$fileSize modified=$fileModified " +
        "page=${position + 1} position=$position offset=$offset"
}

internal fun formatReaderSessionMarker(
    filePath: String,
    fileSize: Long,
    fileModified: Long
): String {
    return "state=active file=$filePath size=$fileSize modified=$fileModified"
}

internal fun isReaderSessionMarkerActive(marker: String?): Boolean {
    return marker?.startsWith("state=active ") == true
}

internal fun isSameReaderSessionMarker(actual: String?, expected: String?): Boolean {
    return actual != null && expected != null && actual == expected
}

internal fun shouldPersistReaderRenderStart(
    lastPersistElapsedRealtime: Long,
    nowElapsedRealtime: Long,
    minimumIntervalMillis: Long
): Boolean {
    return lastPersistElapsedRealtime == Long.MIN_VALUE ||
        nowElapsedRealtime - lastPersistElapsedRealtime >= minimumIntervalMillis.coerceAtLeast(0L)
}

internal fun formatReaderRender(
    filePath: String,
    fileSize: Long,
    fileModified: Long,
    position: Int,
    kind: String,
    targetWidth: Int?,
    targetHeight: Int?,
    bitmapBytes: Int?,
    usedHeapKb: Long,
    maxHeapKb: Long
): String {
    return "file=$filePath size=$fileSize modified=$fileModified " +
        "page=${position + 1} position=$position kind=$kind " +
        "target=${targetWidth ?: "?"}x${targetHeight ?: "?"} " +
        "bitmapBytes=${bitmapBytes ?: "?"} usedHeapKb=$usedHeapKb maxHeapKb=$maxHeapKb"
}

internal fun formatReaderDecodeQueueTiming(
    position: Int,
    kind: String,
    queueWaitNanos: Long,
    pendingBefore: Int
): String {
    return "event=decode_queue page=${position + 1} position=$position kind=$kind " +
        "queueWaitMs=${formatTimingMillis(queueWaitNanos)} pendingBefore=${pendingBefore.coerceAtLeast(0)}"
}

internal fun formatPdfPageRenderTiming(
    position: Int,
    kind: String,
    gateWaitNanos: Long,
    renderNanos: Long,
    targetWidth: Int,
    targetHeight: Int
): String {
    return "event=page_render page=${position + 1} position=$position kind=$kind " +
        "gateWaitMs=${formatTimingMillis(gateWaitNanos)} " +
        "renderMs=${formatTimingMillis(renderNanos)} target=${targetWidth}x$targetHeight"
}

private fun formatTimingMillis(nanos: Long): String =
    String.format(Locale.ROOT, "%.1f", nanos.coerceAtLeast(0L) / 1_000_000.0)
