package com.example.comiclab

import android.os.SystemClock
import android.os.Trace
import android.util.Log

/** Debug-only timings for repeatable on-device performance checks. */
internal object ComicLabPerformance {

    private const val TAG = "ComicLabPerf"
    private const val MAX_TRACE_SECTION_NAME_LENGTH = 127

    fun nowNanos(): Long? =
        if (BuildConfig.DEBUG) SystemClock.elapsedRealtimeNanos() else null

    internal fun resolveStartedAtNanos(
        upstreamStartedAtNanos: Long?,
        fallbackStartedAtNanos: Long?
    ): Long? = upstreamStartedAtNanos?.takeIf { it > 0L }
        ?: fallbackStartedAtNanos?.takeIf { it > 0L }

    fun <T> trace(sectionName: String, block: () -> T): T {
        if (!BuildConfig.DEBUG) {
            return block()
        }

        Trace.beginSection(sectionName.take(MAX_TRACE_SECTION_NAME_LENGTH))
        return try {
            block()
        } finally {
            Trace.endSection()
        }
    }

    fun record(event: String, startedAtNanos: Long?, fields: String = "") {
        if (!BuildConfig.DEBUG || startedAtNanos == null) {
            return
        }

        Log.i(
            TAG,
            formatEvent(
                event = event,
                startedAtNanos = startedAtNanos,
                finishedAtNanos = SystemClock.elapsedRealtimeNanos(),
                fields = fields
            )
        )
    }

    internal fun formatEvent(
        event: String,
        startedAtNanos: Long,
        finishedAtNanos: Long,
        fields: String
    ): String {
        val elapsedMillis = (finishedAtNanos - startedAtNanos)
            .coerceAtLeast(0L) / NANOS_PER_MILLISECOND
        return buildString {
            append("event=")
            append(event)
            append(" elapsedMs=")
            append(elapsedMillis)
            if (fields.isNotBlank()) {
                append(' ')
                append(fields.trim().replace('\n', ' ').replace('\r', ' '))
            }
        }
    }

    private const val NANOS_PER_MILLISECOND = 1_000_000L
}
