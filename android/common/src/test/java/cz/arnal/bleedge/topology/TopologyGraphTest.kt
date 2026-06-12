package cz.arnal.bleedge.topology

import cz.arnal.bleedge.protocol.Capabilities
import cz.arnal.bleedge.protocol.NodeId
import cz.arnal.bleedge.service.PeerInfo
import cz.arnal.bleedge.service.RSSI_UNKNOWN
import cz.arnal.bleedge.service.TopologyEntry
import cz.arnal.bleedge.transport.PHY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TopologyGraphTest {

    private val caps = Capabilities(0)

    /** A 10-byte (20 hex char) NodeID from a short label, zero-padded. */
    private fun id(label: String): NodeId =
        NodeId.fromHex(label.padEnd(20, '0').take(20))

    private fun topo(node: NodeId, neighbors: List<NodeId>, lastSeen: Long) =
        TopologyEntry(node, caps, neighbors, lastAnnounceMs = lastSeen)

    private fun peer(node: NodeId, rssi: Int) =
        PeerInfo(node, rssi, PHY.UNKNOWN, PHY.UNKNOWN, caps)

    private fun build(
        self: NodeId,
        peers: List<PeerInfo> = emptyList(),
        topology: List<TopologyEntry> = emptyList(),
        now: Long = 10_000L,
    ) = buildTopologyGraph(self.toHex(), peers, topology, now) { "name-${it.take(4)}" }

    @Test fun selfAlwaysPresentAndFresh() {
        val self = id("aa")
        val g = build(self, now = 50_000L)
        val node = g.nodes.single { it.id == self.toHex() }
        assertTrue(node.isLocal)
        assertEquals(50_000L, node.lastSeenMs)
        assertTrue(g.hasOnlySelf())
    }

    @Test fun bidirectionalLinkDeduplicated() {
        val a = id("aa")
        val b = id("bb")
        // Both A and B claim each other as a neighbor.
        val g = build(
            self = a,
            topology = listOf(
                topo(a, listOf(b), 9_000L),
                topo(b, listOf(a), 9_500L),
            ),
        )
        assertEquals("one link for the A<->B pair", 1, g.links.size)
        val link = g.links.single()
        assertTrue("collapsed pair is bidirectional", link.bidirectional)
        // canonical: smaller hex is the source.
        assertEquals(minOf(a.toHex(), b.toHex()), link.source)
        assertEquals(maxOf(a.toHex(), b.toHex()), link.target)
    }

    @Test fun oneDirectionalLinkPreservesDirection() {
        val a = id("aa")
        val b = id("bb")
        // Only A claims B; B has not announced A back.
        val g = build(self = a, topology = listOf(topo(a, listOf(b), 9_000L)))
        val link = g.links.single()
        assertFalse(link.bidirectional)
        assertEquals("claimer is the source", a.toHex(), link.source)
        assertEquals("claimed neighbor is the target", b.toHex(), link.target)
    }

    @Test fun directPeerRssiAttachedToLink() {
        val a = id("aa")
        val b = id("bb")
        val g = build(
            self = a,
            peers = listOf(peer(b, -67)),
            topology = listOf(topo(b, listOf(a), 9_000L)),
        )
        val link = g.links.single()
        assertEquals(-67, link.rssi)
        assertTrue("our connection + B's announce = bidirectional", link.bidirectional)
    }

    @Test fun announceOnlyLinkHasUnknownRssi() {
        val a = id("aa")
        val b = id("bb")
        val c = id("cc")
        // B announces C as a neighbor; we have no direct link to either.
        val g = build(self = a, topology = listOf(topo(b, listOf(c), 9_000L)))
        assertEquals(RSSI_UNKNOWN, g.links.single().rssi)
    }

    @Test fun neighborCountAndLastSeenPopulated() {
        val a = id("aa")
        val b = id("bb")
        val c = id("cc")
        val g = build(
            self = a,
            topology = listOf(topo(b, listOf(a, c), 8_000L)),
        )
        val bNode = g.nodes.single { it.id == b.toHex() }
        assertEquals(2, bNode.neighborCount)
        assertEquals(8_000L, bNode.lastSeenMs)
        // C was only referenced as a neighbor — still a node, with no announce of its own.
        assertNotNull(g.nodes.firstOrNull { it.id == c.toHex() })
    }

    @Test fun scalesToHundredNodesWithoutDuplicateLinks() {
        val self = id("00")
        // A ring of 120 nodes, each announcing the next as a neighbor; close the ring.
        val ring = (1..120).map { id("%02x".format(it)) }
        val topology = ring.mapIndexed { i, node ->
            topo(node, listOf(ring[(i + 1) % ring.size]), 9_000L)
        }
        val g = build(self = self, topology = topology)
        assertTrue("self + 120 ring nodes", g.nodes.size >= 121)
        // Each consecutive pair is claimed once each way (i->i+1 and (i+1) announces i+2 only),
        // so the ring yields exactly 120 undirected edges with no duplicate pair rows.
        assertEquals(120, g.links.size)
        // No two links share the same unordered endpoint pair.
        val pairs = g.links.map { setOf(it.source, it.target) }
        assertEquals(pairs.size, pairs.toSet().size)
    }

    @Test fun nodeNotFoundIsNullSafe() {
        val g = build(self = id("aa"))
        assertNull(g.nodes.firstOrNull { it.id == id("ff").toHex() })
    }
}
