package com.trae.ridreceiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RidDecoderTest {

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    private fun le32(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
    )

    private fun altEnc(m: Double) = Math.round((m + 1000.0) / 0.5).toInt()

    private fun ascii(s: String, len: Int): ByteArray {
        val b = ByteArray(len)
        for (i in 0 until minOf(len, s.length)) b[i] = s[i].code.toByte()
        return b
    }

    private fun basicId(id: String = "1581F5BKD225F001ABCD"): ByteArray =
        byteArrayOf(0x02, 0x12) + ascii(id, 20) + ByteArray(3)

    private fun location(speedMult: Boolean = false, speedEnc: Int = 60): ByteArray {
        val b = ByteArray(25)
        b[0] = 0x12 // Location, proto 2
        b[1] = ((2 shl 4) or (if (speedMult) 1 else 0)).toByte()
        b[2] = 90.toByte()
        b[3] = speedEnc.toByte()
        b[4] = 3 // 1.5 m/s
        le32(Math.round(31.230416 * 1e7).toInt()).copyInto(b, 5)
        le32(Math.round(121.473701 * 1e7).toInt()).copyInto(b, 9)
        le16(altEnc(100.0)).copyInto(b, 13)
        le16(altEnc(110.0)).copyInto(b, 15)
        le16(altEnc(50.0)).copyInto(b, 17)
        b[19] = ((4 shl 4) or 10).toByte()
        b[20] = ((4 shl 4) or 3).toByte()
        le16(1234).copyInto(b, 21)
        b[23] = 1
        return b
    }

    private fun system(): ByteArray {
        val b = ByteArray(25)
        b[0] = 0x42 // System, proto 2
        b[1] = 0x01
        le32(Math.round(31.230000 * 1e7).toInt()).copyInto(b, 2)
        le32(Math.round(121.470000 * 1e7).toInt()).copyInto(b, 6)
        le16(1).copyInto(b, 10)
        b[12] = 5
        le16(altEnc(120.0)).copyInto(b, 13)
        le16(altEnc(0.0)).copyInto(b, 15)
        b[17] = 0x10
        le16(altEnc(1.5)).copyInto(b, 18)
        le32(1730000000).copyInto(b, 20)
        return b
    }

    private fun pack(msgs: List<ByteArray>): ByteArray {
        val out = ArrayList<Byte>()
        out.add(0xF2.toByte())
        out.add(25)
        out.add(msgs.size.toByte())
        msgs.forEach { m -> m.forEach { out.add(it) } }
        return out.toByteArray()
    }

    private fun near(a: Double?, b: Double, eps: Double = 0.01): Boolean =
        a != null && abs(a - b) <= eps

    @Test
    fun basicIdDecodes() {
        val m = RidDecoder.decodeMessage(basicId(), 0)
        assertNotNull(m)
        assertEquals("1581F5BKD225F001ABCD", m!!.uasId)
        assertEquals(2, m.uaType)
        assertEquals(1, m.idType)
    }

    @Test
    fun locationDecodes() {
        val m = RidDecoder.decodeMessage(location(), 0)!!
        assertEquals(2, m.status)
        assertEquals(90, m.direction)
        assertTrue(near(m.speedH, 15.0))
        assertTrue(near(m.speedV, 1.5))
        assertTrue(near(m.lat, 31.230416, 1e-6))
        assertTrue(near(m.lon, 121.473701, 1e-6))
        assertTrue(near(m.altBaro, 100.0))
        assertTrue(near(m.altGeo, 110.0))
        assertTrue(near(m.height, 50.0))
        assertEquals(10, m.horizAcc)
        assertEquals(4, m.vertAcc)
        assertTrue(near(m.timestamp, 123.4))
    }

    /** SpeedMult=1 时必须用 0.75 倍率 + 63.75 偏移 */
    @Test
    fun speedMultScaling() {
        val m = RidDecoder.decodeMessage(location(speedMult = true, speedEnc = 60), 0)!!
        assertTrue("speedH=${m.speedH}", near(m.speedH, 108.75))
        val zero = RidDecoder.decodeMessage(location(speedMult = true, speedEnc = 0), 0)!!
        assertTrue("speedH0=${zero.speedH}", near(zero.speedH, 63.75))
    }

    @Test
    fun systemDecodes() {
        val m = RidDecoder.decodeMessage(system(), 0)!!
        assertTrue(near(m.operatorLat, 31.23, 1e-6))
        assertTrue(near(m.operatorLon, 121.47, 1e-6))
        assertEquals(50, m.areaRadius)
        assertTrue(near(m.areaCeiling, 120.0))
        assertEquals(1, m.categoryEU)
    }

    @Test
    fun messagePackAndBleWifiTransports() {
        val p = pack(listOf(basicId(), location(), system()))
        assertEquals(78, p.size)
        assertEquals(3, RidDecoder.decodePayload(p).size)

        // BLE service data: [0x0D][counter][msg]
        val sd = byteArrayOf(0x0D, 7) + location()
        val ble = RidDecoder.decodeBleServiceData(sd)!!
        assertEquals(RidSource.BLE, ble.source)
        assertEquals(7, ble.counter)
        assertEquals(1, ble.messages.size)

        // 原始广播：AD 结构包裹
        val sdPayload = byteArrayOf(0xFA.toByte(), 0xFF.toByte(), 0x0D, 9) + basicId()
        val adv = byteArrayOf((sdPayload.size + 1).toByte(), 0x16) + sdPayload
        val raw = RidDecoder.decodeBleAdvertising(adv)!!
        assertEquals("1581F5BKD225F001ABCD", raw.messages[0].uasId)

        // Wi-Fi 厂商 IE：OUI + type + counter + pack
        val ie = byteArrayOf(0xFA.toByte(), 0x0B, 0xBC.toByte(), 0x0D, 3) + p
        val wifi = RidDecoder.decodeWifiVendorIe(ie)!!
        assertEquals(RidSource.WIFI, wifi.source)
        assertEquals(3, wifi.messages.size)
    }

    @Test
    fun aggregatorMergesByMacAndUasId() {
        val store = DroneStore()
        store.ingest(listOf(RidDecoder.decodeMessage(basicId(), 0)!!), "AA:BB:CC:DD:EE:01", -55, RidSource.BLE)
        store.ingest(listOf(RidDecoder.decodeMessage(location(), 0)!!), "AA:BB:CC:DD:EE:01", -61, RidSource.BLE)
        store.ingest(listOf(RidDecoder.decodeMessage(system(), 0)!!), "AA:BB:CC:DD:EE:01", -58, RidSource.BLE)
        assertEquals(1, store.listSorted().size)
        val d = store.listSorted()[0]
        assertEquals("1581F5BKD225F001ABCD", d.uasId)
        assertTrue(d.hasLocation)
        assertTrue(d.hasSystem)
        assertEquals(-58, d.rssi)

        // 同 UAS ID、不同 MAC（双通道）仍合并
        store.ingest(listOf(RidDecoder.decodeMessage(basicId(), 0)!!), "11:22:33:44:55:66", -70, RidSource.WIFI)
        assertEquals(1, store.listSorted().size)
    }

    @Test
    fun garbageDoesNotCrash() {
        assertEquals(null, RidDecoder.decodeBleAdvertising(byteArrayOf(1, 2, 3)))
        assertEquals(null, RidDecoder.decodeMessage(byteArrayOf(0x12), 0))
        assertEquals(null, RidDecoder.decodeBleServiceData(byteArrayOf(0, 1, 2)))
    }
}
