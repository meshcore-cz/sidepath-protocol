package cz.arnal.bleedge.ble

import cz.arnal.bleedge.protocol.Capabilities
import cz.arnal.bleedge.protocol.NodeId
import cz.arnal.bleedge.transport.PHY

/** Abstraction over a connected outgoing peer (we are the GATT client). */
interface PeerLinkInterface {
    val peerId: NodeId?
    val rssi: Int
    val txPhy: PHY
    val rxPhy: PHY
    fun sendFrame(frame: ByteArray)
}

/** Wraps a [BLEEdgeGattClient] connection. Fragmentation is done by the service. */
class BLEPeerLink(private val gattClient: BLEEdgeGattClient) : PeerLinkInterface {
    override val peerId: NodeId? get() = gattClient.peerNodeId
    override val rssi: Int get() = gattClient.rssi
    override val txPhy: PHY get() = gattClient.txPhy
    override val rxPhy: PHY get() = gattClient.rxPhy

    val publicKey: ByteArray get() = gattClient.peerPublicKey
    val caps: Capabilities get() = gattClient.peerCaps

    override fun sendFrame(frame: ByteArray) = gattClient.sendFrame(frame)

    fun disconnect() = gattClient.disconnect()
}
