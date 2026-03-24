package org.sarangan.ADSBReplay

import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.roundToInt

object GDL90 {
    private const val FLAG: Int = 0x7E
    private const val ESC: Int = 0x7D
    private const val ESC_XOR: Int = 0x20

    const val MSG_HEARTBEAT: Int = 0x00
    const val MSG_OWNSHIP: Int = 0x0A
    const val MSG_OWNSHIP_GEO_ALT: Int = 0x0B
    const val UDP_PORT: Int = 4000

    private val crc16Table: IntArray = IntArray(256).also { table ->
        for (i in 0..255) {
            var crc = (i shl 8) and 0xFFFF
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) {
                    ((crc shl 1) xor 0x1021) and 0xFFFF
                } else {
                    (crc shl 1) and 0xFFFF
                }
            }
            table[i] = crc and 0xFFFF
        }
    }

    /**
     * This matches the FAA GDL90 spec section 2.2.3:
     * crc = Crc16Table[crc >> 8] ^ (crc << 8) ^ block[i];
     */
    private fun crc16Gdl90(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            val byteVal = b.toInt() and 0xFF
            crc = crc16Table[(crc ushr 8) and 0xFF] xor ((crc shl 8) and 0xFFFF) xor byteVal
            crc = crc and 0xFFFF
        }
        return crc
    }

    private fun frame(payload: ByteArray): ByteArray {
        val crc = crc16Gdl90(payload)

        val clearWithCrc = ByteArrayOutputStream()
        clearWithCrc.write(payload)
        clearWithCrc.write(crc and 0xFF)                // LSB first
        clearWithCrc.write((crc ushr 8) and 0xFF)       // MSB second

        val framed = ByteArrayOutputStream()
        framed.write(FLAG)
        for (b in clearWithCrc.toByteArray()) {
            val v = b.toInt() and 0xFF
            if (v == FLAG || v == ESC) {
                framed.write(ESC)
                framed.write(v xor ESC_XOR)
            } else {
                framed.write(v)
            }
        }
        framed.write(FLAG)
        return framed.toByteArray()
    }

    fun heartbeatPacket(nowMillis: Long = System.currentTimeMillis()): ByteArray {
        val utcSeconds = secondsSinceUtcMidnight(nowMillis).coerceIn(0, 0x1FFFF)
        val status1 = 0x80 or 0x01   // GPS valid + UTC OK
        val status2 = ((utcSeconds shr 16) and 0x01) shl 7

        val payload = byteArrayOf(
            MSG_HEARTBEAT.toByte(),
            status1.toByte(),
            status2.toByte(),
            (utcSeconds and 0xFF).toByte(),
            ((utcSeconds shr 8) and 0xFF).toByte(),
            0x00,
            0x00
        )
        return frame(payload)
    }

    fun ownshipPacket(
        latDeg: Double,
        lonDeg: Double,
        altitudeMeters: Float,
        speedMps: Float,
        trueCourseDeg: Float,
        verticalSpeedFpm: Int,
        callsign: String = "N12345",
        participantAddress: Int = 0xABCDEF
    ): ByteArray {
        val lat24 = encodeLatLon24(latDeg)
        val lon24 = encodeLatLon24(lonDeg)

        val altitudeFt = altitudeMeters * 3.28084
        val alt12 = encodeAltitude25ft(altitudeFt)
        val hVelKt = encodeHorizontalVelocity(speedMps * 1.94384f)
        val vVel12 = encodeVerticalVelocity64fpm(verticalSpeedFpm)
        val track8 = encodeTrack(trueCourseDeg)

        val trafficAlertStatus = 0x0
        val addressType = 0x0
        val byte2 = ((trafficAlertStatus and 0x0F) shl 4) or (addressType and 0x0F)

        val nic = 0x9
        val nacp = 0x9
        val misc = 0b1001
        val emitterCategory = 0x01
        val callsignBytes = encodeCallsign(callsign)

        val byte12 = ((alt12 shr 4) and 0xFF)
        val byte13 = ((alt12 and 0x0F) shl 4) or (misc and 0x0F)
        val byte14 = ((nic and 0x0F) shl 4) or (nacp and 0x0F)
        val byte15 = ((hVelKt shr 4) and 0xFF)
        val byte16 = ((hVelKt and 0x0F) shl 4) or ((vVel12 shr 8) and 0x0F)
        val byte17 = (vVel12 and 0xFF)
        val byte28 = 0x00

        val bos = ByteArrayOutputStream()
        bos.write(MSG_OWNSHIP)
        bos.write(byte2)
        bos.write((participantAddress shr 16) and 0xFF)
        bos.write((participantAddress shr 8) and 0xFF)
        bos.write(participantAddress and 0xFF)

        bos.write((lat24 shr 16) and 0xFF)
        bos.write((lat24 shr 8) and 0xFF)
        bos.write(lat24 and 0xFF)

        bos.write((lon24 shr 16) and 0xFF)
        bos.write((lon24 shr 8) and 0xFF)
        bos.write(lon24 and 0xFF)

        bos.write(byte12)
        bos.write(byte13)
        bos.write(byte14)
        bos.write(byte15)
        bos.write(byte16)
        bos.write(byte17)
        bos.write(track8)
        bos.write(emitterCategory)
        bos.write(callsignBytes)
        bos.write(byte28)

        return frame(bos.toByteArray())
    }

    fun ownshipGeoAltitudePacket(
        altitudeMeters: Float,
        vfomMeters: Int = 10,
        verticalWarning: Boolean = false
    ): ByteArray {
        val altitudeFt = altitudeMeters * 3.28084
        val geoAlt5ft = (altitudeFt / 5.0).roundToInt().coerceIn(-32768, 32767)
        val geoAlt16 = geoAlt5ft and 0xFFFF

        val vfom = when {
            vfomMeters < 0 -> 0x7FFF
            vfomMeters >= 32766 -> 0x7FFE
            else -> vfomMeters
        }
        val verticalMetrics = ((if (verticalWarning) 1 else 0) shl 15) or (vfom and 0x7FFF)

        val payload = byteArrayOf(
            MSG_OWNSHIP_GEO_ALT.toByte(),
            ((geoAlt16 shr 8) and 0xFF).toByte(),
            (geoAlt16 and 0xFF).toByte(),
            ((verticalMetrics shr 8) and 0xFF).toByte(),
            (verticalMetrics and 0xFF).toByte()
        )

        return frame(payload)
    }

    private fun encodeLatLon24(deg: Double): Int {
        val scale = 8388608.0 / 180.0
        val raw = (deg * scale).roundToInt()
        return raw and 0xFFFFFF
    }

    private fun encodeAltitude25ft(altitudeFt: Double): Int {
        val v = ((altitudeFt + 1000.0) / 25.0).roundToInt()
        return when {
            v < 0 -> 0
            v > 0xFFE -> 0xFFE
            else -> v
        }
    }

    private fun encodeHorizontalVelocity(knots: Float): Int {
        if (!knots.isFinite()) return 0

        val v = knots.roundToInt()
        return when {
            v < 0 -> 0
            v >= 4094 -> 0xFFE
            else -> v
        }
    }

    private fun encodeVerticalVelocity64fpm(fpm: Int): Int {
        var q = (fpm / 64.0).roundToInt()
        if (q > 0x1FE) q = 0x1FE
        if (q < -0x1FE) q = -0x1FE
        return q and 0xFFF
    }

    private fun encodeTrack(trackDeg: Float): Int {
        if (!trackDeg.isFinite()) return 0

        var t = trackDeg
        while (t < 0f) t += 360f
        while (t >= 360f) t -= 360f
        return ((t / 360.0f) * 256.0f).roundToInt() and 0xFF
    }

    private fun encodeCallsign(s: String): ByteArray {
        val cleaned = s.uppercase()
            .map { ch ->
                when {
                    ch in 'A'..'Z' -> ch
                    ch in '0'..'9' -> ch
                    else -> ' '
                }
            }
            .joinToString("")
            .padEnd(8, ' ')
            .take(8)

        return cleaned.toByteArray(Charsets.US_ASCII)
    }

    private fun secondsSinceUtcMidnight(nowMillis: Long): Int {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = nowMillis
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val min = cal.get(Calendar.MINUTE)
        val sec = cal.get(Calendar.SECOND)
        return hour * 3600 + min * 60 + sec
    }
}