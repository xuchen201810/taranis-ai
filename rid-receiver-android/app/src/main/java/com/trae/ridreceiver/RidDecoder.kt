package com.trae.ridreceiver

import kotlin.math.roundToInt

/**
 * ASTM F3411 / GB 42590 广播式远程识别解码器（Kotlin 版，与小程序 JS 版逻辑一致）。
 */
object RidDecoder {

    const val MSG_SIZE = 25

    const val MSG_BASIC_ID = 0
    const val MSG_LOCATION = 1
    const val MSG_AUTH = 2
    const val MSG_SELF_ID = 3
    const val MSG_SYSTEM = 4
    const val MSG_OPERATOR_ID = 5
    const val MSG_PACKED = 15

    val UA_TYPE = arrayOf(
        "未定义", "固定翼", "多旋翼", "旋翼机", "复合翼", "扑翼机", "滑翔机", "风筝",
        "自由气球", "系留气球", "飞艇", "自由落体", "火箭", "系留动力飞行器", "地面障碍物", "其它"
    )
    val STATUS = arrayOf("未声明", "地面", "空中", "紧急", "远程识别系统故障")
    val ID_TYPE = arrayOf("无", "序列号", "民航局登记号", "UTM 分配 UUID", "会话 ID")
    val LOCATION_TYPE = arrayOf("起飞点", "实时 GNSS", "固定位置")
    val HEIGHT_REF = arrayOf("相对起飞点", "相对地面")

    class Decoded(
        val source: RidSource,
        val counter: Int,
        val messages: List<OdidMessage>
    )

    // ------------------------------------------------------------ 基础读取

    private fun u16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun i32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or
            (b[o + 3].toInt() shl 24)

    private fun u32(b: ByteArray, o: Int): Long = i32(b, o).toLong() and 0xFFFFFFFFL

    private fun ascii(b: ByteArray, o: Int, len: Int): String {
        val sb = StringBuilder()
        for (i in 0 until len) {
            if (o + i >= b.size) break
            val c = b[o + i].toInt() and 0xFF
            if (c == 0) break
            if (c in 0x20..0x7E) sb.append(c.toChar())
        }
        return sb.toString().trim()
    }

    private fun round1(v: Double): Double = (v * 10.0).roundToInt() / 10.0

    private fun round2(v: Double): Double = (v * 100.0).roundToInt() / 100.0

    /** uint16 * 0.5 - 1000，0 / 0xFFFF 视为无效 */
    private fun alt(v: Int): Double? {
        if (v == 0 || v == 0xFFFF) return null
        return round1(v * 0.5 - 1000.0)
    }

    private fun latlon(v: Int): Double? {
        val d = v / 1e7
        if (d == 0.0 || d.isNaN() || d.isInfinite()) return null
        return (d * 1e7).roundToInt() / 1e7
    }

    // ------------------------------------------------------------ 单条消息

    fun decodeMessage(b: ByteArray, off: Int): OdidMessage? {
        if (b.size < off + MSG_SIZE) return null
        val header = b[off].toInt() and 0xFF
        val type = header shr 4
        val proto = header and 0x0F
        val m = OdidMessage()
        m.type = type
        m.proto = proto

        when (type) {
            MSG_BASIC_ID -> {
                val b1 = b[off + 1].toInt() and 0xFF
                m.uaType = b1 and 0x0F
                m.uaTypeText = UA_TYPE.getOrElse(m.uaType) { "未知" }
                m.idType = (b1 shr 4) and 0x0F
                m.idTypeText = ID_TYPE.getOrElse(m.idType) { "保留" }
                m.uasId = ascii(b, off + 2, 20)
            }

            MSG_LOCATION -> {
                val f = b[off + 1].toInt() and 0xFF
                val speedMult = f and 0x01
                val ewDirection = (f shr 1) and 0x01
                m.heightType = (f shr 2) and 0x01
                m.heightTypeText = HEIGHT_REF[m.heightType]
                m.status = (f shr 4) and 0x0F
                m.statusText = STATUS.getOrElse(m.status) { "未知" }

                var dir = (b[off + 2].toInt() and 0xFF) + if (ewDirection == 1) 180 else 0
                if (dir >= 360) dir -= 360
                m.direction = dir

                val speedEnc = b[off + 3].toInt() and 0xFF
                m.speedH = round2(
                    if (speedMult == 1) speedEnc * 0.75 + 63.75 else speedEnc * 0.25
                )
                m.speedV = round2(b[off + 4].toInt() * 0.5)

                m.lat = latlon(i32(b, off + 5))
                m.lon = latlon(i32(b, off + 9))
                m.altBaro = alt(u16(b, off + 13))
                m.altGeo = alt(u16(b, off + 15))
                m.height = alt(u16(b, off + 17))

                m.horizAcc = b[off + 19].toInt() and 0x0F
                m.vertAcc = (b[off + 19].toInt() shr 4) and 0x0F
                m.speedAcc = b[off + 20].toInt() and 0x0F
                m.baroAcc = (b[off + 20].toInt() shr 4) and 0x0F

                val ts = u16(b, off + 21)
                m.timestamp = if (ts == 0xFFFF) null else round1(ts / 10.0)
                m.tsAcc = b[off + 23].toInt() and 0x0F
            }

            MSG_SYSTEM -> {
                val s1 = b[off + 1].toInt() and 0xFF
                m.operatorLocationType = s1 and 0x03
                m.operatorLocationTypeText =
                    LOCATION_TYPE.getOrElse(m.operatorLocationType) { "保留" }
                m.classificationType = (s1 shr 2) and 0x07

                m.operatorLat = latlon(i32(b, off + 2))
                m.operatorLon = latlon(i32(b, off + 6))
                m.areaCount = u16(b, off + 10)
                m.areaRadius = (b[off + 12].toInt() and 0xFF) * 10
                m.areaCeiling = alt(u16(b, off + 13))
                m.areaFloor = alt(u16(b, off + 15))
                m.categoryEU = (b[off + 17].toInt() shr 4) and 0x0F
                m.classEU = b[off + 17].toInt() and 0x0F
                m.operatorAltGeo = alt(u16(b, off + 18))
                m.systemTimestamp = u32(b, off + 20)
            }

            MSG_SELF_ID -> {
                m.selfId = ascii(b, off + 2, 23)
            }

            MSG_OPERATOR_ID -> {
                m.operatorIdType = b[off + 1].toInt() and 0xFF
                m.operatorId = ascii(b, off + 2, 20)
            }

            MSG_AUTH -> {
                val a1 = b[off + 1].toInt() and 0xFF
                m.authType = (a1 shr 4) and 0x0F
                m.dataPage = a1 and 0x0F
            }

            MSG_PACKED -> {
                val single = b[off + 1].toInt() and 0xFF
                val count = b[off + 2].toInt() and 0xFF
                val list = ArrayList<OdidMessage>(count)
                if (single == MSG_SIZE) {
                    for (i in 0 until count) {
                        decodeMessage(b, off + 3 + i * MSG_SIZE)?.let { list.add(it) }
                    }
                }
                m.pack = list
            }

            else -> return null
        }
        return m
    }

    fun decodePayload(bytes: ByteArray): List<OdidMessage> {
        if (bytes.size < MSG_SIZE) return emptyList()
        val first = decodeMessage(bytes, 0) ?: return emptyList()
        return if (first.type == MSG_PACKED) first.pack else listOf(first)
    }

    // ------------------------------------------------------------ 传输解析

    /** BLE Service Data（UUID 0xFFFA 之后）：[0x0D][counter][payload] */
    fun decodeBleServiceData(sd: ByteArray): Decoded? {
        if (sd.size < 3) return null
        if ((sd[0].toInt() and 0xFF) != 0x0D) return null
        val messages = decodePayload(sd.copyOfRange(2, sd.size))
        if (messages.isEmpty()) return null
        return Decoded(RidSource.BLE, sd[1].toInt() and 0xFF, messages)
    }

    /** 原始 BLE 广播（AD structures） */
    fun decodeBleAdvertising(raw: ByteArray): Decoded? {
        if (raw.size < 4) return null
        var i = 0
        while (i < raw.size) {
            val len = raw[i].toInt() and 0xFF
            if (len == 0 || i + len >= raw.size) break
            val type = raw[i + 1].toInt() and 0xFF
            if (type == 0x16 && len >= 3 &&
                (raw[i + 2].toInt() and 0xFF) == 0xFA &&
                (raw[i + 3].toInt() and 0xFF) == 0xFF
            ) {
                val sd = raw.copyOfRange(i + 4, i + 1 + len)
                decodeBleServiceData(sd)?.let { return it }
            }
            i += 1 + len
        }
        return null
    }

    /** Wi-Fi Beacon 厂商 IE payload（element id/length 之后） */
    fun decodeWifiVendorIe(d: ByteArray): Decoded? {
        if (d.size < 6) return null
        if ((d[0].toInt() and 0xFF) != 0xFA ||
            (d[1].toInt() and 0xFF) != 0x0B ||
            (d[2].toInt() and 0xFF) != 0xBC
        ) return null
        if ((d[3].toInt() and 0xFF) != 0x0D) return null
        val messages = decodePayload(d.copyOfRange(5, d.size))
        if (messages.isEmpty()) return null
        return Decoded(RidSource.WIFI, d[4].toInt() and 0xFF, messages)
    }
}
