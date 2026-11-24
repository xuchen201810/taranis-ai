package com.dronesignal.app.signal

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import com.dronesignal.app.model.BleDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class BleScanner(
    private val context: Context,
    private val onResult: (List<BleDevice>) -> Unit,
    private val onLog: (String) -> Unit
) {
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private var job: Job? = null
    private val devices = ConcurrentHashMap<String, BleDevice>()
    private var scanning = false

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handle(result)
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { handle(it) }
        }
        override fun onScanFailed(errorCode: Int) {
            onLog("BLE 扫描失败 (code=$errorCode)")
        }
    }

    @SuppressLint("MissingPermission")
    private fun handle(result: ScanResult) {
        val dev = result.device ?: return
        val name = try { result.scanRecord?.deviceName ?: dev.name } catch (_: SecurityException) { null }
        val address = dev.address ?: return
        val (drone, hint) = DroneDetect.checkBle(name, address)
        devices[address] = BleDevice(
            name = name, address = address, rssi = result.rssi,
            isDrone = drone, droneHint = hint, timestamp = System.currentTimeMillis()
        )
    }

    fun start(scope: CoroutineScope) {
        if (job != null) return
        val leScanner = try { adapter?.bluetoothLeScanner } catch (_: SecurityException) { null }
        if (leScanner == null) {
            onLog("设备不支持或未开启蓝牙 BLE")
            return
        }
        if (adapter?.isEnabled != true) {
            onLog("蓝牙未开启，无法进行 BLE 扫描")
        }
        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            leScanner.startScan(null, settings, callback)
            scanning = true
            onLog("开始扫描附近 BLE 设备…")
        } catch (e: SecurityException) {
            onLog("缺少蓝牙扫描权限: ${e.message}")
            return
        } catch (e: Exception) {
            onLog("BLE 扫描启动失败: ${e.message}")
            return
        }

        job = scope.launch(Dispatchers.Default) {
            while (isActive) {
                publish()
                delay(1000)
            }
        }
    }

    private fun publish() {
        val list = devices.values.sortedWith(
            compareByDescending<BleDevice> { it.isDrone }.thenByDescending { it.rssi }
        )
        onResult(list)
    }

    fun stop() {
        job?.cancel(); job = null
        if (scanning) {
            try {
                adapter?.bluetoothLeScanner?.stopScan(callback)
            } catch (_: Exception) {}
            scanning = false
        }
        devices.clear()
        onLog("BLE 扫描已停止")
    }
}
