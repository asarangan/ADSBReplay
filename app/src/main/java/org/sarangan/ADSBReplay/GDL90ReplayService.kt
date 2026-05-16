package org.sarangan.ADSBReplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Context.NOTIFICATION_SERVICE
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.sarangan.ADSBReplay.GDL90.distanceNm
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class GDL90ReplayService : Service() {

    inner class MyServiceBinder : Binder()
    private val myBinder = MyServiceBinder()

    override fun onBind(intent: Intent?): IBinder {
        return myBinder
    }

    override fun onCreate() {
        Log.d(TAG, "GDL90ReplayService onCreate start")
        super.onCreate()
        Log.d(TAG, "GDL90ReplayService onCreate exit")
    }

    override fun onDestroy() {
        Log.d(TAG, "GDL90ReplayService onDestroy start")
        super.onDestroy()
        Log.d(TAG, "GDL90ReplayService onDestroy exit")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "GDL90ReplayService onStartCommand")

        val notification = TrackPlayServiceNotification().getNotification(
            "GDL90 Replay is Running",
            applicationContext
        )

        ServiceCompat.startForeground(
            this,
            1,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )

        Thread {
            val loopback = InetAddress.getByName("127.0.0.1")
            val socket = DatagramSocket()
            val logTraffic = false
            val logUplink = false
            val logGPS = false

            try {
                if (Data.replayEvents.isEmpty()) {
                    Log.w(TAG, "No replay events available")
                    stopSelf()
                    return@Thread
                }

                Data.GDL90ReplayServiceIsRunning = true

                // Cold start always begins from the first replay event.
                var eventIndex = 0
                var replayBaseElapsed = SystemClock.elapsedRealtime()

                // Reset progress counters at replay start.
                Data.sentOwnshipCount = 0
                Data.sentGeoAltCount = 0
                Data.sentTrafficCount = 0
                Data.sentUplinkCount = 0
                Data.currentGeoAltMeters = null

                var replaySeq = 0
                var uplinkOrdinal = 0
                var trafficOrdinal = 0
                while (eventIndex < Data.replayEvents.size) {
                    val seq = replaySeq++

                    if (Data.stopService) break

                    if (Data.seekBarMoved) {
                        val seekPoint = Data.seekBarPoint.coerceIn(
                            0,
                            (Data.numOfPoints - 1).coerceAtLeast(0)
                        )

                        Data.currentPoint = seekPoint
                        eventIndex = getEventIndexForTrackPoint(seekPoint)
                        Data.recomputeSentCountersUpToEvent(eventIndex)
                        Data.seekBarMoved = false

                        replayBaseElapsed = SystemClock.elapsedRealtime() -
                                Data.replayEvents[eventIndex].relativeTimeMs
                    }

                    val event = Data.replayEvents[eventIndex]

                    waitUntilTargetTime(
                        event.relativeTimeMs,
                        replayBaseElapsed
                    )

                    if (Data.stopService) break
                    if (Data.seekBarMoved) continue

                    //Traffic logging and skipping logic
                    if (event.type == Data.TYPE_TRAFFIC) {

                        val trafLatLon = GDL90.trafficLatLonFromLoggedPacket(event.bytes)
                        val hexHead = event.bytes.take(8)
                            .joinToString("") { "%02X".format(it.toInt() and 0xFF) }
                        val hexTail = event.bytes.takeLast(8)
                            .joinToString("") { "%02X".format(it.toInt() and 0xFF) }

                        val skipAll = false  //Set this true to skip every traffic

                        val skipGroup = setOf(0)//setOf(8334, 18600, 48820)

                        if (skipAll || (eventIndex in skipGroup)) {
                            if (logTraffic) {
                                Log.d(
                                    TAG,
                                    "Skipping Traffic TXSEQ=$seq GPXIDX=$eventIndex TRAFFIC_ORD=$trafficOrdinal " +
                                            "time=${event.relativeTimeMs} rawLen=${event.bytes.size} " +
                                            "head=$hexHead tail=$hexTail"
                                )
                            }
                            eventIndex++
                            continue
                        }


                        val rawCallsign =
                            GDL90.trafficCallsignFromLoggedPacket(event.bytes)

                        val trafficCallsign =
                            Data.normalizeTailNumber(rawCallsign)

                        val addr =
                            GDL90.trafficAddressFromLoggedPacket(event.bytes)

                        val filterTail =
                            Data.normalizeTailNumber(Data.myTailNumber)

                        val callsignMatches =
                            filterTail.isNotEmpty() && trafficCallsign == filterTail

                        val rawCallsignContainsFilter =
                            filterTail.isNotEmpty() &&
                                    rawCallsign?.contains(filterTail, ignoreCase = true) == true

                        if ((callsignMatches || rawCallsignContainsFilter) && addr != null) {
                            Data.filteredTrafficAddresses.add(addr)
                        }

                        val addressMatches =
                            addr != null && Data.filteredTrafficAddresses.contains(addr)

                        if (callsignMatches || rawCallsignContainsFilter || addressMatches) {
                            if (logTraffic) {
                                Log.d(
                                    TAG,
                                    "FILTERED traffic: raw=$rawCallsign " +
                                            "callsign=$trafficCallsign " +
                                            "filter=$filterTail " +
                                            "addr=${addr?.let { "%06X".format(it) } ?: "NULL"} " +
                                            "reason=${
                                                when {
                                                    callsignMatches -> "CALLSIGN"
                                                    rawCallsignContainsFilter -> "RAW_CALLSIGN"
                                                    else -> "ADDRESS"
                                                }
                                            } " +
                                            "eventIndex=$eventIndex"
                                )
                            }

                            eventIndex++
                            continue
                        }

//                        if (trafLatLon != null && Data.currentPoint in Data.trackPoints.indices) {
//                            val own = Data.trackPoints[Data.currentPoint]
//
//                            val distNm = distanceNm(
//                                own.lat,
//                                own.lon,
//                                trafLatLon.lat,
//                                trafLatLon.lon
//                            )
//
//                            if (distNm < 0.5) {
//                                val rawCallsign = GDL90.trafficCallsignFromLoggedPacket(event.bytes)
//                                val normCallsign = Data.normalizeTailNumber(rawCallsign)
//                                val addr = GDL90.trafficAddressFromLoggedPacket(event.bytes)
//
//                                if (logTraffic) {
//                                    Log.e(
//                                        TAG,
//                                        "SUSPECTED OWNSHIP TRAFFIC (BASED ON DISTANCE): " +
//                                                "distNm=${"%.3f".format(distNm)} " +
//                                                "raw=$rawCallsign norm=$normCallsign " +
//                                                "addr=${addr?.let { "%06X".format(it) } ?: "NULL"} " +
//                                                "eventIndex=$eventIndex"
//                                    )
//                                }
//                                eventIndex++
//                                continue
//                            }
//                        }
//
//                        if (addr == 0xAA3637) {
//                            Log.e(TAG, "AA3637 ENCOUNTERED eventIndex=$eventIndex")
//                        }


                        trafficOrdinal++

                        if (logTraffic){
                            Log.d(
                                TAG,
                                "Traffic TXSEQ=$seq GPXIDX=$eventIndex TRAFFIC_ORD=$trafficOrdinal " +
                                        "time=${event.relativeTimeMs} rawLen=${event.bytes.size} " +
                                        "addr=${addr?.let { "%06X".format(it) } ?: "NULL"} " +
                                        "callsign=$trafficCallsign " +
                                        "head=$hexHead tail=$hexTail"
                            )
                        }
                    }



                    //Uplink logging and skipping logic
                   if (event.type == Data.TYPE_UPLINK) {
                       val hexHead = event.bytes.take(16).joinToString("") { "%02X".format(it.toInt() and 0xFF) }
                       val hexTail = event.bytes.takeLast(16).joinToString("") { "%02X".format(it.toInt() and 0xFF) }
                       var skipThis = false
                       val skipAll = false  //Set this true to skip every uplink

                       //Known bad packets
                       //if (eventIndex in setOf(8334,18600,48820)) {
                       if (eventIndex in setOf(0) ){
                           skipThis = true
                       }

                       if (skipAll){
                           skipThis = true
                       }

                       if (skipThis) {
                           if (logUplink) {
                               Log.d(
                                   TAG,
                                   "SKIPPING Uplink TXSEQ=$seq GPXIDX=$eventIndex UPLINK_ORD=$uplinkOrdinal " +
                                           "time=${event.relativeTimeMs} rawLen=${event.bytes.size} " +
                                           "head=$hexHead tail=$hexTail"
                               )
                           }
                           eventIndex++
                           continue
                       }

                       uplinkOrdinal++
                       if (logUplink) {
                           Log.d(
                               TAG,
                               "Uplink TXSEQ=$seq GPXIDX=$eventIndex UPLINK_ORD=$uplinkOrdinal " +
                                       "time=${event.relativeTimeMs} rawLen=${event.bytes.size} " +
                                       "head=$hexHead tail=$hexTail"
                           )
                       }
                    }



                    val bytesToSend: ByteArray? =
                        when (event.type) {
                            Data.TYPE_TRAFFIC,
                            Data.TYPE_UPLINK,
                            Data.TYPE_OWNSHIP_GEO_ALT -> GDL90.frameLoggedPacket(event.bytes)

                            else -> event.bytes
                        }

                    if (bytesToSend == null) {
                        Log.w(
                            TAG,
                            "SKIP malformed packet eventIndex=$eventIndex " +
                                    "type=${event.type} rawLen=${event.bytes.size}"
                        )
                        Log.d(TAG, "SKIP TXSEQ=$seq GPXIDX=$eventIndex UPLINK_ORD=$uplinkOrdinal")
                        eventIndex++
                        continue
                    }

                    if (event.type == Data.TYPE_TRAFFIC) {
//                        val sentCallsign =
//                            Data.normalizeTailNumber(
//                                GDL90.trafficCallsignFromLoggedPacket(event.bytes)
//                            )
//
//                        Log.d(
//                            TAG,
//                            "SENDING TRAFFIC: callsign=$sentCallsign eventIndex=$eventIndex"
//                        )
                    }


                    //Log.d(TAG, "SENT TXSEQ=$seq GPXIDX=${eventIndex}type=${event.type} sendLen=${bytesToSend.size}")

                    sendPacket(
                        socket,
                        loopback,
                        GDL90.UDP_PORT,
                        bytesToSend
                    )

                    when (event.type) {
                        Data.TYPE_OWNSHIP -> {
                            Data.sentOwnshipCount++

                            event.sourceTrackPointIndex?.let { idx ->
                                Data.currentPoint = idx
                                Data.trackStartTime = Data.trackPoints[idx].epoch
                                Data.serviceStartTime = System.currentTimeMillis()
                            }
                        }

                        Data.TYPE_OWNSHIP_GEO_ALT -> {
                            Data.sentGeoAltCount++

                            val hexHead = event.bytes.take(8)
                                .joinToString("") { "%02X".format(it.toInt() and 0xFF) }

                            if (event.bytes.size < 7) {
                                Log.w(
                                    TAG,
                                    "Geo altitude packet shorter than expected payload+CRC: " +
                                            "len=${event.bytes.size} head=$hexHead"
                                )
                            }

                            // Decode for display. Only the first 3 bytes are needed:
                            // [0]=0x0B, [1]=alt MSB, [2]=alt LSB
                            if (event.bytes.size >= 3 &&
                                (event.bytes[0].toInt() and 0xFF) == 0x0B
                            ) {
                                val geoAlt5Ft =
                                    ((event.bytes[1].toInt() and 0xFF) shl 8) or
                                            (event.bytes[2].toInt() and 0xFF)

                                Data.currentGeoAltMeters =
                                    (geoAlt5Ft * 5.0) / 3.28084

                                Log.d(
                                    TAG,
                                    "Geo altitude decoded: raw5ft=$geoAlt5Ft " +
                                            "meters=${Data.currentGeoAltMeters} head=$hexHead"
                                )
                            } else {
                                Log.w(
                                    TAG,
                                    "Could not decode geo altitude: len=${event.bytes.size} head=$hexHead"
                                )
                            }
                        }

                        Data.TYPE_TRAFFIC -> {
                            Data.sentTrafficCount++
                        }

                        Data.TYPE_UPLINK -> {
                            Data.sentUplinkCount++

                        }
                    }

                    eventIndex++
                }
            } finally {
                socket.close()
                Data.GDL90ReplayServiceIsRunning = false
                stopSelf()
            }
        }.start()

        return START_NOT_STICKY
    }

    private fun getEventIndexForTrackPoint(trackPointIndex: Int): Int {
        return if (trackPointIndex in Data.trackPointToReplayEventIndex.indices) {
            Data.trackPointToReplayEventIndex[trackPointIndex]
        } else {
            0
        }
    }

    private fun waitUntilTargetTime(
        relativeTimeMs: Long,
        replayBaseElapsed: Long
    ) {
        while (true) {
            if (Data.stopService || Data.seekBarMoved) return

            val now = SystemClock.elapsedRealtime()
            val target = replayBaseElapsed + relativeTimeMs
            val waitMs = target - now

            if (waitMs <= 0L) return

            val chunk = minOf(waitMs, 50L)
            Thread.sleep(chunk)
        }
    }

    private fun sendPacket(
        socket: DatagramSocket,
        address: InetAddress,
        port: Int,
        bytes: ByteArray
    ) {
        val packet = DatagramPacket(bytes, bytes.size, address, port)
        socket.send(packet)
    }
}

class TrackPlayServiceNotification {
    private val channelID = "SERVICESTACK_CHANNEL_ID"
    private val channelName = "SERVICESTACK_CHANNEL_NAME"

    fun getNotification(message: String, trackPlayContext: Context): Notification {
        (trackPlayContext.getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(
                NotificationChannel(
                    channelID,
                    channelName,
                    NotificationManager.IMPORTANCE_HIGH
                )
            )

        val builder = NotificationCompat.Builder(trackPlayContext, channelID)
        builder.setContentTitle(message)
        builder.setContentText("GDL90 replay is running")
        builder.setSmallIcon(R.drawable.ic_launcher_foreground)
        builder.priority = NotificationCompat.PRIORITY_HIGH
        return builder.build()
    }
}

