package com.example.sleepalarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmSoundSequenceTest {
    @Test
    fun inaccessibleCustomSoundFallsBackInOrderAndStopsAfterLastCandidate() {
        val sequence = AlarmSoundSequence<String>()
        var attempt = requireNotNull(sequence.start(listOf("custom", "alarm", "notification", "ringtone")))
        val tried = mutableListOf(attempt.source)

        repeat(3) {
            val previous = attempt
            attempt = requireNotNull(sequence.nextAfterFailure(previous))
            assertFalse(sequence.isCurrent(previous))
            tried.add(attempt.source)
        }

        assertEquals(listOf("custom", "alarm", "notification", "ringtone"), tried)
        assertNull(sequence.nextAfterFailure(attempt))
        assertFalse(sequence.isCurrent(attempt))
        assertNull(sequence.nextAfterFailure(attempt))
    }

    @Test
    fun selectingSystemDefaultDoesNotRetryTheSameSource() {
        val sequence = AlarmSoundSequence<String>()
        val alarm = requireNotNull(sequence.start(listOf("alarm", "alarm", "notification")))

        val fallback = requireNotNull(sequence.nextAfterFailure(alarm))

        assertEquals("notification", fallback.source)
        assertNull(sequence.nextAfterFailure(fallback))
    }

    @Test
    fun stopRejectsPendingPreparedOrErrorCallbacks() {
        val sequence = AlarmSoundSequence<String>()
        val pending = requireNotNull(sequence.start(listOf("custom", "alarm")))

        sequence.stop()

        assertFalse(sequence.isCurrent(pending))
        assertNull(sequence.nextAfterFailure(pending))
    }

    @Test
    fun previousAlarmCannotAdvanceNewAlarmWithTheSameSource() {
        val sequence = AlarmSoundSequence<String>()
        val oldAlarm = requireNotNull(sequence.start(listOf("custom", "alarm")))
        val newAlarm = requireNotNull(sequence.start(listOf("custom", "alarm")))

        assertFalse(sequence.isCurrent(oldAlarm))
        assertNull(sequence.nextAfterFailure(oldAlarm))
        assertTrue(sequence.isCurrent(newAlarm))
        assertEquals("alarm", sequence.nextAfterFailure(newAlarm)?.source)
    }

    @Test
    fun duplicateErrorCallbackDoesNotSkipFallback() {
        val sequence = AlarmSoundSequence<String>()
        val custom = requireNotNull(sequence.start(listOf("custom", "alarm", "notification")))
        val fallback = requireNotNull(sequence.nextAfterFailure(custom))

        assertNull(sequence.nextAfterFailure(custom))
        assertTrue(sequence.isCurrent(fallback))
        assertEquals("notification", sequence.nextAfterFailure(fallback)?.source)
    }

    @Test
    fun emptySourcesInvalidatePreviousPlayback() {
        val sequence = AlarmSoundSequence<String>()
        val oldAlarm = requireNotNull(sequence.start(listOf("custom", "alarm")))

        assertNull(sequence.start(emptyList()))
        assertFalse(sequence.isCurrent(oldAlarm))
        assertNull(sequence.nextAfterFailure(oldAlarm))
    }
}
