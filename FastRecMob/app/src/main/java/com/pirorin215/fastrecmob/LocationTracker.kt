package com.pirorin215.fastrecmob

import kotlinx.serialization.Serializable
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
import android.util.Log // Added import

@Serializable
data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long
)

class LocationTracker(private val context: Context) {

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    companion object {
        private const val TAG = "LocationTracker"
    }

    suspend fun getCurrentLocation(): Result<LocationData> = suspendCancellableCoroutine { continuation ->
        Log.d(TAG, "getCurrentLocation: Attempting to get current location.")

        if (!hasLocationPermission()) {
            Log.w(TAG, "getCurrentLocation: Location permission not granted.")
            continuation.resumeWithException(SecurityException("Location permission not granted"))
            return@suspendCancellableCoroutine
        }
        Log.d(TAG, "getCurrentLocation: Location permission granted.")


        // Check if location services are enabled. This is a basic check.
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val isGpsEnabled = locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
        val isNetworkEnabled = locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)

        if (!isGpsEnabled && !isNetworkEnabled) {
            Log.w(TAG, "getCurrentLocation: Location services are disabled. GPS: $isGpsEnabled, Network: $isNetworkEnabled")
            continuation.resume(Result.failure(IllegalStateException("Location services are disabled.")))
            return@suspendCancellableCoroutine
        }
        Log.d(TAG, "getCurrentLocation: Location services are enabled. GPS: $isGpsEnabled, Network: $isNetworkEnabled")


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
                    Log.d(TAG, "onLocationResult: Received location: Lat=${location.latitude}, Lng=${location.longitude}")
                    if (continuation.isActive) {
                        continuation.resume(Result.success(LocationData(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            timestamp = location.time
                        )))
                    }
                } ?: run {
                    Log.w(TAG, "onLocationResult: lastLocation is null.")
                }
            }
        }
        Log.d(TAG, "getCurrentLocation: Requesting location updates with FusedLocationProviderClient.")
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
            .addOnFailureListener { e ->
                fusedLocationClient.removeLocationUpdates(locationCallback)
                Log.e(TAG, "getCurrentLocation: Failed to request location updates: ${e.message}", e)
                if (continuation.isActive) {
                    continuation.resume(Result.failure(e))
                }
            }

        continuation.invokeOnCancellation {
            Log.d(TAG, "getCurrentLocation: Location request cancelled. Removing updates.")
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
