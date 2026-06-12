package cz.arnal.bleedge.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import cz.arnal.bleedge.protocol.Capabilities
import cz.arnal.bleedge.protocol.NodeId
import cz.arnal.bleedge.transport.PHYMode

private const val TAG = "BLEManager"

/**
 * Central BLE coordinator. Checks adapter capabilities and owns Advertiser + Scanner + GattServer.
 */
class BLEManager(
    private val context: Context,
    val phyMode: PHYMode,
) {
    val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val adapter: BluetoothAdapter = bluetoothManager.adapter

    // Capability flags
    val isLeCodedPhySupported: Boolean = adapter.isLeCodedPhySupported
    val isLeExtendedAdvertisingSupported: Boolean = adapter.isLeExtendedAdvertisingSupported
    val isMultipleAdvertisementSupported: Boolean = adapter.isMultipleAdvertisementSupported

    private var advertiser: BLEEdgeAdvertiser? = null
    private var scanner: BLEEdgeScanner? = null
    private var gattServer: BLEEdgeGattServer? = null

    fun logCapabilities() {
        Log.i(TAG, "BLE capabilities:")
        Log.i(TAG, "  isLeCodedPhySupported=$isLeCodedPhySupported")
        Log.i(TAG, "  isLeExtendedAdvertisingSupported=$isLeExtendedAdvertisingSupported")
        Log.i(TAG, "  isMultipleAdvertisementSupported=$isMultipleAdvertisementSupported")
        Log.i(TAG, "  phyMode=$phyMode")
    }

    fun createAdvertiser(@Suppress("UNUSED_PARAMETER") nodeId: NodeId): BLEEdgeAdvertiser {
        return BLEEdgeAdvertiser(context, adapter).also { advertiser = it }
    }

    fun createScanner(): BLEEdgeScanner {
        return BLEEdgeScanner(context, adapter).also { scanner = it }
    }

    fun createGattServer(
        pubKey: ByteArray,
        caps: Capabilities,
        onFrameReceived: (ByteArray, android.bluetooth.BluetoothDevice) -> Unit,
        onDeviceConnected: ((android.bluetooth.BluetoothDevice) -> Unit)? = null,
        onDeviceDisconnected: ((android.bluetooth.BluetoothDevice) -> Unit)? = null,
        onLog: ((String) -> Unit)? = null,
    ): BLEEdgeGattServer {
        return BLEEdgeGattServer(context, pubKey, caps, onFrameReceived,
            onDeviceConnected, onDeviceDisconnected, onLog).also { gattServer = it }
    }

    fun stopAll() {
        advertiser?.stopAdvertising()
        scanner?.stopScan()
        gattServer?.close()
    }
}
