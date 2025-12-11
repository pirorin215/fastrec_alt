package com.pirorin215.fastrecmob.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class DeviceInfoResponse(
    @SerialName("wav_count") val wavCount: Int,
    @SerialName("txt_count") val txtCount: Int,
    @SerialName("ini_count") val iniCount: Int,
    @SerialName("battery_level") val batteryLevel: Float,
    @SerialName("battery_voltage") val batteryVoltage: Float,
    @SerialName("app_state") val appState: String,

    @SerialName("littlefs_total_bytes") val littlefsTotalBytes: Long,
    @SerialName("littlefs_used_bytes") val littlefsUsedBytes: Long,
    @SerialName("littlefs_usage_percent") val littlefsUsagePercent: Int
)

@Serializable
data class FileEntry(
    @SerialName("name") val name: String,
    @SerialName("size") val size: Long
)

private val json = Json { ignoreUnknownKeys = true }

// Helper function to parse the JSON array string from GET:ls
fun parseFileEntries(jsonString: String): List<FileEntry> {
    if (jsonString.isBlank()) return emptyList()
    return try {
        json.decodeFromString<List<FileEntry>>(jsonString)
    } catch (e: Exception) {
        // Log the error or handle it appropriately
        println("Error parsing file entries JSON: ${e.message}")
        emptyList()
    }
}


