package com.example.comiclab

import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderProgressPolicyTest {

    @Test
    fun checkpointDelayStaysWithinPlannedWindow() {
        assertTrue(READER_PROGRESS_CHECKPOINT_DELAY_MS in 500L..800L)
    }

    @Test
    fun idleDiscreteAndLifecycleEventsPersistImmediately() {
        assertTrue(
            shouldPersistReaderCheckpointImmediately(
                isIdle = true,
                isDiscreteJump = false,
                isLifecycleEvent = false
            )
        )
        assertTrue(
            shouldPersistReaderCheckpointImmediately(
                isIdle = false,
                isDiscreteJump = true,
                isLifecycleEvent = false
            )
        )
        assertTrue(
            shouldPersistReaderCheckpointImmediately(
                isIdle = false,
                isDiscreteJump = false,
                isLifecycleEvent = true
            )
        )
    }
}
