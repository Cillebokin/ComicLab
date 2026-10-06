package com.example.comiclab.core.reader.progress

internal const val READER_PROGRESS_CHECKPOINT_DELAY_MS = 650L

internal fun shouldPersistReaderCheckpointImmediately(
    isIdle: Boolean,
    isDiscreteJump: Boolean,
    isLifecycleEvent: Boolean
): Boolean {
    return isIdle || isDiscreteJump || isLifecycleEvent
}
