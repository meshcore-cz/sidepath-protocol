package cz.arnal.bleedge.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import cz.arnal.bleedge.core.BLEEdgeUUIDs
import cz.arnal.bleedge.core.Capabilities
import cz.arnal.bleedge.core.NodeID
import cz.arnal.bleedge.core.PROTOCOL_VERSION
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG = "BLEEdgeGattServer"

/**
 * GATT server exposing BLEEdge service with three characteristics:
 * - NODE_INFO (read): version + nodeId + caps
 * - PACKET_IN (write / write-no-response): receive frames from peers
 * - PACKET_OUT (notify): push frames to connected peers
 */
class BLEEdgeGattServer(
    private val context: Context,
    private val pubKey: ByteArray, // 32-byte Ed25519 public key (NodeID = pubKey[:8])
    private val caps: Capabilities,
    private val description: String,
    private val onFrameReceived: (ByteArray, BluetoothDevice) -> Unit,
    private val onDeviceConnected: ((BluetoothDevice) -> Unit)? = null,
    private val onDeviceDisconnected: ((BluetoothDevice) -> Unit)? = null,
    private val onLog: ((String) -> Unit)? = null,
) {
    private var gattServer: BluetoothGattServer? = null
    private val notifyDevices = CopyOnWriteArrayList<BluetoothDevice>()
    private lateinit var packetOutChar: BluetoothGattCharacteristic

    fun start() {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

        val service = BluetoothGattService(BLEEdgeUUIDs.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        // NODE_INFO — READ
        val nodeInfoChar = BluetoothGattCharacteristic(
            BLEEdgeUUIDs.NODE_INFO,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )

        // PACKET_IN — WRITE + WRITE_NO_RESPONSE
        val packetInChar = BluetoothGattCharacteristic(
            BLEEdgeUUIDs.PACKET_IN,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )

        // PACKET_OUT — NOTIFY
        val packetOutC = BluetoothGattCharacteristic(
            BLEEdgeUUIDs.PACKET_OUT,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        // Add CCCD descriptor required for notifications
        val cccd = BluetoothGattDescriptor(
            BLEEdgeUUIDs.CCCD,
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
        Log.i(TAG, "GATT server started node=${NodeID.fromPubKey(pubKey).toHexString()} addServiceQueued=$added")
        onLog?.invoke("GATT server open, addService queued=$added")
    }

    /** Send a frame to all subscribed PACKET_OUT clients. */
    fun notifyFrame(frame: ByteArray) {
        val server = gattServer ?: return
        for (device in notifyDevices) {
            sendNotify(server, device, frame)
        }
    }

    fun notifyFrameTo(frame: ByteArray, device: BluetoothDevice) {
        val server = gattServer ?: return
        if (!notifyDevices.contains(device)) return
        sendNotify(server, device, frame)
    }

    @Suppress("DEPRECATION")
    private fun sendNotify(server: BluetoothGattServer, device: BluetoothDevice, frame: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            server.notifyCharacteristicChanged(device, packetOutChar, false, frame)
        } else {
            packetOutChar.value = frame
            server.notifyCharacteristicChanged(device, packetOutChar, false)
        }
    }

    fun close() {
        gattServer?.close()
        gattServer = null
        notifyDevices.clear()
        Log.i(TAG, "GATT server closed")
    }

    private fun nodeInfoValue(): ByteArray {
        // version(1) + pubkey(32) + caps(1) + descLen(1) + desc(descLen)
        val desc = description.toByteArray(Charsets.UTF_8).let { if (it.size > 255) it.copyOf(255) else it }
        val b = ByteArray(34 + 1 + desc.size)
        b[0] = PROTOCOL_VERSION
        pubKey.copyInto(b, 1)
        b[33] = caps.value.toByte()
        b[34] = desc.size.toByte()
        desc.copyInto(b, 35)
        return b
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            val ok = status == BluetoothGatt.GATT_SUCCESS
            Log.i(TAG, "onServiceAdded status=$status uuid=${service.uuid}")
            onLog?.invoke(
                if (ok) "BLEEdge service registered OK (${service.characteristics.size} chars)"
                else "BLEEdge service registration FAILED status=$status"
            )
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Device connected: ${device.address}")
                onDeviceConnected?.invoke(device)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Device disconnected: ${device.address}")
                notifyDevices.remove(device)
                onDeviceDisconnected?.invoke(device)
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid == BLEEdgeUUIDs.NODE_INFO) {
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
            if (characteristic.uuid == BLEEdgeUUIDs.PACKET_IN) {
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
            if (descriptor.uuid == BLEEdgeUUIDs.CCCD) {
                if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    Log.i(TAG, "PACKET_OUT notifications enabled by ${device.address}")
                    notifyDevices.add(device)
                } else {
                    notifyDevices.remove(device)
                }
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        }
    }
}
