package cz.arnal.bleedge.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import cz.arnal.bleedge.protocol.NodeId
import cz.arnal.bleedge.transport.BLEEDGE_MANUFACTURER_ID
import cz.arnal.bleedge.transport.BLEEdgeUUIDs
import cz.arnal.bleedge.transport.PHYMode

private const val TAG = "BLEEdgeAdvertiser"

/**
 * Manages BLE advertising using extended advertising for LE Coded PHY, or
 * legacy advertising for debug 1M mode.
 */
class BLEEdgeAdvertiser(
    private val context: Context,
    private val adapter: BluetoothAdapter,
) {
    private var advertisingSet: AdvertisingSet? = null
    private var activeCallback: AdvertisingSetCallback? = null

    fun startAdvertising(nodeId: NodeId, phyMode: PHYMode, onLog: ((String) -> Unit)? = null) {
        val leAdvertiser = adapter.bluetoothLeAdvertiser
            ?: run {
                Log.e(TAG, "LE advertiser not available")
                onLog?.invoke("LE advertiser NOT available — advertising cannot start")
                return
            }

        if (phyMode == PHYMode.ONE_M) {
            startLegacyAdvertising(leAdvertiser, nodeId, onLog)
        } else {
            startExtendedAdvertising(leAdvertiser, nodeId, onLog)
        }
    }

    private fun startExtendedAdvertising(
        leAdvertiser: android.bluetooth.le.BluetoothLeAdvertiser,
        nodeId: NodeId,
        onLog: ((String) -> Unit)? = null,
    ) {
        val params = AdvertisingSetParameters.Builder()
            .setLegacyMode(false)
            .setConnectable(true)
            .setScannable(false)
            .setPrimaryPhy(BluetoothDevice.PHY_LE_CODED)
            .setSecondaryPhy(BluetoothDevice.PHY_LE_CODED)
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(BLEEdgeUUIDs.SERVICE))
            // Manufacturer data moved to primary data since setScannable(false) means
            // scan response must be null per Android API contract.
            .addManufacturerData(BLEEDGE_MANUFACTURER_ID, nodeId.bytes)
            .setIncludeDeviceName(false)
            .build()

        val callback = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
                if (status == ADVERTISE_SUCCESS) {
                    advertisingSet = set
                    Log.i(TAG, "Extended advertising started (Coded PHY) txPower=$txPower")
                    onLog?.invoke("advertising started (Coded PHY) txPower=$txPower")
                } else {
                    Log.e(TAG, "Extended advertising FAILED: status=$status")
                    onLog?.invoke("advertising FAILED status=$status")
                }
            }
            override fun onAdvertisingSetStopped(set: AdvertisingSet?) {
                Log.i(TAG, "Extended advertising stopped")
                advertisingSet = null
            }
        }

        activeCallback = callback
        // scanResponse must be null when setScannable(false)
        leAdvertiser.startAdvertisingSet(params, data, null, null, null, callback)
    }

    private fun startLegacyAdvertising(
        leAdvertiser: android.bluetooth.le.BluetoothLeAdvertiser,
        nodeId: NodeId,
        onLog: ((String) -> Unit)? = null,
    ) {
        val params = AdvertisingSetParameters.Builder()
            .setLegacyMode(true)
            .setConnectable(true)
            .setScannable(true)
            .setPrimaryPhy(BluetoothDevice.PHY_LE_1M)
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(BLEEdgeUUIDs.SERVICE))
            .setIncludeDeviceName(false)
            .build()

        val scanResponse = AdvertiseData.Builder()
            .addManufacturerData(BLEEDGE_MANUFACTURER_ID, nodeId.bytes)
            .build()

        val callback = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
                if (status == ADVERTISE_SUCCESS) {
                    advertisingSet = set
                    Log.i(TAG, "Legacy 1M advertising started txPower=$txPower")
                    onLog?.invoke("advertising started (1M legacy) txPower=$txPower")
                } else {
                    Log.e(TAG, "Legacy advertising FAILED: status=$status")
                    onLog?.invoke("advertising FAILED status=$status")
                }
            }
        }
        activeCallback = callback
        leAdvertiser.startAdvertisingSet(params, data, scanResponse, null, null, callback)
    }

    fun stopAdvertising() {
        val leAdvertiser = adapter.bluetoothLeAdvertiser ?: return
        val set = advertisingSet
        val cb = activeCallback
        if (set != null && cb != null) {
            leAdvertiser.stopAdvertisingSet(cb)
        }
        advertisingSet = null
        activeCallback = null
        Log.i(TAG, "Advertising stopped")
    }
}
