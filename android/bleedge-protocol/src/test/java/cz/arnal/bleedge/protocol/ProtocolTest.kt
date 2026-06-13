package cz.arnal.bleedge.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    private fun seed(b: Int) = ByteArray(BLEEdge.SEED_BYTES) { ((it + b) and 0xFF).toByte() }

    @Test fun nodeIdFromPublicKeyIsFirst10Bytes() {
        val id = Identity.fromSeed(seed(1))
        assertEquals(BLEEdge.NODE_ID_BYTES, id.nodeId.bytes.size)
        assertArrayEquals(id.publicKey.copyOfRange(0, 10), id.nodeId.bytes)
        assertEquals(id.nodeId, NodeId.fromHex(id.nodeId.toHex()))
    }

    @Test fun datagramRoundTrip() {
        val a = Identity.fromSeed(seed(1)).nodeId
        val b = Identity.fromSeed(seed(2)).nodeId
        val dg = Datagram(
            source = a, destination = b, ttl = 5,
            route = listOf(b), routeCursor = 0, path = listOf(a),
            protocol = PayloadProtocol.BLEEDGE_CHAT, flags = DatagramFlags.ACK_REQUESTED,
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
            protocol = PayloadProtocol.BLEEDGE_CHAT, payload = byteArrayOf(9))
        val actions = relay.handle(dg, incomingPeer = a.nodeId)
        assertTrue(actions.any { it.type == ActionType.DELIVER_LOCAL })
        val flood = actions.first { it.type == ActionType.RELAY_FLOOD }
        assertEquals(4, flood.datagram.ttl)
        assertEquals(a.nodeId, flood.excludePeer)
        assertTrue(flood.datagram.path.contains(relay.localId))
        // a duplicate is dropped
        assertEquals(ActionType.DROP, relay.handle(dg, a.nodeId).single().type)
    }

    @Test fun routerUnicastAckRequestedBuildsSourceRoutedAck() {
        val sender = Identity.fromSeed(seed(30))
        val dest = Router(Identity.fromSeed(seed(31)))
        val dg = Datagram(source = sender.nodeId, destination = dest.localId, ttl = 5,
            protocol = PayloadProtocol.BLEEDGE_CHAT, flags = DatagramFlags.ACK_REQUESTED, payload = byteArrayOf(1))
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
            protocol = PayloadProtocol.BLEEDGE_CHAT, payload = byteArrayOf(7))
        val action = mid.handle(dg, incomingPeer = src).single()
        assertEquals(ActionType.RELAY_NEXT_HOP, action.type)
        assertEquals(dst, action.nextHop)
        assertEquals(1, action.datagram.ttl)
        assertEquals(1, action.datagram.routeCursor)
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
