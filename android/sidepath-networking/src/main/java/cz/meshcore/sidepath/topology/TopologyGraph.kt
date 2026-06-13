package cz.meshcore.sidepath.topology

import cz.meshcore.sidepath.service.PeerInfo
import cz.meshcore.sidepath.service.RSSI_UNKNOWN
import cz.meshcore.sidepath.service.TopologyEntry

/**
 * One node in the mesh topology graph. [id] is the 8-byte NodeID rendered as lowercase hex
 * (NodeID equality is referential, so the graph keys on hex throughout — never on NodeID).
 */
data class TopologyNode(
    val id: String,
    val name: String,
    val isLocal: Boolean,
    val lastSeenMs: Long,
    val neighborCount: Int,
)

/**
 * One edge between two nodes. A link is undirected at the pair level after dedup; [source]/[target]
 * still preserve the observed direction for one-directional links ([bidirectional] == false): the
 * claimer is [source], the claimed neighbor is [target]. For bidirectional links the endpoints are
 * canonicalised (smaller hex = source) so the same pair never produces two rows.
 *
 * [rssi] is [RSSI_UNKNOWN] unless the link is one of our own direct (GATT-client) connections —
 * ANNOUNCE-derived neighbor relations carry no measurable signal strength.
 */
data class TopologyLink(
    val source: String,
    val target: String,
    val rssi: Int,
    val lastSeenMs: Long,
    val bidirectional: Boolean,
)

data class TopologyGraph(
    val nodes: List<TopologyNode>,
    val links: List<TopologyLink>,
) {
    val isEmpty: Boolean get() = nodes.isEmpty()

    /** True when only the local node is known — nothing useful to visualise yet. */
    fun hasOnlySelf(): Boolean = nodes.size <= 1
}

/**
 * Build the topology graph from the live Sidepath node registry.
 *
 * Sources of truth:
 *  - [topology]: every node we have heard an ANNOUNCE from, with its self-reported neighbor list.
 *  - [peers]: our own direct connections (the only links with a measurable [PeerInfo.rssi]).
 *  - [selfHex]: our own NodeID; always present and never stale.
 *
 * Each known node becomes one [TopologyNode]; each neighbor relationship becomes one [TopologyLink],
 * with bidirectional duplicates collapsed. [nameOf] resolves a display name for a node hex (contact
 * alias → wire name → derived), kept out of this pure builder so it stays host-testable.
 */
fun buildTopologyGraph(
    selfHex: String,
    peers: List<PeerInfo>,
    topology: List<TopologyEntry>,
    nowMs: Long,
    nameOf: (hex: String) -> String,
): TopologyGraph {
    // ---- collect every node referenced anywhere (self, announcers, neighbors, direct peers) ----
    val lastSeen = HashMap<String, Long>()
    val neighborCount = HashMap<String, Int>()

    fun touch(hex: String, seen: Long) {
        lastSeen[hex] = maxOf(lastSeen[hex] ?: 0L, seen)
    }

    touch(selfHex, nowMs)
    for (e in topology) {
        val hex = e.nodeId.toHex()
        touch(hex, e.lastAnnounceMs)
        neighborCount[hex] = e.neighborIds.size
        for (nb in e.neighborIds) touch(nb.toHex(), e.lastAnnounceMs)
    }
    for (p in peers) touch(p.nodeId.toHex(), nowMs) // a connected peer is reachable right now

    // ---- links: fold directed neighbor relations into undirected, deduped pairs ----
    // pairKey = "<minHex>|<maxHex>" → accumulated link state.
    data class Acc(
        var aToB: Boolean = false, // edge minHex -> maxHex observed
        var bToA: Boolean = false, // edge maxHex -> minHex observed
        var rssi: Int = RSSI_UNKNOWN,
        var lastSeenMs: Long = 0L,
    )

    val acc = LinkedHashMap<String, Acc>()
    fun edge(from: String, to: String, rssi: Int, seen: Long) {
        if (from == to) return
        val a = minOf(from, to)
        val b = maxOf(from, to)
        val e = acc.getOrPut("$a|$b") { Acc() }
        if (from == a) e.aToB = true else e.bToA = true
        if (rssi != RSSI_UNKNOWN) e.rssi = rssi
        e.lastSeenMs = maxOf(e.lastSeenMs, seen)
    }

    // ANNOUNCE-derived neighbor edges (no RSSI).
    for (en in topology) {
        val from = en.nodeId.toHex()
        for (nb in en.neighborIds) edge(from, nb.toHex(), RSSI_UNKNOWN, en.lastAnnounceMs)
    }
    // Our own direct connections — these carry the only measurable RSSI.
    for (p in peers) edge(selfHex, p.nodeId.toHex(), p.rssi, nowMs)

    val links = acc.entries.map { (key, e) ->
        val (a, b) = key.split("|", limit = 2)
        val bidir = e.aToB && e.bToA
        // One-directional: keep the observed direction (claimer -> claimed).
        val source = if (bidir || e.aToB) a else b
        val target = if (source == a) b else a
        TopologyLink(
            source = source,
            target = target,
            rssi = e.rssi,
            lastSeenMs = e.lastSeenMs,
            bidirectional = bidir,
        )
    }

    val nodes = lastSeen.keys.map { hex ->
        TopologyNode(
            id = hex,
            name = nameOf(hex),
            isLocal = hex == selfHex,
            lastSeenMs = if (hex == selfHex) nowMs else (lastSeen[hex] ?: 0L),
            neighborCount = neighborCount[hex] ?: 0,
        )
    }.sortedWith(compareByDescending<TopologyNode> { it.isLocal }.thenBy { it.id })

    return TopologyGraph(nodes, links)
}
