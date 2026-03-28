package org.sarangan.ADSBReplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Context.NOTIFICATION_SERVICE
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import android.content.pm.ServiceInfo
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.math.roundToInt

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
                var pt = Data.currentPoint
                Data.GDL90ReplayServiceIsRunning = true

                while (pt < Data.numOfPoints - 1) {
                    if (Data.seekBarMoved) {
                        pt = Data.seekBarPoint
                        Data.seekBarMoved = false
                        Data.trackStartTime = Data.trackPoints[pt].epoch
                        Data.serviceStartTime = System.currentTimeMillis()
                    }

                    Data.currentPoint = pt

                    val current = Data.trackPoints[pt]
                    val next = if (pt + 1 < Data.numOfPoints) Data.trackPoints[pt + 1] else current
                    val prev = if (pt > 0) Data.trackPoints[pt - 1] else current

                    val vsFpm = computeVerticalSpeedFpm(prev, current, next)

                    sendPacket(
                        socket,
                        loopback,
                        GDL90.UDP_PORT,
                        GDL90.heartbeatPacket(System.currentTimeMillis())
                    )

                    sendPacket(
                        socket,
                        loopback,
                        GDL90.UDP_PORT,
                        GDL90.ownshipPacket(
                            latDeg = current.lat,
                            lonDeg = current.lon,
                            altitudeMeters = current.altitude,
                            speedMps = current.speed,
                            trueCourseDeg = current.trueCourse,
                            verticalSpeedFpm = vsFpm,
                            callsign = "N12345",
                            participantAddress = 0xABCDEF
                        )
                    )

                    sendPacket(
                        socket,
                        loopback,
                        GDL90.UDP_PORT,
                        GDL90.ownshipGeoAltitudePacket(
                            altitudeMeters = current.altitude,
                            vfomMeters = 10,
                            verticalWarning = false
                        )
                    )

                    // Send all traffic packets saved in this GPX point.
                    for (trafficPacket in current.trafficPackets) {
                        Log.d(TAG, "Sending traffic packet of ${trafficPacket.size} bytes")
                        sendPacket(socket, loopback, GDL90.UDP_PORT, trafficPacket)
                    }

                    val now = System.currentTimeMillis()
                    val correction = (now - Data.serviceStartTime) -
                            (Data.trackPoints[pt].epoch - Data.trackStartTime)

                    var sleepTime =
                        (Data.trackPoints[pt + 1].epoch - Data.trackPoints[pt].epoch) - correction
                    if (sleepTime < 0L) sleepTime = 0L

                    Log.d(
                        TAG,
                        "Replay pt=$pt traffic=${current.trafficPackets.size} " +
                                "sleep=$sleepTime lat=${current.lat} lon=${current.lon}"
                    )

                    Thread.sleep(sleepTime)
                    pt++

                    if (Data.stopService) break
                }
            } finally {
                socket.close()
                Data.GDL90ReplayServiceIsRunning = false
                stopSelf()
            }
        }.start()

        return START_NOT_STICKY
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

    private fun computeVerticalSpeedFpm(
        prev: Data.TrackPoint,
        current: Data.TrackPoint,
        next: Data.TrackPoint
    ): Int {
        val p1: Data.TrackPoint
        val p2: Data.TrackPoint

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
        val fpm = dzMeters * 3.28084 * 60.0 / dtSec
        return fpm.roundToInt()
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