package com.example.comiclab

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.system.exitProcess

object CrashLogManager {

    private const val CRASH_LOG_DIR_NAME = "crash_logs"
    private const val CRASH_EXPORT_DIR_NAME = "crash_exports"
    private const val READER_DIAGNOSTIC_PREFS_NAME = "reader_diagnostics"
    private const val READER_CHECKPOINT_KEY = "latest_checkpoint"
    private const val READER_RENDER_KEY = "latest_render"
    private const val READER_RENDER_PHASE_KEY = "latest_render_phase"
    private const val READER_SESSION_KEY = "active_reader_session"
    private const val MAX_CRASH_LOG_COUNT = 10
    private val timestampFormatter = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT)
    private val displayTimeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)

    @Volatile
    private var installed = false

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
        val latest = latestCrashLog(context) ?: return null
        val exportDir = File(context.cacheDir, CRASH_EXPORT_DIR_NAME).apply {
            mkdirs()
        }
        val exportFile = File(exportDir, "ComicLab_crash_latest.txt")
        latest.copyTo(exportFile, overwrite = true)
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
            filePath = file.absolutePath,
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
            filePath = file.absolutePath,
            fileSize = file.length(),
            fileModified = file.lastModified()
        ) + " token=${UUID.randomUUID()}"
        diagnosticPrefs(context).edit()
            .putString(READER_SESSION_KEY, marker)
            .remove(READER_RENDER_KEY)
            .putString(READER_RENDER_PHASE_KEY, "session_started")
            .commit()
        return marker
    }

    fun recordReaderSessionFinished(context: Context, marker: String?) {
        val prefs = diagnosticPrefs(context)
        if (!isSameReaderSessionMarker(prefs.getString(READER_SESSION_KEY, null), marker)) {
            return
        }
        prefs.edit()
            .remove(READER_SESSION_KEY)
            .remove(READER_RENDER_PHASE_KEY)
            .commit()
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
            filePath = file.absolutePath,
            fileSize = file.length(),
            fileModified = file.lastModified(),
            position = position,
            kind = kind,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            bitmapBytes = null,
            usedHeapKb = (runtime.totalMemory() - runtime.freeMemory()) / 1024L,
            maxHeapKb = runtime.maxMemory() / 1024L
        )
        diagnosticPrefs(context).edit()
            .putString(READER_RENDER_KEY, snapshot)
            .putString(READER_RENDER_PHASE_KEY, "started")
            .commit()
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
            filePath = file.absolutePath,
            fileSize = file.length(),
            fileModified = file.lastModified(),
            position = position,
            kind = kind,
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
        val stackTrace = StringWriter().use { writer ->
            PrintWriter(writer).use { printWriter ->
                throwable.printStackTrace(printWriter)
            }
            writer.toString()
        }

        return buildString {
            appendLine("ComicLab Crash Log")
            appendLine("Time: ${displayTimeFormatter.format(time)}")
            appendLine("Thread: ${thread.name}")
            appendLine("App: ${appVersionText(context)}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            val diagnostics = diagnosticPrefs(context)
            appendLine("Reader session: ${diagnostics.getString(READER_SESSION_KEY, "none")}")
            appendLine("Reader checkpoint: ${diagnostics.getString(READER_CHECKPOINT_KEY, "unknown")}")
            appendLine("Reader render phase: ${diagnostics.getString(READER_RENDER_PHASE_KEY, "unknown")}")
            appendLine("Reader render: ${diagnostics.getString(READER_RENDER_KEY, "unknown")}")
            appendLine()
            appendLine(stackTrace)
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
        val recovered = runCatching {
            logFile.writeText(
                buildString {
                    appendLine("ComicLab Suspected Reader Interruption")
                    appendLine("Time: ${displayTimeFormatter.format(now)}")
                    appendLine("Reason: reader session was still active when the process started")
                    appendLine("Reader session: $marker")
                    appendLine("Reader checkpoint: ${prefs.getString(READER_CHECKPOINT_KEY, "unknown")}")
                    appendLine("Reader render phase: ${prefs.getString(READER_RENDER_PHASE_KEY, "unknown")}")
                    appendLine("Reader render: ${prefs.getString(READER_RENDER_KEY, "unknown")}")
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

    private fun diagnosticPrefs(context: Context) = context.applicationContext
        .getSharedPreferences(READER_DIAGNOSTIC_PREFS_NAME, Context.MODE_PRIVATE)
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
