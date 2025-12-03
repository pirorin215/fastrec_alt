package com.pirorin215.fastrecmob

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long
)

class LocationTracker(private val context: Context) {

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    suspend fun getCurrentLocation(): Result<LocationData> = suspendCancellableCoroutine { continuation ->
        if (!hasLocationPermission()) {
            continuation.resumeWithException(SecurityException("Location permission not granted"))
            return@suspendCancellableCoroutine
        }

        // Check if location services are enabled. This is a basic check.
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        if (!locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) &&
            !locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) {
            continuation.resume(Result.failure(IllegalStateException("Location services are disabled.")))
            return@suspendCancellableCoroutine
        }


        val locationRequest = LocationRequest.Builder(
            com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY,
            10000 // 10 seconds
        ).setMinUpdateIntervalMillis(5000) // 5 seconds
            .setMaxUpdateDelayMillis(15000) // 15 seconds
            .setDurationMillis(30000) // Try for 30 seconds to get a location
            .build()

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    fusedLocationClient.removeLocationUpdates(this)
                    if (continuation.isActive) {
                        continuation.resume(Result.success(LocationData(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            timestamp = location.time
                        )))
                    }
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
            .addOnFailureListener { e ->
                fusedLocationClient.removeLocationUpdates(locationCallback)
                if (continuation.isActive) {
                    continuation.resume(Result.failure(e))
                }
            }

        continuation.invokeOnCancellation {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
}
