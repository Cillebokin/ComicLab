package com.example.comiclab.core.archive.cache

import android.util.Log
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

/** Coordinates in-process ownership, deletion, and writes for prepared reader directories. */
internal class PreparedReaderCacheLeaseRegistry(
    private val deletionExecutor: Executor = processDeletionExecutor,
    private val deleteDirectory: (File) -> Boolean = { it.deleteRecursively() },
    private val onDeletionFailure: (File, Throwable?) -> Unit = { directory, failure ->
        Log.w(TAG, "prepared reader cache deletion failed: ${directory.absolutePath}", failure)
    }
) {

    private val lock = Any()
    private val directories = mutableMapOf<String, DirectoryState>()
    private var nextLeaseId = 1L

    internal class Lease private constructor(
        private val registry: PreparedReaderCacheLeaseRegistry,
        internal val canonicalPath: String,
        internal val id: Long,
        internal val kind: LeaseKind
    ) : Closeable {
        private val closed = AtomicBoolean(false)

        val directory: File
            get() = File(canonicalPath)

        internal val isClosed: Boolean
            get() = closed.get()

        fun requestDelete() {
            if (!closed.get()) {
                registry.requestDelete(directory)
            }
        }

        fun requestDeleteAndClose() {
            requestDelete()
            close()
        }

        fun <T> withWriteLock(block: () -> T): T {
            check(kind == LeaseKind.WRITER) { "only writer leases may lock a prepared cache" }
            check(!closed.get()) { "writer lease is already closed" }
            return registry.withWriteLock(this, block)
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                registry.release(this)
            }
        }

        companion object {
            internal fun create(
                registry: PreparedReaderCacheLeaseRegistry,
                canonicalPath: String,
                id: Long,
                kind: LeaseKind
            ) = Lease(registry, canonicalPath, id, kind)
        }
    }

    internal enum class LeaseKind {
        PREPARE_OWNER,
        HANDOFF,
        READER_OWNER,
        USAGE,
        WRITER
    }

    private data class LeaseRecord(
        val kind: LeaseKind,
        val acquiredAtMillis: Long
    )

    private class DirectoryState {
        val leases = linkedMapOf<Long, LeaseRecord>()
        val writerLock = ReentrantLock()
        var activeWriteOperations = 0
        var pendingDelete = false
        var deleting = false
        var deletionFailed = false
    }

    fun acquirePrepareOwner(directory: File): Lease? = acquire(
        directory = directory,
        kind = LeaseKind.PREPARE_OWNER,
        cancelPendingDelete = true
    )

    fun acquireHandoffLease(
        directory: File,
        createdAtMillis: Long = System.currentTimeMillis()
    ): Lease? = acquire(
        directory = directory,
        kind = LeaseKind.HANDOFF,
        cancelPendingDelete = false,
        acquiredAtMillis = createdAtMillis
    )

    /** Acquires the reader owner and consumes one matching prepare-to-reader handoff atomically. */
    fun acquireReaderOwner(directory: File): Lease? {
        val canonicalPath = canonicalPath(directory) ?: return null
        return synchronized(lock) {
            val state = directories.getOrPut(canonicalPath) { DirectoryState() }
            if (state.deleting || state.deletionFailed) {
                return@synchronized null
            }

            state.pendingDelete = false
            val handoffId = state.leases.entries
                .firstOrNull { it.value.kind == LeaseKind.HANDOFF }
                ?.key
            if (handoffId != null) {
                state.leases.remove(handoffId)
            }

            createLeaseLocked(state, canonicalPath, LeaseKind.READER_OWNER)
        }
    }

    fun acquireUsageLease(directory: File): Lease? = acquire(
        directory = directory,
        kind = LeaseKind.USAGE,
        cancelPendingDelete = false
    )

    fun acquireWriterLease(directory: File): Lease? = acquire(
        directory = directory,
        kind = LeaseKind.WRITER,
        cancelPendingDelete = false
    )

    fun requestDelete(directory: File) {
        val canonicalPath = canonicalPath(directory) ?: return
        val pathToDelete = synchronized(lock) {
            val state = directories.getOrPut(canonicalPath) { DirectoryState() }
            state.pendingDelete = true
            reserveDeletionLocked(canonicalPath, state)
        }
        pathToDelete?.let(::scheduleDeletion)
    }

    /**
     * Requests deletion for a stale directory. Old handoffs may expire; real owners and active IO
     * leases never expire from this in-process registry.
     */
    fun requestDeleteIfStale(
        directory: File,
        maxAgeMs: Long,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (!directory.isDirectory || nowMillis - directory.lastModified() <= maxAgeMs) {
            return false
        }
        val canonicalPath = canonicalPath(directory) ?: return false
        val pathToDelete = synchronized(lock) {
            val state = directories.getOrPut(canonicalPath) { DirectoryState() }
            state.leases.entries.removeAll { (_, lease) ->
                lease.kind == LeaseKind.HANDOFF && nowMillis - lease.acquiredAtMillis > maxAgeMs
            }
            if (state.leases.isNotEmpty() || state.deleting) {
                return@synchronized null
            }

            state.pendingDelete = true
            reserveDeletionLocked(canonicalPath, state)
        }
        pathToDelete?.let(::scheduleDeletion)
        return pathToDelete != null
    }

    private fun acquire(
        directory: File,
        kind: LeaseKind,
        cancelPendingDelete: Boolean,
        acquiredAtMillis: Long = System.currentTimeMillis()
    ): Lease? {
        val canonicalPath = canonicalPath(directory) ?: return null
        return synchronized(lock) {
            val state = directories.getOrPut(canonicalPath) { DirectoryState() }
            if (state.deleting || state.deletionFailed) {
                return@synchronized null
            }
            if (state.pendingDelete) {
                if (!cancelPendingDelete) {
                    val hasActiveOwner = state.leases.values.any {
                        it.kind == LeaseKind.PREPARE_OWNER || it.kind == LeaseKind.READER_OWNER
                    }
                    val mayContinueOwnedWork = kind == LeaseKind.USAGE || kind == LeaseKind.WRITER
                    if (!hasActiveOwner || !mayContinueOwnedWork) {
                        return@synchronized null
                    }
                } else {
                    state.pendingDelete = false
                }
            }
            createLeaseLocked(state, canonicalPath, kind, acquiredAtMillis)
        }
    }

    private fun createLeaseLocked(
        state: DirectoryState,
        canonicalPath: String,
        kind: LeaseKind,
        acquiredAtMillis: Long = System.currentTimeMillis()
    ): Lease {
        val id = nextLeaseId++
        state.leases[id] = LeaseRecord(kind, acquiredAtMillis)
        return Lease.create(this, canonicalPath, id, kind)
    }

    private fun release(lease: Lease) {
        val pathToDelete = synchronized(lock) {
            val state = directories[lease.canonicalPath] ?: return@synchronized null
            state.leases.remove(lease.id)
            reserveDeletionLocked(lease.canonicalPath, state).also {
                if (it == null) {
                    removeIdleStateLocked(lease.canonicalPath, state)
                }
            }
        }
        pathToDelete?.let(::scheduleDeletion)
    }

    private fun <T> withWriteLock(lease: Lease, block: () -> T): T {
        val writerLock = synchronized(lock) {
            val state = directories[lease.canonicalPath]
                ?: error("prepared reader cache lease is no longer registered")
            check(!lease.isClosed && state.leases[lease.id]?.kind == LeaseKind.WRITER) {
                "writer lease is no longer active"
            }
            state.activeWriteOperations++
            state.writerLock
        }

        writerLock.lock()
        try {
            return block()
        } finally {
            writerLock.unlock()
            val pathToDelete = synchronized(lock) {
                val state = directories[lease.canonicalPath]
                if (state != null) {
                    state.activeWriteOperations--
                    reserveDeletionLocked(lease.canonicalPath, state).also {
                        if (it == null) {
                            removeIdleStateLocked(lease.canonicalPath, state)
                        }
                    }
                } else {
                    null
                }
            }
            pathToDelete?.let(::scheduleDeletion)
        }
    }

    private fun reserveDeletionLocked(canonicalPath: String, state: DirectoryState): String? {
        if (!state.pendingDelete || state.deleting || state.leases.isNotEmpty() ||
            state.activeWriteOperations > 0
        ) {
            return null
        }
        state.deleting = true
        state.deletionFailed = false
        return canonicalPath
    }

    private fun removeIdleStateLocked(canonicalPath: String, state: DirectoryState) {
        if (state.leases.isEmpty() && state.activeWriteOperations == 0 &&
            !state.pendingDelete && !state.deleting
        ) {
            directories.remove(canonicalPath)
        }
    }

    private fun scheduleDeletion(canonicalPath: String) {
        try {
            deletionExecutor.execute {
                val directory = File(canonicalPath)
                var failure: Throwable? = null
                val deleted = try {
                    deleteDirectory(directory)
                } catch (throwable: Throwable) {
                    failure = throwable
                    false
                }

                synchronized(lock) {
                    val state = directories[canonicalPath]
                    if (state != null && state.deleting) {
                        if (deleted) {
                            directories.remove(canonicalPath)
                        } else {
                            state.deleting = false
                            state.deletionFailed = true
                            state.pendingDelete = true
                        }
                    }
                }
                if (!deleted) {
                    onDeletionFailure(directory, failure)
                }
            }
        } catch (throwable: Throwable) {
            synchronized(lock) {
                directories[canonicalPath]?.let { state ->
                    state.deleting = false
                    state.deletionFailed = true
                    state.pendingDelete = true
                }
            }
            onDeletionFailure(File(canonicalPath), throwable)
        }
    }

    private fun canonicalPath(directory: File): String? = canonicalPathFor(directory)

    companion object {
        private const val TAG = "PreparedCacheLease"

        private val processDeletionExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "ComicLabPreparedCacheCleanup").apply { isDaemon = true }
        }

        val process = PreparedReaderCacheLeaseRegistry()

        fun canonicalPathFor(directory: File): String? = runCatching {
            directory.canonicalPath
        }.getOrElse {
            runCatching { directory.absoluteFile.toPath().normalize().toString() }.getOrNull()
        }
    }
}

/** A task owns its lease from enqueue until actual completion or cancellation before it starts. */
internal class PreparedReaderCacheLeaseTask(
    private val lease: PreparedReaderCacheLeaseRegistry.Lease,
    private val shouldRun: () -> Boolean = { true },
    private val block: () -> Unit
) : Runnable {
    private val state = AtomicInteger(QUEUED)

    override fun run() {
        if (!state.compareAndSet(QUEUED, RUNNING)) {
            return
        }
        try {
            if (shouldRun()) {
                block()
            }
        } finally {
            state.set(FINISHED)
            lease.close()
        }
    }

    fun cancelBeforeRun() {
        if (state.compareAndSet(QUEUED, CANCELLED)) {
            lease.close()
        }
    }

    private companion object {
        const val QUEUED = 0
        const val RUNNING = 1
        const val FINISHED = 2
        const val CANCELLED = 3
    }
}
