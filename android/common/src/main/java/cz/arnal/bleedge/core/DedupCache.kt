package cz.arnal.bleedge.core

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Thread-safe dedup cache for PacketIDs.
 * Mirrors the Go DedupCache: 4096 max entries, 5-minute TTL.
 */
class DedupCache(
    private val maxSize: Int = 4096,
    private val ttlMs: Long = 5 * 60 * 1000L,
) {
    private data class Entry(val seenAt: Long)

    private val lock = ReentrantLock()
    private val entries = LinkedHashMap<String, Entry>(maxSize, 0.75f, true) // access-ordered

    /**
     * Returns true if [packetId] was already seen within TTL (duplicate).
     * If not seen, adds it and returns false.
     */
    fun seenOrAdd(packetId: ByteArray): Boolean {
        val key = packetId.joinToString("") { "%02x".format(it) }
        val now = System.currentTimeMillis()
        return lock.withLock {
            val existing = entries[key]
            if (existing != null && now - existing.seenAt < ttlMs) {
                return@withLock true
            }
            // Evict oldest if at capacity
            if (entries.size >= maxSize) {
                val oldest = entries.entries.iterator().next()
                entries.remove(oldest.key)
            }
            entries[key] = Entry(now)
            false
        }
    }

    fun clear() = lock.withLock { entries.clear() }
}
