package org.sarangan.ADSBReplay

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.net.Uri
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedReader
import java.io.IOException
import java.lang.Thread.sleep
import java.util.Date
import kotlin.Int


const val TAG:String = "GPS"


class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG,"MainActivity onCreate - start")
        super.onCreate(savedInstanceState)

        setContentView(R.layout.layout)

        //This is for reading the external file, and passing the Uri to the callback object (which is defined here within {}
        val gpxFilePicker = registerForActivityResult(ActivityResultContracts.GetContent())
        { uri: Uri? ->
            uri?.let {
                //We open the file only to check if it has a speed tag. Some GPX files do not contain a speed tag. If that is the case, we will have to calculate the speed.
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
                inputStream?.close()    //Close the file once the existence of the speed tag has been determined. InputStream cannot be rewound, so we will close and open it again.

                inputStream = contentResolver.openInputStream(uri)
                try {
                    val parser = XmlPullParserHandler() //This is where the XML tags are parsed.
                    parser.parse(inputStream, speedExists)
                    Data.currentPoint = 0
                    when (parser.returnCode) {
                        0 -> {          //return code of 0 from the XML parser means the file was read successfully
                            Data.numOfPoints = Data.trackPoints.size    //Data object is accessible globally.
                            Toast.makeText( //Show a toast message on how many tags were read
                                baseContext,
                                "Read ${Data.numOfPoints} points",
                                Toast.LENGTH_LONG
                            ).show()
                        }

                        1 -> {  //1 means there was some error in the file
                            Toast.makeText(baseContext, "Invalid File", Toast.LENGTH_SHORT).show()
                            Data.numOfPoints = 0
                        }
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }


        //This is the listener for the button that launches the file selector
        val gpxReadFileButton: Button = findViewById<Button>(R.id.buttonFileOpen)
        gpxReadFileButton.setOnClickListener {
            if (Data.GDL90ReplayServiceIsRunning){
                Data.stopService = true
            }
            gpxFilePicker.launch("*/*")
        }
        Log.d(TAG,"MainActivity onCreate - exit")
    }

    override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? {
        Log.d(TAG,"MainActivity onCreateView - start")
        val v = super.onCreateView(name, context, attrs)
        Log.d(TAG,"MainActivity onCreateView - exit")
        return v
    }


    override fun onStart() {
        Log.d(TAG,"MainActivity onStart - start")
        super.onStart()
        Log.d(TAG,"MainActivity onStart - exit")
    }


    override fun onResume() {
        Log.d(TAG,"MainActivity onResume - start")
        super.onResume()
        //We execute the operations in onResume instead of onCreate because onCreate doesn't get called when returning from the file picker.
        //onResume is also called during initial app start, so we need to handle the case depending on if the data object has been initialized or not.
        //Data is an object class (doesn't need instantiation).
        //I suppose we could also do all of this under onStart or onRestart as well.

        //Set the length of the seekbar. During initial app invocation, the datafile will be empty, so we set the length to 0.
        val seekBar: SeekBar = findViewById<SeekBar>(R.id.seekBar)
        Log.d(TAG,"Seekbar set: ${Data.numOfPoints}")
        seekBar.max = if (Data.numOfPoints > 1) {
            Data.numOfPoints - 1
        } else {
            0
        }

        //The seekbar shows the current position, but the user can also drag it to change the current position.
        //When user drags it, the listener will trigger p2 to true. We simply set a flag (which is seekBarMoved) and the new position, and let the
        //foreground Service take care of moving the data position. This listener is also triggered every time seekbar.progress is changed -
        //which is done through a foreground Service Thread that polls currentPosition and sets the seekbar position.
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(p0: SeekBar?, p1: Int, p2: Boolean) {
                if (p2) { //p2 will be true if the seekbar change was caused by the user dragging the bar. It would be false if the change was caused by the Thread.
                    Data.seekBarPoint = p1
                    Data.seekBarMoved = true
                    sleep(500)  //Sleep for 500ms to allow the GPSMockLocationService thread to use the seekBarPoint and update it to its currentPoint
                }
                //Update all the field values based on hte current point
                findViewById<SeekBar>(R.id.seekBar).progress = Data.currentPoint
                findViewById<TextView>(R.id.tvPoint).text =
                    Data.currentPoint.toString()
                findViewById<TextView>(R.id.tvTime).text =
                    Date(Data.trackPoints[Data.currentPoint].epoch).toString()
                findViewById<TextView>(R.id.tvAlt).text =
                    Data.trackPoints[Data.currentPoint].altitude.toFt().toString()
                findViewById<TextView>(R.id.tvSpeed).text =
                    Data.trackPoints[Data.currentPoint].speed.toKts().toString()
                findViewById<GPSTrackPlot.GPSTrackPlot>(R.id.plot)
                    .setCirclePoint(Data.currentPoint)
                findViewById<GPSTrackPlot.GPSTrackPlot>(R.id.plot).postInvalidate()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
            }
        }
        )

        //Next update the track plot.
        val trackPlot = findViewById<GPSTrackPlot.GPSTrackPlot>(R.id.plot)
        trackPlot.setTrackData(Data)
        trackPlot.makeBitmap = true
        trackPlot.setCirclePoint(Data.currentPoint)
        trackPlot.postInvalidate()


        //We launch a thread that continuously checks the data position (which is handled by the foreground Service Thread) and update the seekbar position. This will
        //automatically trigger the seekbar listener every time (once per 50ms).
        Thread {
            while (true) {
                seekBar.progress = Data.currentPoint
                sleep(50)
            }
        }.start()

        //If the GDL90ReplayService is not already running, and there is valid data, launch the foreground service.
        //If we open a second GPX file while one is already running, we don't want to launch a second service thread.
        if ((!Data.GDL90ReplayServiceIsRunning)&&(Data.numOfPoints > 1)) {
            Data.stopService = false
            val intentService = Intent(baseContext, GDL90ReplayService::class.java)
            Log.d(TAG,"Run - Starting Foreground Service")
            Data.serviceStartTime = System.currentTimeMillis()
            Data.trackStartTime = Data.trackPoints[0].epoch
            ContextCompat.startForegroundService(baseContext, intentService)
        }

        Log.d(TAG,"MainActivity onResume - exit")
    }

    override fun onStop() {
        Log.d(TAG,"MainActivity onStop - start")
        super.onStop()
        Log.d(TAG,"MainActivity onStop - exit")
    }

    override fun onPause() {
        Log.d(TAG,"MainActivity onPause - start")
        super.onPause()
        Log.d(TAG,"MainActivity onPause - exit")
    }

    override fun onRestart() {
        Log.d(TAG,"MainActivity onRestart - start")
        super.onRestart()
        Log.d(TAG,"MainActivity onRestart - exit")
    }

    override fun onDestroy() {
        Log.d(TAG,"MainActivity onDestroy - start")
        super.onDestroy()
        Data.stopService = true
        Log.d(TAG,"MainActivity onDestroy - exit")
    }

}
