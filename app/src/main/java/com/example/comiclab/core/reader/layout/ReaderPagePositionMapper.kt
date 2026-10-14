package com.example.comiclab.core.reader.layout

/**
 * 将原始页面索引映射为阅读器中的页面槽位。
 * 双页模式下每两个槽位组成一个 spread，封面单页通过插入空槽实现，
 * 可选的源页锚点用于让当前单页成为重新开启双页时的起始页，
 * 不改变原始文件的页索引，因此阅读进度仍可按源页面保存。
 */
internal class ReaderPagePositionMapper(
    sourceItemCount: Int,
    private val doublePageReading: Boolean,
    coverSinglePage: Boolean,
    spreadAnchorSourcePosition: Int? = null
) {

    private val safeSourceItemCount = sourceItemCount.coerceAtLeast(0)
    private val validSpreadAnchorSourcePosition = spreadAnchorSourcePosition?.takeIf {
        doublePageReading && it in 0 until safeSourceItemCount
    }
    private val hasSingleCoverSlot = doublePageReading &&
        coverSinglePage &&
        safeSourceItemCount > 0 &&
        // 锚定到封面时，当前页优先与下一页组成跨页。
        validSpreadAnchorSourcePosition != 0

    private val baseAdapterItemCount: Int = if (!doublePageReading) {
        safeSourceItemCount
    } else {
        val pairedSourceCount = safeSourceItemCount - if (hasSingleCoverSlot) 1 else 0
        safeSourceItemCount +
            (if (hasSingleCoverSlot) 1 else 0) +
            (pairedSourceCount % 2)
    }

    private val alignmentBlankAdapterPosition = validSpreadAnchorSourcePosition
        ?.let(::baseAdapterPositionForSourcePosition)
        ?.takeIf { it % 2 != 0 }

    val adapterItemCount: Int = baseAdapterItemCount +
        if (alignmentBlankAdapterPosition == null) 0
        else 1 + ((baseAdapterItemCount + 1) % 2)

    fun sourcePositionForAdapterPosition(adapterPosition: Int): Int? {
        if (adapterPosition !in 0 until adapterItemCount) {
            return null
        }
        if (adapterPosition == alignmentBlankAdapterPosition) {
            return null
        }

        val baseAdapterPosition = if (
            alignmentBlankAdapterPosition != null && adapterPosition > alignmentBlankAdapterPosition
        ) {
            adapterPosition - 1
        } else {
            adapterPosition
        }
        if (baseAdapterPosition !in 0 until baseAdapterItemCount) {
            return null
        }
        if (hasSingleCoverSlot && baseAdapterPosition == COVER_BLANK_ADAPTER_POSITION) {
            return null
        }

        val sourcePosition = if (hasSingleCoverSlot && baseAdapterPosition > COVER_BLANK_ADAPTER_POSITION) {
            baseAdapterPosition - 1
        } else {
            baseAdapterPosition
        }
        return sourcePosition.takeIf { it in 0 until safeSourceItemCount }
    }

    fun adapterPositionForSourcePosition(sourcePosition: Int): Int {
        if (sourcePosition !in 0 until safeSourceItemCount) {
            return NO_POSITION
        }
        val baseAdapterPosition = baseAdapterPositionForSourcePosition(sourcePosition)
        return if (alignmentBlankAdapterPosition != null && baseAdapterPosition >= alignmentBlankAdapterPosition) {
            baseAdapterPosition + 1
        } else {
            baseAdapterPosition
        }
    }

    fun spreadStartAdapterPosition(adapterPosition: Int): Int {
        if (adapterItemCount <= 0) {
            return 0
        }

        val boundedPosition = adapterPosition.coerceIn(0, adapterItemCount - 1)
        return if (doublePageReading) {
            boundedPosition - (boundedPosition % 2)
        } else {
            boundedPosition
        }
    }

    fun displayedSourcePositionForAdapterPosition(
        adapterPosition: Int,
        fallbackSourcePosition: Int
    ): Int {
        val spreadStart = spreadStartAdapterPosition(adapterPosition)
        return sourcePositionForAdapterPosition(spreadStart) ?: fallbackSourcePosition
    }

    fun spreadStartAdapterPositionForSourcePosition(sourcePosition: Int): Int {
        val adapterPosition = adapterPositionForSourcePosition(sourcePosition)
        if (adapterPosition == NO_POSITION) {
            return 0
        }
        return spreadStartAdapterPosition(adapterPosition)
    }

    fun spreadStartSourcePositionForSourcePosition(sourcePosition: Int): Int {
        if (safeSourceItemCount <= 0) {
            return 0
        }

        val spreadStartAdapterPosition = spreadStartAdapterPositionForSourcePosition(sourcePosition)
        return sourcePositionForAdapterPosition(spreadStartAdapterPosition)
            ?: sourcePosition.coerceIn(0, safeSourceItemCount - 1)
    }

    fun sourcePositionsInAdapterRange(
        firstAdapterPosition: Int,
        visibleItemCount: Int
    ): List<Int> {
        if (adapterItemCount <= 0 || visibleItemCount <= 0) {
            return emptyList()
        }

        val start = firstAdapterPosition.coerceIn(0, adapterItemCount - 1)
        val endExclusive = (start + visibleItemCount).coerceAtMost(adapterItemCount)
        return (start until endExclusive)
            .mapNotNull(::sourcePositionForAdapterPosition)
            .distinct()
    }

    private fun baseAdapterPositionForSourcePosition(sourcePosition: Int): Int {
        return if (hasSingleCoverSlot && sourcePosition > 0) {
            sourcePosition + 1
        } else {
            sourcePosition
        }
    }

    companion object {
        private const val COVER_BLANK_ADAPTER_POSITION = 1
        private const val NO_POSITION = -1
    }
}
