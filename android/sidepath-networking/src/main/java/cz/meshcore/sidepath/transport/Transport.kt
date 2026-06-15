package cz.meshcore.sidepath.transport

import cz.meshcore.sidepath.protocol.Sidepath
import cz.meshcore.sidepath.protocol.Capabilities
import cz.meshcore.sidepath.protocol.Capability
import cz.meshcore.sidepath.protocol.NodeId
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * BLE-transport-layer types for the Android node. These are deliberately kept in
 * `:sidepath-networking` (not in the pure-Kotlin `:sidepath-protocol` SDK) because PHY, the
 * GATT UUIDs, and NODE_INFO framing are Android BLE concerns, not part of the
 * routing protocol.
 */

/** Sidepath GATT service + characteristic UUIDs (docs/PROTOCOL.md §4.1). */
object SidepathUUIDs {
    val SERVICE: UUID = UUID.fromString("9b7e6a10-7d91-4c19-a3b8-6e2a11f3a001")
    val NODE_INFO: UUID = UUID.fromString("9b7e6a10-7d91-4c19-a3b8-6e2a11f3a002")
    val PACKET_IN: UUID = UUID.fromString("9b7e6a10-7d91-4c19-a3b8-6e2a11f3a003")
    val PACKET_OUT: UUID = UUID.fromString("9b7e6a10-7d91-4c19-a3b8-6e2a11f3a004")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

/** Manufacturer company ID tagging Sidepath adverts; payload = raw 10-byte NodeID. */
const val SIDEPATH_MANUFACTURER_ID = 0xBEED

/** Default Android node capabilities (sender, receiver, relay, coded-phy). */
val ANDROID_CAPABILITIES = Capabilities(
    Capability.SENDER or Capability.RECEIVER or Capability.RELAY or Capability.CODED_PHY,
)

enum class PHY {
    UNKNOWN, PHY_1M, PHY_2M, PHY_CODED;

    override fun toString(): String = when (this) {
        UNKNOWN -> "unknown"
        PHY_1M -> "1M"
        PHY_2M -> "2M"
        PHY_CODED -> "LE Coded"
    }

    companion object {
        fun fromAndroid(phy: Int): PHY = when (phy) {
            android.bluetooth.BluetoothDevice.PHY_LE_1M -> PHY_1M
            android.bluetooth.BluetoothDevice.PHY_LE_2M -> PHY_2M
            android.bluetooth.BluetoothDevice.PHY_LE_CODED -> PHY_CODED
            else -> UNKNOWN
        }
    }
}

enum class PHYMode(val value: String) {
    CODED_ONLY("coded-only"),
    CODED_PREFERRED("coded-preferred"),
    ONE_M("1m");

    companion object {
        fun fromString(s: String): PHYMode = when (s) {
            "1m-debug" -> ONE_M
            "1m" -> ONE_M
            else -> entries.firstOrNull { it.value == s } ?: ONE_M
        }
    }
}

/**
 * NODE_INFO (§4.2) is bootstrap-only and unsigned. Binary layout:
 *
 *   version(1) | public_key(32) | provisional_caps(2 LE)
 */
object NodeInfo {
    data class Decoded(val publicKey: ByteArray, val provisionalCaps: Capabilities) {
        val nodeId: NodeId get() = NodeId.fromPublicKey(publicKey)
    }

    fun encode(publicKey: ByteArray, caps: Capabilities): ByteArray {
        require(publicKey.size == Sidepath.PUBLIC_KEY_BYTES) { "public key must be 32 bytes" }
        val buf = ByteBuffer.allocate(1 + 32 + 2).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(Sidepath.NODE_INFO_VERSION.toByte())
        buf.put(publicKey)
        buf.putShort((caps.value and 0xFFFF).toShort())
        return buf.array()
    }

    /** Decodes NODE_INFO, or null if malformed / wrong version. */
    fun decode(data: ByteArray): Decoded? {
        if (data.size < 1 + 32 + 2) return null
        if ((data[0].toInt() and 0xFF) != Sidepath.NODE_INFO_VERSION) return null
        val pub = data.copyOfRange(1, 33)
        val caps = ((data[33].toInt() and 0xFF) or ((data[34].toInt() and 0xFF) shl 8))
        return Decoded(pub, Capabilities(caps))
    }
}
