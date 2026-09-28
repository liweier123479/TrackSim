package com.example.tracksim

import kotlin.math.max

data class GeoPoint(val lat: Double, val lng: Double)

class TrackEngine {

    val points = mutableListOf<GeoPoint>()
    var loop = false
    var speedKmh = 5.0

    private var segLen = DoubleArray(0)
    private var cum = DoubleArray(0)

    var totalLength = 0.0
        private set

    data class Sample(
        val lat: Double,
        val lng: Double,
        val bearing: Double,
        val traveled: Double,
        val segIndex: Int,
        val finished: Boolean
    )

    fun setPoints(list: List<GeoPoint>) {
        points.clear()
        points.addAll(list)
        rebuild()
    }

    fun rebuild() {
        val n = points.size
        segLen = DoubleArray(max(0, n - 1))
        cum = DoubleArray(n)
        var acc = 0.0
        for (i in 0 until n - 1) {
            val d = GeoUtils.distance(
                points[i].lat, points[i].lng,
                points[i + 1].lat, points[i + 1].lng
            )
            segLen[i] = d
            acc += d
            cum[i + 1] = acc
        }
        totalLength = acc
    }

    fun lapSeconds(): Double {
        val v = speedKmh / 3.6
        return if (v > 0) totalLength / v else 0.0
    }

    fun sampleAt(distance: Double): Sample? {
        if (points.size < 2 || totalLength <= 0.0) return null

        var d = distance
        var finished = false
        if (loop) {
            d = ((d % totalLength) + totalLength) % totalLength
        } else if (d >= totalLength) {
            d = totalLength
            finished = true
        }

        var i = 0
        while (i < segLen.size - 1 && cum[i + 1] <= d) i++

        val len = segLen[i]
        val f = if (len <= 1e-9) 0.0 else ((d - cum[i]) / len).coerceIn(0.0, 1.0)
        val a = points[i]
        val b = points[i + 1]

        return Sample(
            lat = a.lat + (b.lat - a.lat) * f,
            lng = a.lng + (b.lng - a.lng) * f,
            bearing = GeoUtils.bearing(a.lat, a.lng, b.lat, b.lng),
            traveled = d,
            segIndex = i,
            finished = finished
        )
    }
}
