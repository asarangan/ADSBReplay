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

                while (eventIndex < Data.replayEvents.size) {
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

                    sendPacket(
                        socket,
                        loopback,
                        GDL90.UDP_PORT,
                        event.bytes
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

                            // Fully framed packet:
                            // [0]=0x7E, [1]=0x0B, [2]=alt MSB, [3]=alt LSB, ...
                            if (event.bytes.size >= 9 &&
                                (event.bytes[0].toInt() and 0xFF) == 0x7E &&
                                (event.bytes[1].toInt() and 0xFF) == 0x0B
                            ) {
                                val geoAlt5Ft =
                                    ((event.bytes[2].toInt() and 0xFF) shl 8) or
                                            (event.bytes[3].toInt() and 0xFF)

                                Data.currentGeoAltMeters =
                                    (geoAlt5Ft * 5.0) / 3.28084
                            }
                        }

                        Data.TYPE_TRAFFIC -> {
                            Data.sentTrafficCount++
                        }

                        Data.TYPE_UPLINK -> {
                            Data.sentUplinkCount++
                        }
                    }

                    Log.d(
                        TAG,
                        "Replay eventIndex=$eventIndex " +
                                "type=${event.type} " +
                                "t=${event.relativeTimeMs} " +
                                "currentPoint=${Data.currentPoint} " +
                                "traf=${Data.sentTrafficCount}/${Data.totalTrafficCount} " +
                                "uplink=${Data.sentUplinkCount}/${Data.totalUplinkCount}"
                    )

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