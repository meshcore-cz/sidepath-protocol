package cz.arnal.bleedge.core

import com.upokecenter.cbor.CBORObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

const val TRACE_HASH_WIDTH_1 = 1
const val TRACE_HASH_WIDTH_2 = 2
const val TRACE_HASH_WIDTH_4 = 4
const val TRACE_HASH_WIDTH_8 = 8

const val TRACE_METRIC_UNKNOWN = "unknown"
const val TRACE_METRIC_RSSI = "rssi"
const val TRACE_METRIC_SNR = "snr"

data class TracePayload(
    val tag: Int,
    val authCode: Int,
    val flags: Byte,
    val routeData: ByteArray = ByteArray(0),
) {
    fun hashWidth(): Int = 1 shl (flags.toInt() and 0x03)
}

fun randomTraceTag(): Int {
    val b = ByteArray(4)
    SecureRandom().nextBytes(b)
    return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
}

fun traceFlagsForHashWidth(width: Int): Byte = when (width) {
    TRACE_HASH_WIDTH_1 -> 0
    TRACE_HASH_WIDTH_2 -> 1
    TRACE_HASH_WIDTH_4 -> 2
    TRACE_HASH_WIDTH_8 -> 3
    else -> error("invalid trace hash width $width")
}.toByte()

fun encodeTracePayload(p: TracePayload): ByteArray {
    val out = ByteBuffer.allocate(9 + p.routeData.size).order(ByteOrder.LITTLE_ENDIAN)
    out.putInt(p.tag)
    out.putInt(p.authCode)
    out.put(p.flags)
    out.put(p.routeData)
    return out.array()
}

fun decodeTracePayload(payload: ByteArray): TracePayload {
    require(payload.size >= 9) { "trace payload too short: ${payload.size}" }
    val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
    val tag = buf.int
    val auth = buf.int
    val flags = buf.get()
    val routeData = ByteArray(buf.remaining())
    buf.get(routeData)
    return TracePayload(tag, auth, flags, routeData)
}

fun traceRouteData(route: List<NodeID>, width: Int = TRACE_HASH_WIDTH_8): ByteArray {
    traceFlagsForHashWidth(width)
    val out = ByteArray(route.size * width)
    route.forEachIndexed { i, id ->
        id.bytes.copyInto(out, destinationOffset = i * width, startIndex = 0, endIndex = width)
    }
    return out
}

data class TraceResult(
    val tag: Int,
    val authCode: Int,
    val route: List<NodeID>,
    val forwardNodes: List<NodeID>,
    val forwardSamples: List<Byte>,
    val returnNodes: List<NodeID> = emptyList(),
    val returnSamples: List<Byte> = emptyList(),
    val metric: String = TRACE_METRIC_RSSI,
) {
    fun encode(): ByteArray {
        val map = CBORObject.NewOrderedMap()
        map[CBORObject.FromObject(1)] = CBORObject.FromObject(tag)
        map[CBORObject.FromObject(2)] = CBORObject.FromObject(authCode)
        val routeArr = CBORObject.NewArray()
        route.forEach { routeArr.Add(CBORObject.FromObject(it.bytes)) }
        map[CBORObject.FromObject(3)] = routeArr
        val fNodeArr = CBORObject.NewArray()
        forwardNodes.forEach { fNodeArr.Add(CBORObject.FromObject(it.bytes)) }
        map[CBORObject.FromObject(4)] = fNodeArr
        val fSampleArr = CBORObject.NewArray()
        forwardSamples.forEach { fSampleArr.Add(CBORObject.FromObject(it.toInt())) }
        map[CBORObject.FromObject(5)] = fSampleArr
        if (returnNodes.isNotEmpty()) {
            val rNodeArr = CBORObject.NewArray()
            returnNodes.forEach { rNodeArr.Add(CBORObject.FromObject(it.bytes)) }
            map[CBORObject.FromObject(6)] = rNodeArr
        }
        if (returnSamples.isNotEmpty()) {
            val rSampleArr = CBORObject.NewArray()
            returnSamples.forEach { rSampleArr.Add(CBORObject.FromObject(it.toInt())) }
            map[CBORObject.FromObject(7)] = rSampleArr
        }
        map[CBORObject.FromObject(8)] = CBORObject.FromObject(metric)
        return map.EncodeToBytes()
    }

    companion object {
        fun decode(payload: ByteArray): TraceResult {
            val map = CBORObject.DecodeFromBytes(payload)
            return TraceResult(
                tag = map[CBORObject.FromObject(1)].AsInt32(),
                authCode = map[CBORObject.FromObject(2)].AsInt32(),
                route = cborArrayToNodeIDs(map[CBORObject.FromObject(3)]),
                forwardNodes = cborArrayToNodeIDs(map[CBORObject.FromObject(4)]),
                forwardSamples = cborArrayToBytes(map[CBORObject.FromObject(5)]),
                returnNodes = cborArrayToNodeIDs(map[CBORObject.FromObject(6)]),
                returnSamples = cborArrayToBytes(map[CBORObject.FromObject(7)]),
                metric = map[CBORObject.FromObject(8)]?.takeIf { !it.isNull }?.AsString() ?: TRACE_METRIC_UNKNOWN,
            )
        }
    }
}
