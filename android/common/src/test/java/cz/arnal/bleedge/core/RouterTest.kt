package cz.arnal.bleedge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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

    @Test
    fun traceRequestDoesNotAck() {
        val identity = Identity.fromSeed(ByteArray(SEED_SIZE) { 7 })
        val bob = identity.nodeId
        val router = Router(identity)
        val alice = id(1)
        val payload = encodeTracePayload(
            TracePayload(
                tag = 1,
                authCode = 0,
                flags = traceFlagsForHashWidth(TRACE_HASH_WIDTH_8),
                routeData = traceRouteData(listOf(bob), TRACE_HASH_WIDTH_8),
            )
        )
        val pkt = Packet(
            type = PacketType.DATA,
            source = alice,
            destination = bob,
            mode = RoutingMode.SOURCE_ROUTE,
            ttl = 4,
            route = listOf(bob),
            payloadType = PayloadType.TRACE_REQUEST,
            payload = payload,
        )

        val actions = router.handlePacket(pkt, alice)
        assertTrue(actions.any { it.type == ActionType.DELIVER_LOCAL })
        assertFalse(actions.any { it.type == ActionType.SEND_ACK })
    }

    @Test
    fun traceFloodDoesNotRelay() {
        val identity = Identity.fromSeed(ByteArray(SEED_SIZE) { 8 })
        val router = Router(identity)
        val alice = id(1)
        val carol = id(3)
        val payload = encodeTracePayload(
            TracePayload(
                tag = 1,
                authCode = 0,
                flags = traceFlagsForHashWidth(TRACE_HASH_WIDTH_8),
                routeData = traceRouteData(listOf(carol), TRACE_HASH_WIDTH_8),
            )
        )
        val pkt = Packet(
            type = PacketType.DATA,
            source = alice,
            destination = carol,
            mode = RoutingMode.FLOOD,
            ttl = 4,
            payloadType = PayloadType.TRACE_REQUEST,
            payload = payload,
        )

        val actions = router.handlePacket(pkt, alice)
        assertFalse(actions.any { it.type == ActionType.RELAY_FLOOD })
    }
}
