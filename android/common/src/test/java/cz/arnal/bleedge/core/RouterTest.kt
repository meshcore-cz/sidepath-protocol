package cz.arnal.bleedge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class RouterTest {
    private fun id(b: Int) = NodeID(ByteArray(8) { b.toByte() })

    /**
     * alice → bob → carol arrives at carol with trace [bob] (the originator does not put
     * itself in the trace). The ACK source route must end at alice so it is delivered
     * there, not at the last relay bob.
     */
    @Test
    fun ackSourceRouteReachesSource() {
        val identity = Identity.fromSeed(ByteArray(SEED_SIZE) { 9 })
        val carol = identity.nodeId
        val router = Router(identity)
        val alice = id(1)
        val bob = id(2)

        val pkt = Packet(
            type = PacketType.DATA,
            source = alice,
            destination = carol,
            mode = RoutingMode.FLOOD,
            ttl = 3,
            trace = listOf(bob),
            payloadType = PayloadType.TEXT_TEST,
            payload = "ack me".toByteArray(),
        )

        val ack = router.handlePacket(pkt, bob)
            .firstOrNull { it.type == ActionType.SEND_ACK }?.packet
        assertNotNull("expected SEND_ACK", ack)
        assertEquals(RoutingMode.SOURCE_ROUTE, ack!!.mode)
        assertEquals(
            listOf(bob.toHexString(), alice.toHexString()), // carol → bob → alice
            ack.route.map { it.toHexString() },
        )
    }
}
