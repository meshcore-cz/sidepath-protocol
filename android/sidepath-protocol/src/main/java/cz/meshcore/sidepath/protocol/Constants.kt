package cz.meshcore.sidepath.protocol

/**
 * Sidepath protocol constants. Single source of truth mirroring docs/PROTOCOL.md
 * §15. These are wire-level invariants shared by every Sidepath implementation.
 */
object Sidepath {
    const val FRAME_VERSION: Int = 2
    const val DATAGRAM_VERSION: Int = 3
    const val NODE_INFO_VERSION: Int = 1
    // Current (max) announce version we emit. v2 adds the optional trailing `bridges` section
    // (§8.3); v3 replaces the bare neighbor-ID list with a trailing `neighbor_info` section carrying
    // per-link RSSI, PHY, direction, and age (§8.8). A node emits the lowest version that fits its
    // data: v1 (byte-identical to the original layout) with no bridges/neighbor-info, v2 when it only
    // bridges networks, v3 once it advertises neighbor link details. Verifiers accept any version in
    // [MIN_ANNOUNCE_VERSION]..[ANNOUNCE_VERSION] and reconstruct the signed bytes per that version.
    const val ANNOUNCE_VERSION: Int = 3
    const val MIN_ANNOUNCE_VERSION: Int = 1

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

    // Announce v2 `bridges` limits (§8.3). A gateway advertises the external networks it bridges.
    const val MAX_BRIDGES: Int = 8
    const val MAX_NETWORK_CODE_BYTES: Int = 5
}

/**
 * One external network a gateway node bridges, advertised in the v2 ANNOUNCE `bridges` array (§8.3).
 * [code] is the short network code (e.g. "CZ", ≤[Sidepath.MAX_NETWORK_CODE_BYTES] bytes). Radio params
 * are carried only when they differ from the code's canonical definition ([isCustom]); otherwise the
 * receiver resolves them from its network-definitions dataset. [freqHz]/[bandwidthHz] are integer Hz
 * (no float on the wire); [sf] is the spreading factor; [cr] is the N in coding rate 4/N.
 */
data class BridgeAd(
    val code: String,
    val freqHz: Long = 0L,
    val bandwidthHz: Long = 0L,
    val sf: Int = 0,
    val cr: Int = 0,
) {
    /** True when this entry carries explicit radio params (they differ from the code's canonical set). */
    val isCustom: Boolean get() = freqHz > 0L || bandwidthHz > 0L || sf > 0 || cr > 0

    fun isValid(): Boolean {
        val codeLen = code.toByteArray(Charsets.UTF_8).size
        if (codeLen < 1 || codeLen > Sidepath.MAX_NETWORK_CODE_BYTES) return false
        if (isCustom) {
            // A custom entry must fully specify the radio params and keep them in range.
            if (freqHz <= 0L || freqHz > 0xFFFFFFFFL) return false
            if (bandwidthHz <= 0L || bandwidthHz > 0xFFFFFFFFL) return false
            if (sf !in 5..12 || cr !in 5..8) return false
        }
        return true
    }
}

/** BLE PHY identifiers carried in a v3 ANNOUNCE `neighbor_info` entry (§8.8). */
object Phy {
    const val UNKNOWN: Int = 0
    const val LE_1M: Int = 1
    const val LE_2M: Int = 2
    const val CODED: Int = 3
}

/** Which side opened a neighbor link, in a v3 ANNOUNCE `neighbor_info` entry (§8.8). */
object ConnDirection {
    const val OUTGOING: Int = 1
    const val INCOMING: Int = 2

    /** Held over both an inbound and an outbound link at once (§4.4 multi-link). */
    const val BOTH: Int = 3
}

/** Link transport technology carried in a v3 ANNOUNCE `neighbor_info` entry (§8.8). 0 = unknown. */
object Transport {
    const val UNKNOWN: Int = 0
    const val BLE: Int = 1
    const val MESHCORE: Int = 2
    const val TCP: Int = 3
    const val USB: Int = 4
    const val MAX: Int = USB
}

/** Payload protocol registry values (§6.4). */
object PayloadProtocol {
    const val SIDEPATH_CONTROL: Int = 0x0000
    const val MESHCORE_PACKET: Int = 0x0001
    const val SIDEPATH_CHAT: Int = 0x0100
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

/** Control message kinds carried under SIDEPATH_CONTROL (§7). */
object ControlKind {
    const val ANNOUNCE: Int = 1
    const val ACK: Int = 2
    const val TRACE_REQUEST: Int = 3
    const val TRACE_RESPONSE: Int = 4

    /** A gateway relayed a message onto an external network (e.g. MeshCore). Informational (§9.3). */
    const val BRIDGED: Int = 5
}

/** Trace metric identifiers (§11.1). */
object TraceMetric {
    const val UNKNOWN: Int = 0
    const val RSSI_DBM: Int = 1
    const val SNR_Q4: Int = 2

    /** Sentinel sample value meaning "unavailable". */
    const val UNAVAILABLE: Short = -32768
}
