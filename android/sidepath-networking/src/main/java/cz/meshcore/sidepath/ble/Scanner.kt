package cz.meshcore.sidepath.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import cz.meshcore.sidepath.protocol.Sidepath
import cz.meshcore.sidepath.protocol.NodeId
import cz.meshcore.sidepath.transport.SIDEPATH_MANUFACTURER_ID
import cz.meshcore.sidepath.transport.SidepathUUIDs
import cz.meshcore.sidepath.transport.PHYMode

private const val TAG = "SidepathScanner"

// Manufacturer company ID used to tag Sidepath advertisements; payload is the 10-byte NodeID.
private const val MANUFACTURER_ID = SIDEPATH_MANUFACTURER_ID

/**
 * BLE scanner that discovers Sidepath peers using LE Coded PHY extended scanning,
 * or legacy 1M scanning in debug mode.
 *
 * Hardware-level ScanFilter by service UUID is intentionally not used: some devices
 * (notably certain Samsung tablets) silently drop Coded-PHY extended advertising PDUs
 * when a ScanFilter is applied.  Instead we filter at the app level, accepting a device
 * if EITHER:
 *   - it carries the Sidepath manufacturer data (company id 0xBEED) — which also holds the
 *     peer's 8-byte NodeID, letting us identify it before connecting; OR
 *   - it advertises the Sidepath service UUID — needed for peers that cannot broadcast
 *     manufacturer data (e.g. macOS/CoreBluetooth peripherals strip it); the NodeID is
 *     then read from NODE_INFO after connecting.
 */
class SidepathScanner(
    private val context: Context,
    private val adapter: BluetoothAdapter,
) {
    private var scanCallback: ScanCallback? = null

    /**
     * @param onFound   called for each Sidepath device found; the [NodeID] is the
     *                  peer's advertised id (null only if the manufacturer payload
     *                  was malformed but the device is otherwise a candidate)
     * @param onFailed  called if [ScanCallback.onScanFailed] fires (passes the errorCode)
     */
    fun startScan(
        phyMode: PHYMode,
        onFound: (BluetoothDevice, Int, NodeId?) -> Unit,
        onFailed: ((Int) -> Unit)? = null,
    ) {
        val leScanner = adapter.bluetoothLeScanner
            ?: run { Log.e(TAG, "BLE scanner not available"); return }

        val settings = if (phyMode == PHYMode.ONE_M) {
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
        } else {
            ScanSettings.Builder()
                .setLegacy(false)
                .setPhy(
                    when (phyMode) {
                        PHYMode.CODED_ONLY      -> BluetoothDevice.PHY_LE_CODED
                        PHYMode.CODED_PREFERRED -> ScanSettings.PHY_LE_ALL_SUPPORTED
                        PHYMode.ONE_M           -> ScanSettings.PHY_LE_ALL_SUPPORTED
                    }
                )
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
        }

        val bleEdgeUuid = ParcelUuid(SidepathUUIDs.SERVICE)

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
            override fun onBatchScanResults(results: List<ScanResult>) = results.forEach { handle(it) }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed: errorCode=$errorCode")
                onFailed?.invoke(errorCode)
            }

            private fun handle(result: ScanResult) {
                val rec = result.scanRecord
                // Fast path: Sidepath manufacturer data carries the peer's NodeID directly.
                val mfg = rec?.getManufacturerSpecificData(MANUFACTURER_ID)
                // Fallback: a peer that can't broadcast manufacturer data (macOS) still
                // advertises the Sidepath service UUID.
                val hasService = rec?.serviceUuids?.any { it == bleEdgeUuid } == true
                if (mfg == null && !hasService) return // not a Sidepath device

                val nodeId = if (mfg != null && mfg.size >= Sidepath.NODE_ID_BYTES)
                    NodeId(mfg.copyOfRange(0, Sidepath.NODE_ID_BYTES)) else null
                Log.d(TAG, "Sidepath result: ${result.device.address} rssi=${result.rssi} phy=${result.primaryPhy} node=${nodeId?.toHex()}")
                onFound(result.device, result.rssi, nodeId)
            }
        }

        scanCallback = callback
        leScanner.startScan(emptyList(), settings, callback)
        Log.i(TAG, "Scan started phyMode=$phyMode")
    }

    fun stopScan() {
        val leScanner = adapter.bluetoothLeScanner ?: return
        scanCallback?.let {
            leScanner.stopScan(it)
            Log.i(TAG, "Scan stopped")
        }
        scanCallback = null
    }
}
