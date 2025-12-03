package com.pirorin215.fastrecmob.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pirorin215.fastrecmob.LocationData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.lastKnownLocationDataStore: DataStore<Preferences> by preferencesDataStore(name = "last_known_location")

class LastKnownLocationRepository(private val context: Context) {

    private object PreferencesKeys {
        val LAST_LATITUDE = doublePreferencesKey("last_latitude")
        val LAST_LONGITUDE = doublePreferencesKey("last_longitude")
        val LAST_TIMESTAMP = longPreferencesKey("last_timestamp")
    }

    val lastKnownLocationFlow: Flow<LocationData?> = context.lastKnownLocationDataStore.data
        .map { preferences ->
            val latitude = preferences[PreferencesKeys.LAST_LATITUDE]
            val longitude = preferences[PreferencesKeys.LAST_LONGITUDE]
            val timestamp = preferences[PreferencesKeys.LAST_TIMESTAMP]

            if (latitude != null && longitude != null && timestamp != null) {
                LocationData(latitude, longitude, timestamp)
            } else {
                null
            }
        }

    suspend fun saveLastKnownLocation(location: LocationData) {
        context.lastKnownLocationDataStore.edit { preferences ->
            preferences[PreferencesKeys.LAST_LATITUDE] = location.latitude
            preferences[PreferencesKeys.LAST_LONGITUDE] = location.longitude
            preferences[PreferencesKeys.LAST_TIMESTAMP] = location.timestamp
        }
    }

    suspend fun clearLastKnownLocation() {
        context.lastKnownLocationDataStore.edit { preferences ->
            preferences.remove(PreferencesKeys.LAST_LATITUDE)
            preferences.remove(PreferencesKeys.LAST_LONGITUDE)
            preferences.remove(PreferencesKeys.LAST_TIMESTAMP)
        }
    }
}
