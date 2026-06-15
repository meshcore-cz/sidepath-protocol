package cz.meshcore.sidepath.protocol

import kotlin.random.Random

enum class ActionType { DELIVER_LOCAL, RELAY_FLOOD, RELAY_NEXT_HOP, SEND_ACK, DROP }

/**
 * One instruction the transport must execute for an incoming datagram. The
 * [datagram] is the post-processing form (path appended, ttl/cursor updated).
 */
data class Action(
    val type: ActionType,
    val datagram: Datagram,
    val nextHop: NodeId? = null,     // RELAY_NEXT_HOP / SEND_ACK: the single hop to send to
    val excludePeer: NodeId? = null, // RELAY_FLOOD: incoming link to exclude (split horizon)
    val reason: String = "",         // DROP reason
)

object DropReason {
    const val INVALID_VERSION = "invalid-version"
    const val MALFORMED = "malformed"
    const val BAD_TTL = "bad-ttl"
    const val BAD_ROUTE = "bad-route"
    const val PEER_NOT_ALLOWED = "peer-not-allowed"
    const val DUPLICATE = "duplicate"
    const val LOOP = "loop"
    const val NOT_NEXT_HOP = "not-next-hop"
    const val BAD_SIGNATURE = "bad-signature"
}

/**
 * The Sidepath routing engine (docs/PROTOCOL.md §10). Pure logic with no BLE or
 * platform dependencies: it consumes a decoded [Datagram] plus the incoming peer
 * link and returns the [Action]s the transport must execute. Application payloads
 * are opaque; only SIDEPATH_CONTROL announces are inspected (for verification).
 */
class Router(val identity: Identity) {
    val localId: NodeId = identity.nodeId
    val neighbors = NeighborTable()
    val topology = Topology()
    private val dedup = DedupCache()

    /** Optional direct-peer allowlist of hex NodeIDs; empty = allow all. */
    val allowlist: MutableSet<String> = mutableSetOf()

    /** Records [id] as already seen so a flood echo of our own datagram is dropped. */
    fun markOriginated(id: ByteArray) { dedup.seenOrAdd(id) }

    /** Processes an incoming datagram and returns the actions to execute. */
    fun handle(dg: Datagram, incomingPeer: NodeId?): List<Action> {
        // §10.1 common checks
        if (dg.version != Sidepath.DATAGRAM_VERSION) return drop(dg, DropReason.INVALID_VERSION)
        if (dg.id.size != Sidepath.DATAGRAM_ID_BYTES) return drop(dg, DropReason.MALFORMED)
        if (dg.ttl < 1 || dg.ttl > Sidepath.MAX_TTL) return drop(dg, DropReason.BAD_TTL)
        if (dg.path.size > Sidepath.MAX_ROUTE_HOPS) return drop(dg, DropReason.BAD_ROUTE)
        if (dg.route.isNotEmpty() && dg.route.size > Sidepath.MAX_ROUTE_HOPS) return drop(dg, DropReason.BAD_ROUTE)
        if (incomingPeer != null && allowlist.isNotEmpty() && incomingPeer.toHex() !in allowlist) {
            return drop(dg, DropReason.PEER_NOT_ALLOWED)
        }
        if (dedup.seenOrAdd(dg.id)) return drop(dg, DropReason.DUPLICATE)
        if (dg.path.any { it == localId }) return drop(dg, DropReason.LOOP)

        // §10.1.10 verify announces before using or relaying them.
        if (dg.protocol == PayloadProtocol.SIDEPATH_CONTROL) {
            val verified = verifyControlIfAnnounce(dg)
            if (verified == false) return drop(dg, DropReason.BAD_SIGNATURE)
        }

        return if (dg.isSourceRouted) handleSourceRoute(dg, incomingPeer) else handleFlood(dg, incomingPeer)
    }

    /** Returns null if not an announce, true if verified+stored, false if invalid. */
    private fun verifyControlIfAnnounce(dg: Datagram): Boolean? {
        val ctrl = runCatching { ControlMessage.decode(dg.payload) }.getOrNull() ?: return null
        if (ctrl.kind != ControlKind.ANNOUNCE) return null
        val body = runCatching { AnnounceBody.decode(ctrl.body) }.getOrNull() ?: return false
        if (body.publicKey.size != Sidepath.PUBLIC_KEY_BYTES) return false
        val derived = NodeId.fromPublicKey(body.publicKey)
        if (derived != dg.source) return false
        if (!body.isValid()) return false
        topology.update(
            TopoNode(
                id = derived, publicKey = body.publicKey, caps = body.caps, neighbors = body.neighborIds(),
                epoch = body.epoch, seq = body.seq, timestamp = body.timestamp,
                name = body.name, description = body.description, platform = body.platform,
                bridges = body.bridges,
            ),
        )
        return true
    }

    /**
     * Builds a signed ANNOUNCE body for this node, mirroring Go's `Router.BuildAnnounce`. Emits v3 with
     * a `neighbor_info` section (per-link RSSI/PHY/direction/age) once we have any live neighbors;
     * otherwise falls back to the bare-ID v1/v2 layout so a node with no links still emits the smallest
     * announce. [bridges], when present, force at least v2 (and ride along in v3).
     */
    fun buildAnnounceBody(
        caps: Capabilities,
        epoch: Long,
        seq: Long,
        name: String,
        description: String,
        platform: String,
        bridges: List<BridgeAd> = emptyList(),
        timestamp: Long = System.currentTimeMillis() / 1000,
    ): AnnounceBody {
        val infos = neighbors.announceInfo()
        return if (infos.isNotEmpty()) {
            AnnounceBody.createV3(identity, epoch, seq, timestamp, caps, infos, name, description, platform, bridges)
        } else {
            AnnounceBody.create(identity, epoch, seq, timestamp, caps, emptyList(), name, description, platform, bridges)
        }
    }

    private fun handleFlood(dg: Datagram, incomingPeer: NodeId?): List<Action> {
        val actions = mutableListOf<Action>()

        // Local delivery keeps `path` exactly as received: a node never records itself as a hop when
        // it is the recipient, so `path` holds only the intermediate relays (empty = direct).
        val deliverLocally = dg.isBroadcast || dg.destination == localId
        if (deliverLocally) {
            actions += Action(ActionType.DELIVER_LOCAL, dg)
            if (!dg.isBroadcast && dg.ackRequested()) actions += buildAck(dg)
        }

        // §10.2.5 relay if datagram is broadcast or not addressed to local, and TTL allows.
        val relayNeeded = dg.isBroadcast || dg.destination != localId
        if (relayNeeded && dg.ttl > 1) {
            // Relaying onward: append ourselves as an intermediate hop.
            actions += Action(
                ActionType.RELAY_FLOOD,
                dg.copy(path = dg.path + localId, ttl = dg.ttl - 1),
                excludePeer = incomingPeer,
            )
        }
        return actions
    }

    private fun handleSourceRoute(dg: Datagram, @Suppress("UNUSED_PARAMETER") incomingPeer: NodeId?): List<Action> {
        val cursor = dg.routeCursor
        if (cursor >= dg.route.size) return drop(dg, DropReason.BAD_ROUTE)
        if (dg.route[cursor] != localId) return drop(dg, DropReason.NOT_NEXT_HOP)
        if (dg.route.last() != dg.destination) return drop(dg, DropReason.BAD_ROUTE)
        if (dg.ttl != dg.route.size - cursor) return drop(dg, DropReason.BAD_TTL)

        val updated = dg.copy(ttl = dg.ttl - 1, routeCursor = cursor + 1)
        if (updated.routeCursor >= updated.route.size) {
            // We are the destination: deliver with `path` as received (we do not append ourselves).
            val actions = mutableListOf(Action(ActionType.DELIVER_LOCAL, updated))
            if (updated.ackRequested()) actions += buildAck(updated)
            return actions
        }
        // Relaying onward: record ourselves as an intermediate hop.
        val relayed = updated.copy(path = updated.path + localId)
        val nextHop = relayed.route[relayed.routeCursor]
        return listOf(Action(ActionType.RELAY_NEXT_HOP, relayed, nextHop = nextHop))
    }

    /**
     * Builds a source-routed Sidepath ACK for a locally delivered unicast datagram
     * (§9.2). Route = reverse(delivered.path) + [original.source]; `path` is relays-only.
     */
    private fun buildAck(delivered: Datagram): Action {
        // `path` holds only the intermediate relays (we never appended ourselves on delivery), so the
        // return route is the reversed relay list followed by the original source.
        val route = delivered.path.reversed() + delivered.source
        val ack = Datagram(
            id = Datagram.newDatagramId(),
            source = localId,
            destination = delivered.source,
            ttl = route.size,
            route = route,
            routeCursor = 0,
            protocol = PayloadProtocol.SIDEPATH_CONTROL,
            flags = 0,
            payload = AckBody(delivered.id).toControl().encode(),
        )
        dedup.seenOrAdd(ack.id) // we originated it
        return Action(ActionType.SEND_ACK, ack, nextHop = route.first())
    }

    // ---- origination helpers -------------------------------------------------

    /**
     * Builds a broadcast (flood) datagram originated by this node. Marks the id
     * as seen so a flood echo is dropped.
     */
    fun newBroadcast(protocol: Int, payload: ByteArray, ttl: Int = Sidepath.DEFAULT_FLOOD_TTL): Datagram {
        val dg = Datagram(
            source = localId, destination = NodeId.BROADCAST, ttl = ttl.coerceIn(1, Sidepath.MAX_TTL),
            protocol = protocol, payload = payload,
        )
        markOriginated(dg.id)
        return dg
    }

    /**
     * Builds a unicast datagram to [dst]. Uses a known source route when one is
     * available; otherwise falls back to flood unless [requireRoute] is set, in
     * which case it returns null. Marks the id as seen.
     */
    fun newUnicast(
        dst: NodeId,
        protocol: Int,
        payload: ByteArray,
        flags: Int = 0,
        floodTtl: Int = Sidepath.DEFAULT_FLOOD_TTL,
        requireRoute: Boolean = false,
    ): Datagram? {
        val route = selectRoute(dst)
        val dg = if (route != null) {
            Datagram(
                source = localId, destination = dst, ttl = route.size, route = route, routeCursor = 0,
                protocol = protocol, flags = flags, payload = payload,
            )
        } else {
            if (requireRoute) return null
            Datagram(
                source = localId, destination = dst, ttl = floodTtl.coerceIn(1, Sidepath.MAX_TTL),
                protocol = protocol, flags = flags, payload = payload,
            )
        }
        markOriginated(dg.id)
        return dg
    }

    /**
     * Selects a source route to [dst] (§10.4): `[dst]` if it is a direct neighbor,
     * else a BFS path over learned topology, else null (caller may flood).
     */
    fun selectRoute(dst: NodeId): List<NodeId>? {
        if (neighbors.get(dst) != null) return listOf(dst)
        // Seed the search with our direct neighbors: we're never in our own topology, so a plain
        // BFS from localId would dead-end and never find a multi-hop source route.
        val path = topology.bfsPathFromSource(localId, dst, neighbors.all().map { it.id })
        return if (path != null && path.isNotEmpty()) path else null
    }

    fun publicKeyFor(id: NodeId): ByteArray? =
        neighbors.get(id)?.publicKey?.takeIf { it.size == Sidepath.PUBLIC_KEY_BYTES }
            ?: topology.publicKeyFor(id)

    /** Resolves a display name: verified announce name, else deterministic fallback, else "". */
    fun nameFor(id: NodeId): String {
        topology.getNode(id)?.let { tn ->
            if (tn.name.isNotEmpty()) return tn.name
            defaultNodeName(tn.publicKey).takeIf { it.isNotEmpty() }?.let { return it }
        }
        neighbors.get(id)?.publicKey?.let { pk -> defaultNodeName(pk).takeIf { it.isNotEmpty() }?.let { return it } }
        return ""
    }

    /** Random flood relay jitter in milliseconds (§10.2.7). */
    fun floodJitterMs(): Long = Sidepath.FLOOD_JITTER_MIN_MS +
        Random.nextLong(Sidepath.FLOOD_JITTER_MAX_MS - Sidepath.FLOOD_JITTER_MIN_MS + 1)

    private fun drop(dg: Datagram, reason: String) = listOf(Action(ActionType.DROP, dg, reason = reason))
}
