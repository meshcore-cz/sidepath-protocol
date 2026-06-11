package cz.arnal.bleedge.core

import kotlin.random.Random

enum class ActionType {
    DELIVER_LOCAL,
    RELAY_FLOOD,
    RELAY_NEXT_HOP,
    SEND_ACK,
    DROP,
}

data class Action(
    val type: ActionType,
    val reason: String = "",
    val packet: Packet,
    val nextHop: NodeID? = null,
)

object DropReason {
    const val DUPLICATE       = "duplicate"
    const val LOOP            = "loop"
    const val TTL_EXHAUSTED   = "ttl-exhausted"
    const val INVALID_VERSION = "invalid-version"
    const val MALFORMED       = "malformed"
    const val NOT_NEXT_HOP    = "not-next-hop"
    const val PEER_NOT_ALLOWED = "peer-not-allowed"
    const val BAD_SIGNATURE   = "bad-signature"
}

/**
 * Pure routing engine with no BLE dependencies.
 * Mirrors core/router.go exactly.
 */
class Router(val identity: Identity) {
    val localId: NodeID = identity.nodeId
    var description: String = "" // diagnostic node label advertised in ANNOUNCE (unsigned)
    val neighbors = NeighborTable()
    val topology = Topology()
    val allowlist = mutableSetOf<String>() // hex NodeID strings; empty = allow all
    private val dedup = DedupCache()

    /**
     * Processes an incoming packet and returns the list of actions the transport must execute.
     */
    fun handlePacket(pkt: Packet, incomingPeer: NodeID?): List<Action> {
        // 1. Version check
        if (pkt.version != PROTOCOL_VERSION) {
            return listOf(Action(ActionType.DROP, DropReason.INVALID_VERSION, pkt))
        }

        // 2. Allowlist
        if (incomingPeer != null && allowlist.isNotEmpty() &&
            incomingPeer.toHexString() !in allowlist
        ) {
            return listOf(Action(ActionType.DROP, DropReason.PEER_NOT_ALLOWED, pkt))
        }

        // Handle ANNOUNCE
        if (pkt.type == PacketType.ANNOUNCE) {
            return handleAnnounce(pkt, incomingPeer)
        }

        return when (pkt.mode) {
            RoutingMode.FLOOD        -> handleFlood(pkt, incomingPeer)
            RoutingMode.SOURCE_ROUTE -> handleSourceRoute(pkt, incomingPeer)
        }
    }

    private fun handleAnnounce(pkt: Packet, incomingPeer: NodeID?): List<Action> {
        val ap = runCatching { AnnouncePayload.decode(pkt.payload) }.getOrElse {
            return listOf(Action(ActionType.DROP, DropReason.MALFORMED, pkt))
        }
        // Authenticate: NodeID must be bound to the carried public key, and the
        // signature must verify. Reject (and do not relay) on any failure.
        // (NodeID compares by hex — its ByteArray.equals is referential.)
        val derivedHex = if (ap.publicKey.size == 32) NodeID.fromPubKey(ap.publicKey).toHexString() else ""
        if (ap.publicKey.size != 32 ||
            derivedHex != ap.nodeId.toHexString() ||
            derivedHex != pkt.source.toHexString() ||
            !Identity.verifyAnnounce(ap.publicKey, ap.signature, ap.timestamp.toInt(), ap.caps, ap.seq, ap.neighbors)
        ) {
            return listOf(Action(ActionType.DROP, DropReason.BAD_SIGNATURE, pkt))
        }
        topology.update(
            TopoNode(
                id = ap.nodeId,
                caps = ap.caps,
                neighbors = ap.neighbors,
                seq = ap.seq,
                description = ap.description,
                publicKey = ap.publicKey,
            )
        )
        return handleFlood(pkt, incomingPeer)
    }

    private fun handleFlood(pkt: Packet, incomingPeer: NodeID?): List<Action> {
        if (dedup.seenOrAdd(pkt.id)) {
            return listOf(Action(ActionType.DROP, DropReason.DUPLICATE, pkt))
        }
        val localHex = localId.toHexString()
        if (pkt.trace.any { it.toHexString() == localHex }) {
            return listOf(Action(ActionType.DROP, DropReason.LOOP, pkt))
        }
        if (pkt.ttl == 0.toByte()) {
            return listOf(Action(ActionType.DROP, DropReason.TTL_EXHAUSTED, pkt))
        }

        val withTrace = pkt.copy(trace = pkt.trace + localId)
        val actions = mutableListOf<Action>()

        if (withTrace.isBroadcast() || withTrace.destination.toHexString() == localHex) {
            actions += Action(ActionType.DELIVER_LOCAL, packet = withTrace)
            if (withTrace.type == PacketType.DATA && !withTrace.isBroadcast()) {
                actions += buildAck(withTrace)
            }
        }

        val ttlInt = pkt.ttl.toInt() and 0xFF
        if (ttlInt > 1 && (withTrace.isBroadcast() || withTrace.destination.toHexString() != localHex)) {
            val relayPkt = withTrace.copy(ttl = (ttlInt - 1).toByte())
            actions += Action(ActionType.RELAY_FLOOD, packet = relayPkt, nextHop = incomingPeer)
        }

        return actions
    }

    private fun handleSourceRoute(pkt: Packet, incomingPeer: NodeID?): List<Action> {
        if (dedup.seenOrAdd(pkt.id)) {
            return listOf(Action(ActionType.DROP, DropReason.DUPLICATE, pkt))
        }
        val localHex = localId.toHexString()
        if (pkt.trace.any { it.toHexString() == localHex }) {
            return listOf(Action(ActionType.DROP, DropReason.LOOP, pkt))
        }
        if (pkt.ttl == 0.toByte()) {
            return listOf(Action(ActionType.DROP, DropReason.TTL_EXHAUSTED, pkt))
        }

        val cursor = pkt.routeCursor.toInt() and 0xFF
        if (cursor >= pkt.route.size || pkt.route[cursor].toHexString() != localHex) {
            return listOf(Action(ActionType.DROP, DropReason.NOT_NEXT_HOP, pkt))
        }

        val ttlInt = pkt.ttl.toInt() and 0xFF
        val updated = pkt.copy(
            trace = pkt.trace + localId,
            routeCursor = (cursor + 1).toByte(),
            ttl = (ttlInt - 1).toByte(),
        )

        val newCursor = cursor + 1
        if (newCursor >= updated.route.size) {
            // We are the final destination
            val actions = mutableListOf(Action(ActionType.DELIVER_LOCAL, packet = updated))
            if (updated.type == PacketType.DATA) {
                actions += buildAck(updated)
            }
            return actions
        }

        val nextHop = updated.route[newCursor]
        return listOf(Action(ActionType.RELAY_NEXT_HOP, packet = updated, nextHop = nextHop))
    }

    private fun buildAck(data: Packet): Action {
        val ackTtl = (data.trace.size + 1).toByte()
        val ack: Packet
        if (data.trace.size > 1) {
            // Reverse the trace excluding self (last element), then append the original
            // source as the final hop so the ACK is actually delivered there (source-route
            // delivery happens by route exhaustion). The source isn't in the trace (the
            // originator doesn't add itself), so without this the ACK would stop at the last
            // relay. Guard against the source already being terminal.
            val hops = data.trace.dropLast(1)
            val reversed = hops.reversed()
            val route = if (reversed.lastOrNull()?.toHexString() == data.source.toHexString()) reversed
                        else reversed + data.source
            ack = Packet(
                version = PROTOCOL_VERSION,
                type = PacketType.ACK,
                id = newPacketID(),
                source = localId,
                destination = data.source,
                mode = RoutingMode.SOURCE_ROUTE,
                ttl = ackTtl,
                route = route,
                routeCursor = 0,
                payload = data.id, // id of the DATA packet being acked (for per-message delivery tracking)
            )
        } else {
            ack = Packet(
                version = PROTOCOL_VERSION,
                type = PacketType.ACK,
                id = newPacketID(),
                source = localId,
                destination = data.source,
                mode = RoutingMode.FLOOD,
                ttl = ackTtl,
                payload = data.id, // id of the DATA packet being acked (for per-message delivery tracking)
            )
        }
        val nextHop = if (data.trace.size > 1) data.trace[data.trace.size - 2] else data.source
        // We originated this ACK — record it so a flood echo isn't re-flooded back.
        dedup.seenOrAdd(ack.id)
        return Action(ActionType.SEND_ACK, packet = ack, nextHop = nextHop)
    }

    /**
     * Records a packet ID this node is about to send as already seen, so that if a
     * flood echoes the packet back to us we drop it as a duplicate instead of
     * re-flooding our own packet. Call for every packet this node originates.
     */
    fun markOriginated(id: ByteArray) {
        dedup.seenOrAdd(id)
    }

    /** Builds a periodic ANNOUNCE packet for this node. */
    /**
     * Resolves a node's diagnostic label: the direct neighbor's NODE_INFO
     * description if known, else the description from its ANNOUNCE (topology).
     */
    fun descriptionFor(id: NodeID): String {
        neighbors.get(id)?.description?.takeIf { it.isNotEmpty() }?.let { return it }
        return topology.getNode(id)?.description ?: ""
    }

    fun buildAnnounce(caps: Capabilities, seq: Int): Packet {
        val neighborIds = neighbors.ids()
        val ts = System.currentTimeMillis() / 1000
        val sig = identity.signAnnounce(ts.toInt(), caps, seq, neighborIds)
        val ap = AnnouncePayload(
            nodeId = localId,
            caps = caps,
            neighbors = neighborIds,
            seq = seq,
            timestamp = ts,
            publicKey = identity.publicKey,
            signature = sig,
            description = description,
        )
        return Packet(
            version = PROTOCOL_VERSION,
            type = PacketType.ANNOUNCE,
            id = newPacketID(),
            source = localId,
            mode = RoutingMode.FLOOD,
            ttl = 3,
            payloadType = PayloadType.MESH_CORE_RAW,
            payload = ap.encode(),
        )
    }

    /**
     * Selects the best route to [dst].
     * Returns (route, isDirect) where:
     *   - isDirect=true and route=null means direct neighbor
     *   - isDirect=true and route=list means source route via topology
     *   - isDirect=false means no route found (fall back to flood)
     */
    fun selectRoute(dst: NodeID): Pair<List<NodeID>?, Boolean> {
        if (neighbors.get(dst) != null) {
            return Pair(null, true) // direct
        }
        val path = topology.bfsPath(localId, dst)
        return if (path != null && path.isNotEmpty()) Pair(path, true) else Pair(null, false)
    }

    /** Returns a random relay jitter delay between 10ms and 100ms. */
    fun floodJitterMs(): Long = (10L + Random.nextLong(90L))
}
