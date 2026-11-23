package com.trae.ridreceiver

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat

/**
 * 扫描 BLE 广播，提取 ASTM Remote ID 的 Service Data（UUID 0xFFFA）。
 */
class BleScanner(
    private val context: Context,
    private val onDecoded: (mac: String, rssi: Int, decoded: RidDecoder.Decoded) -> Unit
) {

    companion object {
        val REMOTE_ID_UUID: ParcelUuid =
            ParcelUuid.fromString("0000FFFA-0000-1000-8000-00805F9B34FB")
    }

    private val adapter: BluetoothAdapter? by lazy {
        try {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        } catch (_: Exception) {
            null
        }
    }

    private var scanning = false

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handle(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { handle(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
        }

        private fun handle(result: ScanResult) {
            val record = result.scanRecord ?: return
            val sd = record.getServiceData(REMOTE_ID_UUID)
            val decoded = if (sd != null) {
                RidDecoder.decodeBleServiceData(sd)
            } else {
                record.bytes?.let { RidDecoder.decodeBleAdvertising(it) }
            } ?: return

            val address = result.device?.address ?: "ble"
            onDecoded(address, result.rssi, decoded)
        }
    }

    @SuppressLint("MissingPermission")
    fun start(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val granted = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return "缺少蓝牙扫描权限"
        } else {
            if (ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) return "缺少定位权限（Android 11 及以下蓝牙扫描需要）"
        }

        val a = adapter ?: return "本机蓝牙不可用"
        val enabled = try {
            a.isEnabled
        } catch (_: SecurityException) {
            return "缺少蓝牙权限"
        }
        if (!enabled) return "请先打开蓝牙"

        val scanner = a.bluetoothLeScanner ?: return "蓝牙扫描器不可用"
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setReportDelay(0)
            .setLegacy(false)
            .setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
            .build()

        return try {
            // 不加硬件过滤：部分机型不会把 Service Data 计入 serviceUuid 过滤
            scanner.startScan(null, settings, callback)
            scanning = true
            null
        } catch (e: SecurityException) {
            "蓝牙扫描权限被拒绝"
        } catch (e: Exception) {
            "蓝牙扫描启动失败：${e.message}"
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!scanning) return
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (_: Exception) {
        }
    }
}
