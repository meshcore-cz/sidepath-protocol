package cz.arnal.bleedge.protocol

/**
 * BLEEdge protocol constants. Single source of truth mirroring docs/PROTOCOL.md
 * §15. These are wire-level invariants shared by every BLEEdge implementation.
 */
object BLEEdge {
    const val FRAME_VERSION: Int = 2
    const val DATAGRAM_VERSION: Int = 3
    const val NODE_INFO_VERSION: Int = 1
    const val ANNOUNCE_VERSION: Int = 1

    const val NODE_ID_BYTES: Int = 10
    const val DATAGRAM_ID_BYTES: Int = 16
    const val TRANSFER_ID_BYTES: Int = 16
    const val PUBLIC_KEY_BYTES: Int = 32
    const val SIGNATURE_BYTES: Int = 64
    const val SEED_BYTES: Int = 32

    const val MAX_FRAME_SIZE: Int = 200
    const val FRAME_HEADER_SIZE: Int = 23
    const val MAX_FRAME_DATA: Int = MAX_FRAME_SIZE - FRAME_HEADER_SIZE // 177

    const val MAX_TTL: Int = 16
    const val DEFAULT_FLOOD_TTL: Int = 5
    const val MAX_ROUTE_HOPS: Int = 16
    const val ANNOUNCE_TTL: Int = 5

    const val ANNOUNCE_INTERVAL_MS: Long = 15_000L
    const val FLOOD_JITTER_MIN_MS: Long = 10L
    const val FLOOD_JITTER_MAX_MS: Long = 100L
    const val REASSEMBLY_TIMEOUT_MS: Long = 10_000L

    const val DEDUP_LIMIT: Int = 4096
    const val DEDUP_TTL_MS: Long = 5 * 60 * 1000L
    const val NEIGHBOR_TIMEOUT_MS: Long = 60_000L
    const val TOPOLOGY_TIMEOUT_MS: Long = 90_000L

    // Announce body field limits (§8.2).
    const val MAX_NEIGHBORS: Int = 255
    const val MAX_NAME_BYTES: Int = 64
    const val MAX_DESCRIPTION_BYTES: Int = 255
    const val MAX_PLATFORM_BYTES: Int = 64
}

/** Payload protocol registry values (§6.4). */
object PayloadProtocol {
    const val BLEEDGE_CONTROL: Int = 0x0000
    const val MESHCORE_PACKET: Int = 0x0001
    const val BLEEDGE_CHAT: Int = 0x0100
}

/** Datagram routing flags (§6.3). */
object DatagramFlags {
    const val ACK_REQUESTED: Int = 0x0001
}

/** Capability bitmask flags (§8.6). */
object Capability {
    const val SENDER: Int = 0x0001
    const val RECEIVER: Int = 0x0002
    const val RELAY: Int = 0x0004
    const val GATEWAY: Int = 0x0008
    const val CODED_PHY: Int = 0x0010
}

@JvmInline
value class Capabilities(val value: Int) {
    fun has(cap: Int): Boolean = value and cap != 0
    fun isSender(): Boolean = has(Capability.SENDER)
    fun isReceiver(): Boolean = has(Capability.RECEIVER)
    fun isRelay(): Boolean = has(Capability.RELAY)
    fun isGateway(): Boolean = has(Capability.GATEWAY)
    fun hasCodedPhy(): Boolean = has(Capability.CODED_PHY)

    override fun toString(): String {
        val flags = mutableListOf<String>()
        if (isSender()) flags += "sender"
        if (isReceiver()) flags += "receiver"
        if (isRelay()) flags += "relay"
        if (isGateway()) flags += "gateway"
        if (hasCodedPhy()) flags += "coded-phy"
        return if (flags.isEmpty()) "none" else flags.joinToString("|")
    }
}

/** Control message kinds carried under BLEEDGE_CONTROL (§7). */
object ControlKind {
    const val ANNOUNCE: Int = 1
    const val ACK: Int = 2
    const val TRACE_REQUEST: Int = 3
    const val TRACE_RESPONSE: Int = 4
}

/** Trace metric identifiers (§11.1). */
object TraceMetric {
    const val UNKNOWN: Int = 0
    const val RSSI_DBM: Int = 1
    const val SNR_Q4: Int = 2

    /** Sentinel sample value meaning "unavailable". */
    const val UNAVAILABLE: Short = -32768
}
