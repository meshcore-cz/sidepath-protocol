package cz.arnal.bleedge.core

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

data class TopoNode(
    val id: NodeID,
    val caps: Capabilities,
    val neighbors: List<NodeID>,
    val seq: Int,
    val description: String = "", // free-form bio from the node's ANNOUNCE (key 8)
    val name: String = "",        // primary display label from the node's ANNOUNCE (key 9)
    val platform: String = "",    // OS/device string from the node's ANNOUNCE (key 10)
    val publicKey: ByteArray = ByteArray(0), // 32-byte Ed25519 key from ANNOUNCE (key 6); used for chat encryption
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

    /**
     * Records a directly-connected peer's full public key learned from the NODE_INFO handshake,
     * before its first signed ANNOUNCE arrives — so the UI can render a proper identicon and the
     * deterministic name for it. Fills in the pubkey on an existing entry (if missing) or inserts a
     * minimal stub with `seq = -1` that any later ANNOUNCE (seq >= 0) overrides.
     */
    fun learnPublicKey(
        id: NodeID,
        publicKey: ByteArray,
        caps: Capabilities,
        description: String = "",
        name: String = "",
        platform: String = "",
    ) {
        if (publicKey.size != 32) return
        lock.write {
            val existing = nodes[key(id)]
            if (existing != null) {
                // Fill in anything we didn't have yet (pubkey/name/platform), keep the rest.
                nodes[key(id)] = existing.copy(
                    publicKey = if (existing.publicKey.size == 32) existing.publicKey else publicKey,
                    name = existing.name.ifEmpty { name },
                    platform = existing.platform.ifEmpty { platform },
                    lastSeenMs = System.currentTimeMillis(),
                )
            } else {
                nodes[key(id)] = TopoNode(
                    id, caps, emptyList(), seq = -1,
                    description = description, name = name, platform = platform, publicKey = publicKey,
                )
            }
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
