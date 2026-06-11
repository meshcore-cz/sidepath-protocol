package cz.arnal.bleedge.ble

import cz.arnal.bleedge.core.Capabilities
import cz.arnal.bleedge.core.NodeID
import cz.arnal.bleedge.core.PHY
import cz.arnal.bleedge.core.MAX_FRAME_SIZE
import cz.arnal.bleedge.core.fragmentPacket
import cz.arnal.bleedge.core.newPacketID

/** Abstraction over a connected peer, matching the Go core.PeerLink interface. */
interface PeerLinkInterface {
    val peerId: NodeID
    val rssi: Int
    val txPhy: PHY
    val rxPhy: PHY
    fun sendFrame(frame: ByteArray)
}

/**
 * BLEPeerLink wraps a GattClient connection and handles GATT fragmentation.
 */
class BLEPeerLink(
    private val gattClient: BLEEdgeGattClient,
) : PeerLinkInterface {

    override val peerId: NodeID
        get() = gattClient.peerNodeId ?: NodeID(ByteArray(8))

    override val rssi: Int
        get() = gattClient.rssi

    override val txPhy: PHY
        get() = gattClient.txPhy

    override val rxPhy: PHY
        get() = gattClient.rxPhy

    val caps: Capabilities
        get() = gattClient.peerCaps

    val description: String
        get() = gattClient.peerDescription

    /** Send a complete packet by fragmenting it into MAX_FRAME_SIZE frames. */
    fun sendPacketData(data: ByteArray) {
        val pid = newPacketID()
        // Cap at MAX_FRAME_SIZE so broadcast/relay frames fit the smallest peer
        // (e.g. the ESP32 at ATT MTU 247) in a single write. See PROTOCOL.md.
        val frames = fragmentPacket(data, MAX_FRAME_SIZE, pid)
        for (frame in frames) {
            gattClient.sendFrame(frame.encode())
        }
    }

    override fun sendFrame(frame: ByteArray) {
        gattClient.sendFrame(frame)
    }

    fun updatePhy(txPhy: Int, rxPhy: Int) {
        // PHY is tracked inside gattClient via onPhyUpdate callback
    }

    fun disconnect() {
        gattClient.disconnect()
    }
}
