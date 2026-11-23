package com.trae.ridreceiver

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var btnScan: TextView
    private lateinit var btnShare: TextView
    private lateinit var radar: RadarView
    private lateinit var radarHint: TextView
    private lateinit var emptyText: TextView

    private val store = DroneStore()
    private lateinit var ble: BleScanner
    private lateinit var wifiScan: WifiBeaconScanner
    private lateinit var location: LocationProvider
    private lateinit var adapter: DroneAdapter

    private var mqtt: MqttPublisher? = null
    private var scanning = false
    private var ticks = 0
    private var sourceWarning = ""

    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            if (!scanning) return
            tick()
            handler.postDelayed(this, 1000)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.any { !it.value }) {
                Toast.makeText(this, "权限未全部授予，部分接收通道可能不可用", Toast.LENGTH_LONG).show()
            }
            startScanning()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        statusText = findViewById(R.id.statusText)
        btnScan = findViewById(R.id.btnScan)
        btnShare = findViewById(R.id.btnShare)
        radar = findViewById(R.id.radar)
        radarHint = findViewById(R.id.radarHint)
        emptyText = findViewById(R.id.emptyText)

        adapter = DroneAdapter { showDetail(it) }
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        ble = BleScanner(this) { mac, rssi, decoded ->
            store.ingest(decoded.messages, mac, rssi, decoded.source)
        }
        wifiScan = WifiBeaconScanner(this) { bssid, rssi, decoded ->
            store.ingest(decoded.messages, bssid, rssi, decoded.source)
        }
        location = LocationProvider(this)

        btnScan.setOnClickListener {
            if (scanning) stopScanning() else ensurePermissionsAndStart()
        }
        btnShare.setOnClickListener { toggleShare() }

        ensurePermissionsAndStart()
    }

    private fun requiredPermissions(): List<String> {
        val list = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += Manifest.permission.BLUETOOTH_SCAN
            list += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            list += Manifest.permission.BLUETOOTH
            list += Manifest.permission.BLUETOOTH_ADMIN
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        return list
    }

    private fun ensurePermissionsAndStart() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            startScanning()
        }
    }

    private fun startScanning() {
        if (scanning) return
        val warnings = mutableListOf<String>()
        ble.start()?.let { warnings += it }
        wifiScan.start()?.let { warnings += it }
        location.start()

        scanning = true
        sourceWarning = warnings.joinToString("；")
        btnScan.text = "停止"
        btnScan.setTextColor(ContextCompat.getColor(this, R.color.cyan))

        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun stopScanning() {
        scanning = false
        ble.stop()
        wifiScan.stop()
        location.stop()
        handler.removeCallbacks(ticker)
        btnScan.text = "开始"
        btnScan.setTextColor(ContextCompat.getColor(this, R.color.muted))
        statusText.text = "已停止扫描"
    }

    private fun tick() {
        store.prune(30000)
        store.updateGeometry(location.lastKnown)

        val drones = store.listSorted()
        radar.drones = drones
        adapter.submit(drones)
        emptyText.visibility = if (drones.isEmpty()) View.VISIBLE else View.GONE
        radarHint.text = if (location.lastKnown == null) {
            "无 GPS：方位未知，半径按信号强度估算"
        } else {
            "北向朝上 · 量程自动"
        }

        val (bleCount, wifiCount) = store.counts()
        statusText.text = buildString {
            append("扫描中 · BLE ")
            append(bleCount)
            append(" · Wi-Fi ")
            append(wifiCount)
            if (mqtt?.connected == true) append(" · 已分享")
            if (sourceWarning.isNotEmpty()) {
                append(" · ")
                append(sourceWarning)
            }
        }

        ticks++
        if (drones.isNotEmpty() && ticks % 2 == 0) {
            mqtt?.publish(buildJson(drones))
        }
    }

    private fun toggleShare() {
        if (mqtt == null) {
            mqtt = MqttPublisher().also { it.start() }
            btnShare.text = "分享中"
            btnShare.setTextColor(ContextCompat.getColor(this, R.color.green))
            Toast.makeText(this, "正在连接 ${MqttConfig.HOST}…", Toast.LENGTH_SHORT).show()
        } else {
            mqtt?.stop()
            mqtt = null
            btnShare.text = "分享"
            btnShare.setTextColor(ContextCompat.getColor(this, R.color.muted))
        }
    }

    private fun showDetail(drone: Drone) {
        AlertDialog.Builder(this)
            .setTitle(drone.title())
            .setMessage(drone.describe())
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun buildJson(drones: List<Drone>): String {
        val root = JSONObject()
        root.put("type", "rid.snapshot")
        root.put("ts", System.currentTimeMillis())

        val observer = location.lastKnown
        if (observer != null) {
            root.put(
                "observer",
                JSONObject()
                    .put("lat", observer.latitude)
                    .put("lon", observer.longitude)
            )
        }

        val arr = JSONArray()
        for (d in drones) {
            val o = JSONObject()
            o.put("key", d.key)
            o.putOpt("uasId", d.uasId)
            o.put("uaTypeText", d.uaTypeText)
            o.put("status", d.status)
            o.put("statusText", d.statusLabel())
            d.lat?.let { o.put("lat", it) }
            d.lon?.let { o.put("lon", it) }
            d.altGeo?.let { o.put("altGeo", it) }
            d.height?.let { o.put("height", it) }
            o.put("direction", d.direction)
            o.put("speedH", d.speedH)
            o.put("speedV", d.speedV)
            d.operatorLat?.let { o.put("operatorLat", it) }
            d.operatorLon?.let { o.put("operatorLon", it) }
            o.putOpt("operatorId", d.operatorId)
            o.putOpt("selfId", d.selfId)
            o.put("rssi", d.rssi)
            o.put("source", d.source.name)
            d.distanceM?.let { o.put("distanceM", it) }
            d.bearing?.let { o.put("bearing", it) }
            o.put("lastSeen", d.lastSeen)
            o.put("hasLocation", d.hasLocation)
            arr.put(o)
        }
        root.put("drones", arr)
        return root.toString()
    }

    override fun onDestroy() {
        stopScanning()
        mqtt?.stop()
        mqtt = null
        super.onDestroy()
    }
}
