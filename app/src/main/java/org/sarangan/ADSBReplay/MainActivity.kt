package org.sarangan.ADSBReplay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.IOException
import java.util.Date
import kotlin.concurrent.thread

const val TAG: String = "GPS"

class MainActivity : AppCompatActivity() {

    @Volatile
    private var uiUpdaterStarted = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.d(TAG, "POST_NOTIFICATIONS granted = $granted")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "MainActivity onCreate - start")
        super.onCreate(savedInstanceState)

        setContentView(R.layout.layout)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val gpxFilePicker =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
                uri?.let { openGpxFile(it) }
            }

        val gpxReadFileButton: Button = findViewById(R.id.buttonFileOpen)
        gpxReadFileButton.setOnClickListener {
            if (Data.GDL90ReplayServiceIsRunning) {
                Data.stopService = true
            }
            gpxFilePicker.launch("*/*")
        }

        Log.d(TAG, "MainActivity onCreate - exit")
    }

    private fun openGpxFile(uri: Uri) {
        var inputStream = contentResolver.openInputStream(uri)
        val bufferedReader = BufferedReader(inputStream?.reader())

        var speedExists = false
        var line: String?

        do {
            line = bufferedReader.readLine()
            if (line?.contains("<speed>", ignoreCase = true) == true) {
                speedExists = true
                break
            }
        } while (line != null)

        inputStream?.close()

        inputStream = contentResolver.openInputStream(uri)
        try {
            val parser = XmlPullParserHandler()
            parser.parse(inputStream, speedExists)

            when (parser.returnCode) {
                0 -> {
                    Data.currentPoint = 0
                    Data.numOfPoints = Data.trackPoints.size

                    Toast.makeText(
                        baseContext,
                        "Read ${Data.numOfPoints} points, ${Data.replayEvents.size} replay events",
                        Toast.LENGTH_LONG
                    ).show()

                    refreshUiFromData()

                    if (Data.GDL90ReplayServiceIsRunning) {
                        Data.stopService = true
                        Thread.sleep(200)
                    }

                    if (Data.replayEvents.isNotEmpty() && Data.numOfPoints > 0) {
                        Data.stopService = false
                        val intentService = Intent(baseContext, GDL90ReplayService::class.java)
                        Log.d(
                            TAG,
                            "Run - Starting Foreground Service with ${Data.replayEvents.size} replay events"
                        )
                        ContextCompat.startForegroundService(baseContext, intentService)
                    }
                }

                1 -> {
                    Toast.makeText(baseContext, "Invalid File", Toast.LENGTH_SHORT).show()
                    Data.clearAll()
                    refreshUiFromData()
                }

                else -> {
                    Toast.makeText(baseContext, "Unable to read file", Toast.LENGTH_SHORT).show()
                    Data.clearAll()
                    refreshUiFromData()
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
            Toast.makeText(baseContext, "File read error", Toast.LENGTH_SHORT).show()
            Data.clearAll()
            refreshUiFromData()
        } finally {
            inputStream?.close()
        }
    }

    override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? {
        Log.d(TAG, "MainActivity onCreateView - start")
        val v = super.onCreateView(name, context, attrs)
        Log.d(TAG, "MainActivity onCreateView - exit")
        return v
    }

    override fun onStart() {
        Log.d(TAG, "MainActivity onStart - start")
        super.onStart()
        Log.d(TAG, "MainActivity onStart - exit")
    }

    override fun onResume() {
        Log.d(TAG, "MainActivity onResume - start")
        super.onResume()

        val seekBar: SeekBar = findViewById(R.id.seekBar)

        seekBar.max = if (Data.numOfPoints > 1) {
            Data.numOfPoints - 1
        } else {
            0
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (Data.numOfPoints <= 0) {
                    return
                }

                if (fromUser) {
                    val clamped = progress.coerceIn(0, Data.numOfPoints - 1)
                    Data.seekBarPoint = clamped
                    Data.seekBarMoved = true

                    // Give immediate visual feedback in the UI,
                    // while the service catches up to the new replay position.
                    Data.currentPoint = clamped
                }

                refreshUiFromData()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
            }
        })

        refreshUiFromData()
        startUiUpdaterIfNeeded()

        Log.d(TAG, "MainActivity onResume - exit")
    }

    private fun refreshUiFromData() {
        val seekBar: SeekBar = findViewById(R.id.seekBar)
        val tvPoint: TextView = findViewById(R.id.tvPoint)
        val tvTime: TextView = findViewById(R.id.tvTime)
        val tvAlt: TextView = findViewById(R.id.tvAlt)
        val tvSpeed: TextView = findViewById(R.id.tvSpeed)
        val trackPlot = findViewById<GPSTrackPlot.GPSTrackPlot>(R.id.plot)

        if (Data.numOfPoints <= 0 || Data.trackPoints.isEmpty()) {
            seekBar.progress = 0
            tvPoint.text = "0"
            tvTime.text = ""
            tvAlt.text = ""
            tvSpeed.text = ""

            trackPlot.setTrackData(Data)
            trackPlot.makeBitmap = true
            trackPlot.setCirclePoint(0)
            trackPlot.postInvalidate()
            return
        }

        val pointIndex = Data.currentPoint.coerceIn(0, Data.numOfPoints - 1)
        val tp = Data.trackPoints[pointIndex]

        if (seekBar.progress != pointIndex) {
            seekBar.progress = pointIndex
        }

        tvPoint.text = pointIndex.toString()
        tvTime.text = Date(tp.epoch).toString()
        tvAlt.text = tp.altitude.toFt().toString()
        tvSpeed.text = tp.speed.toKts().toString()

        trackPlot.setTrackData(Data)
        trackPlot.makeBitmap = true
        trackPlot.setCirclePoint(pointIndex)
        trackPlot.postInvalidate()
    }

    private fun startUiUpdaterIfNeeded() {
        if (uiUpdaterStarted) return
        uiUpdaterStarted = true

        thread(start = true, name = "ui-updater-thread") {
            while (true) {
                try {
                    runOnUiThread {
                        refreshUiFromData()
                    }
                    Thread.sleep(100)
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun onStop() {
        Log.d(TAG, "MainActivity onStop - start")
        super.onStop()
        Log.d(TAG, "MainActivity onStop - exit")
    }

    override fun onPause() {
        Log.d(TAG, "MainActivity onPause - start")
        super.onPause()
        Log.d(TAG, "MainActivity onPause - exit")
    }

    override fun onRestart() {
        Log.d(TAG, "MainActivity onRestart - start")
        super.onRestart()
        Log.d(TAG, "MainActivity onRestart - exit")
    }

    override fun onDestroy() {
        Log.d(TAG, "MainActivity onDestroy - start")
        super.onDestroy()
        Data.stopService = true
        Log.d(TAG, "MainActivity onDestroy - exit")
    }
}
