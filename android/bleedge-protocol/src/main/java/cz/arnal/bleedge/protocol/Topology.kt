package cz.arnal.bleedge.protocol

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * A node learned from a verified signed announce (§8.5, §12). Freshness is keyed
 * on (epoch, seq); local expiry uses [receivedAtMs], not the signed timestamp.
 */
data class TopoNode(
    val id: NodeId,
    val publicKey: ByteArray,
    val caps: Capabilities,
    val neighbors: List<NodeId>,
    val epoch: Long,
    val seq: Long,
    val timestamp: Long,
    val name: String = "",
    val description: String = "",
    val platform: String = "",
    val receivedAtMs: Long = System.currentTimeMillis(),
)

/**
 * Thread-safe global mesh topology graph built from verified announces. Stale
 * announces (lower epoch, or equal epoch with non-increasing seq) are rejected.
 */
class Topology(private val expiryMs: Long = BLEEdge.TOPOLOGY_TIMEOUT_MS) {
    private val lock = ReentrantReadWriteLock()
    private val nodes = mutableMapOf<String, TopoNode>()

    /** Returns true only when [node] is strictly newer and was therefore stored (§8.5). */
    fun update(node: TopoNode): Boolean = lock.write {
        val existing = nodes[node.id.toHex()]
        if (existing != null && !isNewer(node.epoch, node.seq, existing.epoch, existing.seq)) return@write false
        nodes[node.id.toHex()] = node.copy(receivedAtMs = System.currentTimeMillis())
        true
    }

    private fun isNewer(epoch: Long, seq: Long, curEpoch: Long, curSeq: Long): Boolean =
        epoch > curEpoch || (epoch == curEpoch && seq > curSeq)

    fun getNode(id: NodeId): TopoNode? = lock.read { nodes[id.toHex()] }
    fun allNodes(): List<TopoNode> = lock.read { nodes.values.toList() }
    fun remove(id: NodeId) = lock.write { nodes.remove(id.toHex()) }

    fun publicKeyFor(id: NodeId): ByteArray? =
        lock.read { nodes[id.toHex()]?.publicKey?.takeIf { it.size == BLEEdge.PUBLIC_KEY_BYTES } }

    /**
     * BFS shortest path over the learned graph from [from] to [to] (§10.4).
     * Returns the hops excluding [from] and including [to], or null if no path.
     */
    fun bfsPath(from: NodeId, to: NodeId): List<NodeId>? = bfsPathFromSource(from, to, emptyList())

    /**
     * [bfsPath] but seeds [from]'s adjacency with [fromNeighbors]. A node never appears in its own
     * topology (it doesn't process its own ANNOUNCE), so a plain BFS from the local id dead-ends
     * immediately — every route would be null and every unicast would silently flood. Passing the
     * local node's direct neighbor ids lets the search take its first hop and find real multi-hop
     * source routes (used by [Router.selectRoute] with the local neighbor table).
     */
    fun bfsPathFromSource(from: NodeId, to: NodeId, fromNeighbors: List<NodeId>): List<NodeId>? = lock.read {
        if (from == to) return@read emptyList()
        val visited = mutableSetOf(from.toHex())
        val prev = mutableMapOf<String, NodeId>()
        val queue = ArrayDeque<NodeId>()
        queue.add(from)
        var found = false
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (cur == to) { found = true; break }
            val neighbors = (nodes[cur.toHex()]?.neighbors ?: emptyList()) +
                (if (cur == from) fromNeighbors else emptyList())
            for (nb in neighbors) {
                if (visited.add(nb.toHex())) { prev[nb.toHex()] = cur; queue.add(nb) }
            }
        }
        if (!found) return@read null
        val path = ArrayDeque<NodeId>()
        var cur = to
        while (cur != from) {
            path.addFirst(cur)
            cur = prev[cur.toHex()] ?: return@read null
        }
        path.toList()
    }

    fun reap() = lock.write {
        val now = System.currentTimeMillis()
        nodes.entries.filter { now - it.value.receivedAtMs > expiryMs }.forEach { nodes.remove(it.key) }
    }
}
