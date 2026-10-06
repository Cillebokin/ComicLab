package com.example.comiclab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.AsyncTask
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor

class ReaderSubsamplingImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SubsamplingScaleImageView(context, attrs) {

    var onReaderTap: (() -> Unit)? = null

    private val readerMainHandler = Handler(Looper.getMainLooper())
    private val readerDecodeExecutor = ReaderDecodeExecutor()
    private val transitionCallbacks = mutableListOf<(Result<Unit>) -> Unit>()
    private val closeCallbacks = mutableListOf<(Result<Unit>) -> Unit>()

    private var requestedImageSource: ImageSource? = null
    private var appliedImageSource: ImageSource? = null
    private var sourceMayBeInstalled = false
    private var imageTransitionRunning = false
    private var permanentlyClosing = false
    private var readerCloseResult: Result<Unit>? = null

    private val tapDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                performClick()
                return true
            }
        }
    )

    init {
        setExecutor(readerDecodeExecutor)
        setBackgroundColor(Color.BLACK)
        setMinimumScaleType(SCALE_TYPE_CENTER_INSIDE)
        setPanLimit(PAN_LIMIT_INSIDE)
        setMaxScale(MAX_READER_SCALE)
        setDoubleTapZoomScale(DOUBLE_TAP_READER_SCALE)
        setDoubleTapZoomDuration(DOUBLE_TAP_ZOOM_DURATION_MS)
        setDoubleTapZoomStyle(ZOOM_FOCUS_CENTER)
        setOrientation(ORIENTATION_USE_EXIF)
        setPreferredBitmapConfig(Bitmap.Config.ARGB_8888)
        isClickable = true
    }

    /** Change the source only after prior Subsampling tasks and their main-thread callbacks drain. */
    fun setReaderImage(source: ImageSource) {
        if (permanentlyClosing) {
            return
        }
        requestedImageSource = source
        visibility = if (source === appliedImageSource &&
            !imageTransitionRunning &&
            !readerDecodeExecutor.hasPendingTasks()
        ) {
            VISIBLE
        } else {
            INVISIBLE
        }
        isEnabled = true
        requestReaderImageTransition()
    }

    /** Clear or recycle the current source without blocking the UI thread. */
    fun clearReaderImage(onCleared: (Result<Unit>) -> Unit = {}) {
        if (permanentlyClosing) {
            val completed = readerCloseResult
            if (completed != null) {
                onCleared(completed)
            } else {
                transitionCallbacks += onCleared
            }
            return
        }
        transitionCallbacks += onCleared
        requestedImageSource = null
        visibility = INVISIBLE
        requestReaderImageTransition()
    }

    /** Permanently close this view after its decoder initialization and tile callbacks finish. */
    fun closeReaderImage(onClosed: (Result<Unit>) -> Unit) {
        val completed = readerCloseResult
        if (completed != null) {
            onClosed(completed)
            return
        }
        closeCallbacks += onClosed
        if (permanentlyClosing) {
            return
        }
        permanentlyClosing = true
        requestedImageSource = null
        onReaderTap = null
        isEnabled = false
        visibility = GONE
        requestReaderImageTransition()
    }

    private fun requestReaderImageTransition() {
        if (imageTransitionRunning) {
            return
        }
        if (requestedImageSource === appliedImageSource &&
            !sourceMayBeInstalled &&
            !readerDecodeExecutor.hasPendingTasks()
        ) {
            if (permanentlyClosing) {
                readerDecodeExecutor.seal()
                finishReaderClose(Result.success(Unit))
            } else {
                dispatchTransitionCallbacks(Result.success(Unit))
            }
            return
        }

        imageTransitionRunning = true
        readerDecodeExecutor.drain(::finishReaderImageTransition)
    }

    private fun finishReaderImageTransition() {
        val recycled = runCatching { super.recycle() }
        if (recycled.isFailure) {
            imageTransitionRunning = false
            readerDecodeExecutor.seal()
            val failure = Result.failure<Unit>(
                recycled.exceptionOrNull() ?: IllegalStateException("Subsampling recycle failed")
            )
            if (permanentlyClosing) {
                finishReaderClose(failure)
            } else {
                dispatchTransitionCallbacks(failure)
            }
            return
        }

        sourceMayBeInstalled = false
        appliedImageSource = null
        if (permanentlyClosing) {
            readerDecodeExecutor.seal()
            imageTransitionRunning = false
            finishReaderClose(Result.success(Unit))
            return
        }

        readerDecodeExecutor.resume()
        val nextSource = requestedImageSource
        var sourceFailure: Throwable? = null
        if (nextSource != null) {
            sourceMayBeInstalled = true
            runCatching { super.setImage(nextSource) }
                .onSuccess {
                    appliedImageSource = nextSource
                    visibility = VISIBLE
                }
                .onFailure {
                    sourceFailure = it
                    Log.w(READER_VIEW_LOG_TAG, "Subsampling image source could not be applied", it)
                }
        }
        imageTransitionRunning = false
        if (sourceFailure != null) {
            requestedImageSource = null
            visibility = INVISIBLE
            dispatchTransitionCallbacks(
                Result.failure(
                    sourceFailure ?: IllegalStateException("Subsampling source application failed")
                )
            )
            return
        }
        dispatchTransitionCallbacks(Result.success(Unit))
        if (requestedImageSource !== appliedImageSource) {
            requestReaderImageTransition()
        }
    }

    private fun dispatchTransitionCallbacks(result: Result<Unit>) {
        val callbacks = transitionCallbacks.toList()
        transitionCallbacks.clear()
        callbacks.forEach { callback ->
            runCatching { callback(result) }
                .onFailure { Log.w(READER_VIEW_LOG_TAG, "Subsampling transition callback failed", it) }
        }
    }

    private fun finishReaderClose(result: Result<Unit>) {
        if (readerCloseResult != null) {
            return
        }
        readerCloseResult = result
        dispatchTransitionCallbacks(result)
        val callbacks = closeCallbacks.toList()
        closeCallbacks.clear()
        callbacks.forEach { callback ->
            runCatching { callback(result) }
                .onFailure { Log.w(READER_VIEW_LOG_TAG, "Subsampling close callback failed", it) }
        }
    }

    /** Tracks each library AsyncTask through its main-thread result callback, including nested tiles. */
    private inner class ReaderDecodeExecutor : Executor {
        private val lock = Any()
        private val delegate: Executor = AsyncTask.THREAD_POOL_EXECUTOR
        private val tasks = mutableSetOf<TrackedTask>()
        private val drainCallbacks = mutableListOf<() -> Unit>()
        private var acceptingTasks = true
        private var permanentlySealed = false
        private var drainDispatchScheduled = false

        override fun execute(command: Runnable) {
            val task = TrackedTask(command)
            val shouldSubmit = synchronized(lock) {
                tasks += task
                acceptingTasks && !permanentlySealed
            }
            if (!shouldSubmit) {
                task.cancelBeforeStart()
                return
            }
            runCatching { delegate.execute(task) }
                .onFailure {
                    Log.w(READER_VIEW_LOG_TAG, "Subsampling task submission failed", it)
                    task.cancelBeforeStart()
                }
        }

        fun hasPendingTasks(): Boolean = synchronized(lock) { tasks.isNotEmpty() }

        fun drain(onDrained: () -> Unit) {
            val pending = synchronized(lock) {
                acceptingTasks = false
                drainCallbacks += onDrained
                tasks.toList()
            }
            pending.forEach(TrackedTask::cancelBeforeStart)
            dispatchDrainCallbacksIfIdle()
        }

        fun resume() {
            synchronized(lock) {
                if (!permanentlySealed) {
                    acceptingTasks = true
                }
            }
        }

        fun seal() {
            synchronized(lock) {
                acceptingTasks = false
                permanentlySealed = true
            }
        }

        private fun taskFinished(task: TrackedTask) {
            synchronized(lock) { tasks.remove(task) }
            dispatchDrainCallbacksIfIdle()
        }

        private fun dispatchDrainCallbacksIfIdle() {
            val shouldPost = synchronized(lock) {
                if (tasks.isNotEmpty() || drainCallbacks.isEmpty() || drainDispatchScheduled) {
                    false
                } else {
                    drainDispatchScheduled = true
                    true
                }
            }
            if (!shouldPost) {
                return
            }
            readerMainHandler.post {
                val callbacks = synchronized(lock) {
                    drainDispatchScheduled = false
                    if (tasks.isEmpty()) {
                        drainCallbacks.toList().also { drainCallbacks.clear() }
                    } else {
                        emptyList()
                    }
                }
                callbacks.forEach { it() }
                dispatchDrainCallbacksIfIdle()
            }
        }

        private inner class TrackedTask(private val command: Runnable) : Runnable {
            private val stateLock = Any()
            private var started = false
            private var cancelledBeforeStart = false

            override fun run() {
                val shouldRun = synchronized(stateLock) {
                    if (cancelledBeforeStart) {
                        false
                    } else {
                        started = true
                        true
                    }
                }
                if (!shouldRun) {
                    return
                }
                try {
                    command.run()
                } finally {
                    // FutureTask.done() posts AsyncTask.onPostExecute/onCancelled before this post.
                    readerMainHandler.post { taskFinished(this) }
                }
            }

            fun cancelBeforeStart() {
                val cancelled = synchronized(stateLock) {
                    if (started || cancelledBeforeStart) {
                        false
                    } else {
                        cancelledBeforeStart = true
                        (command as? FutureTask<*>)?.cancel(false)
                        true
                    }
                }
                if (cancelled) {
                    (delegate as? ThreadPoolExecutor)?.remove(this)
                    // FutureTask cancellation posts the library callback before this completion.
                    readerMainHandler.post { taskFinished(this) }
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        tapDetector.onTouchEvent(event)
        // SubsamplingScaleImageView 会在放大页面到达边缘时主动释放父级拦截，
        // 外层不要再次覆盖该状态，否则 RecyclerView 无法接管翻页手势。
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        onReaderTap?.invoke()
        return true
    }

    companion object {
        private const val READER_VIEW_LOG_TAG = "ComicLabReader"
        private const val MAX_READER_SCALE = 6f
        private const val DOUBLE_TAP_READER_SCALE = 2.5f
        private const val DOUBLE_TAP_ZOOM_DURATION_MS = 160
    }
}
