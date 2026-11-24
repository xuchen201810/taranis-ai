package com.dronesignal.app.signal

/**
 * Heuristics to identify nearby WiFi networks / BLE devices that likely
 * belong to a drone or its remote controller, based on SSID/name and the
 * vendor OUI prefix of the MAC address.
 */
object DroneDetect {

    private val KEYWORDS = listOf(
        "TELLO", "DJI", "MAVIC", "PHANTOM", "SPARK", "INSPIRE", "AVATA", "MINI",
        "PARROT", "BEBOP", "ANAFI", "AR.DRONE", "ARDISCOVERY",
        "HOLYBRO", "PIXHAWK", "PX4", "ARDUPILOT", "ARDUPILOTMEGA", "CUAV", "MRO",
        "HUBSAN", "SYMA", "WLTOYS", "JJRC", "EACHINE", "BETAFPV", "EMAX", "DYS",
        "WALKERA", "XROCKER", "DRONE", "UAV", "QUAD", "COPEX", "GOPRO",
        "YUNEEC", "TYPHOON", "HPLUSON", "AUTEL", "EVONANO", "LITEBEE",
        "FPV", "RUNCAM", "CADDX", "FOXEER", "TBS", "IMMERSIONRC", "SKYZONE",
        "SIYI", "HERELINK", "ZHUOYI", "SKYDROID", "SIYI", "CUAV"
    )

    // Known drone / drone-peripheral vendor OUI prefixes (lowercase, no colons).
    private val OUI_PREFIXES = setOf(
        "60601f", "9003b7", "3c1ebf", "34d262", "34d263",  // DJI
        "00121c", "00267e",                                     // Parrot
        "000f5e", "001986", "001255",                          // various OEM drones
        "847ebb", "a0143d", "a4cf12",                          // drone/FPV
        "240ac4", "246fd3",                                    // CADDX / FPV
        "98cdac", "9801a7",                                    // Walkera / Hubsan
        "e0b9e5", "d89685", "d43639",                          // misc
        "0013ef", "001a7c"
    )

    fun checkWifi(ssid: String?, bssid: String?): Pair<Boolean, String?> {
        val s = ssid.orEmpty().uppercase()
        for (k in KEYWORDS) {
            if (s.contains(k)) return true to "SSID:$k"
        }
        val mac = bssid.orEmpty().replace(":", "").lowercase()
        if (mac.length >= 6 && OUI_PREFIXES.contains(mac.substring(0, 6))) {
            return true to "OUI:${bssid?.take(8)}"
        }
        // DJI Tello style: SSID starts with "TELLO-" or "TELLO_".
        if (s.startsWith("TELLO")) return true to "SSID:TELLO"
        return false to null
    }

    fun checkBle(name: String?, address: String?): Pair<Boolean, String?> {
        val n = name.orEmpty().uppercase()
        if (n.isNotEmpty()) {
            for (k in KEYWORDS) {
                if (n.contains(k)) return true to "NAME:$k"
            }
            if (n.startsWith("TELLO")) return true to "NAME:TELLO"
        }
        val mac = address.orEmpty().replace(":", "").lowercase()
        if (mac.length >= 6 && OUI_PREFIXES.contains(mac.substring(0, 6))) {
            return true to "OUI:${address?.take(8)}"
        }
        return false to null
    }
}
