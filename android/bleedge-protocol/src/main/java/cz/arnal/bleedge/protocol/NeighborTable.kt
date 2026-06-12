package cz.arnal.bleedge.protocol

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * A directly connected BLEEdge neighbor. RSSI/PHY and the bound public key come
 * from the BLE peer link and NODE_INFO; authoritative metadata (name etc.)
 * comes from signed announces in [Topology], not from here.
 */
data class NeighborEntry(
    val id: NodeId,
    val publicKey: ByteArray = ByteArray(0), // 32 bytes from NODE_INFO, when known
    val rssi: Int = RSSI_UNKNOWN,
    val provisionalCaps: Capabilities = Capabilities(0),
    val lastSeenMs: Long = System.currentTimeMillis(),
) {
    companion object { const val RSSI_UNKNOWN = -32768 }
}

/** Thread-safe neighbor table; entries expire after [timeoutMs] of inactivity (§12). */
class NeighborTable(private val timeoutMs: Long = BLEEdge.NEIGHBOR_TIMEOUT_MS) {
    private val lock = ReentrantReadWriteLock()
    private val neighbors = mutableMapOf<String, NeighborEntry>()

    fun upsert(entry: NeighborEntry) = lock.write {
        neighbors[entry.id.toHex()] = entry.copy(lastSeenMs = System.currentTimeMillis())
    }

    fun get(id: NodeId): NeighborEntry? = lock.read { neighbors[id.toHex()] }
    fun all(): List<NeighborEntry> = lock.read { neighbors.values.toList() }
    fun remove(id: NodeId) = lock.write { neighbors.remove(id.toHex()) }

    /** NodeIDs of current neighbors, sorted and unique — ready for an ANNOUNCE body. */
    fun ids(): List<NodeId> = lock.read {
        neighbors.values.map { it.id }.distinctBy { it.toHex() }.sortedWith { a, b -> a.compareTo(b) }
    }

    fun touch(id: NodeId) = lock.write {
        neighbors[id.toHex()]?.let { neighbors[id.toHex()] = it.copy(lastSeenMs = System.currentTimeMillis()) }
    }

    fun reap() = lock.write {
        val now = System.currentTimeMillis()
        neighbors.entries.filter { now - it.value.lastSeenMs > timeoutMs }.forEach { neighbors.remove(it.key) }
    }
}
