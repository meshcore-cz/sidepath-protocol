package cz.arnal.bleedge.core

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

data class TopoNode(
    val id: NodeID,
    val caps: Capabilities,
    val neighbors: List<NodeID>,
    val seq: Int,
    val lastSeenMs: Long = System.currentTimeMillis(),
)

/**
 * Thread-safe global mesh topology learned from ANNOUNCE packets.
 */
class Topology(private val expiryMs: Long = 90_000L) {
    private val lock = ReentrantReadWriteLock()
    private val nodes = mutableMapOf<String, TopoNode>()

    private fun key(id: NodeID) = id.toHexString()

    /** Update inserts or refreshes a node. Ignores stale (lower seq) updates. */
    fun update(node: TopoNode) {
        lock.write {
            val existing = nodes[key(node.id)]
            if (existing != null && existing.seq >= node.seq) return
            nodes[key(node.id)] = node.copy(lastSeenMs = System.currentTimeMillis())
        }
    }

    fun getNode(id: NodeID): TopoNode? = lock.read { nodes[key(id)] }

    fun allNodes(): List<TopoNode> = lock.read { nodes.values.toList() }

    fun expireNode(id: NodeID) = lock.write { nodes.remove(key(id)) }

    /**
     * BFS shortest path from [from] to [to].
     * Returns list of hops excluding [from], including [to]. Returns null if no path.
     */
    fun bfsPath(from: NodeID, to: NodeID): List<NodeID>? {
        if (from.toHexString() == to.toHexString()) return emptyList()

        return lock.read {
            val visited = mutableSetOf(from.toHexString())
            val prev = mutableMapOf<String, NodeID>()
            val queue = ArrayDeque<NodeID>()
            queue.add(from)

            var found = false
            outer@ while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                if (cur.toHexString() == to.toHexString()) {
                    found = true
                    break@outer
                }
                val node = nodes[key(cur)] ?: continue
                for (nb in node.neighbors) {
                    if (visited.add(nb.toHexString())) {
                        prev[nb.toHexString()] = cur
                        queue.add(nb)
                    }
                }
            }

            if (!found) return@read null

            // Reconstruct path
            val path = mutableListOf<NodeID>()
            var cur = to
            while (cur.toHexString() != from.toHexString()) {
                path.add(0, cur)
                cur = prev[cur.toHexString()] ?: return@read null
            }
            path
        }
    }

    fun reap() {
        val now = System.currentTimeMillis()
        lock.write {
            val stale = nodes.entries.filter { now - it.value.lastSeenMs > expiryMs }
            stale.forEach { nodes.remove(it.key) }
        }
    }
}
