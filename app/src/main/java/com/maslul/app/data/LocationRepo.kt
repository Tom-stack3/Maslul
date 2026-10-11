package com.maslul.app.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class LocationRepo(private val context: Context) {
    private val client by lazy { LocationServices.getFusedLocationProviderClient(context) }
    private val _last = MutableStateFlow<GeoPoint?>(null)
    /** Most recent known position, or null before the first fix. */
    val last: StateFlow<GeoPoint?> = _last.asStateFlow()

    /** Recent fixes, oldest first, for the way you're moving. */
    private val fixes = ArrayDeque<Motion.Fix>()

    private fun record(l: Location) {
        val f = Motion.Fix(
            GeoPoint(l.latitude, l.longitude), l.elapsedRealtimeNanos / 1_000_000,
            l.bearing.takeIf { l.hasBearing() }, l.speed.takeIf { l.hasSpeed() }, l.accuracy.takeIf { l.hasAccuracy() },
        )
        synchronized(fixes) {
            if (fixes.lastOrNull()?.atMs?.let { it >= f.atMs } == true) return
            fixes.addLast(f)
            while (fixes.size > 2 && f.atMs - fixes.first().atMs > Motion.KEEP_MS) fixes.removeFirst()
        }
    }

    /** Which way you're moving, in degrees from north; null when still or not known (see [Motion.heading]). */
    fun heading(): Float? = Motion.heading(synchronized(fixes) { fixes.toList() }, SystemClock.elapsedRealtime())

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun current(): GeoPoint? {
        if (!hasPermission()) return null
        val fresh = withTimeoutOrNull(6000) {
            suspendCancellableCoroutine { cont ->
                val cts = CancellationTokenSource()
                client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                    .addOnSuccessListener { cont.resume(it?.let { l -> record(l); GeoPoint(l.latitude, l.longitude) }) }
                    .addOnFailureListener { cont.resume(null) }
                cont.invokeOnCancellation { cts.cancel() }
            }
        }
        val result = fresh ?: suspendCancellableCoroutine<GeoPoint?> { cont ->
            client.lastLocation
                .addOnSuccessListener { cont.resume(it?.let { l -> GeoPoint(l.latitude, l.longitude) }) }
                .addOnFailureListener { cont.resume(null) }
        }
        if (result != null) _last.value = result
        return result
    }

    @SuppressLint("MissingPermission")
    fun updates(intervalMs: Long = 5000): Flow<GeoPoint> = callbackFlow {
        if (!hasPermission()) { close(); return@callbackFlow }
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(r: LocationResult) {
                r.lastLocation?.let {
                    record(it)
                    val p = GeoPoint(it.latitude, it.longitude)
                    _last.value = p
                    trySend(p)
                }
            }
        }
        client.requestLocationUpdates(req, cb, Looper.getMainLooper())
        awaitClose { client.removeLocationUpdates(cb) }
    }

    companion object {
        /** Used when location is unavailable: Tel Aviv Savidor Center. */
        val DEFAULT = GeoPoint(32.0840, 34.7980)
    }
}

/** Which way you're moving, from recent location fixes. */
object Motion {
    class Fix(val point: GeoPoint, val atMs: Long, val bearing: Float?, val speedMps: Float?, val accuracyM: Float? = null)

    const val KEEP_MS = 3 * 60_000L
    /** An older fix than this says nothing about now. */
    private const val FRESH_MS = 60_000L
    /** A fix's own bearing counts from this speed: below it, it's mostly noise. */
    private const val MOVING_MPS = 2f
    /**
     * Fixes this far apart (and twice their uncertainty, at least walking speed) show where
     * you're heading, when the last one has no bearing.
     */
    private const val APART_M = 80.0
    private const val SINCE_MS = 2 * 60_000L

    /**
     * Degrees from north, or null: the latest fix's own bearing when it's moving, else from an
     * earlier fix far enough back (a train stopped at a station still came from somewhere).
     * Fixes jumping about while you stand still are too slow apart to count.
     */
    fun heading(fixes: List<Fix>, nowMs: Long): Float? {
        val last = fixes.lastOrNull()?.takeIf { nowMs - it.atMs <= FRESH_MS } ?: return null
        if (last.bearing != null && (last.speedMps ?: 0f) >= MOVING_MPS) return last.bearing
        for (f in fixes.asReversed()) {
            if (last.atMs - f.atMs > SINCE_MS) break
            val d = GeoMath.distance(f.point, last.point)
            val sec = (last.atMs - f.atMs) / 1000.0
            val fuzz = 2.0 * ((f.accuracyM ?: 0f) + (last.accuracyM ?: 0f))
            if (d >= maxOf(APART_M, fuzz) && sec > 0 && d / sec >= 1.2) return GeoMath.bearing(f.point, last.point)
        }
        return null
    }
}
