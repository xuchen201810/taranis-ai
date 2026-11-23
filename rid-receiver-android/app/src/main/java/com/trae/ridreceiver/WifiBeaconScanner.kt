package com.trae.ridreceiver

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * 解析 Wi-Fi Beacon 中的 Remote ID 厂商 IE（OUI FA:0B:BC, type 0x0D）。
 * Android 11+ 才能通过 ScanResult.InformationElement 读取厂商 IE。
 */
class WifiBeaconScanner(
    private val context: Context,
    private val onDecoded: (bssid: String, rssi: Int, decoded: RidDecoder.Decoded) -> Unit
) {

    private val wifi: WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            poll()
        }
    }

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            requestScan()
            poll()
            handler.postDelayed(this, 5000)
        }
    }

    private fun supported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun start(): String? {
        if (!supported()) return "Android 11 以下无法读取 Wi-Fi Beacon 厂商 IE，仅使用蓝牙通道"
        val w = wifi ?: return "本机 Wi-Fi 不可用"
        if (!w.isWifiEnabled) return "Wi-Fi 未开启（仅蓝牙通道工作）"

        running = true
        try {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (_: Exception) {
        }
        handler.post(loop)
        return null
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(loop)
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission", "DEPRECATION")
    private fun requestScan() {
        try {
            wifi?.startScan()
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun poll() {
        if (!running || !supported()) return
        val results = try {
            wifi?.scanResults ?: return
        } catch (e: SecurityException) {
            return
        } catch (_: Exception) {
            return
        }

        for (r in results) {
            val elements = try {
                r.informationElements ?: continue
            } catch (_: Exception) {
                continue
            }
            for (ie in elements) {
                if (ie.id != 221) continue // 0xDD Vendor Specific
                val buf = ie.bytes ?: continue
                val bytes = ByteArray(buf.remaining())
                buf.duplicate().get(bytes)
                val decoded = RidDecoder.decodeWifiVendorIe(bytes) ?: continue
                val bssid = r.BSSID ?: "wifi"
                onDecoded(bssid, r.level, decoded)
            }
        }
    }
}
