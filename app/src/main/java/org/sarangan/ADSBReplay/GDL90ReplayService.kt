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

                var eventIndex = 0
                var replayBaseElapsed = SystemClock.elapsedRealtime()

                while (eventIndex < Data.replayEvents.size) {
                    if (Data.stopService) break

                    if (Data.seekBarMoved) {
                        val seekPoint = Data.seekBarPoint.coerceIn(0, (Data.numOfPoints - 1).coerceAtLeast(0))
                        Data.currentPoint = seekPoint
                        eventIndex = getEventIndexForTrackPoint(seekPoint)
                        Data.seekBarMoved = false
                        replayBaseElapsed = SystemClock.elapsedRealtime() -
                                Data.replayEvents[eventIndex].relativeTimeMs
                    }

                    val event = Data.replayEvents[eventIndex]
                    waitUntilTargetTime(event.relativeTimeMs, replayBaseElapsed)

                    if (Data.stopService) break

                    sendPacket(socket, loopback, GDL90.UDP_PORT, event.bytes)

                    if (event.type == Data.TYPE_OWNSHIP) {
                        event.sourceTrackPointIndex?.let { idx ->
                            Data.currentPoint = idx
                            Data.trackStartTime = Data.trackPoints[idx].epoch
                            Data.serviceStartTime = System.currentTimeMillis()
                        }
                    }

                    Log.d(
                        TAG,
                        "Replay eventIndex=$eventIndex type=${event.type} " +
                                "t=${event.relativeTimeMs} currentPoint=${Data.currentPoint}"
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

    private fun getStartEventIndexFromCurrentPoint(): Int {
        return if (Data.currentPoint in Data.trackPointToReplayEventIndex.indices) {
            Data.trackPointToReplayEventIndex[Data.currentPoint]
        } else {
            0
        }
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