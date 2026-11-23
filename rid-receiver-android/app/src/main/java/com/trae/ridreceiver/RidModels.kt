package com.trae.ridreceiver

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class RidSource { BLE, WIFI }

/** 一条远程识别消息（25 字节）解码结果 */
class OdidMessage {
    var type: Int = -1
    var proto: Int = 0

    // Basic ID
    var uaType: Int = 0
    var uaTypeText: String = ""
    var idType: Int = 0
    var idTypeText: String = ""
    var uasId: String? = null

    // Location
    var status: Int = 0
    var statusText: String = ""
    var direction: Int = 0
    var speedH: Double = 0.0
    var speedV: Double = 0.0
    var lat: Double? = null
    var lon: Double? = null
    var altBaro: Double? = null
    var altGeo: Double? = null
    var height: Double? = null
    var heightType: Int = 0
    var heightTypeText: String = ""
    var horizAcc: Int = 0
    var vertAcc: Int = 0
    var baroAcc: Int = 0
    var speedAcc: Int = 0
    var timestamp: Double? = null
    var tsAcc: Int = 0

    // System
    var operatorLocationType: Int = 0
    var operatorLocationTypeText: String = ""
    var classificationType: Int = 0
    var operatorLat: Double? = null
    var operatorLon: Double? = null
    var areaCount: Int = 0
    var areaRadius: Int = 0
    var areaCeiling: Double? = null
    var areaFloor: Double? = null
    var categoryEU: Int = 0
    var classEU: Int = 0
    var operatorAltGeo: Double? = null
    var systemTimestamp: Long = 0

    // Self ID / Operator ID / Auth
    var selfId: String? = null
    var operatorId: String? = null
    var operatorIdType: Int = 0
    var authType: Int = 0
    var dataPage: Int = 0

    var pack: List<OdidMessage> = emptyList()
}

/** 聚合后的无人机记录（多条广播累积） */
class Drone(var key: String, var uasId: String? = null) {
    var uaTypeText: String = ""
    var idTypeText: String = ""
    var status: Int = 0
    var statusText: String = ""
    var lat: Double? = null
    var lon: Double? = null
    var altBaro: Double? = null
    var altGeo: Double? = null
    var height: Double? = null
    var heightTypeText: String = ""
    var direction: Int = 0
    var speedH: Double = 0.0
    var speedV: Double = 0.0
    var operatorLat: Double? = null
    var operatorLon: Double? = null
    var operatorAltGeo: Double? = null
    var operatorLocationTypeText: String = ""
    var operatorId: String? = null
    var selfId: String? = null
    var areaCount: Int = 0
    var areaRadius: Int = 0
    var areaCeiling: Double? = null
    var areaFloor: Double? = null
    var categoryEU: Int = 0
    var classEU: Int = 0

    var rssi: Int = 0
    var source: RidSource = RidSource.BLE
    var mac: String? = null
    var counter: Int = 0
    var firstSeen: Long = 0
    var lastSeen: Long = 0
    var hasLocation: Boolean = false
    var hasSystem: Boolean = false

    // 由观察者位置计算
    var distanceM: Double? = null
    var bearing: Double? = null

    fun apply(m: OdidMessage) {
        when (m.type) {
            RidDecoder.MSG_BASIC_ID -> {
                if (!m.uasId.isNullOrBlank()) uasId = m.uasId
                uaTypeText = m.uaTypeText
                idTypeText = m.idTypeText
            }
            RidDecoder.MSG_LOCATION -> {
                status = m.status
                statusText = m.statusText
                direction = m.direction
                speedH = m.speedH
                speedV = m.speedV
                lat = m.lat
                lon = m.lon
                altBaro = m.altBaro
                altGeo = m.altGeo
                height = m.height
                heightTypeText = m.heightTypeText
                hasLocation = true
            }
            RidDecoder.MSG_SYSTEM -> {
                operatorLocationTypeText = m.operatorLocationTypeText
                operatorLat = m.operatorLat
                operatorLon = m.operatorLon
                operatorAltGeo = m.operatorAltGeo
                areaCount = m.areaCount
                areaRadius = m.areaRadius
                areaCeiling = m.areaCeiling
                areaFloor = m.areaFloor
                categoryEU = m.categoryEU
                classEU = m.classEU
                hasSystem = true
            }
            RidDecoder.MSG_SELF_ID -> if (!m.selfId.isNullOrBlank()) selfId = m.selfId
            RidDecoder.MSG_OPERATOR_ID -> if (!m.operatorId.isNullOrBlank()) operatorId = m.operatorId
        }
    }

    fun title(): String {
        val id = uasId
        return if (!id.isNullOrBlank()) id else "未知 ID"
    }

    fun statusColorRes(): Int = when (status) {
        2 -> R.color.green
        3, 4 -> R.color.red
        1 -> R.color.blue
        else -> R.color.amber
    }

    fun statusLabel(): String = when (status) {
        0 -> "未声明"
        1 -> "地面"
        2 -> "空中"
        3 -> "紧急"
        4 -> "RID 故障"
        else -> "状态未知"
    }

    fun describe(): String {
        val sb = StringBuilder()
        fun line(k: String, v: String?) {
            if (!v.isNullOrBlank()) sb.append(k).append("：").append(v).append('\n')
        }
        line("UAS ID", uasId)
        line("机型", uaTypeText)
        line("ID 类型", idTypeText)
        line("状态", statusLabel())
        line("信号来源", if (source == RidSource.BLE) "蓝牙广播" else "Wi-Fi Beacon")
        line("信号强度", "$rssi dBm")
        line("距离", Format.distance(distanceM))
        line("方位", bearing?.let { "${it.toInt()}°" })
        val lat = lat
        val lon = lon
        line(
            "经纬度",
            if (lat != null && lon != null) String.format(Locale.US, "%.6f, %.6f", lat, lon) else "未知"
        )
        val altParts = listOfNotNull(
            altBaro?.let { "气压 ${Format.meters(it)}" },
            altGeo?.let { "大地 ${Format.meters(it)}" }
        )
        line("高度", if (altParts.isEmpty()) null else altParts.joinToString(" / "))
        line("相对高度", height?.let { "${Format.meters(it)}（$heightTypeText）" })
        line("水平/垂直速度", String.format(Locale.US, "%.1f m/s · %.1f m/s", speedH, speedV))
        line("航向", "$direction°")
        line(
            "操控者位置",
            if (operatorLat != null && operatorLon != null) {
                String.format(
                    Locale.US, "%.6f, %.6f（%s）",
                    operatorLat!!, operatorLon!!, operatorLocationTypeText
                )
            } else null
        )
        line("操控者高度", operatorAltGeo?.let { Format.meters(it) })
        line(
            "作业区域",
            if (areaCount > 0) {
                "半径 ${areaRadius} m · 高 " + (areaCeiling?.let { Format.meters(it) } ?: "—")
            } else null
        )
        line("自我描述", selfId)
        line("操控者 ID", operatorId)
        line("EU 类别/等级", if (categoryEU > 0) "$categoryEU / $classEU" else null)
        line("最近更新", Format.time(lastSeen))
        val macLine = mac?.let { "MAC：$it\n" } ?: ""
        return sb.toString() + macLine
    }
}

object Format {
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun time(ts: Long): String = timeFmt.format(Date(ts))

    fun distance(m: Double?): String? {
        if (m == null) return null
        return if (m < 1000) "${m.toInt()} m" else String.format(Locale.US, "%.2f km", m / 1000.0)
    }

    fun meters(v: Double): String = String.format(Locale.US, "%.1f m", v)
}
