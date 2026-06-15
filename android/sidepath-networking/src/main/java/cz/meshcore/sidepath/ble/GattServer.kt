package cz.meshcore.sidepath.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import cz.meshcore.sidepath.protocol.Capabilities
import cz.meshcore.sidepath.protocol.NodeId
import cz.meshcore.sidepath.transport.SidepathUUIDs
import cz.meshcore.sidepath.transport.NodeInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG = "SidepathGattServer"
private const val MAX_NOTIFY_QUEUE = 128
private const val MAX_NOTIFY_FAILURES = 3

/**
 * GATT server exposing Sidepath service with three characteristics:
 * - NODE_INFO (read): version + nodeId + caps
 * - PACKET_IN (write / write-no-response): receive frames from peers
 * - PACKET_OUT (notify): push frames to connected peers
 */
class SidepathGattServer(
    private val context: Context,
    private val pubKey: ByteArray, // 32-byte Ed25519 public key (NodeID = pubKey[:10])
    private val caps: Capabilities,
    private val onFrameReceived: (ByteArray, BluetoothDevice) -> Unit,
    private val onDeviceConnected: ((BluetoothDevice) -> Unit)? = null,
    private val onDeviceDisconnected: ((BluetoothDevice) -> Unit)? = null,
    private val onDeviceUnreachable: ((BluetoothDevice, String) -> Unit)? = null,
    private val onLinkSample: ((BluetoothDevice, latencyMs: Int, ok: Boolean) -> Unit)? = null,
    private val onLog: ((String) -> Unit)? = null,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var gattServer: BluetoothGattServer? = null
    private val notifyDevices = CopyOnWriteArrayList<BluetoothDevice>()
    // Every central currently connected to our server (subscribed or not), so we can force-disconnect
    // them when the mesh stops — otherwise an idle GATT link survives and the peer never learns we left.
    private val connectedDevices = CopyOnWriteArrayList<BluetoothDevice>()
    private val notifyLock = Any()
    private val notifyQueues = ConcurrentHashMap<String, ArrayDeque<ByteArray>>()
    private val notifyInFlight = ConcurrentHashMap<String, Boolean>()
    private val notifyStartedAtMs = ConcurrentHashMap<String, Long>()
    private val notifyFailures = ConcurrentHashMap<String, Int>()
    private lateinit var packetOutChar: BluetoothGattCharacteristic

    fun start() {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

        val service = BluetoothGattService(SidepathUUIDs.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        // NODE_INFO — READ
        val nodeInfoChar = BluetoothGattCharacteristic(
            SidepathUUIDs.NODE_INFO,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )

        // PACKET_IN — WRITE + WRITE_NO_RESPONSE
        val packetInChar = BluetoothGattCharacteristic(
            SidepathUUIDs.PACKET_IN,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )

        // PACKET_OUT — INDICATE (ATT-acknowledged push, so peer→us delivery is reliable like our
        // Write Requests in the other direction).
        val packetOutC = BluetoothGattCharacteristic(
            SidepathUUIDs.PACKET_OUT,
            BluetoothGattCharacteristic.PROPERTY_INDICATE,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        // Add CCCD descriptor required for notifications
        val cccd = BluetoothGattDescriptor(
            SidepathUUIDs.CCCD,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
        )
        packetOutC.addDescriptor(cccd)
        packetOutChar = packetOutC

        service.addCharacteristic(nodeInfoChar)
        service.addCharacteristic(packetInChar)
        service.addCharacteristic(packetOutC)

        val server = bm.openGattServer(context, serverCallback)
        gattServer = server
        val added = server.addService(service)
        Log.i(TAG, "GATT server started node=${NodeId.fromPublicKey(pubKey).toHex()} addServiceQueued=$added")
        onLog?.invoke("GATT server open, addService queued=$added")
    }

    /** Send a frame to all subscribed PACKET_OUT clients. */
    fun notifyFrame(frame: ByteArray) {
        for (device in notifyDevices) {
            enqueueNotify(device, frame)
        }
    }

    fun notifyFrameTo(frame: ByteArray, device: BluetoothDevice): Boolean {
        if (!notifyDevices.contains(device)) return false
        return enqueueNotify(device, frame)
    }

    /** True while [device] is subscribed to PACKET_OUT, i.e. this inbound link can carry frames. */
    fun isSubscribed(device: BluetoothDevice): Boolean = notifyDevices.contains(device)

    private fun enqueueNotify(device: BluetoothDevice, frame: ByteArray): Boolean {
        if (gattServer == null || !notifyDevices.contains(device)) return false
        synchronized(notifyLock) {
            val q = notifyQueues.getOrPut(deviceKey(device)) { ArrayDeque() }
            if (q.size >= MAX_NOTIFY_QUEUE) q.removeFirst()
            q.addLast(frame.copyOf())
        }
        mainHandler.post { drainNotifyQueue(device) }
        return true
    }

    private fun drainNotifyQueue(device: BluetoothDevice) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { drainNotifyQueue(device) }
            return
        }
        val server = gattServer ?: return
        if (!notifyDevices.contains(device)) return
        val key = deviceKey(device)
        val frame = synchronized(notifyLock) {
            if (notifyInFlight[key] == true) return
            val q = notifyQueues[key] ?: return
            val next = q.removeFirstOrNull() ?: return
            notifyInFlight[key] = true
            next
        }
        if (!sendNotify(server, device, frame)) {
            synchronized(notifyLock) {
                notifyInFlight[key] = false
                notifyStartedAtMs.remove(key)
                notifyQueues.getOrPut(key) { ArrayDeque() }.addFirst(frame)
            }
            scheduleNotifyRetryOrFail(device, "notify busy")
        } else {
            notifyStartedAtMs[key] = SystemClock.elapsedRealtime()
        }
    }

    // confirm=true → send an ATT indication; onNotificationSent then fires when the central confirms
    // receipt (not merely when the buffer drains), which is what makes the push reliable.
    @Suppress("DEPRECATION")
    private fun sendNotify(server: BluetoothGattServer, device: BluetoothDevice, frame: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = server.notifyCharacteristicChanged(device, packetOutChar, true, frame)
            status == BluetoothStatusCodes.SUCCESS
        } else {
            packetOutChar.value = frame
            server.notifyCharacteristicChanged(device, packetOutChar, true)
        }

    private fun markDeviceUnreachable(device: BluetoothDevice, reason: String) {
        if (!notifyDevices.remove(device)) return
        clearNotifyState(device)
        Log.w(TAG, "Device unreachable: ${device.address} reason=$reason")
        onLog?.invoke("device unreachable addr=${device.address} reason=$reason")
        onDeviceUnreachable?.invoke(device, reason)
    }

    private fun scheduleNotifyRetryOrFail(device: BluetoothDevice, reason: String) {
        val key = deviceKey(device)
        val failures = notifyFailures.merge(key, 1, Int::plus) ?: 1
        if (failures >= MAX_NOTIFY_FAILURES) {
            markDeviceUnreachable(device, "$reason after $failures attempts")
            return
        }
        val delayMs = 100L * failures
        onLog?.invoke("notify retry addr=${device.address} reason=$reason attempt=$failures")
        mainHandler.postDelayed({ drainNotifyQueue(device) }, delayMs)
    }

    private fun clearNotifyState(device: BluetoothDevice) {
        val key = deviceKey(device)
        synchronized(notifyLock) {
            notifyQueues.remove(key)
            notifyInFlight.remove(key)
            notifyStartedAtMs.remove(key)
            notifyFailures.remove(key)
        }
    }

    private fun deviceKey(device: BluetoothDevice): String = device.address

    /**
     * Force-disconnects every central connected to our server. Called when the mesh stops so peers
     * promptly see us go away instead of holding a stale, idle GATT link. The server object stays
     * usable (re-advertising re-accepts connections).
     */
    fun disconnectAll() {
        val server = gattServer ?: return
        for (device in connectedDevices) {
            server.cancelConnection(device)
        }
        connectedDevices.clear()
        notifyDevices.clear()
        synchronized(notifyLock) {
            notifyQueues.clear()
            notifyInFlight.clear()
            notifyStartedAtMs.clear()
            notifyFailures.clear()
        }
    }

    fun close() {
        gattServer?.close()
        gattServer = null
        connectedDevices.clear()
        notifyDevices.clear()
        synchronized(notifyLock) {
            notifyQueues.clear()
            notifyInFlight.clear()
            notifyStartedAtMs.clear()
            notifyFailures.clear()
        }
        Log.i(TAG, "GATT server closed")
    }

    // NODE_INFO (§4.2): version(1) | public_key(32) | provisional_caps(2 LE).
    private fun nodeInfoValue(): ByteArray = NodeInfo.encode(pubKey, caps)

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            val ok = status == BluetoothGatt.GATT_SUCCESS
            Log.i(TAG, "onServiceAdded status=$status uuid=${service.uuid}")
            onLog?.invoke(
                if (ok) "Sidepath service registered OK (${service.characteristics.size} chars)"
                else "Sidepath service registration FAILED status=$status"
            )
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Device connected: ${device.address}")
                if (!connectedDevices.contains(device)) connectedDevices.add(device)
                onDeviceConnected?.invoke(device)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Device disconnected: ${device.address}")
                connectedDevices.remove(device)
                notifyDevices.remove(device)
                clearNotifyState(device)
                onDeviceDisconnected?.invoke(device)
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid == SidepathUUIDs.NODE_INFO) {
                val value = nodeInfoValue()
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                    if (offset < value.size) value.copyOfRange(offset, value.size) else ByteArray(0))
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            if (characteristic.uuid == SidepathUUIDs.PACKET_IN) {
                Log.d(TAG, "PACKET_IN write ${value.size} bytes from ${device.address}")
                onFrameReceived(value, device)
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            } else {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            if (descriptor.uuid == SidepathUUIDs.CCCD) {
                // Peers subscribe with ENABLE_INDICATION_VALUE (0x0002) — PACKET_OUT is pushed as
                // acknowledged indications (§4.4, sendNotify uses confirm=true). Accept either enable
                // value so a notification-style subscriber still registers; only a disable (0x0000)
                // unsubscribes. Checking solely for ENABLE_NOTIFICATION_VALUE rejected every real
                // subscriber, leaving notifyDevices empty so we could never push back to a central
                // that dialed us (lost ACKs, trace responses and direct replies over the inbound link).
                val enable = value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE) ||
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                if (enable) {
                    Log.i(TAG, "PACKET_OUT indications enabled by ${device.address}")
                    if (!notifyDevices.contains(device)) notifyDevices.add(device)
                    clearNotifyState(device)
                } else {
                    notifyDevices.remove(device)
                    clearNotifyState(device)
                }
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val key = deviceKey(device)
            val startedAt = synchronized(notifyLock) {
                notifyInFlight[key] = false
                notifyStartedAtMs.remove(key)
            }
            val latencyMs = startedAt
                ?.let { (SystemClock.elapsedRealtime() - it).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt() }
                ?: 1
            if (status == BluetoothGatt.GATT_SUCCESS) {
                notifyFailures.remove(key)
                onLinkSample?.invoke(device, latencyMs, true)
                drainNotifyQueue(device)
            } else {
                onLinkSample?.invoke(device, latencyMs, false)
                scheduleNotifyRetryOrFail(device, "notification failed status=$status")
            }
        }
    }
}
