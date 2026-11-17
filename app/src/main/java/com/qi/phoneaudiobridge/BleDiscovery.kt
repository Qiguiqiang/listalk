package com.qi.phoneaudiobridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log

/**
 * BLE proximity discovery (v2): scans for the canonical AirBridge service
 * UUID (Protocol.BLE_SERVICE_UUID). Sightings are used ONLY for presence /
 * proximity (RSSI) in the nearby-device list — audio always stays on WiFi.
 *
 * The PC advertisement may carry service data under the same UUID whose
 * payload is the PC's deviceId as UTF-8 (optionally followed by a NUL and
 * free-form bytes); when present we merge the sighting onto that deviceId,
 * otherwise it is keyed by Bluetooth address as an anonymous nearby hint.
 *
 * Everything is defensive: no Bluetooth, no permission, or Bluetooth off
 * simply means "BLE unavailable" — reported via [statusText] for the UI,
 * never a crash. Scanning runs in LOW_POWER mode while the engine runs.
 */
class BleDiscovery(
    private val context: Context,
    private val onSighting: (deviceIdHint: String?, address: String, rssi: Int) -> Unit
) {
    @Volatile var statusText: String = "BLE 未启动"
        private set
    @Volatile var scanning: Boolean = false
        private set

    private var callback: ScanCallback? = null

    fun hasPermission(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= 31) {
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (scanning) return
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bm?.adapter
        if (adapter == null) { statusText = "这台手机不支持蓝牙"; return }
        if (!adapter.isEnabled) { statusText = "蓝牙未开启，附近发现仅用局域网"; return }
        if (!hasPermission()) { statusText = "需要附近设备权限才能用蓝牙发现"; return }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) { statusText = "蓝牙扫描不可用"; return }
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid.fromString(Protocol.BLE_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handle(result)
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                for (r in results) handle(r)
            }
            override fun onScanFailed(errorCode: Int) {
                statusText = "蓝牙扫描失败（$errorCode），仅用局域网发现"
                scanning = false
                Log.w("BleDiscovery", "scan failed: $errorCode")
            }
        }
        callback = cb
        try {
            scanner.startScan(listOf(filter), settings, cb)
            scanning = true
            statusText = "蓝牙发现已开启"
        } catch (e: Exception) {
            statusText = "蓝牙扫描启动失败，仅用局域网发现"
            Log.w("BleDiscovery", "startScan failed: ${e.message}")
        }
    }

    private fun handle(result: ScanResult) {
        val record = result.scanRecord ?: return
        val data = record.getServiceData(ParcelUuid.fromString(Protocol.BLE_SERVICE_UUID))
        var hint: String? = null
        if (data != null && data.isNotEmpty()) {
            val raw = String(data, Charsets.UTF_8)
            val cleaned = raw.substringBefore('\u0000').trim()
            if (cleaned.isNotEmpty() && cleaned.length <= 64) hint = cleaned
        }
        onSighting(hint, result.device?.address ?: return, result.rssi)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!scanning) return
        try {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val cb = callback
            if (bm?.adapter?.bluetoothLeScanner != null && cb != null) {
                bm.adapter.bluetoothLeScanner.stopScan(cb)
            }
        } catch (_: Exception) {}
        scanning = false
        callback = null
        statusText = "BLE 已停止"
    }
}
