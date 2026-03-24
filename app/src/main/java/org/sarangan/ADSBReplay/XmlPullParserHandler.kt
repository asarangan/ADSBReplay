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

    //The XML is read into the Data object, which is declared globally.
    //Return code indicates whether conversion was successful
    var returnCode: Int = 0

    //This is the parser that processes the string that was read from the file
    fun parse(inputStream: InputStream?, speedExists:Boolean) {
        //Define the date format used by GPSLogger. Example: 2023-04-02T19:40:14Z. The X stands for time zone. SSS for fractional seconds
        val simpleDateFormats: ArrayList<SimpleDateFormat> = arrayListOf<SimpleDateFormat>(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SX", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSX", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX", Locale.US)
        )

        try {
            val factory = XmlPullParserFactory.newInstance()
            //Namespace allows the use of xmlns in the name tags
            factory.isNamespaceAware = true
            val parser: XmlPullParser = factory.newPullParser()
            //Attach the input stream to the parser. The second parameter is encoding, so it can be null
            parser.setInput(inputStream, null)
            //The reading process is initiated with next(). It returns the event type.
            //Event type is START_TAG, END_TAG, TEXT, etc.. The first event type is from the header. It will be updated during the loop
            var eventType: Int = parser.next()
            //Declare a variable to hold the text between the start and end tags
            lateinit var text: String
            //Declare an initial instance of Trackpoint. This will be used to save the header tags, but will be disposed when the trkpt tag is encountered
            var trackPoint = Data.TrackPoint()

            //This is the main loop. Keep reading until end of the document
            Data.trackPoints.clear() //If we are calling this parser a second time, we need to clear the array.
            while (eventType != XmlPullParser.END_DOCUMENT) {
                //Read the current tag name
                val tagName: String? = parser.name

                //If the tag is trkpt and it is the start tag, then create a new trackpoint and read the lat & lon attribute of the trkpt tag.
                // It will be like: <trkpt lat="39.58983167" lon="-84.22876667">
                if ((eventType == XmlPullParser.START_TAG) && (tagName.equals(
                        "trkpt",
                        ignoreCase = true
                    ))
                ) {
                    trackPoint =
                        Data.TrackPoint()  //Create an new track point instance. Discard the old.
                    trackPoint.lat = parser.getAttributeValue(null, "lat").toDouble()
                    trackPoint.lon = parser.getAttributeValue(null, "lon").toDouble()
                }

                //If the event type is not the start tag or end tag (ie middle text), then save this text for processing in the subsequent while loop
                //when the tag closes.
                if (eventType == XmlPullParser.TEXT) {
                    text = parser.text
                }
                //If it is an end tag, then depending on which end tag it is, we do different things
                if (eventType == XmlPullParser.END_TAG) {
                    when {
                        //If tag name is ele, then that is the altitude.
                        tagName.equals("ele", ignoreCase = true) -> trackPoint.altitude =
                            text.toFloat()
                        //If tag name is speed, then that is the speed in m/s
                        tagName.equals("speed", ignoreCase = true) -> trackPoint.speed =
                            text.toFloat()
                        //If tag name is time, then that is the time in the format specified in simpledateformat
                        //The time tag is also in the header tags, but this will get saved in the initial TrackPoint instance and will be discarded when the new TrackPoint instance is created
                        //We have several different time formats, varying in the number of decimal points for seconds. We will check them all.
                        tagName.equals("time", ignoreCase = true) -> {
                            for (i in 0 until simpleDateFormats.size) {
                                try {
                                    trackPoint.epoch = simpleDateFormats[i].parse(text).time
                                }
                                catch (e: ParseException){
                                    //Log.d(TAG,"Error detecting ${simpleDateFormats[i]}")
                                }
                            }
                        }
                        //If it is the end of the trkpt tag, we are done reading the data point, so calculate true course from the last point in the stack,
                        // but only if the trackpoint stack is not empty.
                        //If the track point stack if empty (ie this is the first point), then set the true course to 0
                        //If speedExists is false (ie the GPX file does not contain the <speed> tag, we need to calculate it
                        tagName.equals("trkpt", ignoreCase = true) -> {
                            val trueCourse: Float = if (Data.trackPoints.size > 1) {
                                trueCourse(trackPoint)
                            } else {
                                0.0F
                            }
                            //Save the true course into the track point instance
                            trackPoint.trueCourse = trueCourse

                            if (!speedExists) {
                                val speed = if (Data.trackPoints.size > 1) {
                                    speed(trackPoint)
                                } else {
                                    0.0F
                                }
                                trackPoint.speed = speed
                            }
                            //Next, push the track point into the stack.
                            Data.trackPoints.add(trackPoint)
                        }
                    }
                }
                //Read the next parsing event. The output of the next is the event type
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

    //The following function calculates the true course between the current point being read, and the last track point in the array.
    //This calculation is being done before loading the current track point into the array
    //The true course is returned from the function
    private fun trueCourse(trackPoint: Data.TrackPoint): Float {
        //Longitude of the current track point
        val lon2: Double = trackPoint.lon.toRad()
        //Longitude of the last track point in the array
        val lon1: Double = Data.trackPoints[Data.trackPoints.size - 1].lon.toRad()
        val lat2: Double = trackPoint.lat.toRad()
        val lat1: Double = Data.trackPoints[Data.trackPoints.size - 1].lat.toRad()
        //val geoField:GeomagneticField = GeomagneticField(lat1.toFloat(),lon1.toFloat(),0.0F,System.currentTimeMillis())
        //val magVar = geoField.getDeclination()    //in degrees. -ve for W. -4.8 in Dayton. We are not using this
        return ((atan2(                          //This is the true course formula based on lat/lon from Ed Williams
            sin(lon2 - lon1) * cos(lat2),
            cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(lon2 - lon1)
        ) + 2.0 * PI) % (2.0 * PI)).toDeg().toFloat()
    }

    //The following function calculate the speed between the current point being read, and the last point in the array.
    //This calculation is being done before loading the current track point into the array.
    //The speed (in m/s) is returned from the function
    private fun speed(trackPoint: Data.TrackPoint): Float {
        val prev = Data.trackPoints[Data.trackPoints.size - 1]

        val time2 = trackPoint.epoch
        val time1 = prev.epoch
        val dtSec = (time2 - time1).toDouble() * 1.0e-3

        if (dtSec <= 0.0) return 0.0F

        val lon2 = trackPoint.lon.toRad()
        val lon1 = prev.lon.toRad()
        val lat2 = trackPoint.lat.toRad()
        val lat1 = prev.lat.toRad()
        val arg = sin(lat1) * sin(lat2) + cos(lat1) * cos(lat2) * cos(lon1 - lon2)

        // Clamp to valid acos domain to avoid NaN from floating-point roundoff.
        val clampedArg = arg.coerceIn(-1.0, 1.0)
        val centralAngle = acos(clampedArg)
        if (!centralAngle.isFinite()) return 0.0F
        val speedMps = (centralAngle / dtSec).toFloat().toM()

        return if (speedMps.isFinite() && speedMps >= 0.0F) speedMps else 0.0F
    }
}