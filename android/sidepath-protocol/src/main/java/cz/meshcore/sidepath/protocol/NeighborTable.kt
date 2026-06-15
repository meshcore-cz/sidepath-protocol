package cz.meshcore.sidepath.protocol

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * A directly connected Sidepath neighbor. RSSI/PHY and the bound public key come
 * from the BLE peer link and NODE_INFO; authoritative metadata (name etc.)
 * comes from signed announces in [Topology], not from here.
 *
 * Alongside the last-sample link facts it carries the live link statistics this
 * node observes — a smoothed RSSI, a representative round-trip latency, and a
 * delivery-reliability score — which seed the quality hints in our v3 ANNOUNCE
 * (§8.8). They are updated by [NeighborTable.recordRtt]/[NeighborTable.recordDelivery]
 * and by RSSI updates, and snapshotted by [NeighborTable.announceInfo].
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
    // Live link statistics (0 = no sample / unknown for each); never serialized directly.
    val rssiEwma: Int = 0,
    val rttMs: Int = 0,
    val qualityQ8: Int = 0,
    val deliverEwma: Double = 0.0,
    val deliverInit: Boolean = false,
) {
    companion object { const val RSSI_UNKNOWN = -32768 }
}

/** Thread-safe neighbor table; entries expire after [timeoutMs] of inactivity (§12). */
class NeighborTable(private val timeoutMs: Long = Sidepath.NEIGHBOR_TIMEOUT_MS) {
    private val lock = ReentrantReadWriteLock()
    private val neighbors = mutableMapOf<String, NeighborEntry>()

    /**
     * Inserts or refreshes a neighbor. Live link statistics accumulated for an existing entry are
     * preserved across refreshes, and a fresh RSSI sample is folded into the smoothed [rssiEwma].
     */
    fun upsert(entry: NeighborEntry) = lock.write {
        val prev = neighbors[entry.id.toHex()]
        val rssiEwma = when {
            entry.rssi !in -128..-1 -> prev?.rssiEwma ?: 0 // no usable sample; keep prior smoothing
            prev == null || prev.rssiEwma == 0 -> entry.rssi
            else -> ewmaInt(prev.rssiEwma, entry.rssi, RSSI_ALPHA)
        }
        neighbors[entry.id.toHex()] = entry.copy(
            lastSeenMs = System.currentTimeMillis(),
            rssiEwma = rssiEwma,
            rttMs = prev?.rttMs ?: entry.rttMs,
            qualityQ8 = prev?.qualityQ8 ?: entry.qualityQ8,
            deliverEwma = prev?.deliverEwma ?: entry.deliverEwma,
            deliverInit = prev?.deliverInit ?: entry.deliverInit,
        )
    }

    fun get(id: NodeId): NeighborEntry? = lock.read { neighbors[id.toHex()] }
    fun all(): List<NeighborEntry> = lock.read { neighbors.values.toList() }
    fun remove(id: NodeId) = lock.write { neighbors.remove(id.toHex()) }

    /** NodeIDs of current neighbors, sorted and unique — ready for an ANNOUNCE body. */
    fun ids(): List<NodeId> = lock.read {
        neighbors.values.map { it.id }.distinctBy { it.toHex() }.sortedWith { a, b -> a.compareTo(b) }
    }

    /**
     * Folds a round-trip latency sample (ms) into a neighbor's smoothed RTT. Callers should pass only
     * direct-link samples (e.g. an ACK or trace to a direct neighbor), not end-to-end multi-hop times.
     * No-op for an unknown neighbor. Mirrors Go's `NeighborTable.RecordRTT`.
     */
    fun recordRtt(id: NodeId, ms: Int) = lock.write {
        if (ms <= 0) return@write
        neighbors[id.toHex()]?.let { n ->
            val rtt = if (n.rttMs == 0) ms else ewmaInt(n.rttMs, ms, RTT_ALPHA)
            neighbors[id.toHex()] = n.copy(rttMs = rtt.coerceIn(0, 65535))
        }
    }

    /**
     * Folds one delivery outcome (an ACK received, or a timeout) into a neighbor's smoothed
     * reliability, updating [NeighborEntry.qualityQ8]. Callers should pass only direct-link outcomes.
     * Quality is floored at 1 once sampled so 0 still means "no data". Mirrors Go's `RecordDelivery`.
     */
    fun recordDelivery(id: NodeId, ok: Boolean) = lock.write {
        val n = neighbors[id.toHex()] ?: return@write
        val x = if (ok) 1.0 else 0.0
        val ewma = if (!n.deliverInit) x else n.deliverEwma * (1 - DELIVER_ALPHA) + x * DELIVER_ALPHA
        val q = (ewma * 255 + 0.5).toInt().coerceIn(1, 255)
        neighbors[id.toHex()] = n.copy(deliverEwma = ewma, deliverInit = true, qualityQ8 = q)
    }

    /**
     * Snapshots the table as wire [NeighborInfo] entries for a v3 ANNOUNCE (§8.8), capturing each
     * link's RSSI, PHY in both directions, which side opened it, how long ago it was last seen, plus
     * the extended quality hints (transport, smoothed RSSI, reliability, latency). RSSI is clamped to
     * a signed byte (dBm); age is whole seconds since lastSeen. Entries are returned unsorted;
     * [AnnounceBody.createV3] sorts and de-duplicates them. Mirrors Go's `NeighborTable.AnnounceInfo`.
     */
    fun announceInfo(): List<NeighborInfo> = lock.read {
        val now = System.currentTimeMillis()
        neighbors.values.map { n ->
            // RSSI is dBm in [-128,-1]; 0 means "no sample" (§8.8). Inbound links and the unknown
            // sentinel have no real reading, so report 0 rather than clamping it to a bogus -128.
            val rssi = if (n.rssi in -128..-1) n.rssi else 0
            val rssiEwma = if (n.rssiEwma in -128..-1) n.rssiEwma else 0
            val ageS = ((now - n.lastSeenMs).coerceAtLeast(0L)) / 1000L
            NeighborInfo(
                id = n.id, rssi = rssi, txPhy = n.txPhy, rxPhy = n.rxPhy,
                direction = n.direction, ageS = ageS,
                transport = Transport.BLE, // every link in this table is a BLE peer link
                rssiEwma = rssiEwma, qualityQ8 = n.qualityQ8, latencyMs = n.rttMs,
                // queueQ8 (congestion) has no source yet; left 0 = unknown.
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

    private companion object {
        // EWMA smoothing factors (higher = more weight on the newest sample), mirroring Go core.
        const val RSSI_ALPHA = 0.3
        const val RTT_ALPHA = 0.3
        const val DELIVER_ALPHA = 0.25
        fun ewmaInt(prev: Int, sample: Int, alpha: Double): Int =
            (prev * (1 - alpha) + sample * alpha).toInt()
    }
}
