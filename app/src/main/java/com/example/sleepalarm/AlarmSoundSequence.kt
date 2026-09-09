package com.example.sleepalarm

/** A finite fallback sequence whose attempt identity rejects callbacks from old players. */
internal class AlarmSoundSequence<T> {
    class Attempt<T>(val source: T)

    private var remaining: Iterator<T> = emptyList<T>().iterator()
    private var current: Attempt<T>? = null

    fun start(sources: List<T>): Attempt<T>? {
        remaining = sources.distinct().iterator()
        return advance()
    }

    fun isCurrent(attempt: Attempt<T>): Boolean = current === attempt

    fun nextAfterFailure(attempt: Attempt<T>): Attempt<T>? {
        if (!isCurrent(attempt)) return null
        return advance()
    }

    fun stop() {
        current = null
        remaining = emptyList<T>().iterator()
    }

    private fun advance(): Attempt<T>? {
        current = if (remaining.hasNext()) Attempt(remaining.next()) else null
        return current
    }
}
