package cz.arnal.bleedge.core

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

data class NeighborEntry(
    val id: NodeID,
    val rssi: Int,
    val txPhy: PHY,
    val rxPhy: PHY,
    val caps: Capabilities,
    val lastSeenMs: Long = System.currentTimeMillis(),
    val phyInvalid: Boolean = false, // true if coded-only mode and PHY is not Coded
)

/**
 * Thread-safe neighbor table. Entries expire after [timeoutMs] ms of inactivity.
 */
class NeighborTable(private val timeoutMs: Long = 60_000L) {
    private val lock = ReentrantReadWriteLock()
    private val neighbors = mutableMapOf<String, NeighborEntry>()

    private fun key(id: NodeID) = id.toHexString()

    fun upsert(entry: NeighborEntry) {
        lock.write {
            neighbors[key(entry.id)] = entry.copy(lastSeenMs = System.currentTimeMillis())
        }
    }

    fun get(id: NodeID): NeighborEntry? = lock.read { neighbors[key(id)] }

    fun all(): List<NeighborEntry> = lock.read { neighbors.values.toList() }

    fun remove(id: NodeID) = lock.write { neighbors.remove(key(id)) }

    fun ids(): List<NodeID> = lock.read { neighbors.values.map { it.id } }

    fun touch(id: NodeID) = lock.write {
        neighbors[key(id)]?.let { neighbors[key(id)] = it.copy(lastSeenMs = System.currentTimeMillis()) }
    }

    fun reap() {
        val now = System.currentTimeMillis()
        lock.write {
            val stale = neighbors.entries.filter { now - it.value.lastSeenMs > timeoutMs }
            stale.forEach { neighbors.remove(it.key) }
        }
    }
}
