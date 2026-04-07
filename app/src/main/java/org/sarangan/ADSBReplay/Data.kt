package org.sarangan.ADSBReplay

import kotlin.math.PI

object Data {


    @Volatile var currentPoint = 0
    @Volatile var seekBarPoint = 0
    @Volatile var seekBarMoved = false
    var numOfPoints = 0
    val trackPoints: MutableList<TrackPoint> = mutableListOf()


    @Volatile var sentOwnshipCount = 0
    @Volatile var sentGeoAltCount = 0
    @Volatile var sentTrafficCount = 0
    @Volatile var sentUplinkCount = 0

    var totalOwnshipCount = 0
    var totalGeoAltCount = 0
    var totalTrafficCount = 0
    var totalUplinkCount = 0

    var serviceStartTime: Long = 0
    var trackStartTime: Long = 0

    @Volatile
    var currentGeoAltMeters: Double? = null

    var replayStartEpoch: Long = 0L
    var replayEndEpoch: Long = 0L
    var replayDurationMs: Long = 0L

    @Volatile var GDL90ReplayServiceIsRunning = false
    @Volatile var stopService = false

    val timedPackets: MutableList<TimedPacket> = mutableListOf()
    val replayEvents: MutableList<ReplayEvent> = mutableListOf()

    // For seek: trackpoint index -> first replay event index at that same time
    val trackPointToReplayEventIndex: MutableList<Int> = mutableListOf()

    const val TYPE_HEARTBEAT = 0
    const val TYPE_UPLINK = 7
    const val TYPE_OWNSHIP = 10
    const val TYPE_OWNSHIP_GEO_ALT = 11
    const val TYPE_TRAFFIC = 20

    fun recomputeSentCountersUpToEvent(eventIndexExclusive: Int) {
        sentOwnshipCount = 0
        sentGeoAltCount = 0
        sentTrafficCount = 0
        sentUplinkCount = 0
        currentGeoAltMeters = null

        val limit = eventIndexExclusive.coerceIn(0, replayEvents.size)
        for (i in 0 until limit) {
            when (replayEvents[i].type) {
                TYPE_OWNSHIP -> sentOwnshipCount++
                TYPE_OWNSHIP_GEO_ALT -> {
                    sentGeoAltCount++

                    val bytes = replayEvents[i].bytes
                    if (bytes.size >= 4 && (bytes[0].toInt() and 0xFF) == 0x7E) {
                        val geoAlt5Ft =
                            ((bytes[2].toInt() and 0xFF) shl 8) or
                                    (bytes[3].toInt() and 0xFF)
                        currentGeoAltMeters = (geoAlt5Ft * 5.0) / 3.28084
                    }
                }
                TYPE_TRAFFIC -> sentTrafficCount++
                TYPE_UPLINK -> sentUplinkCount++
            }
        }
    }

    class TrackPoint {
        var epoch: Long = 0
        var lat: Double = 0.0
        var lon: Double = 0.0
        var speed: Float = 0.0F
        var altitude: Float = 0.0F   // meters from <ele>
        var trueCourse: Float = 0.0F
    }

    data class TimedPacket(
        val epoch: Long,
        val type: Int,
        val bytes: ByteArray
    )

    data class ReplayEvent(
        val relativeTimeMs: Long,
        val type: Int,
        val priority: Int,
        val bytes: ByteArray,
        val sourceTrackPointIndex: Int? = null
    )

    fun clearAll() {
        currentPoint = 0
        seekBarPoint = 0
        seekBarMoved = false
        numOfPoints = 0

        currentGeoAltMeters = null

        trackPoints.clear()
        timedPackets.clear()
        replayEvents.clear()
        trackPointToReplayEventIndex.clear()

        replayStartEpoch = 0L
        replayEndEpoch = 0L
        replayDurationMs = 0L

        serviceStartTime = 0L
        trackStartTime = 0L
        stopService = false

        sentOwnshipCount = 0
        sentGeoAltCount = 0
        sentTrafficCount = 0
        sentUplinkCount = 0

        totalOwnshipCount = 0
        totalGeoAltCount = 0
        totalTrafficCount = 0
        totalUplinkCount = 0

    }

    fun buildReplayTimeline() {
        replayEvents.clear()
        trackPointToReplayEventIndex.clear()

        if (trackPoints.isEmpty() && timedPackets.isEmpty()) {
            replayStartEpoch = 0L
            replayEndEpoch = 0L
            replayDurationMs = 0L
            return
        }

        val minTrackEpoch = trackPoints.minOfOrNull { it.epoch }
        val maxTrackEpoch = trackPoints.maxOfOrNull { it.epoch }
        val minTimedEpoch = timedPackets.minOfOrNull { it.epoch }
        val maxTimedEpoch = timedPackets.maxOfOrNull { it.epoch }

        replayStartEpoch = listOfNotNull(minTrackEpoch, minTimedEpoch).minOrNull() ?: 0L
        replayEndEpoch = listOfNotNull(maxTrackEpoch, maxTimedEpoch).maxOrNull() ?: replayStartEpoch
        replayDurationMs = (replayEndEpoch - replayStartEpoch).coerceAtLeast(0L)

        // 1) Heartbeats every 1 second from start to end inclusive.
        var hbEpoch = replayStartEpoch
        while (hbEpoch <= replayEndEpoch) {
            replayEvents.add(
                ReplayEvent(
                    relativeTimeMs = hbEpoch - replayStartEpoch,
                    type = TYPE_HEARTBEAT,
                    priority = priorityForType(TYPE_HEARTBEAT),
                    bytes = GDL90.heartbeatPacket(hbEpoch),
                    sourceTrackPointIndex = null
                )
            )
            hbEpoch += 1000L
        }

        // 2) Ownship (type 10) synthesized from trkpt lat/lon/ele/speed/course.
        for (i in trackPoints.indices) {
            val tp = trackPoints[i]
            val prev = if (i > 0) trackPoints[i - 1] else tp
            val next = if (i + 1 < trackPoints.size) trackPoints[i + 1] else tp
            val vsFpm = computeVerticalSpeedFpm(prev, tp, next)

            replayEvents.add(
                ReplayEvent(
                    relativeTimeMs = tp.epoch - replayStartEpoch,
                    type = TYPE_OWNSHIP,
                    priority = priorityForType(TYPE_OWNSHIP),
                    bytes = GDL90.ownshipPacket(
                        latDeg = tp.lat,
                        lonDeg = tp.lon,
                        altitudeMeters = tp.altitude,
                        speedMps = tp.speed,
                        trueCourseDeg = tp.trueCourse,
                        verticalSpeedFpm = vsFpm,
                        callsign = "N12345",
                        participantAddress = 0xABCDEF
                    ),
                    sourceTrackPointIndex = i
                )
            )
        }

        // 3) Timed packets parsed from GPX.
        for (packet in timedPackets) {
            replayEvents.add(
                ReplayEvent(
                    relativeTimeMs = packet.epoch - replayStartEpoch,
                    type = packet.type,
                    priority = priorityForType(packet.type),
                    bytes = packet.bytes,
                    sourceTrackPointIndex = null
                )
            )
        }

        replayEvents.sortWith(
            compareBy<ReplayEvent> { it.relativeTimeMs }
                .thenBy { it.priority }
        )

        // Build seek map for each trackpoint.
        for (i in trackPoints.indices) {
            val rel = trackPoints[i].epoch - replayStartEpoch
            val idx = replayEvents.indexOfFirst { event ->
                event.relativeTimeMs == rel && event.sourceTrackPointIndex == i
            }.let { if (it >= 0) it else replayEvents.indexOfFirst { it.relativeTimeMs >= rel }.coerceAtLeast(0) }

            trackPointToReplayEventIndex.add(idx)
        }

        totalOwnshipCount = replayEvents.count { it.type == TYPE_OWNSHIP }
        totalGeoAltCount = replayEvents.count { it.type == TYPE_OWNSHIP_GEO_ALT }
        totalTrafficCount = replayEvents.count { it.type == TYPE_TRAFFIC }
        totalUplinkCount = replayEvents.count { it.type == TYPE_UPLINK }

        sentOwnshipCount = 0
        sentGeoAltCount = 0
        sentTrafficCount = 0
        sentUplinkCount = 0


    }

    private fun priorityForType(type: Int): Int {
        return when (type) {
            TYPE_HEARTBEAT -> 0
            TYPE_OWNSHIP -> 1
            TYPE_OWNSHIP_GEO_ALT -> 2
            TYPE_TRAFFIC -> 3
            TYPE_UPLINK -> 4
            else -> 99
        }
    }

    private fun computeVerticalSpeedFpm(
        prev: TrackPoint,
        current: TrackPoint,
        next: TrackPoint
    ): Int {
        val p1: TrackPoint
        val p2: TrackPoint

        if (next.epoch != current.epoch) {
            p1 = current
            p2 = next
        } else if (current.epoch != prev.epoch) {
            p1 = prev
            p2 = current
        } else {
            return 0
        }

        val dtSec = (p2.epoch - p1.epoch).toDouble() / 1000.0
        if (dtSec <= 0.0) return 0

        val dzMeters = (p2.altitude - p1.altitude).toDouble()
        return (dzMeters * 3.28084 * 60.0 / dtSec).toInt()
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
    return this / 180.0 * PI
}

fun Double.toDeg(): Double {
    return this / PI * 180.0
}

fun Float.toFt(): Double {
    return (this * 32.8084).toInt() / 10.0
}

fun Double.toFt(): Double {
    return (this * 3.28084 * 10.0).toInt() / 10.0
}

