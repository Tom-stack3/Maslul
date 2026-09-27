package com.maslul.app.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
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
                    .addOnSuccessListener { cont.resume(it?.let { l -> GeoPoint(l.latitude, l.longitude) }) }
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
