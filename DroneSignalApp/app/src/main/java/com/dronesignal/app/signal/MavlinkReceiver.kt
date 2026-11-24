package com.dronesignal.app.signal

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException

/**
 * Listens for MAVLink telemetry over UDP on the standard GCS port (14550).
 * Also emits a periodic GCS HEARTBEAT so drones that only stream after
 * receiving a heartbeat will start sending telemetry.
 */
class MavlinkReceiver(
    private val port: Int = 14550,
    private val onLog: (String) -> Unit = {}
) {
    private val _packets = MutableSharedFlow<MavlinkParser.RawPacket>(extraBufferCapacity = 64)
    val packets: SharedFlow<MavlinkParser.RawPacket> = _packets.asSharedFlow()

    private var socket: DatagramSocket? = null
    private var job: Job? = null
    private var seq = 0

    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) {
            val sock = try {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 1000
                    bind(InetSocketAddress(port))
                }
            } catch (e: Exception) {
                onLog("UDP 绑定端口 $port 失败: ${e.message}")
                Log.w(TAG, "bind failed", e)
                return@launch
            }
            socket = sock
            onLog("UDP 监听 $port 端口，等待 MAVLink 遥测…")

            val buf = ByteArray(2048)
            val packet = DatagramPacket(buf, buf.size)
            var lastHeartbeat = 0L
            while (isActive) {
                try {
                    sock.receive(packet)
                    if (packet.length > 0) {
                        val data = packet.data.copyOfRange(0, packet.length)
                        val parsed = MavlinkParser.parse(data)
                        for (p in parsed) _packets.tryEmit(p)
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    // expected, loop continues
                } catch (e: SocketException) {
                    if (!isActive) break
                    onLog("UDP 异常: ${e.message}")
                    delay(500)
                } catch (e: Exception) {
                    if (!isActive) break
                    Log.w(TAG, "recv error", e)
                    delay(500)
                }
                val now = System.currentTimeMillis()
                if (now - lastHeartbeat > 1000) {
                    sendHeartbeat(sock)
                    lastHeartbeat = now
                }
            }
            try { sock.close() } catch (_: Exception) {}
            onLog("UDP 监听已停止")
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    private fun sendHeartbeat(sock: DatagramSocket) {
        val s = seq++
        // HEARTBEAT payload (9 bytes): type=6(GCS), autopilot=0, base_mode=0, custom_mode=0, status=0, version=3
        val payload = byteArrayOf(6, 0, 0, 0, 0, 0, 0, 0, 3)
        val buf = buildMavlink1(0, 255, 190, s, payload)
        try {
            val addr = InetAddress.getByName("255.255.255.255")
            sock.send(DatagramPacket(buf, buf.size, addr, port))
        } catch (_: Exception) {
            // broadcast may be blocked; ignore
        }
    }

    private fun buildMavlink1(msgId: Int, sysId: Int, compId: Int, seq: Int, payload: ByteArray): ByteArray {
        val len = payload.size
        val out = ByteArray(6 + len + 2)
        out[0] = 0xFE.toByte()
        out[1] = len.toByte()
        out[2] = (seq and 0xFF).toByte()
        out[3] = (sysId and 0xFF).toByte()
        out[4] = (compId and 0xFF).toByte()
        out[5] = (msgId and 0xFF).toByte()
        System.arraycopy(payload, 0, out, 6, len)
        // crc over out[1..6+len) then append CRC_EXTRA for msgId
        var crc = 0xFFFF
        for (k in 1 until 6 + len) {
            crc = crcX25(crc, out[k].toInt() and 0xFF)
        }
        crc = crcX25(crc, 50) // HEARTBEAT seed
        out[6 + len] = (crc and 0xFF).toByte()
        out[6 + len + 1] = ((crc ushr 8) and 0xFF).toByte()
        return out
    }

    private fun crcX25(crc: Int, b: Int): Int {
        var c = (crc xor b) and 0xFFFF
        repeat(8) { c = if ((c and 1) != 0) (c ushr 1) xor 0x8408 else c ushr 1 }
        return c and 0xFFFF
    }

    companion object {
        private const val TAG = "MavlinkReceiver"
    }
}
