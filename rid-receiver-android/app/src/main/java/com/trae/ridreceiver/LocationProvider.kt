package com.trae.ridreceiver

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper

/**
 * 观察者自身位置（用于计算与无人机的距离/方位）。
 * 不依赖 Google Play 服务，GPS/网络定位都尝试。
 */
class LocationProvider(private val context: Context) : LocationListener {

    private val manager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    @Volatile
    var lastKnown: Location? = null
        private set

    private var started = false

    @SuppressLint("MissingPermission")
    fun start() {
        if (started) return
        started = true
        val m = manager ?: return
        lastKnown = bestLastKnown()
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                @Suppress("DEPRECATION")
                m.requestLocationUpdates(provider, 2000L, 1f, this, Looper.getMainLooper())
            } catch (_: Exception) {
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!started) return
        started = false
        try {
            manager?.removeUpdates(this)
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun bestLastKnown(): Location? {
        val m = manager ?: return null
        var best: Location? = null
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            val l = try {
                m.getLastKnownLocation(provider)
            } catch (_: Exception) {
                null
            }
            if (l != null && (best == null || l.time > best.time)) best = l
        }
        return best
    }

    override fun onLocationChanged(location: Location) {
        lastKnown = location
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
}
