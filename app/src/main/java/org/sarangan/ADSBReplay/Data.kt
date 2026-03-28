package org.sarangan.ADSBReplay

import kotlin.math.PI

object Data {
    var currentPoint = 0
    var seekBarPoint = 0
    var seekBarMoved = false
    var numOfPoints = 0
    val trackPoints: MutableList<TrackPoint> = mutableListOf()

    var serviceStartTime: Long = 0
    var trackStartTime: Long = 0

    var GDL90ReplayServiceIsRunning = false
    var stopService = false

    class TrackPoint {
        var epoch: Long = 0
        var lat: Double = 0.0
        var lon: Double = 0.0
        var speed: Float = 0.0F
        var altitude: Float = 0.0F
        var trueCourse: Float = 0.0F

        val trafficPackets: MutableList<ByteArray> = mutableListOf()
    }
}

fun Float.toKts(): Float {
    return ((this * 19.4384).toInt() / 10.0).toFloat()
}

fun Float.toM(): Float {
    return (this * 180.0 / PI * 60.0 * 1852.0).toFloat()
}

fun Float.toMph(): Float {
    return ((this * 22.3694).toInt() / 10.0).toFloat()
}

fun Double.toRad(): Double {
    return (this / 180.0 * PI)
}

fun Double.toDeg(): Double {
    return (this / PI * 180.0)
}

fun Float.toFt(): Double {
    return (this * 32.8084).toInt() / 10.0
}