package com.example.tracksim

import android.annotation.SuppressLint
import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.SystemClock
import android.util.Log

class MockLocationController(context: Context) {

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val activeProviders = mutableListOf<String>()
    private var installed = false

    companion object {
        private const val TAG = "MockLocation"
    }

    private fun candidateProviders(): List<String> {
        val list = mutableListOf(LocationManager.GPS_PROVIDER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(LocationManager.FUSED_PROVIDER)
        }
        list.add(LocationManager.NETWORK_PROVIDER)
        return list
    }

    @SuppressLint("MissingPermission")
    fun install(): Boolean {
        if (installed) return true
        var ok = false
        for (name in candidateProviders()) {
            try {
                try { lm.removeTestProvider(name) } catch (_: Exception) { }
                addTestProvider(name)
                lm.setTestProviderEnabled(name, true)
                activeProviders.add(name)
                ok = true
            } catch (e: Exception) {
                Log.w(TAG, "install($name) 失败: ${e.javaClass.simpleName} ${e.message}")
            }
        }
        installed = ok
        return ok
    }

    @Suppress("DEPRECATION")
    private fun addTestProvider(name: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val props = ProviderProperties.Builder()
                .setHasNetworkRequirement(false)
                .setHasSatelliteRequirement(name == LocationManager.GPS_PROVIDER)
                .setHasCellRequirement(false)
                .setHasMonetaryCost(false)
                .setHasAltitudeSupport(true)
                .setHasSpeedSupport(true)
                .setHasBearingSupport(true)
                .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                .setAccuracy(ProviderProperties.ACCURACY_FINE)
                .build()
            lm.addTestProvider(name, props)
        } else {
            lm.addTestProvider(
                name,
                false, false, false, false,
                true, true, true,
                Criteria.POWER_LOW,
                Criteria.ACCURACY_FINE
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun push(lat: Double, lng: Double, bearing: Float, speedMps: Float) {
        if (!installed || activeProviders.isEmpty()) return
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtimeNanos()

        for (name in activeProviders) {
            try {
                val loc = Location(name).apply {
                    latitude = lat
                    longitude = lng
                    this.speed = speedMps.coerceAtLeast(0f)
                    this.bearing = ((bearing % 360f) + 360f) % 360f
                    accuracy = 3f
                    altitude = 50.0
                    time = now
                    elapsedRealtimeNanos = elapsed
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        verticalAccuracyMeters = 1f
                        speedAccuracyMetersPerSecond = 0.5f
                        bearingAccuracyDegrees = 1f
                    }
                }
                lm.setTestProviderLocation(name, loc)
            } catch (e: Exception) {
                Log.w(TAG, "push($name) 失败: ${e.message}")
            }
        }
    }

    fun uninstall() {
        for (name in activeProviders) {
            try { lm.setTestProviderEnabled(name, false) } catch (_: Exception) { }
            try { lm.removeTestProvider(name) } catch (_: Exception) { }
        }
        activeProviders.clear()
        installed = false
    }
}
