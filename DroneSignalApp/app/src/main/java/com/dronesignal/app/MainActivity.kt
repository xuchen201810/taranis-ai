package com.dronesignal.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dronesignal.app.model.BleDevice
import com.dronesignal.app.model.DroneTelemetry
import com.dronesignal.app.model.LogEntry
import com.dronesignal.app.model.WifiNetwork
import com.dronesignal.app.signal.SignalService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var repo: com.dronesignal.app.signal.SignalRepository

    private lateinit var statusDot: View
    private lateinit var tvStatus: TextView
    private lateinit var btnStart: TextView
    private lateinit var tvTelemState: TextView
    private lateinit var telemetryContainer: LinearLayout
    private lateinit var tvCurrentWifi: TextView
    private lateinit var wifiContainer: LinearLayout
    private lateinit var bleContainer: LinearLayout
    private lateinit var logContainer: LinearLayout
    private lateinit var logScroll: ScrollView

    private val valueViews = HashMap<String, TextView>()

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        repo = (application as DroneSignalApp).repository

        statusDot = findViewById(R.id.statusDot)
        tvStatus = findViewById(R.id.tvStatus)
        btnStart = findViewById(R.id.btnStart)
        tvTelemState = findViewById(R.id.tvTelemState)
        telemetryContainer = findViewById(R.id.telemetryContainer)
        tvCurrentWifi = findViewById(R.id.tvCurrentWifi)
        wifiContainer = findViewById(R.id.wifiContainer)
        bleContainer = findViewById(R.id.bleContainer)
        logContainer = findViewById(R.id.logContainer)
        logScroll = findViewById(R.id.logScroll)

        buildTelemetryRows()
        btnStart.setOnClickListener {
            if (repo.isRunning.value) stop() else permLauncher.launch(neededPermissions())
        }

        // If the service was already running (e.g. after process death), sync UI.
        updateRunningUI(repo.isRunning.value)

        observe()
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { repo.isRunning.collect { updateRunningUI(it) } }
                launch { repo.telemetry.collect { renderTelemetry(it) } }
                launch { repo.wifi.collect { renderWifi(it) } }
                launch { repo.ble.collect { renderBle(it) } }
                launch { repo.currentWifi.collect { tvCurrentWifi.text = it } }
                launch { repo.logs.collect { renderLog(it) } }
            }
        }
    }

    // ---------- start / stop ----------

    private fun start() {
        repo.start(this)
        if (hasLocationPermission()) {
            val intent = Intent(this, SignalService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                @Suppress("DEPRECATION") startService(intent)
            }
        } else {
            repo.log("W", "未授予位置权限：后台保活服务未启动，仍可在前台接收信号")
        }
    }

    private fun stop() {
        repo.stop()
        stopService(Intent(this, SignalService::class.java))
    }

    private fun neededPermissions(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += Manifest.permission.BLUETOOTH_SCAN
            list += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.NEARBY_WIFI_DEVICES
            list += Manifest.permission.POST_NOTIFICATIONS
        }
        return list.toTypedArray()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    // ---------- UI rendering ----------

    private fun updateRunningUI(running: Boolean) {
        if (running) {
            btnStart.text = getString(R.string.stop)
            btnStart.setBackgroundResource(R.drawable.bg_stop_button)
            tvStatus.text = getString(R.string.status_running)
            tint(statusDot, R.color.ok)
        } else {
            btnStart.text = getString(R.string.start)
            btnStart.setBackgroundResource(R.drawable.bg_start_button)
            tvStatus.text = getString(R.string.status_idle)
            tint(statusDot, R.color.text_muted)
        }
    }

    private fun buildTelemetryRows() {
        val rows = listOf(
            listOf("系统" to "system", "飞控" to "autopilot"),
            listOf("模式" to "mode", "状态" to "status"),
            listOf("电量" to "battery", "电压" to "voltage"),
            listOf("电流" to "current", "海拔" to "alt"),
            listOf("相对高度" to "relAlt", "地速" to "groundspeed"),
            listOf("空速" to "airspeed", "航向" to "heading"),
            listOf("爬升" to "climb", "坐标" to "gps"),
            listOf("定位" to "fix", "姿态" to "attitude")
        )
        for (pair in rows) {
            telemetryContainer.addView(makeRow(pair[0], pair[1]))
        }
    }

    private fun makeRow(a: Pair<String, String>, b: Pair<String, String>): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        row.addView(makeField(a.first, a.second, 1f))
        row.addView(makeField(b.first, b.second, 1f))
        return row
    }

    private fun makeField(label: String, key: String, weight: Float): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
                .apply { marginEnd = dp(8) }
        }
        val tvLabel = TextView(this).apply {
            text = label
            setTextColor(color(R.color.text_muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        val tvValue = TextView(this).apply {
            text = "—"
            setTextColor(color(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            setSingleLine(true)
        }
        valueViews[key] = tvValue
        col.addView(tvLabel)
        col.addView(tvValue)
        return col
    }

    private fun renderTelemetry(t: DroneTelemetry?) {
        if (t == null) {
            tvTelemState.text = "尚未收到 MAVLink 遥测…"
            tvTelemState.setTextColor(color(R.color.text_muted))
            for (v in valueViews.values) v.text = "—"
            return
        }
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(t.lastUpdate))
        tvTelemState.text = "来源 ${t.source} · 系统 ${t.sysId}/${t.compId} · 更新于 $time"
        tvTelemState.setTextColor(color(R.color.text_secondary))

        val v = valueViews
        v["system"]?.text = if (t.mavType.isNotBlank()) "${t.mavType} (${t.sysId}/${t.compId})" else "—"
        v["autopilot"]?.text = t.autopilot.ifBlank { "—" }
        v["mode"]?.text = if (t.baseMode.isNotBlank()) t.baseMode else "—"
        v["status"]?.text = if (t.systemStatus.isNotBlank()) t.systemStatus else "—"
        v["battery"]?.text = t.batteryPercent?.let { "$it%" } ?: "—"
        v["voltage"]?.text = t.voltageV?.let { "%.2f V".format(it) } ?: "—"
        v["current"]?.text = t.currentA?.let { "%.2f A".format(it) } ?: "—"
        v["alt"]?.text = t.altM?.let { "%.1f m".format(it) } ?: "—"
        v["relAlt"]?.text = t.relativeAltM?.let { "%.1f m".format(it) } ?: "—"
        v["groundspeed"]?.text = t.groundspeedMs?.let { "%.1f m/s".format(it) } ?: "—"
        v["airspeed"]?.text = t.airspeedMs?.let { "%.1f m/s".format(it) } ?: "—"
        v["heading"]?.text = t.headingDeg?.let { "$it°" } ?: "—"
        v["climb"]?.text = t.climbMs?.let { "%+.1f m/s".format(it) } ?: "—"
        v["gps"]?.text = if (t.lat != null && t.lon != null)
            "%.6f, %.6f".format(t.lat, t.lon) else "—"
        v["fix"]?.text = t.fixType?.let { "${fixName(it)} · ${t.satellites ?: 0} 星" } ?: "—"
        v["attitude"]?.text = if (t.rollDeg != null && t.pitchDeg != null && t.yawDeg != null)
            "%.0f° / %.0f° / %.0f°".format(t.rollDeg, t.pitchDeg, t.yawDeg) else "—"

        // Low-battery warning color
        val bat = v["battery"]
        if (t.batteryPercent != null && t.batteryPercent <= 20) {
            bat?.setTextColor(color(R.color.danger))
        } else {
            bat?.setTextColor(color(R.color.text_primary))
        }
    }

    private fun renderWifi(list: List<WifiNetwork>) {
        wifiContainer.removeAllViews()
        if (list.isEmpty()) {
            wifiContainer.addView(placeholder("未发现 WiFi 网络（需授予位置 / 附近设备权限）"))
            return
        }
        for (net in list) {
            val prefix = when {
                net.connected -> "● 已连接  "
                net.isDrone -> "[无人机]  "
                else -> ""
            }
            val text = "$prefix${net.ssid}  ${net.levelDbm}dBm  ${net.frequencyMHz}MHz  ${net.bssid}" +
                    (net.droneHint?.let { "  ($it)" } ?: "")
            val color = when {
                net.isDrone -> R.color.drone
                net.connected -> R.color.ok
                else -> R.color.text_primary
            }
            wifiContainer.addView(rowText(text, color))
        }
    }

    private fun renderBle(list: List<BleDevice>) {
        bleContainer.removeAllViews()
        if (list.isEmpty()) {
            bleContainer.addView(placeholder("未发现 BLE 设备（需开启蓝牙并授予权限）"))
            return
        }
        for (dev in list) {
            val name = dev.name ?: "(无名称)"
            val prefix = if (dev.isDrone) "[无人机]  " else ""
            val text = "$prefix$name  ${dev.rssi}dBm  ${dev.address}" +
                    (dev.droneHint?.let { "  ($it)" } ?: "")
            val color = if (dev.isDrone) R.color.drone else R.color.text_primary
            bleContainer.addView(rowText(text, color))
        }
    }

    private fun renderLog(list: List<LogEntry>) {
        logContainer.removeAllViews()
        for (e in list) {
            val color = when (e.level) {
                "E" -> R.color.danger
                "W" -> R.color.drone
                "D" -> R.color.text_muted
                else -> R.color.text_secondary
            }
            val tv = TextView(this).apply {
                text = "${e.time}  ${e.level}  ${e.message}"
                setTextColor(color(color))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(dp(4), dp(1), dp(4), dp(1))
                setLineSpacing(0f, 1.15f)
                maxLines = 3
            }
            logContainer.addView(tv)
        }
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------- small helpers ----------

    private fun placeholder(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color(R.color.text_muted))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(dp(6), dp(6), dp(6), dp(6))
    }

    private fun rowText(text: String, colorRes: Int): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color(colorRes))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setBackgroundResource(R.drawable.bg_row)
        setPadding(dp(10), dp(7), dp(10), dp(7))
        setSingleLine(true)
        ellipsize = android.text.TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(5) }
    }

    private fun fixName(fix: Int): String = when (fix) {
        0 -> "无定位"
        1 -> "无定位"
        2 -> "2D 定位"
        3 -> "3D 定位"
        4 -> "DGPS"
        5 -> "RTK 浮点"
        6 -> "RTK 固定"
        else -> "FIX_$fix"
    }

    private fun color(res: Int): Int = ContextCompat.getColor(this, res)
    private fun tint(v: View, res: Int) {
        val d = v.background.mutate()
        DrawableCompat.setTint(d, color(res))
        v.background = d
    }
    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}
