package com.example.manager

/** Limit presentation writes, never protocol acknowledgements or final state changes. */
internal class ProgressUpdates(private val clock: () -> Long = System::nanoTime) {
    private var last = clock()
    fun due(): Boolean {
        val now = clock()
        if (now - last < 250_000_000L) return false
        last = now
        return true
    }
}
