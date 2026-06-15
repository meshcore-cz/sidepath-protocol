package cz.meshcore.sidepath.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    private fun seed(b: Int) = ByteArray(Sidepath.SEED_BYTES) { ((it + b) and 0xFF).toByte() }

    @Test fun nodeIdFromPublicKeyIsFirst10Bytes() {
        val id = Identity.fromSeed(seed(1))
        assertEquals(Sidepath.NODE_ID_BYTES, id.nodeId.bytes.size)
        assertArrayEquals(id.publicKey.copyOfRange(0, 10), id.nodeId.bytes)
        assertEquals(id.nodeId, NodeId.fromHex(id.nodeId.toHex()))
    }

    @Test fun datagramRoundTrip() {
        val a = Identity.fromSeed(seed(1)).nodeId
        val b = Identity.fromSeed(seed(2)).nodeId
        val dg = Datagram(
            source = a, destination = b, ttl = 5,
            route = listOf(b), routeCursor = 0, path = listOf(a),
            protocol = PayloadProtocol.SIDEPATH_CHAT, flags = DatagramFlags.ACK_REQUESTED,
            payload = byteArrayOf(1, 2, 3),
        )
        val decoded = Datagram.decode(dg.encode())
        assertEquals(dg, decoded)
        assertTrue(decoded.ackRequested())
        assertTrue(decoded.isSourceRouted)
    }

    @Test fun broadcastDatagramOmitsDefaults() {
        val a = Identity.fromSeed(seed(3)).nodeId
        val dg = Datagram(source = a, destination = NodeId.BROADCAST, ttl = 5, protocol = 0, payload = ByteArray(0))
        val decoded = Datagram.decode(dg.encode())
        assertTrue(decoded.isBroadcast)
        assertFalse(decoded.isSourceRouted)
        assertEquals(0, decoded.routeCursor)
        assertEquals(0, decoded.flags)
    }

    @Test fun frameFragmentAndReassemble() {
        val payload = ByteArray(500) { (it and 0xFF).toByte() }
        val frames = Frame.fragment(payload, maxFrameSize = 200)
        assertTrue(frames.size > 1)
        frames.forEach { assertEquals(frames.size, it.fragmentCount) }
        val r = Reassembler()
        var out: ByteArray? = null
        // deliver out of order; CRC must still verify
        for (f in frames.reversed()) out = r.addFrame("peerX", Frame.decode(f.encode())) ?: out
        assertArrayEquals(payload, out)
    }

    @Test fun reassemblyIsKeyedByPeerAndTransferId() {
        val payload = ByteArray(300) { it.toByte() }
        val tid = Frame.newTransferId()
        val frames = Frame.fragment(payload, 200, tid)
        val r = Reassembler()
        // Same transfer id on two different links must not cross-contaminate.
        assertNull(r.addFrame("linkA", frames[0]))
        assertNull(r.addFrame("linkB", frames[0]))
        assertNotNull(r.addFrame("linkA", frames[1]))
    }

    @Test fun announceSignVerifyAndTamperDetection() {
        val id = Identity.fromSeed(seed(7))
        val nb = listOf(Identity.fromSeed(seed(8)).nodeId, Identity.fromSeed(seed(9)).nodeId)
        val body = AnnounceBody.create(id, epoch = 3, seq = 4, timestamp = 100, caps = Capabilities(0x1F),
            neighbors = nb, name = "alice", description = "", platform = "test")
        assertTrue(body.isValid())
        // neighbors come back sorted+unique
        assertTrue(body.neighbors.zipWithNext().all { (x, y) -> x < y })
        // round-trip via control message
        val ctrl = body.toControl()
        val decoded = AnnounceBody.decode(ControlMessage.decode(ctrl.encode()).body)
        assertTrue(decoded.isValid())
        // tamper the name -> signature fails
        assertFalse(decoded.copy(name = "mallory").isValid())
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    // Mirror of core/bridge_announce_test.go's sharedBridgeVector. Both impls MUST produce these
    // exact signed bytes (and signature) for the same identity/fields/bridges, or the v2 ANNOUNCE
    // wire format has drifted between Go and Kotlin.
    private val sharedBridgeSignedMsgHex =
        "53494445504154482d414e4e4f554e43452d5631000200d05a1d1ea251396d557afbd4588b3c6d99dbeb972fed10a32562ea26dcdcfa03000000000000000400000064000000000000001f0002004bddee550ef734cae04e677edd9c7a6fbaa5d6090500616c6963650000040074657374020002435a000245550108e6d33390d003000b05"
    private val sharedBridgeSigHex =
        "a6c31fbf37746375910f5677ca0e4084214f92f391281301322a648a5e0210e408d5af6a8aff381a1359f8cee05943b7fabe411f8083169f069aa99e28f9fe0b"

    @Test fun bridgeAnnounceCrossImplVector() {
        val id = Identity.fromSeed(seed(7))
        val nb = listOf(Identity.fromSeed(seed(8)).nodeId, Identity.fromSeed(seed(9)).nodeId)
        val bridges = listOf(
            BridgeAd("CZ"),
            BridgeAd("EU", freqHz = 869_525_000L, bandwidthHz = 250_000L, sf = 11, cr = 5),
        )
        val body = AnnounceBody.create(
            id, epoch = 3, seq = 4, timestamp = 100, caps = Capabilities(0x1F),
            neighbors = nb, name = "alice", description = "", platform = "test", bridges = bridges,
        )
        // A body with bridges is emitted as v2.
        assertEquals(2, body.announceVersion)
        // Signed bytes + signature match the Go vector byte-for-byte.
        val msg = Identity.announceSignedMessage(
            id.publicKey, 3, 4, 100, Capabilities(0x1F), body.neighbors, "alice", "", "test", 2, bridges,
        )
        assertEquals(sharedBridgeSignedMsgHex, msg.hex())
        assertEquals(sharedBridgeSigHex, body.signature.hex())
        assertTrue(body.isValid())

        // CBOR round-trip via the control envelope preserves the bridges (k12).
        val decoded = AnnounceBody.decode(ControlMessage.decode(body.toControl().encode()).body)
        assertEquals(listOf("CZ", "EU"), decoded.bridges.map { it.code })
        assertFalse(decoded.bridges[0].isCustom)
        assertTrue(decoded.bridges[1].isCustom)
        assertEquals(869_525_000L, decoded.bridges[1].freqHz)
        assertTrue(decoded.isValid())
        // Tampering a signed bridge code breaks verification.
        assertFalse(decoded.copy(bridges = listOf(BridgeAd("XX"), decoded.bridges[1])).isValid())
    }

    // Mirror of core/announce_v3_test.go's sharedV3Vector. Both impls MUST produce these exact signed
    // bytes (and signature) for the same identity/fields/neighbor_info, or the v3 ANNOUNCE wire format
    // (§8.8) has drifted between Go and Kotlin. Inputs: identity seed offset 7; epoch 3, seq 4,
    // timestamp 100, caps 0x1F; name "alice", desc "", platform "test"; no bridges; neighbor_info two
    // entries (sorted by ID), each carrying the original six fields plus the extended v3 hints
    // (transport, rssi_ewma, quality_q8, latency_ms, queue_q8).
    private val sharedV3SignedMsgHex =
        "53494445504154482d414e4e4f554e43452d5631000300d05a1d1ea251396d557afbd4588b3c6d99dbeb972fed10a32562ea26dcdcfa03000000000000000400000064000000000000001f0000000500616c6963650000040074657374000002004bddee550ef734cae04ece0102010c00000001cd4b080006677edd9c7a6fbaa5d609ba0303022c01000002b996120020"
    private val sharedV3SigHex =
        "cdc3a6b00664e763d0341888de329175748f17af606a5897ef98d86607a90224f6c0c07a13d6243a1faf59388f1a2a3f4fb3318e0ed733c074438933a855bb0d"

    @Test fun announceV3CrossImplVector() {
        val id = Identity.fromSeed(seed(7))
        // Pass unsorted to confirm createV3 sorts by ID.
        val n8 = NeighborInfo(Identity.fromSeed(seed(8)).nodeId, rssi = -50, txPhy = Phy.LE_1M, rxPhy = Phy.LE_2M, direction = ConnDirection.OUTGOING, ageS = 12L,
            transport = Transport.BLE, rssiEwma = -51, qualityQ8 = 75, latencyMs = 8, queueQ8 = 6)
        val n9 = NeighborInfo(Identity.fromSeed(seed(9)).nodeId, rssi = -70, txPhy = Phy.CODED, rxPhy = Phy.CODED, direction = ConnDirection.INCOMING, ageS = 300L,
            transport = Transport.MESHCORE, rssiEwma = -71, qualityQ8 = 150, latencyMs = 18, queueQ8 = 32)
        val body = AnnounceBody.createV3(
            id, epoch = 3, seq = 4, timestamp = 100, caps = Capabilities(0x1F),
            neighborInfo = listOf(n9, n8), name = "alice", description = "", platform = "test",
        )
        // A body with neighbor info is v3 and leaves the bare neighbor list empty.
        assertEquals(3, body.announceVersion)
        assertTrue(body.neighbors.isEmpty())
        assertEquals(2, body.neighborInfo.size)

        // Signed bytes + signature match the Go vector byte-for-byte.
        val msg = Identity.announceSignedMessage(
            id.publicKey, 3, 4, 100, Capabilities(0x1F), emptyList(), "alice", "", "test", 3, emptyList(), body.neighborInfo,
        )
        assertEquals(sharedV3SignedMsgHex, msg.hex())
        assertEquals(sharedV3SigHex, body.signature.hex())
        assertTrue(body.isValid())

        // CBOR round-trip via the control envelope preserves neighbor_info (k13), sorted by ID.
        val decoded = AnnounceBody.decode(ControlMessage.decode(body.toControl().encode()).body)
        assertTrue(decoded.neighbors.isEmpty())
        assertEquals(listOf(-50, -70), decoded.neighborInfo.map { it.rssi })
        assertEquals(listOf(Phy.LE_1M, Phy.CODED), decoded.neighborInfo.map { it.txPhy })
        assertEquals(listOf(Phy.LE_2M, Phy.CODED), decoded.neighborInfo.map { it.rxPhy })
        assertEquals(listOf(ConnDirection.OUTGOING, ConnDirection.INCOMING), decoded.neighborInfo.map { it.direction })
        assertEquals(listOf(12L, 300L), decoded.neighborInfo.map { it.ageS })
        // Extended v3 hints survive the round-trip too.
        assertEquals(listOf(Transport.BLE, Transport.MESHCORE), decoded.neighborInfo.map { it.transport })
        assertEquals(listOf(-51, -71), decoded.neighborInfo.map { it.rssiEwma })
        assertEquals(listOf(75, 150), decoded.neighborInfo.map { it.qualityQ8 })
        assertEquals(listOf(8, 18), decoded.neighborInfo.map { it.latencyMs })
        assertEquals(listOf(6, 32), decoded.neighborInfo.map { it.queueQ8 })
        assertTrue(decoded.neighborInfo.zipWithNext().all { (x, y) -> x.id < y.id })
        assertTrue(decoded.isValid())
        // neighborIds reads through to the v3 info list.
        assertEquals(decoded.neighborInfo.map { it.id }, decoded.neighborIds())

        // Tampering any signed neighbor field breaks the signature.
        val tampered = decoded.neighborInfo.toMutableList()
        tampered[0] = tampered[0].copy(rssi = -51)
        assertFalse(decoded.copy(neighborInfo = tampered).isValid())
        // neighbor_info on a sub-v3 body is rejected.
        assertFalse(decoded.copy(announceVersion = 2).isValid())
        // Carrying both the bare list and neighbor_info is rejected.
        assertFalse(decoded.copy(neighbors = listOf(Identity.fromSeed(seed(8)).nodeId)).isValid())
    }

    @Test fun routerBuildAnnounceEmitsV3FromNeighborTable() {
        val id = Identity.fromSeed(seed(7))
        val r = Router(id)
        // With no neighbors, the router falls back to the bare-ID v1 layout.
        val v1 = r.buildAnnounceBody(caps = Capabilities(Capability.RELAY), epoch = 1, seq = 1,
            name = "n", description = "", platform = "p")
        assertEquals(Sidepath.MIN_ANNOUNCE_VERSION, v1.announceVersion)
        assertTrue(v1.isValid())

        // A live neighbor makes the router announce v3, populating neighbor_info from the table.
        r.neighbors.upsert(NeighborEntry(
            id = Identity.fromSeed(seed(8)).nodeId, rssi = -42,
            txPhy = Phy.LE_1M, rxPhy = Phy.LE_1M, direction = ConnDirection.OUTGOING,
        ))
        val v3 = r.buildAnnounceBody(caps = Capabilities(Capability.RELAY), epoch = 1, seq = 2,
            name = "n", description = "", platform = "p")
        assertEquals(3, v3.announceVersion)
        assertEquals(1, v3.neighborInfo.size)
        assertEquals(-42, v3.neighborInfo[0].rssi)
        assertEquals(Phy.LE_1M, v3.neighborInfo[0].txPhy)
        assertEquals(ConnDirection.OUTGOING, v3.neighborInfo[0].direction)
        assertTrue(v3.isValid())
    }

    @Test fun neighborTableAnnounceInfoCarriesDirectionAndDropsUnknownRssi() {
        val table = NeighborTable()
        val outId = Identity.fromSeed(seed(8)).nodeId
        val bothId = Identity.fromSeed(seed(9)).nodeId
        table.upsert(NeighborEntry(id = outId, rssi = -50, txPhy = Phy.LE_1M, rxPhy = Phy.LE_1M, direction = ConnDirection.OUTGOING))
        // Inbound-only links have no RSSI sample; the unknown sentinel must not leak onto the wire.
        table.upsert(NeighborEntry(id = bothId, rssi = NeighborEntry.RSSI_UNKNOWN, direction = ConnDirection.BOTH))

        val infos = table.announceInfo().associateBy { it.id.toHex() }
        val out = infos.getValue(outId.toHex())
        assertEquals(-50, out.rssi)
        assertEquals(ConnDirection.OUTGOING, out.direction)
        val both = infos.getValue(bothId.toHex())
        assertEquals(ConnDirection.BOTH, both.direction)
        assertEquals(0, both.rssi) // unknown sentinel → "no sample"

        // A neighbor advertised as in+out still yields a valid, verifiable v3 announce.
        val id = Identity.fromSeed(seed(7))
        val body = AnnounceBody.createV3(id, epoch = 5, seq = 6, timestamp = 100, caps = Capabilities(0x1F),
            neighborInfo = table.announceInfo(), name = "n", description = "", platform = "p")
        assertTrue(body.isValid())
        assertTrue(body.neighborInfo.any { it.direction == ConnDirection.BOTH })
    }

    @Test fun networkDefsBundledResourceLoads() {
        val defs = NetworkDefs.builtins()
        val cz = defs.firstOrNull { it.code == "CZ" }
        assertNotNull(cz)
        assertEquals("Czech Republic", cz!!.name)
        // Canonical radio params are integer Hz (matching the wire format).
        assertEquals(869_432_000L, cz.freqHz)
        assertEquals(62_500L, cz.bandwidthHz)
        assertEquals(7, cz.sf)
        assertEquals(5, cz.cr)
        assertTrue(cz.analyzerUrls.isNotEmpty())
        // The geometry round-trips as a JSON string consumers can parse offline.
        assertTrue(cz.geoJson.contains("Polygon"))
    }

    @Test fun networkDefsParseSkipsInvalidEntries() {
        val json = """
            [
              {"code":"CZ","name":"Czech","freqHz":869432000,"bandwidthHz":62500,"sf":7,"cr":5},
              {"code":"","name":"no code"},
              {"code":"TOOLONG","name":"over 5 bytes"}
            ]
        """.trimIndent()
        val defs = NetworkDefs.parse(json)
        assertEquals(listOf("CZ"), defs.map { it.code })
    }

    @Test fun announceV1BackCompat() {
        val id = Identity.fromSeed(seed(7))
        val nb = listOf(Identity.fromSeed(seed(8)).nodeId)
        // No bridges -> v1, byte-identical to the original layout.
        val v1 = AnnounceBody.create(
            id, epoch = 1, seq = 1, timestamp = 50, caps = Capabilities(0x02),
            neighbors = nb, name = "n", description = "", platform = "p",
        )
        assertEquals(Sidepath.MIN_ANNOUNCE_VERSION, v1.announceVersion)
        assertTrue(v1.isValid())
        // A v1 body carrying bridges is rejected (bridges only exist from v2).
        assertFalse(v1.copy(bridges = listOf(BridgeAd("CZ"))).isValid())
    }

    @Test fun topologyEpochSeqFreshness() {
        val topo = Topology()
        val id = Identity.fromSeed(seed(11))
        fun node(epoch: Long, seq: Long) = TopoNode(id.nodeId, id.publicKey, Capabilities(1), emptyList(), epoch, seq, 0)
        assertTrue(topo.update(node(1, 1)))
        assertFalse(topo.update(node(1, 1)))   // equal -> stale
        assertTrue(topo.update(node(1, 2)))    // higher seq
        assertFalse(topo.update(node(1, 1)))   // older seq -> rejected (replay)
        assertTrue(topo.update(node(2, 0)))    // higher epoch wins even with lower seq
    }

    @Test fun selectRouteFindsMultiHopViaLocalNeighbors() {
        // A node is never in its own topology, so selectRoute must seed BFS with its direct
        // neighbors to find a multi-hop source route (§10.4).
        val local = Identity.fromSeed(seed(60))
        val relay = Identity.fromSeed(seed(61)).nodeId
        val dst = Identity.fromSeed(seed(62)).nodeId
        val r = Router(local)
        r.neighbors.upsert(NeighborEntry(id = relay)) // directly connected to the relay only
        r.topology.update(TopoNode(relay, ByteArray(32), Capabilities(1), listOf(local.nodeId, dst), 1, 1, 0))
        r.topology.update(TopoNode(dst, ByteArray(32), Capabilities(1), listOf(relay), 1, 1, 0))

        assertEquals(listOf(relay, dst), r.selectRoute(dst))
        assertEquals(listOf(relay), r.selectRoute(relay))
        assertNull(r.selectRoute(Identity.fromSeed(seed(69)).nodeId))
    }

    @Test fun routerFloodDeliversAndRelays() {
        val a = Identity.fromSeed(seed(20))
        val relay = Router(Identity.fromSeed(seed(21)))
        // a broadcasts; relay should deliver locally AND relay-flood with ttl-1
        val dg = Datagram(source = a.nodeId, destination = NodeId.BROADCAST, ttl = 5,
            protocol = PayloadProtocol.SIDEPATH_CHAT, payload = byteArrayOf(9))
        val actions = relay.handle(dg, incomingPeer = a.nodeId)
        // Local delivery records no hop for itself: `path` holds relays only (here, empty).
        val delivered = actions.first { it.type == ActionType.DELIVER_LOCAL }.datagram
        assertEquals(emptyList<NodeId>(), delivered.path)
        val flood = actions.first { it.type == ActionType.RELAY_FLOOD }
        assertEquals(4, flood.datagram.ttl)
        assertEquals(a.nodeId, flood.excludePeer)
        // The relay copy DOES append this node, since it is forwarding.
        assertTrue(flood.datagram.path.contains(relay.localId))
        // a duplicate is dropped
        assertEquals(ActionType.DROP, relay.handle(dg, a.nodeId).single().type)
        // §4.4 multi-link: the same datagram arriving over a *different* physical link (a different
        // incoming peer) is still suppressed purely by datagram ID — dedup never keys on the link.
        val otherLink = Identity.fromSeed(seed(99)).nodeId
        assertEquals(ActionType.DROP, relay.handle(dg, otherLink).single().type)
    }

    @Test fun routerUnicastAckRequestedBuildsSourceRoutedAck() {
        val sender = Identity.fromSeed(seed(30))
        val dest = Router(Identity.fromSeed(seed(31)))
        val dg = Datagram(source = sender.nodeId, destination = dest.localId, ttl = 5,
            protocol = PayloadProtocol.SIDEPATH_CHAT, flags = DatagramFlags.ACK_REQUESTED, payload = byteArrayOf(1))
        val actions = dest.handle(dg, incomingPeer = sender.nodeId)
        assertTrue(actions.any { it.type == ActionType.DELIVER_LOCAL })
        val ack = actions.first { it.type == ActionType.SEND_ACK }.datagram
        assertEquals(dest.localId, ack.source)
        assertEquals(sender.nodeId, ack.destination)
        assertEquals(listOf(sender.nodeId), ack.route) // directly delivered -> [source]
        assertEquals(1, ack.ttl)
        val ackBody = AckBody.decode(ControlMessage.decode(ack.payload).body)
        assertArrayEquals(dg.id, ackBody.ackedId)
    }

    @Test fun sourceRouteRelaysToNextHop() {
        val src = Identity.fromSeed(seed(40)).nodeId
        val mid = Router(Identity.fromSeed(seed(41)))
        val dst = Identity.fromSeed(seed(42)).nodeId
        val route = listOf(mid.localId, dst)
        val dg = Datagram(source = src, destination = dst, ttl = route.size, route = route, routeCursor = 0,
            protocol = PayloadProtocol.SIDEPATH_CHAT, payload = byteArrayOf(7))
        val action = mid.handle(dg, incomingPeer = src).single()
        assertEquals(ActionType.RELAY_NEXT_HOP, action.type)
        assertEquals(dst, action.nextHop)
        assertEquals(1, action.datagram.ttl)
        assertEquals(1, action.datagram.routeCursor)
        // Forwarding records this relay as an intermediate hop.
        assertEquals(listOf(mid.localId), action.datagram.path)
    }

    @Test fun sourceRouteDirectDeliveryHasEmptyPath() {
        val src = Identity.fromSeed(seed(43)).nodeId
        val dst = Router(Identity.fromSeed(seed(44)))
        val route = listOf(dst.localId)
        val dg = Datagram(source = src, destination = dst.localId, ttl = route.size, route = route, routeCursor = 0,
            protocol = PayloadProtocol.SIDEPATH_CHAT, payload = byteArrayOf(7))
        val delivered = dst.handle(dg, incomingPeer = src).single { it.type == ActionType.DELIVER_LOCAL }.datagram
        // Direct (single-hop) delivery has no relays, so `path` is empty.
        assertEquals(emptyList<NodeId>(), delivered.path)
    }

    @Test fun ttlAndVersionGuards() {
        val r = Router(Identity.fromSeed(seed(50)))
        val a = Identity.fromSeed(seed(51)).nodeId
        val v2 = Datagram(version = 2, source = a, destination = NodeId.BROADCAST, ttl = 5, protocol = 0, payload = ByteArray(0))
        assertEquals(DropReason.INVALID_VERSION, r.handle(v2, a).single().reason)
        val badTtl = Datagram(source = a, destination = NodeId.BROADCAST, ttl = 99, protocol = 0, payload = ByteArray(0))
        assertEquals(DropReason.BAD_TTL, r.handle(badTtl, a).single().reason)
    }
}
