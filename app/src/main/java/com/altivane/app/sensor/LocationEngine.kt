package com.altivane.app.sensor

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle

/**
 * GPS 采集引擎：框架 LocationManager（免 GMS，华为机可用），1Hz。
 */
class LocationEngine(private val ctx: Context) {

    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var listener: LocationListener? = null

    fun gpsEnabled(): Boolean =
        lm.isProviderEnabled(LocationManager.GPS_PROVIDER)

    fun start(onLoc: (Location) -> Unit): Boolean {
        if (!gpsEnabled()) return false
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) = onLoc(location)
            override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        listener = l
        lm.requestLocationUpdates(
            LocationManager.GPS_PROVIDER, 1000L, 0f, l, ctx.mainLooper
        )
        return true
    }

    fun stop() {
        listener?.let { lm.removeUpdates(it) }
        listener = null
    }
}
