package com.maslul.app.ui.components

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.maslul.app.data.GeoPoint
import kotlin.math.abs

/** Smallest signed difference a − b between two compass angles, in −180..180. */
fun angleDiff(a: Float, b: Float): Float = ((a - b + 540f) % 360f) - 180f

/**
 * Which way the phone points, in degrees clockwise from true north, while [enabled]; null
 * without a compass sensor. Smoothed, and only updated on a change of a couple of degrees so
 * the map isn't redrawn for sensor noise.
 */
@Composable
fun rememberHeading(enabled: Boolean, at: GeoPoint?): Float? {
    val context = LocalContext.current
    var heading by remember { mutableStateOf<Float?>(null) }
    // Magnetic north differs from true north (~5° in Israel); the map is drawn to true north.
    val declination by rememberUpdatedState(
        at?.let { GeomagneticField(it.lat.toFloat(), it.lon.toFloat(), 0f, System.currentTimeMillis()).declination } ?: 0f,
    )
    DisposableEffect(enabled) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (!enabled || sm == null || sensor == null) {
            heading = null
            return@DisposableEffect onDispose { }
        }
        @Suppress("DEPRECATION")
        val display = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        val rot = FloatArray(9)
        val remapped = FloatArray(9)
        val orientation = FloatArray(3)
        var smoothed: Float? = null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                // Azimuth relative to the top of the screen, whichever way the phone is turned.
                val (ax, ay) = when (display.rotation) {
                    Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                }
                SensorManager.remapCoordinateSystem(rot, ax, ay, remapped)
                SensorManager.getOrientation(remapped, orientation)
                val deg = (Math.toDegrees(orientation[0].toDouble()).toFloat() + declination + 360f) % 360f
                val s = smoothed?.let { (it + angleDiff(deg, it) * 0.25f + 360f) % 360f } ?: deg
                smoothed = s
                val shown = heading
                if (shown == null || abs(angleDiff(s, shown)) >= 2f) heading = s
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(listener) }
    }
    return heading
}
