package org.sarangan.ADSBReplay

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.InputStream
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.*

class XmlPullParserHandler {

    var returnCode: Int = 0

    fun parse(inputStream: InputStream?, speedExists: Boolean) {
        val simpleDateFormats = arrayListOf(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SX", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSX", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX", Locale.US)
        )

        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser: XmlPullParser = factory.newPullParser()
            parser.setInput(inputStream, null)

            Data.trackPoints.clear()

            var eventType = parser.eventType
            var text = ""

            var currentTrackPoint: Data.TrackPoint? = null

            // Detached ADS-B event state
            var currentEventTime: Long? = null
            var currentEventType: String? = null
            var currentPacketHex: String? = null

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tagName = parser.name ?: ""

                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when {
                            tagName.equals("trkpt", ignoreCase = true) -> {
                                currentTrackPoint = Data.TrackPoint()
                                currentTrackPoint.lat =
                                    parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                                currentTrackPoint.lon =
                                    parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                            }

                            tagName.equals("adsb:event", ignoreCase = true) ||
                                    tagName.equals("event", ignoreCase = true) -> {
                                currentEventTime = parseTime(
                                    parser.getAttributeValue(null, "time"),
                                    simpleDateFormats
                                )
                                currentEventType = parser.getAttributeValue(null, "type")
                                currentPacketHex = null
                            }
                        }
                    }

                    XmlPullParser.TEXT -> {
                        text = parser.text ?: ""
                    }

                    XmlPullParser.END_TAG -> {
                        when {
                            tagName.equals("time", ignoreCase = true) -> {
                                if (currentTrackPoint != null) {
                                    currentTrackPoint.epoch = parseTime(text, simpleDateFormats) ?: 0L
                                }
                            }

                            tagName.equals("ele", ignoreCase = true) -> {
                                if (currentTrackPoint != null) {
                                    currentTrackPoint.altitude = text.toFloatOrNull() ?: 0.0F
                                }
                            }

                            tagName.equals("speed", ignoreCase = true) -> {
                                if (currentTrackPoint != null) {
                                    currentTrackPoint.speed = text.toFloatOrNull() ?: 0.0F
                                }
                            }

                            tagName.equals("trkpt", ignoreCase = true) -> {
                                val tp = currentTrackPoint
                                if (tp != null) {
                                    tp.trueCourse = if (Data.trackPoints.isNotEmpty()) {
                                        trueCourse(tp)
                                    } else {
                                        0.0F
                                    }

                                    if (!speedExists) {
                                        tp.speed = if (Data.trackPoints.isNotEmpty()) {
                                            speed(tp)
                                        } else {
                                            0.0F
                                        }
                                    }

                                    Data.trackPoints.add(tp)
                                }
                                currentTrackPoint = null
                            }

                            tagName.equals("adsb:packet", ignoreCase = true) ||
                                    tagName.equals("packet", ignoreCase = true) -> {
                                currentPacketHex = text.trim()
                            }

                            tagName.equals("adsb:event", ignoreCase = true) ||
                                    tagName.equals("event", ignoreCase = true) -> {
                                val packet = decodeHexPacket(currentPacketHex)
                                val eventTime = currentEventTime
                                val eventTypeValue = currentEventType?.lowercase()

                                if (packet != null && eventTime != null) {
                                    val tp = findTrackPointByEpoch(eventTime)
                                    if (tp != null) {
                                        when (eventTypeValue) {
                                            "traffic" -> tp.trafficPackets.add(packet)
                                            "uplink" -> tp.uplinkPackets.add(packet)
                                        }
                                    }
                                }

                                currentEventTime = null
                                currentEventType = null
                                currentPacketHex = null
                            }
                        }
                    }
                }

                eventType = parser.next()
            }
        } catch (e: XmlPullParserException) {
            e.printStackTrace()
            returnCode = 1
        } catch (e: IOException) {
            e.printStackTrace()
            returnCode = 2
        }
    }

    private fun parseTime(
        raw: String?,
        formats: List<SimpleDateFormat>
    ): Long? {
        if (raw == null) return null
        for (fmt in formats) {
            try {
                val d = fmt.parse(raw)
                if (d != null) return d.time
            } catch (_: ParseException) {
            }
        }
        return null
    }

    private fun findTrackPointByEpoch(epoch: Long): Data.TrackPoint? {
        // Exact match is expected for your saved format.
        for (tp in Data.trackPoints) {
            if (tp.epoch == epoch) return tp
        }
        return null
    }

    private fun decodeHexPacket(raw: String?): ByteArray? {
        if (raw == null) return null
        val hex = raw.trim().replace("\\s+".toRegex(), "")
        if (hex.isEmpty() || hex.length % 2 != 0) return null

        return try {
            ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun trueCourse(trackPoint: Data.TrackPoint): Float {
        val prev = Data.trackPoints[Data.trackPoints.size - 1]
        val lon2 = trackPoint.lon.toRad()
        val lon1 = prev.lon.toRad()
        val lat2 = trackPoint.lat.toRad()
        val lat1 = prev.lat.toRad()

        return ((atan2(
            sin(lon2 - lon1) * cos(lat2),
            cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(lon2 - lon1)
        ) + 2.0 * PI) % (2.0 * PI)).toDeg().toFloat()
    }

    private fun speed(trackPoint: Data.TrackPoint): Float {
        val prev = Data.trackPoints[Data.trackPoints.size - 1]

        val dtSec = (trackPoint.epoch - prev.epoch).toDouble() / 1000.0
        if (dtSec <= 0.0) return 0.0F

        val lat1 = prev.lat.toRad()
        val lon1 = prev.lon.toRad()
        val lat2 = trackPoint.lat.toRad()
        val lon2 = trackPoint.lon.toRad()

        val dLat = lat2 - lat1
        val dLon = lon2 - lon1

        val a = sin(dLat / 2.0).pow(2.0) +
                cos(lat1) * cos(lat2) * sin(dLon / 2.0).pow(2.0)

        val clampedA = a.coerceIn(0.0, 1.0)
        val c = 2.0 * atan2(sqrt(clampedA), sqrt(1.0 - clampedA))

        val earthRadiusM = 6371000.0
        val distanceM = earthRadiusM * c
        val speedMps = distanceM / dtSec

        return if (speedMps.isFinite()) speedMps.toFloat() else 0.0F
    }
}