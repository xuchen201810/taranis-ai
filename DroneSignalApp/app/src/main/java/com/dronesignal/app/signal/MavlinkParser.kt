package com.dronesignal.app.signal

import com.dronesignal.app.model.DroneTelemetry
import kotlin.math.sqrt

/**
 * Minimal MAVLink v1 / v2 parser focused on the telemetry messages a drone
 * ground station cares about. Validates the X.25 checksum when the CRC_EXTRA
 * for a message id is known, and otherwise accepts the packet best-effort.
 */
object MavlinkParser {

    private const val MAGIC_V1 = 0xFE
    private const val MAGIC_V2 = 0xFD

    // msg id -> CRC_EXTRA (seed byte), from mavlink generated C headers (common.xml)
    private val CRC_EXTRA = mapOf(
        0 to 50,      // HEARTBEAT
        1 to 124,     // SYS_STATUS
        2 to 137,     // SYSTEM_TIME
        24 to 24,     // GPS_RAW_INT
        30 to 39,     // ATTITUDE
        32 to 185,    // LOCAL_POSITION_NED
        33 to 104,    // GLOBAL_POSITION_INT
        74 to 20,     // VFR_HUD
        147 to 154    // BATTERY_STATUS
    )

    data class RawPacket(
        val msgId: Int, val sysId: Int, val compId: Int,
        val payload: ByteArray, val v2: Boolean
    )

    fun parse(data: ByteArray): List<RawPacket> {
        val out = ArrayList<RawPacket>()
        var i = 0
        while (i < data.size) {
            val b = data[i].toInt() and 0xFF
            when (b) {
                MAGIC_V1 -> {
                    if (i + 6 > data.size) break
                    val len = data[i + 1].toInt() and 0xFF
                    val total = 6 + len + 2
                    if (i + total > data.size) break
                    val sys = data[i + 3].toInt() and 0xFF
                    val comp = data[i + 4].toInt() and 0xFF
                    val msgId = data[i + 5].toInt() and 0xFF
                    val payload = data.copyOfRange(i + 6, i + 6 + len)
                    val crcIdx = i + 6 + len
                    val crc = readU16(data, crcIdx)
                    if (verifyCrc(data, i + 1, crcIdx, msgId, crc)) {
                        out.add(RawPacket(msgId, sys, comp, payload, false))
                    }
                    i += total
                }
                MAGIC_V2 -> {
                    if (i + 10 > data.size) break
                    val len = data[i + 1].toInt() and 0xFF
                    val incompat = data[i + 2].toInt() and 0xFF
                    val sys = data[i + 5].toInt() and 0xFF
                    val comp = data[i + 6].toInt() and 0xFF
                    val msgId = (data[i + 7].toInt() and 0xFF) or
                            ((data[i + 8].toInt() and 0xFF) shl 8) or
                            ((data[i + 9].toInt() and 0xFF) shl 16)
                    val sigLen = if (incompat and 0x01 != 0) 13 else 0
                    val total = 10 + len + 2 + sigLen
                    if (i + total > data.size) break
                    val payload = data.copyOfRange(i + 10, i + 10 + len)
                    val crcIdx = i + 10 + len
                    val crc = readU16(data, crcIdx)
                    if (verifyCrc(data, i + 1, crcIdx, msgId, crc)) {
                        out.add(RawPacket(msgId, sys, comp, payload, true))
                    }
                    i += total
                }
                else -> i++
            }
        }
        return out
    }

    private fun verifyCrc(data: ByteArray, from: Int, toExclusive: Int, msgId: Int, expected: Int): Boolean {
        var crc = 0xFFFF
        for (k in from until toExclusive) {
            crc = crcX25(crc, data[k].toInt() and 0xFF)
        }
        val extra = CRC_EXTRA[msgId] ?: return true // accept if seed unknown
        crc = crcX25(crc, extra)
        return (crc and 0xFFFF) == expected
    }

    private fun crcX25(crc: Int, b: Int): Int {
        var c = (crc xor b) and 0xFFFF
        repeat(8) {
            c = if ((c and 1) != 0) (c ushr 1) xor 0x8408 else c ushr 1
        }
        return c and 0xFFFF
    }

    private fun readU16(data: ByteArray, idx: Int): Int =
        ((data[idx].toInt() and 0xFF) or ((data[idx + 1].toInt() and 0xFF) shl 8)) and 0xFFFF

    // ---- payload helpers (little endian) ----
    private fun u8(p: ByteArray, i: Int) = if (i in p.indices) p[i].toInt() and 0xFF else 0
    private fun i8(p: ByteArray, i: Int) = if (i in p.indices) p[i].toInt() else 0
    private fun u16(p: ByteArray, i: Int): Int =
        if (i + 1 in p.indices) ((p[i].toInt() and 0xFF) or ((p[i + 1].toInt() and 0xFF) shl 8)) and 0xFFFF else 0
    private fun i16(p: ByteArray, i: Int): Int =
        if (i + 1 in p.indices) ((p[i].toInt() and 0xFF) or ((p[i + 1].toInt() and 0xFF) shl 8)).toShort().toInt() else 0
    private fun u32(p: ByteArray, i: Int): Long =
        if (i + 3 in p.indices) ((p[i].toLong() and 0xFF) or
                ((p[i + 1].toLong() and 0xFF) shl 8) or
                ((p[i + 2].toLong() and 0xFF) shl 16) or
                ((p[i + 3].toLong() and 0xFF) shl 24)) else 0L
    private fun i32(p: ByteArray, i: Int): Int =
        if (i + 3 in p.indices) ((p[i].toInt() and 0xFF) or
                ((p[i + 1].toInt() and 0xFF) shl 8) or
                ((p[i + 2].toInt() and 0xFF) shl 16) or
                ((p[i + 3].toInt() and 0xFF) shl 24)) else 0
    private fun f32(p: ByteArray, i: Int): Float =
        if (i + 3 in p.indices) Float.fromBits(i32(p, i)) else Float.NaN

    /** Merge a parsed packet into an existing telemetry snapshot. */
    fun apply(packet: RawPacket, cur: DroneTelemetry?): DroneTelemetry {
        val t = cur?.copy(lastUpdate = System.currentTimeMillis()) ?: DroneTelemetry()
        val p = packet.payload
        return when (packet.msgId) {
            0 -> { // HEARTBEAT (9)
                val type = mavType(u8(p, 0))
                t.copy(
                    sysId = packet.sysId, compId = packet.compId,
                    mavType = type,
                    autopilot = mavAutopilot(u8(p, 1)),
                    baseMode = decodeBaseMode(u8(p, 2)),
                    customMode = u32(p, 3),
                    systemStatus = mavStatus(u8(p, 7))
                )
            }
            1 -> { // SYS_STATUS (31)
                val mv = u16(p, 14)
                val cb = i16(p, 16)
                val br = i8(p, 18)
                t.copy(
                    voltageV = if (mv != 0xFFFF) mv / 1000f else t.voltageV,
                    currentA = if (cb != -1) cb / 100f else t.currentA,
                    batteryPercent = if (br in 0..100) br else t.batteryPercent
                )
            }
            24 -> { // GPS_RAW_INT (30)
                val lat = i32(p, 8); val lon = i32(p, 12)
                t.copy(
                    lat = if (lat != 0) lat / 1e7 else null,
                    lon = if (lon != 0) lon / 1e7 else null,
                    altM = let { val a = i32(p, 16); if (a != 0) a / 1000f else t.altM },
                    fixType = u8(p, 28),
                    satellites = u8(p, 29)
                )
            }
            30 -> { // ATTITUDE (28)
                t.copy(
                    rollDeg = Math.toDegrees(f32(p, 4).toDouble()).toFloat(),
                    pitchDeg = Math.toDegrees(f32(p, 8).toDouble()).toFloat(),
                    yawDeg = Math.toDegrees(f32(p, 12).toDouble()).toFloat()
                )
            }
            33 -> { // GLOBAL_POSITION_INT (28)
                val vx = i16(p, 20); val vy = i16(p, 22)
                t.copy(
                    lat = let { val v = i32(p, 4); if (v != 0) v / 1e7 else t.lat },
                    lon = let { val v = i32(p, 8); if (v != 0) v / 1e7 else t.lon },
                    altM = let { val v = i32(p, 12); if (v != 0) v / 1000f else t.altM },
                    relativeAltM = let { val v = i32(p, 16); if (v != 0) v / 1000f else t.relativeAltM },
                    groundspeedMs = if (vx != 0 || vy != 0) sqrt((vx * vx + vy * vy).toDouble()).toFloat() / 100f else t.groundspeedMs,
                    headingDeg = let { val h = u16(p, 26); if (h != 0xFFFF) h / 100 else t.headingDeg }
                )
            }
            74 -> { // VFR_HUD (20)
                t.copy(
                    airspeedMs = f32(p, 0),
                    groundspeedMs = f32(p, 4),
                    headingDeg = i16(p, 8),
                    altM = f32(p, 12),
                    climbMs = f32(p, 16)
                )
            }
            147 -> { // BATTERY_STATUS (39)
                val cb = i16(p, 30) // cA
                val br = i8(p, 37)
                val v0 = u16(p, 10); val v1 = u16(p, 12)
                val v = when {
                    v0 != 0xFFFF && v1 != 0xFFFF -> (v0 + v1) / 1000f
                    v0 != 0xFFFF -> v0 / 1000f
                    else -> t.voltageV
                }
                t.copy(
                    batteryPercent = if (br in 0..100) br else t.batteryPercent,
                    voltageV = v,
                    currentA = if (cb != -1) cb / 100f else t.currentA
                )
            }
            else -> t
        }
    }

    private fun mavType(t: Int) = when (t) {
        0 -> "通用"
        1 -> "固定翼"
        2 -> "四旋翼"
        3 -> "共轴双旋翼"
        4 -> "直升机"
        6 -> "地面站"
        10 -> "地面车"
        11 -> "水面船"
        13 -> "六旋翼"
        14 -> "八旋翼"
        15 -> "三旋翼"
        22 -> "VTOL"
        26 -> "云台"
        29 -> "十二旋翼"
        30 -> "相机"
        else -> "TYPE_$t"
    }

    private fun mavAutopilot(a: Int) = when (a) {
        3 -> "ArduPilot"
        4 -> "OpenPilot"
        10 -> "PX4"
        12 -> "AutoQuad"
        0 -> "通用"
        else -> "AP_$a"
    }

    private fun mavStatus(s: Int) = when (s) {
        0 -> "未初始化"
        1 -> "启动中"
        2 -> "校准中"
        3 -> "待机"
        4 -> "活跃"
        5 -> "严重警告"
        6 -> "紧急"
        7 -> "断电"
        else -> "状态_$s"
    }

    private fun decodeBaseMode(mode: Int): String {
        val armed = (mode and 0x80) != 0
        val parts = mutableListOf<String>()
        if (armed) parts += "已解锁" else parts += "已锁定"
        if ((mode and 0x01) != 0) parts += "测试"
        if ((mode and 0x04) != 0) parts += "仿真"
        if ((mode and 0x08) != 0) parts += "增稳"
        if ((mode and 0x10) != 0) parts += "引导"
        if ((mode and 0x20) != 0) parts += "自动"
        if ((mode and 0x40) != 0) parts += "手动"
        return parts.joinToString(" ")
    }
}
