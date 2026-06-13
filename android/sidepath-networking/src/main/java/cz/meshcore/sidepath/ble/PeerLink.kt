package cz.meshcore.sidepath.ble

import cz.meshcore.sidepath.protocol.Capabilities
import cz.meshcore.sidepath.protocol.NodeId
import cz.meshcore.sidepath.transport.PHY

/** Abstraction over a connected outgoing peer (we are the GATT client). */
interface PeerLinkInterface {
    val peerId: NodeId?
    val isUsable: Boolean
    val rssi: Int
    val txPhy: PHY
    val rxPhy: PHY
    fun sendFrame(frame: ByteArray): Boolean
}

/** Wraps a [SidepathGattClient] connection. Fragmentation is done by the service. */
class BLEPeerLink(private val gattClient: SidepathGattClient) : PeerLinkInterface {
    override val peerId: NodeId? get() = gattClient.peerNodeId
    override val isUsable: Boolean get() = gattClient.isUsable
    override val rssi: Int get() = gattClient.rssi
    override val txPhy: PHY get() = gattClient.txPhy
    override val rxPhy: PHY get() = gattClient.rxPhy

    val publicKey: ByteArray get() = gattClient.peerPublicKey
    val caps: Capabilities get() = gattClient.peerCaps

    override fun sendFrame(frame: ByteArray): Boolean = gattClient.sendFrame(frame)

    fun disconnect() = gattClient.disconnect()
}
