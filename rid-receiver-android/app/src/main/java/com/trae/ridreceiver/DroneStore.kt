package com.trae.ridreceiver

import android.location.Location

/**
 * 多条广播按 MAC / UAS ID 聚合成无人机记录。
 * BLE Legacy 每帧只广播一条消息，必须先累积再展示。
 */
class DroneStore {

    private val map = LinkedHashMap<String, Drone>()

    @Synchronized
    fun ingest(
        messages: List<OdidMessage>,
        mac: String?,
        rssi: Int,
        source: RidSource,
        now: Long = System.currentTimeMillis()
    ): Drone? {
        if (messages.isEmpty()) return null
        val uasId = messages.firstNotNullOfOrNull { it.uasId?.takeIf { id -> id.isNotBlank() } }

        // 1. 优先按 UAS ID 合并（BLE / Wi-Fi 双通道、随机 MAC）
        var drone: Drone? = null
        if (uasId != null) {
            drone = map.values.firstOrNull { it.uasId == uasId }
        }
        // 2. 再按来源+MAC
        val macKey = if (mac != null) "${source.name}:$mac" else null
        if (drone == null && macKey != null) drone = map[macKey]
        // 3. 已按 UAS ID 建过记录时，也能用 MAC 追加上下文帧
        if (drone == null && mac != null) {
            drone = map.values.firstOrNull { it.mac == mac }
        }

        // 4. 新建
        if (drone == null) {
            val key = if (uasId != null) "uas:$uasId" else (macKey ?: "unknown:$now")
            drone = Drone(key, uasId).also {
                it.firstSeen = now
                map[key] = it
            }
        }

        for (m in messages) drone.apply(m)

        drone.lastSeen = now
        drone.rssi = rssi
        drone.source = source
        if (mac != null) drone.mac = mac
        return drone
    }

    @Synchronized
    fun updateGeometry(observer: Location?) {
        if (observer == null) {
            map.values.forEach { it.distanceM = null; it.bearing = null }
            return
        }
        val results = FloatArray(3)
        for (d in map.values) {
            val lat = d.lat
            val lon = d.lon
            if (lat == null || lon == null) {
                d.distanceM = null
                d.bearing = null
                continue
            }
            Location.distanceBetween(observer.latitude, observer.longitude, lat, lon, results)
            d.distanceM = results[0].toDouble()
            d.bearing = results[1].toDouble()
        }
    }

    @Synchronized
    fun prune(timeoutMs: Long, now: Long = System.currentTimeMillis()) {
        val it = map.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value.lastSeen > timeoutMs) it.remove()
        }
    }

    @Synchronized
    fun listSorted(): List<Drone> {
        return map.values.sortedWith(
            compareBy<Drone> { it.distanceM ?: Double.MAX_VALUE }
                .thenByDescending { it.rssi }
        )
    }

    @Synchronized
    fun counts(): Pair<Int, Int> {
        var ble = 0
        var wifi = 0
        for (d in map.values) {
            if (d.source == RidSource.BLE) ble++ else wifi++
        }
        return ble to wifi
    }

    @Synchronized
    fun clear() = map.clear()
}
