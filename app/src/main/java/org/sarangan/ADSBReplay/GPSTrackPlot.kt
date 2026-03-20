package org.sarangan.ADSBReplay

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class GPSTrackPlot {

    class GPSTrackPlot : View {
        constructor(context: Context) : super(context) {}
        constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {}
        constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(
            context,
            attrs,
            defStyleAttr
        ) {
        }

        constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int, defStyleRes: Int) : super(
            context,
            attrs,
            defStyleAttr,
            defStyleRes
        ) {
        }

        private var trackPaint: Paint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 5F
            color = Color.BLUE
        }
        private var circlePaint: Paint = Paint().apply {
            style = Paint.Style.FILL
            strokeWidth = 5F
            color = Color.RED
        }

        private lateinit var trackPath: Path
        private var xDataOffset: Float = 0F
        private var yDataOffset: Float = 0F
        private var xScale: Float = 0F
        private var yScale: Float = 0F
        lateinit var xDataPoints: FloatArray
        lateinit var yDataPoints: FloatArray
        private var circlePoint: Int = 0
        private var circleRadius: Float = 0F
        lateinit var bitmapObject: Bitmap
        var makeBitmap: Boolean = false


        fun setTrackData(data: Data) {
            if (data.numOfPoints > 0) {
                xDataPoints = FloatArray(data.numOfPoints)
                yDataPoints = FloatArray(data.numOfPoints)
                for (i in 0 until data.numOfPoints) {
                    xDataPoints[i] = data.trackPoints[i].lon.toFloat()
                    yDataPoints[i] = data.trackPoints[i].lat.toFloat()
                }
            } else {
                xDataPoints = FloatArray(2)
                yDataPoints = FloatArray(2)
                xDataPoints[0] = 0F
                xDataPoints[1] = 0F
                yDataPoints[0] = 0F
                yDataPoints[1] = 0F
            }
        }


        fun setCirclePoint(i: Int) {
            circlePoint = i
        }

        private fun makeBitmap() {
            bitmapObject = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            trackPath = Path()
            xDataOffset = xDataPoints.minOf { it }
            yDataOffset = yDataPoints.minOf { it }
            xScale = width * 0.95F / (xDataPoints.maxOf { it } - xDataPoints.minOf { it })
            yScale = height * 0.95F / (yDataPoints.maxOf { it } - yDataPoints.minOf { it })
            val myCanvas = Canvas(bitmapObject)
            var myPixel: Pixel = toPixel(xDataPoints[0], yDataPoints[0])
            trackPath.moveTo(myPixel.x, myPixel.y) //shift origin to graph's origin
            for (i in 0 until xDataPoints.size) {
                myPixel = toPixel(xDataPoints[i], yDataPoints[i])
                trackPath.lineTo(myPixel.x, myPixel.y)
            }
            myCanvas.drawPath(trackPath, trackPaint)
        }

        class Pixel {
            var x: Float = 0F
            var y: Float = 0F
        }

        fun toPixel(x: Float, y: Float): Pixel {
            val myPixel: Pixel = Pixel()
            myPixel.x = (x - xDataOffset) * xScale + 0.025F * width
            myPixel.y = height - (y - yDataOffset) * yScale - 0.025F * height
            return myPixel
        }

        private fun drawGraphPlotLines(canvas: Canvas, path: Path, paint: Paint) {
            val myPixel: Pixel = toPixel(xDataPoints[0], yDataPoints[0])
            trackPath.moveTo(myPixel.x, myPixel.y) //shift origin to graph's origin
            for (i in 0 until xDataPoints.size) {
                val myPixel: Pixel = toPixel(xDataPoints[i], yDataPoints[i])
                trackPath.lineTo(myPixel.x, myPixel.y)
            }
            canvas.drawPath(trackPath, paint)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (makeBitmap) {
                makeBitmap()
                circleRadius =
                    height / 100F  //This has to be set here because height and width become valid only in onDraw
                canvas.drawBitmap(bitmapObject, 0F, 0F, null)
            }

            val myPixel: Pixel = toPixel(xDataPoints[circlePoint], yDataPoints[circlePoint])
            canvas.drawCircle(myPixel.x, myPixel.y, circleRadius, circlePaint)
        }
    }
}




