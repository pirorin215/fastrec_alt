package com.pirorin215.fastrecmob

import kotlinx.serialization.Serializable
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await

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

    suspend fun getCurrentLocation(): Result<LocationData> {
        Log.d(TAG, "getCurrentLocation: Attempting to get current location.")

        if (!hasLocationPermission()) {
            Log.w(TAG, "getCurrentLocation: Location permission not granted.")
            return Result.failure(SecurityException("Location permission not granted"))
        }
        Log.d(TAG, "getCurrentLocation: Location permission granted.")


        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val isGpsEnabled = locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
        val isNetworkEnabled = locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)

        if (!isGpsEnabled && !isNetworkEnabled) {
            Log.w(TAG, "getCurrentLocation: Location services are disabled. GPS: $isGpsEnabled, Network: $isNetworkEnabled")
            return Result.failure(IllegalStateException("Location services are disabled."))
        }
        Log.d(TAG, "getCurrentLocation: Location services are enabled. GPS: $isGpsEnabled, Network: $isNetworkEnabled")

        val cancellationTokenSource = CancellationTokenSource()
        return try {
            // Use the modern getCurrentLocation API which is Coroutine-friendly
            val location = fusedLocationClient.getCurrentLocation(
                com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY,
                cancellationTokenSource.token
            ).await()

            if (location != null) {
                Log.d(TAG, "getCurrentLocation: Received location: Lat=${location.latitude}, Lng=${location.longitude}")
                Result.success(LocationData(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    timestamp = location.time
                ))
            } else {
                Log.w(TAG, "getCurrentLocation: FusedLocationProviderClient returned null location.")
                Result.failure(Exception("Failed to get location: FusedLocationProviderClient returned null."))
            }
        } catch (e: Exception) {
            Log.e(TAG, "getCurrentLocation: Exception while getting location: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
}