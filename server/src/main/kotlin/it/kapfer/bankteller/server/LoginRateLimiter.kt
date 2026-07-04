package it.kapfer.bankteller.server

import java.util.concurrent.ConcurrentHashMap

object LoginRateLimiter {
    private val attempts = ConcurrentHashMap<String, AttemptTracker>()
    private const val MAX_ATTEMPTS = 5
    private const val WINDOW_DURATION_MS = 15 * 60 * 1000L // 15 minutes

    private data class AttemptTracker(
        val timestamps: MutableList<Long> = mutableListOf()
    )

    fun check(ip: String): RateLimitResult {
        val now = System.currentTimeMillis()
        val tracker = attempts[ip]

        if (tracker != null) {
            tracker.timestamps.removeAll { now - it > WINDOW_DURATION_MS }
            if (tracker.timestamps.size >= MAX_ATTEMPTS) {
                val oldestInWindow = tracker.timestamps.min()
                val retryAfterSeconds = ((oldestInWindow + WINDOW_DURATION_MS - now) / 1000).toInt()
                return RateLimitResult.RateLimited(maxOf(1, retryAfterSeconds))
            }
        }

        return RateLimitResult.Allowed
    }

    fun recordFailedAttempt(ip: String) {
        val now = System.currentTimeMillis()
        val tracker = attempts.getOrPut(ip) { AttemptTracker() }
        tracker.timestamps.removeAll { now - it > WINDOW_DURATION_MS }
        tracker.timestamps.add(now)
    }

    fun clearOnSuccess(ip: String) {
        attempts.remove(ip)
    }

    sealed class RateLimitResult {
        data object Allowed : RateLimitResult()
        data class RateLimited(val retryAfterSeconds: Int) : RateLimitResult()
    }
}
