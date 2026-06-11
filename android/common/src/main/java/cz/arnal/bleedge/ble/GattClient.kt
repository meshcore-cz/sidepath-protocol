package cz.arnal.bleedge.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import cz.arnal.bleedge.core.BLEEdgeUUIDs
import cz.arnal.bleedge.core.Capabilities
import cz.arnal.bleedge.core.NodeID
import cz.arnal.bleedge.core.PHY
import cz.arnal.bleedge.core.PHYMode

private const val TAG = "BLEEdgeGattClient"
private const val KEEPALIVE_MS = 10_000L
// Safety release for the write queue in case onCharacteristicWrite doesn't fire.
private const val WRITE_TIMEOUT_MS = 200L

/**
 * GATT client: connects to a remote BLEEdge peer and manages the full characteristic lifecycle.
 *
 * Threading model:
 *  - All GATT operations are serialised through [mainHandler] (main-thread looper).
 *  - [connect] must be called on the main thread.
 *  - All public methods are safe to call from any thread.
 */
class BLEEdgeGattClient(
    private val context: Context,
    private val phyMode: PHYMode,
    private val onPhyUpdate: (txPhy: Int, rxPhy: Int) -> Unit,
    private val onFrameReceived: (ByteArray) -> Unit,
    /** Return false to abort (deterministic connection rule). */
    private val onNodeInfoRead: ((peerNodeId: NodeID, peerCaps: Capabilities) -> Boolean)? = null,
    private val onDisconnected: (() -> Unit)? = null,
    val onLog: ((addr: String, msg: String) -> Unit)? = null,
    initialRssi: Int = 0,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var gatt: BluetoothGatt? = null
    /** Set to true when we intentionally disconnect so the state-change callback skips cleanup. */
    @Volatile private var intentionalDisconnect = false

    // Write queue — only touched on mainHandler thread.
    private val writeQueue = ArrayDeque<ByteArray>()
    private var writeInProgress = false

    var peerNodeId: NodeID? = null; private set
    var peerCaps: Capabilities = Capabilities(0); private set
    var peerDescription: String = ""; private set
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
        Log.i(TAG, "Connecting to ${device.address}")
        onLog?.invoke(device.address, "connecting")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    /** Thread-safe. Queues a proper disconnect; [onDisconnected] fires when complete. */
    fun disconnect() {
        intentionalDisconnect = true
        mainHandler.post {
            stopKeepalive()
            writeQueue.clear()
            writeInProgress = false
            val g = gatt
            if (g != null) {
                g.disconnect()
                // close() is called inside onConnectionStateChange(STATE_DISCONNECTED)
            }
        }
    }

    /** Thread-safe. Enqueues a frame; frames are written one at a time using Write Request. */
    fun sendFrame(frame: ByteArray) {
        mainHandler.post {
            writeQueue.addLast(frame.copyOf())
            drainWriteQueue()
        }
    }

    // ---- write queue ---------------------------------------------------------

    private fun drainWriteQueue() {
        if (writeInProgress) return
        val frame = writeQueue.removeFirstOrNull() ?: return
        val char = packetInChar ?: run { writeQueue.clear(); return }
        val g = gatt ?: run { writeQueue.clear(); return }

        writeInProgress = true

        // Use Write Request (WRITE_TYPE_DEFAULT) for reliable serialisation via onCharacteristicWrite.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(char, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            char.value = frame
            @Suppress("DEPRECATION")
            g.writeCharacteristic(char)
        }

        // Safety: release the queue lock if the callback never fires.
        mainHandler.postDelayed({
            if (writeInProgress) {
                writeInProgress = false
                drainWriteQueue()
            }
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
        gatt?.readRemoteRssi()
        mainHandler.postDelayed(::keepaliveTick, KEEPALIVE_MS)
    }

    // ---- GATT callbacks ------------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val addr = gatt.device.address
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected to $addr status=$status")
                    onLog?.invoke(addr, "connected status=$status, requesting MTU=512")
                    if (intentionalDisconnect) { gatt.disconnect(); return }
                    gatt.requestMtu(512)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected from $addr status=$status")
                    onLog?.invoke(addr, "disconnected status=$status")
                    mainHandler.post {
                        stopKeepalive()
                        writeQueue.clear()
                        writeInProgress = false
                        gatt.close()
                        this@BLEEdgeGattClient.gatt = null
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
            val service = gatt.getService(BLEEdgeUUIDs.SERVICE) ?: run {
                onLog?.invoke(addr, "BLEEdge service NOT FOUND — not a BLEEdge peer")
                gatt.disconnect()
                return
            }
            nodeInfoChar = service.getCharacteristic(BLEEdgeUUIDs.NODE_INFO)
            packetInChar = service.getCharacteristic(BLEEdgeUUIDs.PACKET_IN)
            packetOutChar = service.getCharacteristic(BLEEdgeUUIDs.PACKET_OUT)
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
            if (status != BluetoothGatt.GATT_SUCCESS) return
            handleNodeInfo(gatt, characteristic.value ?: return)
        }

        // API 33+
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            handleNodeInfo(gatt, value)
        }

        private fun handleNodeInfo(gatt: BluetoothGatt, data: ByteArray) {
            if (data.size < 34) return
            if (intentionalDisconnect) { gatt.disconnect(); return }
            // version(1) + pubkey(32) + caps(1) + descLen(1) + desc(descLen); NodeID = pubkey[:8]
            val nodeId = NodeID.fromPubKey(data.copyOfRange(1, 33))
            val caps = Capabilities(data[33].toInt() and 0xFF)
            val description = if (data.size >= 35) {
                val descLen = data[34].toInt() and 0xFF
                if (35 + descLen <= data.size) String(data, 35, descLen, Charsets.UTF_8) else ""
            } else ""
            peerDescription = description
            peerNodeId = nodeId
            peerCaps = caps
            Log.i(TAG, "NODE_INFO peer=${nodeId.toHexString()} caps=$caps")
            onLog?.invoke(gatt.device.address, "peer=${nodeId.toHexString()} caps=$caps")
            if (onNodeInfoRead != null && !onNodeInfoRead.invoke(nodeId, caps)) {
                Log.i(TAG, "Aborting connection to ${nodeId.toHexString()} per deterministic rule")
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
            if (characteristic.uuid != BLEEdgeUUIDs.PACKET_OUT) return
            onFrameReceived((characteristic.value ?: return).copyOf())
        }

        // API 33+
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid != BLEEdgeUUIDs.PACKET_OUT) return
            onFrameReceived(value.copyOf())
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Write failed status=$status on ${gatt.device.address}")
                onLog?.invoke(gatt.device.address, "write failed status=$status")
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
            if (descriptor.uuid != BLEEdgeUUIDs.CCCD) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onLog?.invoke(addr, "CCCD write FAILED status=$status")
                gatt.disconnect()
                return
            }
            Log.i(TAG, "Subscribed to PACKET_OUT on $addr")
            onLog?.invoke(addr, "subscribed to PACKET_OUT, requesting PHY")
            startKeepalive()
            val phyMask = when (phyMode) {
                PHYMode.DEBUG_1M        -> BluetoothDevice.PHY_LE_1M_MASK
                PHYMode.CODED_ONLY,
                PHYMode.CODED_PREFERRED -> BluetoothDevice.PHY_LE_CODED_MASK
            }
            gatt.setPreferredPhy(phyMask, phyMask, BluetoothDevice.PHY_OPTION_S8)
        }

        override fun onPhyUpdate(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            this@BLEEdgeGattClient.txPhy = PHY.fromAndroid(txPhy)
            this@BLEEdgeGattClient.rxPhy = PHY.fromAndroid(rxPhy)
            Log.i(TAG, "PHY tx=${PHY.fromAndroid(txPhy)} rx=${PHY.fromAndroid(rxPhy)} on ${gatt.device.address}")
            onPhyUpdate(txPhy, rxPhy)
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssiValue: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) rssi = rssiValue
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
        val descriptor = char.getDescriptor(BLEEdgeUUIDs.CCCD) ?: run {
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

private fun NodeID.toHexString() = bytes.joinToString("") { "%02x".format(it) }
