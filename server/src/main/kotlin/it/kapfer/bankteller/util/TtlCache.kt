package it.kapfer.bankteller.util

import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal time-to-live cache for single-instance server state.
 *
 * Entry is considered fresh while `now - writtenAtMillis < ttlMillis`.
 * `get` returns null for missing OR expired entries (expired entries are
 * evicted lazily on read). Thread-safe via [ConcurrentHashMap].
 *
 * Used for the Enable Banking whitelist cache (60 s TTL) in the onboarding
 * routes and the application-status cache in the onboarding service.
 */
class TtlCache<K, V>(private val ttlMillis: Long) {
    private data class Entry<V>(val value: V, val writtenAtMillis: Long)

    private val entries = ConcurrentHashMap<K, Entry<V>>()

    fun get(key: K): V? {
        val entry = entries[key] ?: return null
        if (System.currentTimeMillis() - entry.writtenAtMillis >= ttlMillis) {
            entries.remove(key, entry)
            return null
        }
        return entry.value
    }

    fun put(key: K, value: V) {
        entries[key] = Entry(value, System.currentTimeMillis())
    }

    fun invalidate(key: K) {
        entries.remove(key)
    }

    fun clear() {
        entries.clear()
    }
}
