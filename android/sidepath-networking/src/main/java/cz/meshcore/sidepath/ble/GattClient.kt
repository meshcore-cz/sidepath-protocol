package cz.meshcore.sidepath.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import cz.meshcore.sidepath.protocol.Capabilities
import cz.meshcore.sidepath.protocol.NodeId
import cz.meshcore.sidepath.transport.SidepathUUIDs
import cz.meshcore.sidepath.transport.NodeInfo
import cz.meshcore.sidepath.transport.PHY
import cz.meshcore.sidepath.transport.PHYMode

private const val TAG = "SidepathGattClient"
private const val KEEPALIVE_MS = 15_000L
private const val WRITE_TIMEOUT_MS = 8_000L

enum class LinkHealth { CONNECTING, READY, DEGRADED, CLOSED }

/**
 * GATT client: connects to a remote Sidepath peer and manages the full characteristic lifecycle.
 *
 * Threading model:
 *  - All GATT operations are serialised through [mainHandler] (main-thread looper).
 *  - [connect] must be called on the main thread.
 *  - All public methods are safe to call from any thread.
 */
class SidepathGattClient(
    private val context: Context,
    private val phyMode: PHYMode,
    private val onPhyUpdate: (txPhy: Int, rxPhy: Int) -> Unit,
    private val onFrameReceived: (ByteArray) -> Unit,
    /** Return false to abort (deterministic connection rule). [peerPubKey] is the full 32-byte key. */
    private val onNodeInfoRead: ((peerNodeId: NodeId, peerPubKey: ByteArray, peerCaps: Capabilities) -> Boolean)? = null,
    private val onDisconnected: (() -> Unit)? = null,
    val onLog: ((addr: String, msg: String) -> Unit)? = null,
    initialRssi: Int = 0,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var gatt: BluetoothGatt? = null
    /** Set to true when we intentionally disconnect so the state-change callback skips cleanup. */
    @Volatile private var intentionalDisconnect = false
    @Volatile private var closing = false
    @Volatile var health: LinkHealth = LinkHealth.CONNECTING
        private set
    val isUsable: Boolean get() = health == LinkHealth.READY && gatt != null && packetInChar != null

    // Write queue — only touched on mainHandler thread.
    private val writeQueue = ArrayDeque<ByteArray>()
    private var writeInProgress = false
    private var writeGeneration = 0L

    var peerNodeId: NodeId? = null; private set
    var peerPublicKey: ByteArray = ByteArray(0); private set
    var peerCaps: Capabilities = Capabilities(0); private set
    var txPhy: PHY = PHY.UNKNOWN; private set
    var rxPhy: PHY = PHY.UNKNOWN; private set
    var rssi: Int = initialRssi; private set

    private var mtu: Int = 23
    private var nodeInfoChar: BluetoothGattCharacteristic? = null
    private var packetInChar: BluetoothGattCharacteristic? = null
    private var packetOutChar: BluetoothGattCharacteristic? = null

    // ---- public API ----------------------------------------------------------

    /** Must be called on the main thread. */
    fun connect(device: BluetoothDevice) {
        intentionalDisconnect = false
        closing = false
        health = LinkHealth.CONNECTING
        Log.i(TAG, "Connecting to ${device.address}")
        onLog?.invoke(device.address, "connecting")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    /** Thread-safe. Queues a proper disconnect; [onDisconnected] fires when complete. */
    fun disconnect() {
        intentionalDisconnect = true
        mainHandler.post {
            closeLink()
            val g = gatt
            if (g != null) {
                g.disconnect()
                // close() is called inside onConnectionStateChange(STATE_DISCONNECTED)
            }
        }
    }

    /** Thread-safe. Enqueues a frame; frames are written one at a time using Write Request. */
    fun sendFrame(frame: ByteArray): Boolean {
        if (!isUsable) return false
        mainHandler.post {
            if (!isUsable) return@post
            writeQueue.addLast(frame.copyOf())
            drainWriteQueue()
        }
        return true
    }

    // ---- write queue ---------------------------------------------------------

    private fun drainWriteQueue() {
        if (closing || !isUsable || writeInProgress) return
        val frame = writeQueue.removeFirstOrNull() ?: return
        val char = packetInChar ?: run { failLink("PACKET_IN unavailable while writing"); return }
        val g = gatt ?: run { failLink("GATT unavailable while writing"); return }

        writeInProgress = true
        val generation = ++writeGeneration

        // Use Write Request (WRITE_TYPE_DEFAULT) for reliable serialisation via onCharacteristicWrite.
        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = g.writeCharacteristic(char, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            status == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            char.value = frame
            @Suppress("DEPRECATION")
            g.writeCharacteristic(char)
        }
        if (!accepted) {
            failLink("write request rejected")
            return
        }

        // Missing write callbacks usually mean the controller has lost the peer.
        mainHandler.postDelayed({
            if (writeInProgress && writeGeneration == generation) failLink("write callback timeout")
        }, WRITE_TIMEOUT_MS)
    }

    // ---- keepalive -----------------------------------------------------------

    private fun startKeepalive() {
        mainHandler.postDelayed(::keepaliveTick, KEEPALIVE_MS)
    }

    private fun stopKeepalive() {
        mainHandler.removeCallbacks(::keepaliveTick)
    }

    private fun keepaliveTick() {
        if (closing || health == LinkHealth.CLOSED) return
        val accepted = gatt?.readRemoteRssi() == true
        if (!accepted) {
            onLog?.invoke(peerNodeId?.toHex() ?: "unknown", "RSSI keepalive request skipped")
        }
        mainHandler.postDelayed(::keepaliveTick, KEEPALIVE_MS)
    }

    private fun failLink(reason: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { failLink(reason) }
            return
        }
        val g = gatt
        val addr = g?.device?.address ?: peerNodeId?.toHex() ?: "unknown"
        Log.w(TAG, "Failing link $addr: $reason")
        onLog?.invoke(addr, "link failed: $reason")
        closeLink()
        onDisconnected?.invoke()
        g?.disconnect()
    }

    private fun closeLink() {
        closing = true
        health = LinkHealth.CLOSED
        stopKeepalive()
        writeQueue.clear()
        writeInProgress = false
        writeGeneration += 1
    }

    // ---- GATT callbacks ------------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val addr = gatt.device.address
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected to $addr status=$status")
                    onLog?.invoke(addr, "connected status=$status, requesting MTU=512")
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        failLink("connect failed status=$status")
                        return
                    }
                    if (intentionalDisconnect) { gatt.disconnect(); return }
                    gatt.requestMtu(512)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected from $addr status=$status")
                    onLog?.invoke(addr, "disconnected status=$status")
                    mainHandler.post {
                        closeLink()
                        gatt.close()
                        this@SidepathGattClient.gatt = null
                        // Fire callback regardless of whether disconnect was intentional —
                        // the service needs it to remove the peer from its maps.
                        onDisconnected?.invoke()
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, newMtu: Int, status: Int) {
            val addr = gatt.device.address
            if (status == BluetoothGatt.GATT_SUCCESS) {
                mtu = newMtu
                Log.i(TAG, "MTU=$newMtu on $addr")
                onLog?.invoke(addr, "MTU=$newMtu, discovering services")
            } else {
                onLog?.invoke(addr, "MTU request failed status=$status, using default mtu=$mtu")
            }
            if (intentionalDisconnect) { gatt.disconnect(); return }
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val addr = gatt.device.address
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onLog?.invoke(addr, "service discovery FAILED status=$status")
                Log.e(TAG, "Service discovery failed: $status on $addr")
                gatt.disconnect()
                return
            }
            if (intentionalDisconnect) { gatt.disconnect(); return }
            val service = gatt.getService(SidepathUUIDs.SERVICE) ?: run {
                onLog?.invoke(addr, "Sidepath service NOT FOUND — not a Sidepath peer")
                gatt.disconnect()
                return
            }
            nodeInfoChar = service.getCharacteristic(SidepathUUIDs.NODE_INFO)
            packetInChar = service.getCharacteristic(SidepathUUIDs.PACKET_IN)
            packetOutChar = service.getCharacteristic(SidepathUUIDs.PACKET_OUT)
            onLog?.invoke(addr, "services ok, reading NODE_INFO")
            if (nodeInfoChar == null) {
                onLog?.invoke(addr, "NODE_INFO characteristic missing")
                gatt.disconnect()
                return
            }
            gatt.readCharacteristic(nodeInfoChar!!)
        }

        // API < 33
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return // handled below
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failLink("characteristic read failed status=$status")
                return
            }
            handleNodeInfo(gatt, characteristic.value ?: return)
        }

        // API 33+
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failLink("characteristic read failed status=$status")
                return
            }
            handleNodeInfo(gatt, value)
        }

        private fun handleNodeInfo(gatt: BluetoothGatt, data: ByteArray) {
            if (intentionalDisconnect) { gatt.disconnect(); return }
            // NODE_INFO (§4.2): version(1) | public_key(32) | provisional_caps(2 LE). NodeID = pubkey[:10].
            val info = NodeInfo.decode(data) ?: run {
                onLog?.invoke(gatt.device.address, "NODE_INFO malformed (${data.size} bytes)")
                gatt.disconnect(); return
            }
            val nodeId = info.nodeId
            peerPublicKey = info.publicKey
            peerNodeId = nodeId
            peerCaps = info.provisionalCaps
            Log.i(TAG, "NODE_INFO peer=${nodeId.toHex()} caps=${info.provisionalCaps}")
            onLog?.invoke(gatt.device.address, "peer=${nodeId.toHex()} caps=${info.provisionalCaps}")
            if (onNodeInfoRead != null && !onNodeInfoRead.invoke(nodeId, info.publicKey, info.provisionalCaps)) {
                Log.i(TAG, "Aborting connection to ${nodeId.toHex()} per deterministic rule")
                gatt.disconnect()
                return
            }
            subscribeToPacketOut(gatt)
        }

        // API < 33
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return // handled below
            if (characteristic.uuid != SidepathUUIDs.PACKET_OUT) return
            onFrameReceived((characteristic.value ?: return).copyOf())
        }

        // API 33+
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid != SidepathUUIDs.PACKET_OUT) return
            onFrameReceived(value.copyOf())
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Write failed status=$status on ${gatt.device.address}")
                failLink("write failed status=$status")
                return
            }
            mainHandler.post {
                writeInProgress = false
                drainWriteQueue()
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            val addr = gatt.device.address
            if (descriptor.uuid != SidepathUUIDs.CCCD) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onLog?.invoke(addr, "CCCD write FAILED status=$status")
                gatt.disconnect()
                return
            }
            Log.i(TAG, "Subscribed to PACKET_OUT on $addr")
            onLog?.invoke(addr, "subscribed to PACKET_OUT, requesting PHY")
            health = LinkHealth.READY
            closing = false
            startKeepalive()
            val phyMask = when (phyMode) {
                PHYMode.ONE_M           -> BluetoothDevice.PHY_LE_1M_MASK
                PHYMode.CODED_ONLY,
                PHYMode.CODED_PREFERRED -> BluetoothDevice.PHY_LE_CODED_MASK
            }
            gatt.setPreferredPhy(phyMask, phyMask, BluetoothDevice.PHY_OPTION_S8)
        }

        override fun onPhyUpdate(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            this@SidepathGattClient.txPhy = PHY.fromAndroid(txPhy)
            this@SidepathGattClient.rxPhy = PHY.fromAndroid(rxPhy)
            Log.i(TAG, "PHY tx=${PHY.fromAndroid(txPhy)} rx=${PHY.fromAndroid(rxPhy)} on ${gatt.device.address}")
            onPhyUpdate(txPhy, rxPhy)
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssiValue: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                rssi = rssiValue
            } else {
                onLog?.invoke(gatt.device.address, "RSSI read failed status=$status")
            }
        }
    }

    private fun subscribeToPacketOut(gatt: BluetoothGatt) {
        val char = packetOutChar ?: run {
            onLog?.invoke(gatt.device.address, "PACKET_OUT characteristic missing")
            gatt.disconnect()
            return
        }
        if (!gatt.setCharacteristicNotification(char, true)) {
            onLog?.invoke(gatt.device.address, "setCharacteristicNotification FAILED")
            gatt.disconnect()
            return
        }
        val descriptor = char.getDescriptor(SidepathUUIDs.CCCD) ?: run {
            onLog?.invoke(gatt.device.address, "CCCD descriptor missing")
            gatt.disconnect()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }
}
