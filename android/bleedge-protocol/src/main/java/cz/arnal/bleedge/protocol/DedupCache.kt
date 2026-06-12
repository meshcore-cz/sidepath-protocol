package cz.arnal.bleedge.protocol

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Thread-safe deduplication cache for datagram IDs (§12): bounded LRU with a TTL,
 * evicting the oldest entry when full.
 */
class DedupCache(
    private val maxSize: Int = BLEEdge.DEDUP_LIMIT,
    private val ttlMs: Long = BLEEdge.DEDUP_TTL_MS,
) {
    private val lock = ReentrantLock()
    private val entries = LinkedHashMap<String, Long>(maxSize, 0.75f, true) // access-ordered

    /** Returns true if [id] was already seen within TTL; otherwise records it and returns false. */
    fun seenOrAdd(id: ByteArray): Boolean {
        val key = id.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val now = System.currentTimeMillis()
        return lock.withLock {
            val seenAt = entries[key]
            if (seenAt != null && now - seenAt < ttlMs) return@withLock true
            if (entries.size >= maxSize) entries.remove(entries.entries.iterator().next().key)
            entries[key] = now
            false
        }
    }

    fun clear() = lock.withLock { entries.clear() }
}
