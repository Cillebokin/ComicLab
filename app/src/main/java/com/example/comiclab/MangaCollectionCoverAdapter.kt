package com.example.comiclab

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.LruCache
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.ImageView
import androidx.appcompat.content.res.AppCompatResources
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MangaCollectionCoverAdapter(
    private val sources: List<MangaCollectionCoverSource>,
    private val bindCover: (MangaCollectionCoverSource, ImageView) -> Unit,
    private val onCoverClick: (() -> Unit)? = null,
    private val onCoverLongClick: (() -> Boolean)? = null
) : RecyclerView.Adapter<MangaCollectionCoverAdapter.CoverViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CoverViewHolder {
        val imageView = ImageView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                parent.resources.getDimensionPixelSize(R.dimen.file_item_collection_cover_width),
                ViewGroup.LayoutParams.MATCH_PARENT
            ).also { params ->
                params.marginEnd = parent.resources.getDimensionPixelSize(R.dimen.space_xs)
            }
            background = android.graphics.drawable.ColorDrawable(Color.BLACK)
            scaleType = ImageView.ScaleType.CENTER_CROP
            isClickable = onCoverClick != null
            isFocusable = onCoverClick != null
            foreground = AppCompatResources.getDrawable(
                context,
                R.drawable.bg_bookcase_cover_pressed
            )
            setOnClickListener {
                onCoverClick?.invoke()
            }
            setOnLongClickListener(onCoverLongClick?.let { listener ->
                View.OnLongClickListener { listener() }
            })
            isLongClickable = onCoverLongClick != null
        }
        return CoverViewHolder(imageView)
    }

    override fun onBindViewHolder(holder: CoverViewHolder, position: Int) {
        val source = sources[position]
        holder.imageView.tag = source.file.absolutePath
        holder.imageView.contentDescription = holder.imageView.context.getString(
            R.string.collection_cover_accessibility,
            source.file.name,
            position + 1,
            sources.size
        )
        holder.imageView.setBackgroundResource(R.drawable.bg_file_icon_frame)
        holder.imageView.scaleType = ImageView.ScaleType.CENTER_INSIDE
        holder.imageView.setImageResource(R.drawable.png_press_package_icon)
        bindCover(source, holder.imageView)
    }

    override fun onViewRecycled(holder: CoverViewHolder) {
        holder.imageView.tag = null
        holder.imageView.setImageDrawable(null)
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = sources.size

    class CoverViewHolder(val imageView: ImageView) : RecyclerView.ViewHolder(imageView)
}

class MangaCollectionCoverRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : RecyclerView(context, attrs) {

    private val touchRouter = MangaCollectionCoverTouchRouter(
        ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    )
    private var onBlankClick: (() -> Unit)? = null
    private var onBlankLongClick: (() -> Boolean)? = null
    private var onRowPressStateChanged: ((Boolean) -> Unit)? = null
    // RecyclerView 不会把空白区的长按转交给 View 的长按监听器
    private val blankLongPressRunnable = Runnable {
        if (touchRouter.isBlankLongPressEligible) {
            touchRouter.onLongClick()
            onBlankLongClick?.invoke()
        }
    }

    internal val hasActiveTouchGesture: Boolean
        get() = touchRouter.isTouchActive

    internal fun onLongPressConsumed() {
        touchRouter.onLongClick()
    }

    init {
        layoutManager = RoutedHorizontalLayoutManager(context) {
            canBookcaseCoverScrollHorizontally(
                direction = touchRouter.currentDirection,
                isSettling = scrollState == RecyclerView.SCROLL_STATE_SETTLING
            )
        }
        addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(
                recyclerView: RecyclerView,
                event: MotionEvent
            ): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_CANCEL -> touchRouter.cancel()
                    MotionEvent.ACTION_POINTER_DOWN -> touchRouter.onPointerDown()
                }
                return false
            }
        })
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                if (scrollState == RecyclerView.SCROLL_STATE_SETTLING) {
                    stopScroll()
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                touchRouter.onDown(
                    isBlank = findChildViewUnder(event.x, event.y) == null,
                    x = event.x,
                    y = event.y
                )
                removeCallbacks(blankLongPressRunnable)
                if (touchRouter.isBlankLongPressEligible && onBlankLongClick != null) {
                    postDelayed(
                        blankLongPressRunnable,
                        ViewConfiguration.getLongPressTimeout().toLong()
                    )
                }
                dispatchRowPressState()
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1) {
                    val direction = touchRouter.onMove(event.x, event.y)
                    if (!touchRouter.isBlankLongPressEligible) {
                        removeCallbacks(blankLongPressRunnable)
                    }
                    dispatchRowPressState()
                    when (direction) {
                        BookcaseGestureDirection.HORIZONTAL ->
                            parent?.requestDisallowInterceptTouchEvent(true)

                        BookcaseGestureDirection.VERTICAL ->
                            parent?.requestDisallowInterceptTouchEvent(false)

                        BookcaseGestureDirection.UNDECIDED -> Unit
                    }
                } else {
                    touchRouter.onPointerDown()
                    removeCallbacks(blankLongPressRunnable)
                    dispatchRowPressState()
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                touchRouter.onPointerDown()
                removeCallbacks(blankLongPressRunnable)
                dispatchRowPressState()
            }
        }

        var handled = false
        try {
            handled = super.dispatchTouchEvent(event)
            if (action == MotionEvent.ACTION_UP && touchRouter.onUp()) {
                onBlankClick?.invoke()
            }
            return handled
        } finally {
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    if (!handled) {
                        removeCallbacks(blankLongPressRunnable)
                        touchRouter.cancel()
                        dispatchRowPressState()
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(blankLongPressRunnable)
                    touchRouter.cancel()
                    dispatchRowPressState()
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(blankLongPressRunnable)
        touchRouter.cancel()
        dispatchRowPressState()
        parent?.requestDisallowInterceptTouchEvent(false)
        super.onDetachedFromWindow()
    }

    fun setOnBlankClickListener(listener: (() -> Unit)?) {
        onBlankClick = listener
    }

    fun setOnBlankLongClickListener(listener: (() -> Boolean)?) {
        onBlankLongClick = listener
        if (listener == null) {
            removeCallbacks(blankLongPressRunnable)
        }
    }

    fun setOnRowPressStateChangedListener(listener: ((Boolean) -> Unit)?) {
        if (onRowPressStateChanged !== listener) {
            onRowPressStateChanged?.invoke(false)
        }
        onRowPressStateChanged = listener
        dispatchRowPressState()
    }

    private fun dispatchRowPressState() {
        onRowPressStateChanged?.invoke(touchRouter.isPressFeedbackActive)
    }

    private class RoutedHorizontalLayoutManager(
        context: Context,
        private val canScrollForCurrentGesture: () -> Boolean
    ) : LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false) {
        override fun canScrollHorizontally(): Boolean =
            canScrollForCurrentGesture() && super.canScrollHorizontally()
    }
}

internal enum class BookcaseGestureDirection {
    UNDECIDED,
    HORIZONTAL,
    VERTICAL
}

internal class MangaCollectionCoverTouchRouter(
    private val touchSlop: Float
) {
    private var touchActive = false
    private var startedOnBlank = false
    private var tapEligible = false
    private var pressFeedbackCancelled = false
    private var multiplePointers = false
    private var direction = BookcaseGestureDirection.UNDECIDED
    private var downX = 0f
    private var downY = 0f

    val isTouchActive: Boolean
        get() = touchActive

    val currentDirection: BookcaseGestureDirection
        get() = direction

    val isPressFeedbackActive: Boolean
        get() = touchActive && !pressFeedbackCancelled && !multiplePointers

    val isBlankLongPressEligible: Boolean
        get() = touchActive && startedOnBlank && tapEligible &&
            direction == BookcaseGestureDirection.UNDECIDED

    fun onDown(isBlank: Boolean, x: Float, y: Float) {
        touchActive = true
        startedOnBlank = isBlank
        tapEligible = true
        pressFeedbackCancelled = false
        multiplePointers = false
        direction = BookcaseGestureDirection.UNDECIDED
        downX = x
        downY = y
    }

    fun onMove(x: Float, y: Float): BookcaseGestureDirection {
        if (!touchActive) {
            return direction
        }

        val deltaX = x - downX
        val deltaY = y - downY
        val absX = kotlin.math.abs(deltaX)
        val absY = kotlin.math.abs(deltaY)
        if (deltaX * deltaX + deltaY * deltaY > touchSlop * touchSlop) {
            tapEligible = false
            pressFeedbackCancelled = true
        }

        val axisLockThreshold = touchSlop * 2f
        when (direction) {
            BookcaseGestureDirection.UNDECIDED -> when {
                absX > axisLockThreshold && absX > absY * AXIS_DOMINANCE_RATIO ->
                    direction = BookcaseGestureDirection.HORIZONTAL

                absY > axisLockThreshold && absY > absX * AXIS_DOMINANCE_RATIO ->
                    direction = BookcaseGestureDirection.VERTICAL
            }

            BookcaseGestureDirection.HORIZONTAL -> {
                if (absY > axisLockThreshold && absY > absX * AXIS_DOMINANCE_RATIO) {
                    direction = BookcaseGestureDirection.VERTICAL
                }
            }

            BookcaseGestureDirection.VERTICAL -> {
                if (absX > axisLockThreshold && absX > absY * AXIS_DOMINANCE_RATIO) {
                    direction = BookcaseGestureDirection.HORIZONTAL
                }
            }
        }
        return direction
    }

    fun onUp(): Boolean {
        val isBlankTap = touchActive && tapEligible && startedOnBlank &&
            direction == BookcaseGestureDirection.UNDECIDED
        cancel()
        return isBlankTap
    }

    fun onPointerDown() {
        startedOnBlank = false
        tapEligible = false
        multiplePointers = true
    }

    fun onLongClick() {
        startedOnBlank = false
        tapEligible = false
    }

    fun cancel() {
        touchActive = false
        startedOnBlank = false
        tapEligible = false
        pressFeedbackCancelled = false
        multiplePointers = false
        direction = BookcaseGestureDirection.UNDECIDED
    }

    companion object {
        private const val AXIS_DOMINANCE_RATIO = 1.25f
    }
}

internal fun canBookcaseCoverScrollHorizontally(
    direction: BookcaseGestureDirection,
    isSettling: Boolean
): Boolean = direction == BookcaseGestureDirection.HORIZONTAL || isSettling

internal object BookcaseRowViewTypes {
    const val REGULAR = 0
    const val BOOKCASE = 1
    const val COUNT = 2

    fun forBookcase(isBookcase: Boolean): Int = if (isBookcase) BOOKCASE else REGULAR
}

internal class MangaCollectionCoverLoadTracker<T : Any> {

    private val lock = Any()
    private val waitingTargets = mutableMapOf<String, MutableList<java.lang.ref.WeakReference<T>>>()

    fun register(cacheKey: String, target: T): Boolean = synchronized(lock) {
        val targets = waitingTargets[cacheKey]
        if (targets == null) {
            waitingTargets[cacheKey] = mutableListOf(java.lang.ref.WeakReference(target))
            return@synchronized true
        }

        targets.removeAll { it.get() == null }
        if (targets.none { it.get() === target }) {
            targets.add(java.lang.ref.WeakReference(target))
        }
        false
    }

    fun complete(cacheKey: String): List<T> = synchronized(lock) {
        waitingTargets.remove(cacheKey).orEmpty().mapNotNull { it.get() }
    }

    fun clear() = synchronized(lock) {
        waitingTargets.clear()
    }
}

internal class AbsListViewScrollStateTracker : AbsListView.OnScrollListener {

    private var trackedListView: AbsListView? = null
    var currentState: Int = AbsListView.OnScrollListener.SCROLL_STATE_IDLE
        private set

    fun attach(listView: AbsListView) {
        if (trackedListView === listView) {
            return
        }

        trackedListView = listView
        listView.setOnScrollListener(this)
    }

    override fun onScrollStateChanged(view: AbsListView, scrollState: Int) {
        currentState = scrollState
    }

    override fun onScroll(
        view: AbsListView,
        firstVisibleItem: Int,
        visibleItemCount: Int,
        totalItemCount: Int
    ) = Unit
}

internal class MangaCollectionCoverScrollState {

    private data class Position(
        val index: Int,
        val startOffset: Int
    )

    private val positions = object : LruCache<String, Position>(MAX_STORED_POSITIONS) {}

    fun attach(
        recyclerView: RecyclerView,
        cacheKey: String
    ) {
        recyclerView.clearOnScrollListeners()
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                save(cacheKey, recyclerView)
            }
        })
    }

    fun save(cacheKey: String?, recyclerView: RecyclerView) {
        val key = cacheKey?.takeIf { it.startsWith("collection:") } ?: return

        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val index = layoutManager.findFirstVisibleItemPosition()
        if (index == RecyclerView.NO_POSITION) {
            return
        }

        val firstVisible = layoutManager.findViewByPosition(index)
        val offset = firstVisible?.let {
            layoutManager.getDecoratedLeft(it) - recyclerView.paddingLeft
        } ?: 0
        positions.put(key, Position(index, offset))
    }

    fun restore(
        cacheKey: String,
        recyclerView: RecyclerView,
        itemCount: Int
    ) {
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val saved = positions.get(cacheKey)
        if (saved == null || itemCount <= 0) {
            layoutManager.scrollToPosition(0)
        } else {
            layoutManager.scrollToPositionWithOffset(
                saved.index.coerceIn(0, itemCount - 1),
                saved.startOffset
            )
        }

    }

    companion object {
        private const val MAX_STORED_POSITIONS = 80
    }
}

internal fun AbsListView.hasActiveCollectionCoverInteraction(): Boolean {
    if ((this as? BookcaseAwareListView)?.hasActiveTouchGesture == true) {
        return true
    }

    if (isPressed) {
        return true
    }

    for (rowIndex in 0 until childCount) {
        val row = getChildAt(rowIndex)
        if (row.isPressed) {
            return true
        }

        val recyclerView = row.findViewById<RecyclerView>(R.id.collectionCoverList) ?: continue
        if (recyclerView.isPressed || recyclerView.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
            return true
        }
        if ((recyclerView as? MangaCollectionCoverRecyclerView)?.hasActiveTouchGesture == true) {
            return true
        }
        for (coverIndex in 0 until recyclerView.childCount) {
            if (recyclerView.getChildAt(coverIndex).isPressed) {
                return true
            }
        }
    }
    return false
}
