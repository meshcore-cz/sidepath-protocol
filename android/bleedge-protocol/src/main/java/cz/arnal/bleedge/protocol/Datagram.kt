package cz.arnal.bleedge.protocol

import com.upokecenter.cbor.CBORObject
import java.security.SecureRandom

/**
 * A BLEEdge datagram — the end-to-end routing unit (docs/PROTOCOL.md §6). It is
 * encoded as a CBOR map with compact integer keys. The routing engine treats
 * [payload] as opaque bytes identified by [protocol].
 *
 * Routing mode is inferred (§6.1): an empty [route] means flood routing; a
 * non-empty [route] means source routing.
 */
data class Datagram(
    val version: Int = BLEEdge.DATAGRAM_VERSION,
    val id: ByteArray = newDatagramId(),
    val source: NodeId,
    val destination: NodeId,
    val ttl: Int,
    val route: List<NodeId> = emptyList(),
    val routeCursor: Int = 0,
    val path: List<NodeId> = emptyList(),
    val protocol: Int,
    val flags: Int = 0,
    val payload: ByteArray = ByteArray(0),
) {
    val isBroadcast: Boolean get() = destination.isBroadcast()
    val isSourceRouted: Boolean get() = route.isNotEmpty()
    fun ackRequested(): Boolean = flags and DatagramFlags.ACK_REQUESTED != 0

    fun encode(): ByteArray {
        val map = CBORObject.NewOrderedMap()
        map[k(1)] = CBORObject.FromObject(version)
        map[k(2)] = CBORObject.FromObject(id)
        map[k(3)] = CBORObject.FromObject(source.bytes)
        map[k(4)] = CBORObject.FromObject(destination.bytes)
        map[k(5)] = CBORObject.FromObject(ttl)
        if (route.isNotEmpty()) map[k(6)] = nodeArray(route)
        if (routeCursor != 0) map[k(7)] = CBORObject.FromObject(routeCursor)
        if (path.isNotEmpty()) map[k(8)] = nodeArray(path)
        map[k(9)] = CBORObject.FromObject(protocol)
        if (flags != 0) map[k(10)] = CBORObject.FromObject(flags)
        map[k(11)] = CBORObject.FromObject(payload)
        return map.EncodeToBytes()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Datagram) return false
        return version == other.version && id.contentEquals(other.id) &&
            source == other.source && destination == other.destination && ttl == other.ttl &&
            route == other.route && routeCursor == other.routeCursor && path == other.path &&
            protocol == other.protocol && flags == other.flags && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = id.contentHashCode()

    companion object {
        private fun k(i: Int) = CBORObject.FromObject(i)

        private fun nodeArray(ids: List<NodeId>): CBORObject {
            val arr = CBORObject.NewArray()
            ids.forEach { arr.Add(CBORObject.FromObject(it.bytes)) }
            return arr
        }

        private fun nodeList(obj: CBORObject?): List<NodeId> {
            if (obj == null || obj.isNull || obj.isUndefined) return emptyList()
            return (0 until obj.size()).map { NodeId(obj[k(it)].GetByteString()) }
        }

        /** Decodes a datagram. Throws on malformed CBOR or missing required fields. */
        fun decode(data: ByteArray): Datagram {
            val map = CBORObject.DecodeFromBytes(data)
            val routeCursor = map[k(7)]?.takeIf { !it.isNull }?.AsInt32() ?: 0
            val flags = map[k(10)]?.takeIf { !it.isNull }?.AsInt32() ?: 0
            return Datagram(
                version = map[k(1)].AsInt32(),
                id = map[k(2)].GetByteString(),
                source = NodeId(map[k(3)].GetByteString()),
                destination = NodeId(map[k(4)].GetByteString()),
                ttl = map[k(5)].AsInt32(),
                route = nodeList(map[k(6)]),
                routeCursor = routeCursor,
                path = nodeList(map[k(8)]),
                protocol = map[k(9)].AsInt32(),
                flags = flags,
                payload = map[k(11)].GetByteString(),
            )
        }

        fun newDatagramId(): ByteArray =
            ByteArray(BLEEdge.DATAGRAM_ID_BYTES).also { SecureRandom().nextBytes(it) }
    }
}
