package com.example.network

/** A transport-wide budget; reconnecting does not reset it. No idle timers. */
internal class PeerRateLimit : IllegalArgumentException("Peer request limit exceeded. Wait before reconnecting.")

internal class WorkBudget(private val clock: () -> Long = System::nanoTime) {
    private data class Bucket(var tokens: Double, var time: Long)
    private val buckets = mutableMapOf<String, Bucket>()
    @Synchronized private fun take(name: String, amount: Double, rate: Double, capacity: Double) {
        val now = clock()
        val bucket = buckets.getOrPut(name) { Bucket(capacity, now) }
        val tokens = minOf(capacity, bucket.tokens + maxOf(0L, now - bucket.time) / 1e9 * rate)
        bucket.tokens = maxOf(0.0, tokens - amount); bucket.time = now
        if (amount > tokens) throw PeerRateLimit()
    }
    fun frame(bytes: Int) {
        take("frames", 1.0, 512.0, 1024.0)
        take("bytes", bytes.toDouble(), 16.0 * 1024 * 1024, 32.0 * 1024 * 1024)
    }
    fun message(kind: String) {
        val limits = when (kind) {
            "CLIPBOARD" -> 2.0 to 10.0
            "NOTIFICATION" -> 30.0 to 60.0
            "NOTIFICATION_ACTION", "HEARTBEAT" -> 10.0 to 20.0
            "FILE_INIT" -> 1.0 to 4.0
            else -> return
        }
        take(kind, 1.0, limits.first, limits.second)
    }
}
