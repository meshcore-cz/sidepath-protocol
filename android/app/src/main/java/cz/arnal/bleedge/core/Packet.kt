package cz.arnal.bleedge.core

import com.upokecenter.cbor.CBORObject
import java.security.SecureRandom

// PacketID is 16 random bytes (UUID v4 random).
typealias PacketID = ByteArray

fun newPacketID(): PacketID {
    val b = ByteArray(16)
    SecureRandom().nextBytes(b)
    return b
}

/** Decode a CBOR array of byte strings into a list of NodeIDs. */
private fun cborArrayToNodeIDs(obj: CBORObject?): List<NodeID> {
    if (obj == null || obj.isNull || obj.isUndefined) return emptyList()
    val n = obj.size()
    return (0 until n).map { i -> NodeID(obj[CBORObject.FromObject(i)].GetByteString()) }
}

/**
 * Packet is the core mesh routing packet.
 * CBOR keys are compact integers (1-based), matching the Go implementation.
 */
data class Packet(
    val version: Byte = PROTOCOL_VERSION,
    val type: PacketType = PacketType.DATA,
    val id: PacketID = newPacketID(),
    val source: NodeID = NodeID(ByteArray(8)),
    val destination: NodeID = NodeID(ByteArray(8)), // zero = broadcast
    val mode: RoutingMode = RoutingMode.FLOOD,
    val ttl: Byte = 4,
    val routeCursor: Byte = 0,
    val route: List<NodeID> = emptyList(),
    val trace: List<NodeID> = emptyList(),
    val payloadType: PayloadType = PayloadType.TEXT_TEST,
    val payload: ByteArray = ByteArray(0),
    val seq: Int = 0,
) {
    fun isBroadcast(): Boolean = destination.isBroadcast()

    fun encode(): ByteArray {
        val map = CBORObject.NewOrderedMap()
        map[CBORObject.FromObject(1)] = CBORObject.FromObject(version.toInt() and 0xFF)
        map[CBORObject.FromObject(2)] = CBORObject.FromObject(type.value.toInt() and 0xFF)
        map[CBORObject.FromObject(3)] = CBORObject.FromObject(id)
        map[CBORObject.FromObject(4)] = CBORObject.FromObject(source.bytes)
        map[CBORObject.FromObject(5)] = CBORObject.FromObject(destination.bytes)
        map[CBORObject.FromObject(6)] = CBORObject.FromObject(mode.value.toInt() and 0xFF)
        map[CBORObject.FromObject(7)] = CBORObject.FromObject(ttl.toInt() and 0xFF)
        map[CBORObject.FromObject(8)] = CBORObject.FromObject(routeCursor.toInt() and 0xFF)
        val routeArr = CBORObject.NewArray()
        route.forEach { routeArr.Add(CBORObject.FromObject(it.bytes)) }
        map[CBORObject.FromObject(9)] = routeArr
        val traceArr = CBORObject.NewArray()
        trace.forEach { traceArr.Add(CBORObject.FromObject(it.bytes)) }
        map[CBORObject.FromObject(10)] = traceArr
        map[CBORObject.FromObject(11)] = CBORObject.FromObject(payloadType.value.toInt() and 0xFF)
        map[CBORObject.FromObject(12)] = CBORObject.FromObject(payload)
        if (seq != 0) {
            map[CBORObject.FromObject(13)] = CBORObject.FromObject(seq)
        }
        return map.EncodeToBytes()
    }

    companion object {
        fun decode(data: ByteArray): Packet {
            val map = CBORObject.DecodeFromBytes(data)
            val version = map[CBORObject.FromObject(1)].AsInt32().toByte()
            val type = PacketType.fromValue(map[CBORObject.FromObject(2)].AsInt32().toByte())
            val id = map[CBORObject.FromObject(3)].GetByteString()
            val source = NodeID(map[CBORObject.FromObject(4)].GetByteString())
            val destination = NodeID(map[CBORObject.FromObject(5)].GetByteString())
            val mode = RoutingMode.fromValue(map[CBORObject.FromObject(6)].AsInt32().toByte())
            val ttl = map[CBORObject.FromObject(7)].AsInt32().toByte()
            val routeCursor = map[CBORObject.FromObject(8)].AsInt32().toByte()
            val routeObj = map[CBORObject.FromObject(9)]
            val route = cborArrayToNodeIDs(routeObj)
            val traceObj = map[CBORObject.FromObject(10)]
            val trace = cborArrayToNodeIDs(traceObj)
            val payloadType = PayloadType.fromValue(map[CBORObject.FromObject(11)].AsInt32().toByte())
            val payload = map[CBORObject.FromObject(12)].GetByteString()
            val seqObj = map[CBORObject.FromObject(13)]
            val seq = if (seqObj != null && !seqObj.isNull) seqObj.AsInt32() else 0
            return Packet(
                version = version,
                type = type,
                id = id,
                source = source,
                destination = destination,
                mode = mode,
                ttl = ttl,
                routeCursor = routeCursor,
                route = route,
                trace = trace,
                payloadType = payloadType,
                payload = payload,
                seq = seq,
            )
        }
    }
}

/**
 * AnnouncePayload is the payload inside PacketType.ANNOUNCE packets.
 */
data class AnnouncePayload(
    val nodeId: NodeID,
    val caps: Capabilities,
    val neighbors: List<NodeID>,
    val seq: Int,
    val timestamp: Long,
) {
    fun encode(): ByteArray {
        val map = CBORObject.NewOrderedMap()
        map[CBORObject.FromObject(1)] = CBORObject.FromObject(nodeId.bytes)
        map[CBORObject.FromObject(2)] = CBORObject.FromObject(caps.value)
        val nbArr = CBORObject.NewArray()
        neighbors.forEach { nbArr.Add(CBORObject.FromObject(it.bytes)) }
        map[CBORObject.FromObject(3)] = nbArr
        map[CBORObject.FromObject(4)] = CBORObject.FromObject(seq)
        map[CBORObject.FromObject(5)] = CBORObject.FromObject(timestamp)
        return map.EncodeToBytes()
    }

    companion object {
        fun decode(data: ByteArray): AnnouncePayload {
            val map = CBORObject.DecodeFromBytes(data)
            val nodeId = NodeID(map[CBORObject.FromObject(1)].GetByteString())
            val caps = Capabilities(map[CBORObject.FromObject(2)].AsInt32())
            val nbObj = map[CBORObject.FromObject(3)]
            val neighbors = cborArrayToNodeIDs(nbObj)
            val seq = map[CBORObject.FromObject(4)].AsInt32()
            val timestamp = map[CBORObject.FromObject(5)].AsInt64()
            return AnnouncePayload(nodeId, caps, neighbors, seq, timestamp)
        }
    }
}
