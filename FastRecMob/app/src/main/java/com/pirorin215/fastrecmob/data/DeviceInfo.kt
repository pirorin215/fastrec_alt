package com.pirorin215.fastrecmob.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class DeviceInfoResponse(
    val ls: String,
    @SerialName("battery_level") val batteryLevel: Float,
    @SerialName("battery_voltage") val batteryVoltage: Float,
    @SerialName("app_state") val appState: String,
    @SerialName("wifi_status") val wifiStatus: String,
    @SerialName("connected_ssid") val connectedSsid: String,
    @SerialName("wifi_rssi") val wifiRssi: Int,
    @SerialName("littlefs_total_bytes") val littlefsTotalBytes: Long,
    @SerialName("littlefs_used_bytes") val littlefsUsedBytes: Long,
    @SerialName("littlefs_usage_percent") val littlefsUsagePercent: Int
)

data class FileEntry(val name: String, val size: String)

// Helper function to parse the 'ls' string
fun parseFileEntries(lsString: String): List<FileEntry> {
    return lsString.trim().split('\n').mapNotNull {
        if (it.isBlank()) return@mapNotNull null
        val parts = it.split(" (", limit = 2)
        if (parts.size == 2) {
            val name = parts[0].trim()
            val size = parts[1].removeSuffix(" bytes)").trim()
            FileEntry(name, size)
        } else {
            // Handle directories or other non-file entries if necessary
            FileEntry(it.trim(), "N/A")
        }
    }
}

