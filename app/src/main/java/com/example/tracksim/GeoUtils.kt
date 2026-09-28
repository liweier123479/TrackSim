package com.example.tracksim

import kotlin.math.*

object GeoUtils {
    const val M_PER_DEG_LAT = 111320.0

    fun metersPerDegLng(latDeg: Double): Double =
        M_PER_DEG_LAT * cos(Math.toRadians(latDeg))

    /** 球面距离（米） */
    fun distance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return 2 * R * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** 方位角（度，正北 0，顺时针） */
    fun bearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val la1 = Math.toRadians(lat1)
        val la2 = Math.toRadians(lat2)
        val dLng = Math.toRadians(lng2 - lng1)
        val y = sin(dLng) * cos(la2)
        val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLng)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }
}
