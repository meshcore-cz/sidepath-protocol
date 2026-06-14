package cz.meshcore.sidepath.protocol

import com.upokecenter.cbor.CBORObject

/**
 * A Sidepath control message (docs/PROTOCOL.md §7), carried as the payload of a
 * datagram with `protocol = SIDEPATH_CONTROL`. CBOR map: { 1: kind, 2: body }.
 */
data class ControlMessage(val kind: Int, val body: CBORObject) {
    fun encode(): ByteArray {
        val map = CBORObject.NewOrderedMap()
        map[CBORObject.FromObject(1)] = CBORObject.FromObject(kind)
        map[CBORObject.FromObject(2)] = body
        return map.EncodeToBytes()
    }

    companion object {
        fun decode(data: ByteArray): ControlMessage {
            val map = CBORObject.DecodeFromBytes(data)
            return ControlMessage(map[CBORObject.FromObject(1)].AsInt32(), map[CBORObject.FromObject(2)])
        }
    }
}

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

private fun shortArray(values: List<Short>): CBORObject {
    val arr = CBORObject.NewArray()
    values.forEach { arr.Add(CBORObject.FromObject(it.toInt())) }
    return arr
}

private fun shortList(obj: CBORObject?): List<Short> {
    if (obj == null || obj.isNull || obj.isUndefined) return emptyList()
    return (0 until obj.size()).map { obj[k(it)].AsInt32().toShort() }
}

/** Encodes the v2 announce `bridges` array: each entry a map {1:code, [2:freqHz,3:bwHz,4:sf,5:cr]}. */
private fun bridgeArray(bridges: List<BridgeAd>): CBORObject {
    val arr = CBORObject.NewArray()
    bridges.forEach { b ->
        val m = CBORObject.NewOrderedMap()
        m[k(1)] = CBORObject.FromObject(b.code)
        if (b.isCustom) {
            m[k(2)] = CBORObject.FromObject(b.freqHz)
            m[k(3)] = CBORObject.FromObject(b.bandwidthHz)
            m[k(4)] = CBORObject.FromObject(b.sf)
            m[k(5)] = CBORObject.FromObject(b.cr)
        }
        arr.Add(m)
    }
    return arr
}

private fun bridgeList(obj: CBORObject?): List<BridgeAd> {
    if (obj == null || obj.isNull || obj.isUndefined) return emptyList()
    return (0 until obj.size()).map { i ->
        val m = obj[k(i)]
        BridgeAd(
            code = m[k(1)].AsString(),
            freqHz = m[k(2)]?.AsInt64Value() ?: 0L,
            bandwidthHz = m[k(3)]?.AsInt64Value() ?: 0L,
            sf = m[k(4)]?.AsInt32() ?: 0,
            cr = m[k(5)]?.AsInt32() ?: 0,
        )
    }
}

/**
 * A signed ANNOUNCE body (§8.2). [neighbors] MUST be sorted lexicographically and
 * unique. The signature covers the fixed layout from [Identity.announceSignedMessage],
 * not this CBOR encoding.
 */
data class AnnounceBody(
    val announceVersion: Int = Sidepath.ANNOUNCE_VERSION,
    val publicKey: ByteArray,
    val epoch: Long,
    val seq: Long,
    val timestamp: Long,
    val caps: Capabilities,
    val neighbors: List<NodeId>,
    val name: String,
    val description: String,
    val platform: String,
    val signature: ByteArray,
    // v2 ANNOUNCE: the external networks this (gateway) node bridges. Empty on v1 and on non-gateways.
    val bridges: List<BridgeAd> = emptyList(),
) {
    /** Verifies field-length limits, sorted/unique neighbors, and the Ed25519 signature (§8.4). */
    fun isValid(): Boolean {
        if (announceVersion < Sidepath.MIN_ANNOUNCE_VERSION || announceVersion > Sidepath.ANNOUNCE_VERSION) return false
        if (publicKey.size != Sidepath.PUBLIC_KEY_BYTES) return false
        if (neighbors.size > Sidepath.MAX_NEIGHBORS) return false
        if (name.toByteArray(Charsets.UTF_8).size > Sidepath.MAX_NAME_BYTES) return false
        if (description.toByteArray(Charsets.UTF_8).size > Sidepath.MAX_DESCRIPTION_BYTES) return false
        if (platform.toByteArray(Charsets.UTF_8).size > Sidepath.MAX_PLATFORM_BYTES) return false
        if (!neighborsSortedUnique()) return false
        // Bridges only exist from v2; reject any carried on a v1 body, and bound/validate v2 entries.
        if (announceVersion < 2 && bridges.isNotEmpty()) return false
        if (bridges.size > Sidepath.MAX_BRIDGES) return false
        if (bridges.any { !it.isValid() }) return false
        return Identity.verifyAnnounce(
            publicKey, signature, epoch, seq, timestamp, caps, neighbors, name, description, platform,
            announceVersion, bridges,
        )
    }

    private fun neighborsSortedUnique(): Boolean {
        for (i in 1 until neighbors.size) {
            if (neighbors[i - 1] >= neighbors[i]) return false
        }
        return true
    }

    fun toControl(): ControlMessage = ControlMessage(ControlKind.ANNOUNCE, encodeBody())

    fun encodeBody(): CBORObject {
        val map = CBORObject.NewOrderedMap()
        map[k(1)] = CBORObject.FromObject(announceVersion)
        map[k(2)] = CBORObject.FromObject(publicKey)
        map[k(3)] = CBORObject.FromObject(epoch)
        map[k(4)] = CBORObject.FromObject(seq)
        map[k(5)] = CBORObject.FromObject(timestamp)
        map[k(6)] = CBORObject.FromObject(caps.value)
        map[k(7)] = nodeArray(neighbors)
        map[k(8)] = CBORObject.FromObject(name)
        map[k(9)] = CBORObject.FromObject(description)
        map[k(10)] = CBORObject.FromObject(platform)
        map[k(11)] = CBORObject.FromObject(signature)
        // Only present on v2 gateway announces; absent keeps v1 bodies byte-for-byte unchanged.
        if (bridges.isNotEmpty()) map[k(12)] = bridgeArray(bridges)
        return map
    }

    companion object {
        fun decode(body: CBORObject): AnnounceBody = AnnounceBody(
            announceVersion = body[k(1)].AsInt32(),
            publicKey = body[k(2)].GetByteString(),
            epoch = body[k(3)].AsInt64Value(),
            seq = body[k(4)].AsInt64Value(),
            timestamp = body[k(5)].AsInt64Value(),
            caps = Capabilities(body[k(6)].AsInt32()),
            neighbors = nodeList(body[k(7)]),
            name = body[k(8)].AsString(),
            description = body[k(9)].AsString(),
            platform = body[k(10)].AsString(),
            signature = body[k(11)].GetByteString(),
            bridges = bridgeList(body[k(12)]),
        )

        /**
         * Builds and signs an announce body for [identity]. Emits v1 (byte-identical to the original
         * layout) when [bridges] is empty, or v2 with the `bridges` section when it isn't.
         */
        fun create(
            identity: Identity,
            epoch: Long,
            seq: Long,
            timestamp: Long,
            caps: Capabilities,
            neighbors: List<NodeId>,
            name: String,
            description: String,
            platform: String,
            bridges: List<BridgeAd> = emptyList(),
        ): AnnounceBody {
            val sorted = neighbors.distinctBy { it.toHex() }.sortedWith { a, b -> a.compareTo(b) }
            val version = if (bridges.isEmpty()) 1 else Sidepath.ANNOUNCE_VERSION
            val sig = identity.signAnnounce(
                epoch, seq, timestamp, caps, sorted, name, description, platform, version, bridges,
            )
            return AnnounceBody(
                version, identity.publicKey, epoch, seq, timestamp, caps,
                sorted, name, description, platform, sig, bridges,
            )
        }
    }
}

/** ACK body (§9.1): the id of the datagram being acknowledged. */
data class AckBody(val ackedId: ByteArray) {
    fun toControl(): ControlMessage {
        val map = CBORObject.NewOrderedMap()
        map[k(1)] = CBORObject.FromObject(ackedId)
        return ControlMessage(ControlKind.ACK, map)
    }

    companion object {
        fun decode(body: CBORObject): AckBody = AckBody(body[k(1)].GetByteString())
    }
}

/**
 * BRIDGED body (§9.3): sent to a channel message's sender after a gateway relayed it onto an
 * external network (e.g. MeshCore as a GRP_TXT). [bridgedId] is the bridged datagram id,
 * [bridgeId] the gateway NodeID, [meshHash] an optional short correlation hash. Informational only.
 */
data class BridgedBody(
    val bridgedId: ByteArray,
    val bridgeId: NodeId,
    val meshHash: ByteArray?,
) {
    fun toControl(): ControlMessage {
        val map = CBORObject.NewOrderedMap()
        map[k(1)] = CBORObject.FromObject(bridgedId)
        map[k(2)] = CBORObject.FromObject(bridgeId.bytes)
        meshHash?.let { map[k(3)] = CBORObject.FromObject(it) }
        return ControlMessage(ControlKind.BRIDGED, map)
    }

    companion object {
        fun decode(body: CBORObject): BridgedBody = BridgedBody(
            bridgedId = body[k(1)].GetByteString(),
            bridgeId = NodeId(body[k(2)].GetByteString()),
            meshHash = body[k(3)]?.GetByteString(),
        )
    }
}

/** TRACE_REQUEST body (§11.2). */
data class TraceRequestBody(
    val tag: Long,
    val metric: Int,
    val forwardSamples: List<Short>,
) {
    fun toControl(): ControlMessage {
        val map = CBORObject.NewOrderedMap()
        map[k(1)] = CBORObject.FromObject(tag)
        map[k(2)] = CBORObject.FromObject(metric)
        map[k(3)] = shortArray(forwardSamples)
        return ControlMessage(ControlKind.TRACE_REQUEST, map)
    }

    companion object {
        fun decode(body: CBORObject): TraceRequestBody = TraceRequestBody(
            tag = body[k(1)].AsInt64Value(),
            metric = body[k(2)].AsInt32(),
            forwardSamples = shortList(body[k(3)]),
        )
    }
}

/** TRACE_RESPONSE body (§11.3). */
data class TraceResponseBody(
    val tag: Long,
    val metric: Int,
    val forwardPath: List<NodeId>,
    val forwardSamples: List<Short>,
    val returnSamples: List<Short>,
) {
    fun toControl(): ControlMessage {
        val map = CBORObject.NewOrderedMap()
        map[k(1)] = CBORObject.FromObject(tag)
        map[k(2)] = CBORObject.FromObject(metric)
        map[k(3)] = nodeArray(forwardPath)
        map[k(4)] = shortArray(forwardSamples)
        map[k(5)] = shortArray(returnSamples)
        return ControlMessage(ControlKind.TRACE_RESPONSE, map)
    }

    companion object {
        fun decode(body: CBORObject): TraceResponseBody = TraceResponseBody(
            tag = body[k(1)].AsInt64Value(),
            metric = body[k(2)].AsInt32(),
            forwardPath = nodeList(body[k(3)]),
            forwardSamples = shortList(body[k(4)]),
            returnSamples = shortList(body[k(5)]),
        )
    }
}
