package cz.meshcore.sidepath.protocol

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * A directly connected Sidepath neighbor. RSSI/PHY and the bound public key come
 * from the BLE peer link and NODE_INFO; authoritative metadata (name etc.)
 * comes from signed announces in [Topology], not from here.
 */
data class NeighborEntry(
    val id: NodeId,
    val publicKey: ByteArray = ByteArray(0), // 32 bytes from NODE_INFO, when known
    val rssi: Int = RSSI_UNKNOWN,
    val txPhy: Int = Phy.UNKNOWN,
    val rxPhy: Int = Phy.UNKNOWN,
    val direction: Int = ConnDirection.OUTGOING,
    val provisionalCaps: Capabilities = Capabilities(0),
    val lastSeenMs: Long = System.currentTimeMillis(),
) {
    companion object { const val RSSI_UNKNOWN = -32768 }
}

/** Thread-safe neighbor table; entries expire after [timeoutMs] of inactivity (§12). */
class NeighborTable(private val timeoutMs: Long = Sidepath.NEIGHBOR_TIMEOUT_MS) {
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

    /**
     * Snapshots the table as wire [NeighborInfo] entries for a v3 ANNOUNCE (§8.8), capturing each
     * link's RSSI, PHY in both directions, which side opened it, and how long ago it was last seen.
     * RSSI is clamped to a signed byte (dBm); age is whole seconds since lastSeen. Entries are
     * returned unsorted; [AnnounceBody.createV3] sorts and de-duplicates them. Mirrors Go's
     * `NeighborTable.AnnounceInfo`.
     */
    fun announceInfo(): List<NeighborInfo> = lock.read {
        val now = System.currentTimeMillis()
        neighbors.values.map { n ->
            // RSSI is dBm in [-128,-1]; 0 means "no sample" (§8.8). Inbound links and the unknown
            // sentinel have no real reading, so report 0 rather than clamping it to a bogus -128.
            val rssi = if (n.rssi in -128..-1) n.rssi else 0
            val ageS = ((now - n.lastSeenMs).coerceAtLeast(0L)) / 1000L
            NeighborInfo(
                id = n.id, rssi = rssi, txPhy = n.txPhy, rxPhy = n.rxPhy,
                direction = n.direction, ageS = ageS,
            )
        }
    }

    fun touch(id: NodeId) = lock.write {
        neighbors[id.toHex()]?.let { neighbors[id.toHex()] = it.copy(lastSeenMs = System.currentTimeMillis()) }
    }

    fun reap() = lock.write {
        val now = System.currentTimeMillis()
        neighbors.entries.filter { now - it.value.lastSeenMs > timeoutMs }.forEach { neighbors.remove(it.key) }
    }
}
