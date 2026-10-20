package com.example.comiclab.ui.reader.manga

internal const val READER_CONTINUITY_OVERLAY_MAX_MS = 1_200L

internal fun shouldRemoveReaderContinuityOverlay(
    hasScrolled: Boolean,
    visiblePagesReady: Boolean,
    elapsedMs: Long
): Boolean = elapsedMs >= READER_CONTINUITY_OVERLAY_MAX_MS ||
    (hasScrolled && visiblePagesReady)

internal fun areVisibleReaderPagesRenderable(
    visiblePositions: Collection<Int>,
    hasRenderableContent: (Int) -> Boolean
): Boolean = visiblePositions.isNotEmpty() && visiblePositions.all(hasRenderableContent)
