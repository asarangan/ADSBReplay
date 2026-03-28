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

            var eventType: Int = parser.next()
            var text: String = ""
            var trackPoint = Data.TrackPoint()

            Data.trackPoints.clear()

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tagName: String? = parser.name

                if (eventType == XmlPullParser.START_TAG &&
                    tagName.equals("trkpt", ignoreCase = true)
                ) {
                    trackPoint = Data.TrackPoint()
                    trackPoint.lat = parser.getAttributeValue(null, "lat").toDouble()
                    trackPoint.lon = parser.getAttributeValue(null, "lon").toDouble()
                }

                if (eventType == XmlPullParser.TEXT) {
                    text = parser.text ?: ""
                }

                if (eventType == XmlPullParser.END_TAG) {
                    when {
                        tagName.equals("ele", ignoreCase = true) -> {
                            trackPoint.altitude = text.toFloatOrNull() ?: 0.0F
                        }

                        tagName.equals("speed", ignoreCase = true) -> {
                            trackPoint.speed = text.toFloatOrNull() ?: 0.0F
                        }

                        tagName.equals("time", ignoreCase = true) -> {
                            for (fmt in simpleDateFormats) {
                                try {
                                    val parsed = fmt.parse(text)
                                    if (parsed != null) {
                                        trackPoint.epoch = parsed.time
                                        break
                                    }
                                } catch (_: ParseException) {
                                }
                            }
                        }

                        // Handle ADS-B traffic packets stored inside GPX extensions.
                        // Match both "adsb:packet" and local-name-only "packet".
                        tagName.equals("adsb:packet", ignoreCase = true) ||
                                tagName.equals("packet", ignoreCase = true) -> {
                            val packet = decodeHexPacket(text)
                            if (packet != null) {
                                trackPoint.trafficPackets.add(packet)
                            }
                        }

                        tagName.equals("trkpt", ignoreCase = true) -> {
                            val trueCourse = if (Data.trackPoints.isNotEmpty()) {
                                trueCourse(trackPoint)
                            } else {
                                0.0F
                            }
                            trackPoint.trueCourse = trueCourse

                            if (!speedExists) {
                                trackPoint.speed = if (Data.trackPoints.isNotEmpty()) {
                                    speed(trackPoint)
                                } else {
                                    0.0F
                                }
                            }

                            Data.trackPoints.add(trackPoint)
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

    private fun decodeHexPacket(raw: String): ByteArray? {
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