package com.dronesignal.app.signal

import android.content.Context
import com.dronesignal.app.model.BleDevice
import com.dronesignal.app.model.DroneTelemetry
import com.dronesignal.app.model.LogEntry
import com.dronesignal.app.model.WifiNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Central, app-wide coordinator for drone signal reception. Owns the worker
 * coroutines and exposes immutable StateFlows consumed by the UI / service.
 */
class SignalRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _telemetry = MutableStateFlow<DroneTelemetry?>(null)
    val telemetry: StateFlow<DroneTelemetry?> = _telemetry.asStateFlow()

    private val _wifi = MutableStateFlow<List<WifiNetwork>>(emptyList())
    val wifi: StateFlow<List<WifiNetwork>> = _wifi.asStateFlow()

    private val _ble = MutableStateFlow<List<BleDevice>>(emptyList())
    val ble: StateFlow<List<BleDevice>> = _ble.asStateFlow()

    private val _currentWifi = MutableStateFlow("")
    val currentWifi: StateFlow<String> = _currentWifi.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    @Volatile var packetCount = 0L
        private set
    @Volatile var lastPacketTime = 0L
        private set

    private var mavReceiver: MavlinkReceiver? = null
    private var wifiScanner: WifiScanner? = null
    private var bleScanner: BleScanner? = null
    private var collectorJob: Job? = null
    private var heartbeatLogJob: Job? = null

    fun start(context: Context) {
        if (_isRunning.value) return
        _isRunning.value = true
        log("I", "一键启动：开始接收无人机信号")

        val appCtx = context.applicationContext
        val mav = MavlinkReceiver { log("I", it) }
        mavReceiver = mav
        mav.start(scope)

        collectorJob = scope.launch {
            mav.packets.collect { pkt ->
                packetCount++
                lastPacketTime = System.currentTimeMillis()
                _telemetry.value = MavlinkParser.apply(pkt, _telemetry.value)
            }
        }

        wifiScanner = WifiScanner(appCtx,
            onResult = { _wifi.value = it },
            onCurrent = { _currentWifi.value = it },
            onLog = { log("I", it) }
        ).also { it.start(scope) }

        bleScanner = BleScanner(appCtx,
            onResult = { _ble.value = it },
            onLog = { log("I", it) }
        ).also { it.start(scope) }

        heartbeatLogJob = scope.launch {
            var lastCount = 0L
            while (true) {
                kotlinx.coroutines.delay(5000)
                val c = packetCount
                if (c > lastCount) {
                    log("I", "已接收 MAVLink 报文 ${c} 条")
                    lastCount = c
                } else if (_isRunning.value) {
                    log("D", "等待遥测…(若连接到无人机 WiFi 仍无数据，确认端口 14550)")
                }
            }
        }
    }

    fun stop() {
        if (!_isRunning.value) return
        _isRunning.value = false
        log("I", "停止接收无人机信号")
        collectorJob?.cancel(); collectorJob = null
        heartbeatLogJob?.cancel(); heartbeatLogJob = null
        mavReceiver?.stop(); mavReceiver = null
        wifiScanner?.stop(); wifiScanner = null
        bleScanner?.stop(); bleScanner = null
        packetCount = 0
        lastPacketTime = 0
    }

    fun log(level: String, message: String) {
        val entry = LogEntry(timeFmt.format(Date()), level, message)
        val cur = _logs.value
        val next = if (cur.size >= 500) cur.drop(cur.size - 499) + entry else cur + entry
        _logs.value = next
    }
}
