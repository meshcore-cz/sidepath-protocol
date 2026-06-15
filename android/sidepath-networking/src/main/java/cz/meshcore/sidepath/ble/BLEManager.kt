package cz.meshcore.sidepath.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import cz.meshcore.sidepath.protocol.Capabilities
import cz.meshcore.sidepath.protocol.NodeId
import cz.meshcore.sidepath.transport.PHYMode

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

    private var advertiser: SidepathAdvertiser? = null
    private var scanner: SidepathScanner? = null
    private var gattServer: SidepathGattServer? = null

    fun logCapabilities() {
        Log.i(TAG, "BLE capabilities:")
        Log.i(TAG, "  isLeCodedPhySupported=$isLeCodedPhySupported")
        Log.i(TAG, "  isLeExtendedAdvertisingSupported=$isLeExtendedAdvertisingSupported")
        Log.i(TAG, "  isMultipleAdvertisementSupported=$isMultipleAdvertisementSupported")
        Log.i(TAG, "  phyMode=$phyMode")
    }

    fun createAdvertiser(@Suppress("UNUSED_PARAMETER") nodeId: NodeId): SidepathAdvertiser {
        return SidepathAdvertiser(context, adapter).also { advertiser = it }
    }

    fun createScanner(): SidepathScanner {
        return SidepathScanner(context, adapter).also { scanner = it }
    }

    fun createGattServer(
        pubKey: ByteArray,
        caps: Capabilities,
        onFrameReceived: (ByteArray, android.bluetooth.BluetoothDevice) -> Unit,
        onDeviceConnected: ((android.bluetooth.BluetoothDevice) -> Unit)? = null,
        onDeviceDisconnected: ((android.bluetooth.BluetoothDevice) -> Unit)? = null,
        onDeviceUnreachable: ((android.bluetooth.BluetoothDevice, String) -> Unit)? = null,
        onLinkSample: ((android.bluetooth.BluetoothDevice, latencyMs: Int, ok: Boolean) -> Unit)? = null,
        onLog: ((String) -> Unit)? = null,
    ): SidepathGattServer {
        return SidepathGattServer(context, pubKey, caps, onFrameReceived,
            onDeviceConnected, onDeviceDisconnected, onDeviceUnreachable, onLinkSample, onLog).also { gattServer = it }
    }

    fun stopAll() {
        advertiser?.stopAdvertising()
        scanner?.stopScan()
        gattServer?.close()
    }
}
