package com.example.comiclab.ui.reader.manga.page

internal data class PreloadWindow(
    val previewStart: Int,
    val previewEnd: Int,
    val protectedStart: Int,
    val protectedEnd: Int,
    val previewPositions: List<Int>,
    val fullPositions: List<Int>
) {
    companion object {
        internal fun from(
            firstVisible: Int,
            lastVisible: Int,
            lastIndex: Int,
            scrollDirection: Int,
            isFastScroll: Boolean,
            isIdle: Boolean,
            isPdfSource: Boolean
        ): PreloadWindow {
            val movingForward = scrollDirection >= 0
            val fullBefore: Int
            val fullAfter: Int
            val previewBefore: Int
            val previewAfter: Int

            when {
                isPdfSource && isIdle -> {
                    fullBefore = PDF_IDLE_FULL_PRELOAD_BEFORE_COUNT
                    fullAfter = PDF_IDLE_FULL_PRELOAD_AFTER_COUNT
                    previewBefore = PDF_IDLE_PREVIEW_PRELOAD_BEFORE_COUNT
                    previewAfter = PDF_IDLE_PREVIEW_PRELOAD_AFTER_COUNT
                }

                isPdfSource && isFastScroll && movingForward -> {
                    fullBefore = 0
                    fullAfter = 0
                    previewBefore = PDF_FAST_PREVIEW_PRELOAD_BEFORE_COUNT
                    previewAfter = PDF_FAST_PREVIEW_PRELOAD_FORWARD_COUNT
                }

                isPdfSource && isFastScroll -> {
                    fullBefore = 0
                    fullAfter = 0
                    previewBefore = PDF_FAST_PREVIEW_PRELOAD_FORWARD_COUNT
                    previewAfter = PDF_FAST_PREVIEW_PRELOAD_BEFORE_COUNT
                }

                isPdfSource && movingForward -> {
                    fullBefore = 0
                    fullAfter = 0
                    previewBefore = PDF_NORMAL_PREVIEW_PRELOAD_BEFORE_COUNT
                    previewAfter = PDF_NORMAL_PREVIEW_PRELOAD_FORWARD_COUNT
                }

                isPdfSource -> {
                    fullBefore = 0
                    fullAfter = 0
                    previewBefore = PDF_NORMAL_PREVIEW_PRELOAD_FORWARD_COUNT
                    previewAfter = PDF_NORMAL_PREVIEW_PRELOAD_BEFORE_COUNT
                }

                isIdle -> {
                    fullBefore = IDLE_FULL_PRELOAD_BEFORE_COUNT
                    fullAfter = IDLE_FULL_PRELOAD_AFTER_COUNT
                    previewBefore = IDLE_PREVIEW_PRELOAD_BEFORE_COUNT
                    previewAfter = IDLE_PREVIEW_PRELOAD_AFTER_COUNT
                }

                isFastScroll && movingForward -> {
                    fullBefore = 0
                    fullAfter = 0
                    previewBefore = FAST_PREVIEW_PRELOAD_BEFORE_COUNT
                    previewAfter = FAST_PREVIEW_PRELOAD_FORWARD_COUNT
                }

                isFastScroll -> {
                    fullBefore = 0
                    fullAfter = 0
                    previewBefore = FAST_PREVIEW_PRELOAD_FORWARD_COUNT
                    previewAfter = FAST_PREVIEW_PRELOAD_BEFORE_COUNT
                }

                movingForward -> {
                    fullBefore = NORMAL_FULL_PRELOAD_BEFORE_COUNT
                    fullAfter = NORMAL_FULL_PRELOAD_FORWARD_COUNT
                    previewBefore = NORMAL_PREVIEW_PRELOAD_BEFORE_COUNT
                    previewAfter = NORMAL_PREVIEW_PRELOAD_FORWARD_COUNT
                }

                else -> {
                    fullBefore = NORMAL_FULL_PRELOAD_FORWARD_COUNT
                    fullAfter = NORMAL_FULL_PRELOAD_BEFORE_COUNT
                    previewBefore = NORMAL_PREVIEW_PRELOAD_FORWARD_COUNT
                    previewAfter = NORMAL_PREVIEW_PRELOAD_BEFORE_COUNT
                }
            }

            val fullStart = (firstVisible - fullBefore).coerceAtLeast(0)
            val fullEnd = (lastVisible + fullAfter).coerceAtMost(lastIndex)
            val previewStart = (firstVisible - previewBefore).coerceAtLeast(0)
            val previewEnd = (lastVisible + previewAfter).coerceAtMost(lastIndex)
            val cacheProtectedBefore = if (isPdfSource) {
                PDF_CACHE_PROTECTED_BEFORE_COUNT
            } else {
                CACHE_PROTECTED_BEFORE_COUNT
            }
            val cacheProtectedAfter = if (isPdfSource) {
                PDF_CACHE_PROTECTED_AFTER_COUNT
            } else {
                CACHE_PROTECTED_AFTER_COUNT
            }
            val protectedStart = (firstVisible - cacheProtectedBefore).coerceAtLeast(0)
            val protectedEnd = (lastVisible + cacheProtectedAfter).coerceAtMost(lastIndex)

            val previewPositions = orderedPreloadPositions(
                firstVisible = firstVisible,
                lastVisible = lastVisible,
                start = previewStart,
                end = previewEnd,
                movingForward = movingForward
            )
            val fullPositions = orderedPreloadPositions(
                firstVisible = firstVisible,
                lastVisible = lastVisible,
                start = fullStart,
                end = fullEnd,
                movingForward = movingForward
            )

            return PreloadWindow(
                previewStart = previewStart,
                previewEnd = previewEnd,
                protectedStart = protectedStart,
                protectedEnd = protectedEnd,
                previewPositions = previewPositions,
                fullPositions = fullPositions
            )
        }

        private fun orderedPreloadPositions(
            firstVisible: Int,
            lastVisible: Int,
            start: Int,
            end: Int,
            movingForward: Boolean
        ): List<Int> {
            if (end < start) {
                return emptyList()
            }

            val forwardPositions = if (lastVisible + 1 <= end) {
                (lastVisible + 1..end).toList()
            } else {
                emptyList()
            }
            val backwardPositions = if (firstVisible - 1 >= start) {
                (firstVisible - 1 downTo start).toList()
            } else {
                emptyList()
            }

            return if (movingForward) {
                forwardPositions + backwardPositions
            } else {
                backwardPositions + forwardPositions
            }
        }

        private const val NORMAL_FULL_PRELOAD_BEFORE_COUNT = 1
        private const val NORMAL_FULL_PRELOAD_FORWARD_COUNT = 1
        private const val NORMAL_PREVIEW_PRELOAD_BEFORE_COUNT = 1
        private const val NORMAL_PREVIEW_PRELOAD_FORWARD_COUNT = 5
        private const val FAST_PREVIEW_PRELOAD_BEFORE_COUNT = 1
        private const val FAST_PREVIEW_PRELOAD_FORWARD_COUNT = 8
        private const val IDLE_FULL_PRELOAD_BEFORE_COUNT = 1
        private const val IDLE_FULL_PRELOAD_AFTER_COUNT = 1
        private const val IDLE_PREVIEW_PRELOAD_BEFORE_COUNT = 2
        private const val IDLE_PREVIEW_PRELOAD_AFTER_COUNT = 4
        private const val CACHE_PROTECTED_BEFORE_COUNT = 1
        private const val CACHE_PROTECTED_AFTER_COUNT = 3
        private const val PDF_NORMAL_PREVIEW_PRELOAD_BEFORE_COUNT = 1
        private const val PDF_NORMAL_PREVIEW_PRELOAD_FORWARD_COUNT = 3
        private const val PDF_FAST_PREVIEW_PRELOAD_BEFORE_COUNT = 1
        private const val PDF_FAST_PREVIEW_PRELOAD_FORWARD_COUNT = 16
        private const val PDF_IDLE_FULL_PRELOAD_BEFORE_COUNT = 1
        private const val PDF_IDLE_FULL_PRELOAD_AFTER_COUNT = 1
        private const val PDF_IDLE_PREVIEW_PRELOAD_BEFORE_COUNT = 2
        private const val PDF_IDLE_PREVIEW_PRELOAD_AFTER_COUNT = 16
        private const val PDF_CACHE_PROTECTED_BEFORE_COUNT = 1
        private const val PDF_CACHE_PROTECTED_AFTER_COUNT = 2
    }
}
