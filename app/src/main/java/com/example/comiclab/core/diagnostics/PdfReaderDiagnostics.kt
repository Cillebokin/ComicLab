package com.example.comiclab.core.diagnostics

import android.content.Context
import android.os.SystemClock
import com.example.comiclab.BuildConfig
import androidx.core.content.edit
import com.example.comiclab.core.store.PrefsGateway
import java.io.File
import java.util.UUID

/** Release 也记录；热路径只更新有界内存，持久化由阅读器定时或退出时异步刷新。 */
internal object PdfReaderDiagnostics {
    // 与逐页检查点分开，避免每次旧诊断写入都连带序列化整份较大的快照。
    private const val PREFS = "pdf_reader_timing"
    private const val KEY = "latest_pdf_timing"
    private val ownershipLock = Any()
    @Volatile private var latest: Session? = null
    private data class Scope(val session: Session, val task: Long)
    private val scope = ThreadLocal<Scope>()

    fun begin(file: File): Session = synchronized(ownershipLock) {
        Session("app=${BuildConfig.VERSION_NAME} code=${BuildConfig.VERSION_CODE} build=${BuildConfig.BUILD_TYPE} " +
            "file=${file.absolutePath.replace('\n', ' ').replace('\r', ' ').take(512)} " +
            "size=${file.length()} modified=${file.lastModified()}", clock = SystemClock::elapsedRealtimeNanos)
            .also { latest = it }
    }

    fun persist(context: Context, session: Session) = synchronized(ownershipLock) {
        if (latest === session) PrefsGateway.open(context.applicationContext, PREFS).edit {
            putString(KEY, "savedAtEpochMs=${System.currentTimeMillis()} process=${android.os.Process.myPid()}\n" + session.snapshot())
        }
    }

    fun snapshot(context: Context): String? = latest?.snapshot()?.let { "source=live\n$it" }
        ?: PrefsGateway.open(context.applicationContext, PREFS).getString(KEY, null)?.let {
            "source=persisted (possibly from a previous process)\n$it"
        }

    fun <T> withTask(session: Session?, task: Long, action: () -> T): T {
        if (session == null) return action()
        val previous = scope.get()
        scope.set(Scope(session, task))
        return try { action() } finally {
            if (previous == null) scope.remove() else scope.set(previous)
        }
    }

    fun nativePhase(phase: String, details: String = "", durationNanos: Long = 0) {
        scope.get()?.let { it.session.phase(it.task, phase, details, durationNanos) }
    }

    class Session(private val identity: String, private val clock: () -> Long) {
        @Volatile internal var eventSinkForTest: ((String) -> Unit)? = null
        private val lock = Any()
        private val token = UUID.randomUUID().toString()
        private val startedWallTime = System.currentTimeMillis()
        private val started = clock()
        private var closedAt: Long? = null
        private val history = ArrayDeque<String>()
        private val counters = linkedMapOf<String, Long>()
        private data class Task(val page: Int, val kind: String, var phase: String, val since: Long,
            var phaseSince: Long = since)
        private data class Placeholder(val page: Int, val since: Long, var boundAt: Long? = null, var cachedAt: Long? = null,
            val trace: ArrayDeque<String> = ArrayDeque())
        private val slowEvidence = ArrayDeque<String>()
        private val tasks = linkedMapOf<Long, Task>()
        private val placeholders = linkedMapOf<Long, Placeholder>()
        private var placeholderSequence = 0L
        private var dropped = 0L
        private var queueMax = 0L
        private var nativeMax = 0L
        private var legacyDiagnosticMax = 0L
        private var placeholderMax = 0L
        private var drawAfterBindMax = 0L
        private var bindMax = 0L
        private var latestViewport = "none"

        private fun event(name: String, fields: String = "", page: Int? = null) {
            counters[name] = (counters[name] ?: 0) + 1
            if (history.size == 64) { history.removeFirst(); dropped++ }
            val line = "tMs=${ms(clock() - started)} event=$name $fields".trimEnd()
            history.addLast(line)
            eventSinkForTest?.invoke(line)
            if (page != null) placeholders.values.filter { it.page == page }.forEach {
                if (it.trace.size == 32) it.trace.removeFirst()
                it.trace.addLast(line)
                if (name == "cache_accepted") it.cachedAt = clock()
            }
        }

        private fun makeRoom() {
            if (tasks.size == 64) {
                // 排队任务可以丢采样，仍在阻塞工作线程的原生调用必须保留。
                val queued = tasks.entries.firstOrNull { it.value.phase == "queued" }
                tasks.remove(queued?.key ?: tasks.keys.first())
                dropped++
            }
        }

        fun queued(id: Long, position: Int, kind: String, preload: Boolean, pending: Int) = synchronized(lock) {
            if (closedAt != null) return@synchronized
            makeRoom()
            tasks[id] = Task(position + 1, kind, "queued", clock())
            event("queued", "task=$id page=${position + 1} kind=$kind preload=$preload pending=$pending", position + 1)
        }

        fun started(id: Long, queueNanos: Long, position: Int? = null, kind: String? = null) = synchronized(lock) {
            if (closedAt != null) return@synchronized
            if (id !in tasks && position != null && kind != null) {
                makeRoom()
                tasks[id] = Task(position + 1, kind, "started", clock())
            }
            tasks[id]?.phase = "started"
            tasks[id]?.phaseSince = clock()
            queueMax = maxOf(queueMax, queueNanos)
            event("started", "task=$id queueWaitMs=${ms(queueNanos)}", tasks[id]?.page)
        }

        fun phase(id: Long, phase: String, details: String = "", durationNanos: Long = 0) = synchronized(lock) {
            if (closedAt != null) return@synchronized
            tasks[id]?.phase = phase
            tasks[id]?.phaseSince = clock()
            if (phase == "native_done") nativeMax = maxOf(nativeMax, durationNanos)
            if (phase.startsWith("legacy_diagnostic")) legacyDiagnosticMax = maxOf(legacyDiagnosticMax, durationNanos)
            event(phase, "task=$id $details", tasks[id]?.page)
        }

        fun finished(id: Long) = synchronized(lock) {
            if (closedAt != null) return@synchronized
            tasks.remove(id)
            // 任务返回不等于已缓存、绑定或绘制；分别由独立事件证明。
            event("task_returned", "task=$id")
        }

        fun cancelled(id: Long, reason: String) = synchronized(lock) {
            if (closedAt != null) return@synchronized
            tasks.remove(id)
            event("cancelled", "task=$id reason=$reason")
        }

        fun note(event: String, fields: String) = synchronized(lock) {
            if (closedAt == null) {
                if (event == "viewport") latestViewport = fields
                event(event, fields)
            }
        }

        fun placeholderStarted(position: Int): Long = synchronized(lock) {
            if (closedAt != null) return@synchronized 0
            if (placeholders.size == 16) { placeholders.remove(placeholders.keys.first()); dropped++ }
            val id = ++placeholderSequence
            placeholders[id] = Placeholder(position + 1, clock())
            event("placeholder", "page=${position + 1} view=$id")
            id
        }

        fun placeholderBound(id: Long, source: String) = synchronized(lock) {
            if (closedAt != null) return@synchronized
            val item = placeholders[id] ?: return@synchronized
            item.boundAt = clock()
            val elapsed = clock() - item.since
            placeholderMax = maxOf(placeholderMax, elapsed)
            event("bound", "page=${item.page} view=$id source=$source placeholderMs=${ms(elapsed)} lastCacheToBindMs=${item.cachedAt?.let { ms(clock() - it) } ?: "unknown"}", item.page)
        }

        fun binding(position: Int, durationNanos: Long) = synchronized(lock) {
            if (closedAt != null) return@synchronized
            bindMax = maxOf(bindMax, durationNanos)
            event("bind_cpu", "page=${position + 1} elapsedMs=${ms(durationNanos)}")
        }

        fun drawn(id: Long) = synchronized(lock) {
            if (closedAt != null) return@synchronized
            val item = placeholders[id] ?: return@synchronized
            val bound = item.boundAt ?: return@synchronized
            placeholders.remove(id)
            val elapsed = clock() - bound
            drawAfterBindMax = maxOf(drawAfterBindMax, elapsed)
            event("draw", "page=${item.page} view=$id afterBindMs=${ms(elapsed)}")
            retainSlow(id, item, "draw")
        }

        fun abandoned(id: Long, reason: String) = synchronized(lock) {
            if (closedAt != null) return@synchronized
            val item = placeholders.remove(id) ?: return@synchronized
            event("abandoned", "page=${item.page} view=$id reason=$reason ageMs=${ms(clock() - item.since)}")
            retainSlow(id, item, "abandoned:$reason")
        }

        private fun retainSlow(id: Long, item: Placeholder, outcome: String) {
            if (clock() - item.since < 250_000_000) return
            if (slowEvidence.size == 8) slowEvidence.removeFirst()
            slowEvidence.addLast("slow view=$id page=${item.page} outcome=$outcome ageMs=${ms(clock() - item.since)} viewport=$latestViewport\n" +
                item.trace.joinToString("\n") + "\n" + history.last())
        }

        fun close() = synchronized(lock) {
            if (closedAt == null) { event("closed"); closedAt = clock() }
        }

        fun snapshot(): String = synchronized(lock) {
            val now = closedAt ?: clock()
            buildString {
                appendLine("PDF reader timing v1 session=$token startedAtEpochMs=$startedWallTime state=${if (closedAt == null) "active" else "closed"}")
                appendLine(identity)
                appendLine("Latest viewport: $latestViewport")
                appendLine("Binding/draw callbacks are CPU-side observations, not proof of GPU presentation. Incomplete work is not automatically a failure.")
                appendLine("Native render time includes waits inside the platform PDF renderer. gateWaitMs covers this session's Java gate only.")
                appendLine("queueMaxMs=${ms(queueMax)} nativeMaxMs=${ms(nativeMax)} placeholderMaxMs=${ms(placeholderMax)} bindMaxMs=${ms(bindMax)} drawAfterBindMaxMs=${ms(drawAfterBindMax)} legacyDiagnosticMaxMs=${ms(legacyDiagnosticMax)}")
                appendLine("activeTasks=${tasks.size} activePlaceholders=${placeholders.size} dropped=$dropped")
                appendLine("counts=" + counters.entries.joinToString(" ") { "${it.key}:${it.value}" })
                tasks.forEach { (id, item) -> appendLine("task=$id page=${item.page} kind=${item.kind} phase=${item.phase} ageMs=${ms(now - item.since)} phaseAgeMs=${ms(now - item.phaseSince)}") }
                placeholders.forEach { (id, item) -> appendLine("view=$id page=${item.page} phase=${if (item.boundAt == null) "awaiting_bitmap" else "awaiting_draw"} ageMs=${ms(now - item.since)}") }
                appendLine("Slow placeholder evidence (last 8 completed >=250ms; holder lifetime may include offscreen time):")
                slowEvidence.forEach(::appendLine)
                placeholders.filterValues { now - it.since >= 250_000_000 }.forEach { (id, item) ->
                    appendLine("slow-active view=$id page=${item.page}")
                    item.trace.forEach(::appendLine)
                }
                history.forEach(::appendLine)
            }
        }

        private fun ms(nanos: Long): String {
            val value = nanos.coerceAtLeast(0)
            val tenths = value / 100_000 + if (value % 100_000 >= 50_000) 1 else 0
            return "${tenths / 10}.${tenths % 10}"
        }
    }
}
