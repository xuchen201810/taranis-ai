package com.dronesignal.app.signal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.dronesignal.app.model.WifiNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class WifiScanner(
    private val context: Context,
    private val onResult: (List<WifiNetwork>) -> Unit,
    private val onCurrent: (String) -> Unit,
    private val onLog: (String) -> Unit
) {
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var job: Job? = null
    private var receiverRegistered = false

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                readAndPublish()
            }
        }
    }

    fun start(scope: CoroutineScope) {
        if (job != null) return
        val wm = wifiManager
        if (wm == null) {
            onLog("设备不支持 WiFi")
            return
        }
        try {
            val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(scanReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(scanReceiver, filter)
            }
            receiverRegistered = true
        } catch (e: Exception) {
            onLog("注册 WiFi 扫描广播失败: ${e.message}")
        }
        onLog("开始扫描附近 WiFi 网络…")

        job = scope.launch(Dispatchers.Default) {
            while (isActive) {
                publishCurrent()
                try { wm.startScan() } catch (_: Exception) {}
                readAndPublish()
                delay(12_000)
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        if (receiverRegistered) {
            try { context.unregisterReceiver(scanReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
        onLog("WiFi 扫描已停止")
    }

    private fun readAndPublish() {
        val wm = wifiManager ?: return
        val results: List<ScanResult> = try { wm.scanResults } catch (_: Exception) { emptyList() }
        val connectedBssid = try { wm.connectionInfo?.bssid } catch (_: Exception) { null }
        val list = results.mapNotNull { r ->
            val ssid = r.SSID?.takeIf { it.isNotBlank() && it != "<unknown ssid>" } ?: "(隐藏网络)"
            val (drone, hint) = DroneDetect.checkWifi(ssid, r.BSSID)
            WifiNetwork(
                ssid = ssid,
                bssid = r.BSSID ?: "",
                levelDbm = r.level,
                frequencyMHz = r.frequency,
                capabilities = r.capabilities ?: "",
                isDrone = drone,
                droneHint = hint,
                connected = connectedBssid != null && connectedBssid.equals(r.BSSID, ignoreCase = true),
                timestamp = System.currentTimeMillis()
            )
        }.sortedWith(compareByDescending<WifiNetwork> { it.isDrone }.thenByDescending { it.levelDbm })
        onResult(list)
    }

    private fun publishCurrent() {
        val wm = wifiManager ?: return
        val info: WifiInfo? = try { wm.connectionInfo } catch (_: Exception) { null }
        if (info == null || info.networkId == -1) {
            onCurrent("当前未连接 WiFi（可连接无人机遥控器热点以接收遥测）")
            return
        }
        val ssid = info.ssid?.removePrefix("\"")?.removeSuffix("\"") ?: "未知"
        val bssid = info.bssid ?: ""
        val (drone, hint) = DroneDetect.checkWifi(ssid, bssid)
        val tag = if (drone) "  ⚠ 疑似无人机网络($hint)" else ""
        onCurrent("当前连接: $ssid  $bssid  ${info.frequency}MHz  ${info.linkSpeed}Mbps$tag")
    }
}
