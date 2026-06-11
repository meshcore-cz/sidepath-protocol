package cz.arnal.bleedge.core

import java.util.UUID

// Protocol version (must match Go side). v2 = Ed25519 identities + signed ANNOUNCE.
const val PROTOCOL_VERSION: Byte = 2

// Ed25519 identity seed length (RFC 8032).
const val SEED_SIZE = 32

// NodeID is an 8-byte identifier for a mesh node.
@JvmInline
value class NodeID(val bytes: ByteArray) {
    init { require(bytes.size == 8) { "NodeID must be 8 bytes" } }

    fun toHexString(): String = bytes.joinToString("") { "%02x".format(it) }

    operator fun compareTo(other: NodeID): Int {
        for (i in 0..7) {
            val a = bytes[i].toInt() and 0xFF
            val b = other.bytes[i].toInt() and 0xFF
            if (a < b) return -1
            if (a > b) return 1
        }
        return 0
    }

    fun isLessThan(other: NodeID): Boolean = compareTo(other) < 0

    override fun toString(): String = toHexString()

    companion object {
        val BROADCAST = NodeID(ByteArray(8))

        fun fromHex(hex: String): NodeID {
            require(hex.length == 16) { "NodeID hex must be 16 chars, got: $hex" }
            val bytes = ByteArray(8) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            return NodeID(bytes)
        }

        fun random(): NodeID {
            val bytes = ByteArray(8)
            java.security.SecureRandom().nextBytes(bytes)
            return NodeID(bytes)
        }

        /** Routing NodeID = first 8 bytes of an Ed25519 public key. */
        fun fromPubKey(pub: ByteArray): NodeID = NodeID(pub.copyOfRange(0, 8))
    }
}

fun NodeID.isBroadcast(): Boolean = bytes.all { it == 0.toByte() }

enum class PacketType(val value: Byte) {
    DATA(1),
    ANNOUNCE(2),
    ACK(3);

    companion object {
        fun fromValue(v: Byte) = entries.first { it.value == v }
    }
}

enum class RoutingMode(val value: Byte) {
    FLOOD(1),
    SOURCE_ROUTE(2);

    companion object {
        fun fromValue(v: Byte) = entries.first { it.value == v }
    }
}

enum class PayloadType(val value: Byte) {
    TEXT_TEST(1),
    MESH_CORE_RAW(2),
    CHAT_PLAIN(3),       // broadcast channel text (UTF-8)
    CHAT_ENCRYPTED(4),   // direct message: Crypto sealed envelope (CBOR)
    CHANNEL(5),          // MeshCore-compatible group channel: GRP_TXT payload (ChannelCrypto)
    TRACE_REQUEST(6),    // MeshCore-shaped trace request
    TRACE_RESPONSE(7);   // BLEEdge trace result returned to requester

    companion object {
        fun fromValue(v: Byte) = entries.firstOrNull { it.value == v } ?: TEXT_TEST
    }
}

enum class PHY {
    UNKNOWN, PHY_1M, PHY_2M, PHY_CODED;

    override fun toString(): String = when (this) {
        UNKNOWN   -> "unknown"
        PHY_1M    -> "1M"
        PHY_2M    -> "2M"
        PHY_CODED -> "LE Coded"
    }

    companion object {
        fun fromAndroid(phy: Int): PHY = when (phy) {
            android.bluetooth.BluetoothDevice.PHY_LE_1M    -> PHY_1M
            android.bluetooth.BluetoothDevice.PHY_LE_2M    -> PHY_2M
            android.bluetooth.BluetoothDevice.PHY_LE_CODED -> PHY_CODED
            else -> UNKNOWN
        }
    }
}

// Capability bitmask flags
object Capability {
    const val SENDER: Int    = 0x01
    const val RECEIVER: Int  = 0x02
    const val RELAY: Int     = 0x04
    const val GATEWAY: Int   = 0x08
    const val CODED_PHY: Int = 0x10
}

@JvmInline
value class Capabilities(val value: Int) {
    fun has(cap: Int): Boolean = value and cap != 0
    fun isRelay(): Boolean = has(Capability.RELAY)
    fun isGateway(): Boolean = has(Capability.GATEWAY)
    fun hasCodedPhy(): Boolean = has(Capability.CODED_PHY)
    fun isSender(): Boolean = has(Capability.SENDER)
    fun isReceiver(): Boolean = has(Capability.RECEIVER)
    override fun toString(): String {
        val flags = mutableListOf<String>()
        if (isSender()) flags += "sender"
        if (isReceiver()) flags += "receiver"
        if (isRelay()) flags += "relay"
        if (isGateway()) flags += "gateway"
        if (hasCodedPhy()) flags += "coded-phy"
        return flags.joinToString("|")
    }
}

val ANDROID_CAPABILITIES = Capabilities(
    Capability.SENDER or Capability.RECEIVER or Capability.RELAY or Capability.CODED_PHY
)

enum class PHYMode(val value: String) {
    CODED_ONLY("coded-only"),
    CODED_PREFERRED("coded-preferred"),
    DEBUG_1M("1m");

    companion object {
        fun fromString(s: String) = when (s) {
            "1m-debug" -> DEBUG_1M // legacy stored value
            else -> entries.firstOrNull { it.value == s } ?: DEBUG_1M
        }
    }
}

// GATT UUIDs — must match Linux/Go side
object BLEEdgeUUIDs {
    val SERVICE     = UUID.fromString("9b7e6a10-7d91-4c19-a3b8-6e2a11f3a001")
    val NODE_INFO   = UUID.fromString("9b7e6a10-7d91-4c19-a3b8-6e2a11f3a002")
    val PACKET_IN   = UUID.fromString("9b7e6a10-7d91-4c19-a3b8-6e2a11f3a003")
    val PACKET_OUT  = UUID.fromString("9b7e6a10-7d91-4c19-a3b8-6e2a11f3a004")
    val CCCD        = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
