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

            Data.clearAll()

            var eventType = parser.eventType
            var text = ""

            var currentTrackPoint: Data.TrackPoint? = null

            var currentEventTime: Long? = null
            var currentEventType: String? = null
            var currentPacketHex: String? = null

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tagName = parser.name ?: ""

                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when {
                            tagName.equals("trkpt", ignoreCase = true) -> {
                                currentTrackPoint = Data.TrackPoint().apply {
                                    lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                                    lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                                }
                            }

                            tagName.equals("adsb:event", ignoreCase = true) ||
                                    tagName.equals("event", ignoreCase = true) -> {
                                currentEventTime = parseTime(
                                    parser.getAttributeValue(null, "time"),
                                    simpleDateFormats
                                )
                                currentEventType = parser.getAttributeValue(null, "type")?.trim()?.lowercase()
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
                                currentTrackPoint?.let { Data.trackPoints.add(it) }
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
                                val eventTypeValue = currentEventType

                                if (packet != null && eventTime != null) {
                                    when (eventTypeValue) {
                                        "traffic" -> Data.timedPackets.add(
                                            Data.TimedPacket(
                                                epoch = eventTime,
                                                type = Data.TYPE_TRAFFIC,
                                                bytes = packet
                                            )
                                        )

                                        "uplink" -> Data.timedPackets.add(
                                            Data.TimedPacket(
                                                epoch = eventTime,
                                                type = Data.TYPE_UPLINK,
                                                bytes = packet
                                            )
                                        )

                                        "ownship_geo_altitude" -> Data.timedPackets.add(
                                            Data.TimedPacket(
                                                epoch = eventTime,
                                                type = Data.TYPE_OWNSHIP_GEO_ALT,
                                                bytes = packet
                                            )
                                        )
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

            finalizeTrackPoints(speedExists)
            Data.numOfPoints = Data.trackPoints.size
            Data.buildReplayTimeline()

        } catch (e: XmlPullParserException) {
            e.printStackTrace()
            returnCode = 1
        } catch (e: IOException) {
            e.printStackTrace()
            returnCode = 2
        }
    }

    private fun finalizeTrackPoints(speedExists: Boolean) {
        for (i in Data.trackPoints.indices) {
            val tp = Data.trackPoints[i]

            tp.trueCourse = if (i > 0) {
                trueCourse(Data.trackPoints[i - 1], tp)
            } else {
                0.0F
            }

            if (!speedExists) {
                tp.speed = if (i > 0) {
                    speed(Data.trackPoints[i - 1], tp)
                } else {
                    0.0F
                }
            }
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

    private fun trueCourse(prev: Data.TrackPoint, current: Data.TrackPoint): Float {
        val lon2 = current.lon.toRad()
        val lon1 = prev.lon.toRad()
        val lat2 = current.lat.toRad()
        val lat1 = prev.lat.toRad()

        return ((atan2(
            sin(lon2 - lon1) * cos(lat2),
            cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(lon2 - lon1)
        ) + 2.0 * PI) % (2.0 * PI)).toDeg().toFloat()
    }

    private fun speed(prev: Data.TrackPoint, current: Data.TrackPoint): Float {
        val dtSec = (current.epoch - prev.epoch).toDouble() / 1000.0
        if (dtSec <= 0.0) return 0.0F

        val lat1 = prev.lat.toRad()
        val lon1 = prev.lon.toRad()
        val lat2 = current.lat.toRad()
        val lon2 = current.lon.toRad()

        val dLat = lat2 - lat1
        val dLon = lon2 - lon1

        val a = sin(dLat / 2.0).pow(2.0) +
                cos(lat1) * cos(lat2) * sin(dLon / 2.0).pow(2.0)
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        val distanceMeters = 6371000.0 * c

        return (distanceMeters / dtSec).toFloat()
    }
}