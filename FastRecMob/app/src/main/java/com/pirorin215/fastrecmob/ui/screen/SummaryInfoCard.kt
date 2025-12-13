package com.pirorin215.fastrecmob.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.pirorin215.fastrecmob.data.DeviceInfoResponse
import android.util.Log

private const val TAG = "SummaryInfoCard"

@Composable
fun SummaryInfoCard(deviceInfo: DeviceInfoResponse?) {
    Log.d(TAG, "SummaryInfoCard received DeviceInfo: Battery=${deviceInfo?.batteryLevel ?: "N/A"}%, WAVs=${deviceInfo?.wavCount ?: "N/A"}") // Added log
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceAround // 均等配置
            ) {

                InfoItem(icon = Icons.Default.BatteryChargingFull, label = "Battery", value = "${String.format("%.0f", deviceInfo?.batteryLevel ?: 0.0f)}%", modifier = Modifier.weight(1f))
                InfoItem(icon = Icons.Default.SdStorage, label = "Storage", value = "${deviceInfo?.littlefsUsagePercent ?: 0}%", modifier = Modifier.weight(1f))
                InfoItem(icon = Icons.Default.Audiotrack, label = "WAVs", value = (deviceInfo?.wavCount ?: 0).toString(), modifier = Modifier.weight(1f))
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse" else "Expand"
                    )
                }
            }
            if (expanded) {
                Spacer(modifier = Modifier.size(8.dp))
                InfoRow(label = "バッテリー電圧", value = "${String.format("%.2f", deviceInfo?.batteryVoltage ?: 0.0f)} V")
                InfoRow(label = "アプリ状態", value = deviceInfo?.appState ?: "-")
                InfoRow(label = "ストレージ使用量", value = "${deviceInfo?.littlefsUsedBytes ?: 0} bytes")
                InfoRow(label = "ストレージ総容量", value = "${(deviceInfo?.littlefsTotalBytes ?: 0) / 1024 / 1024} MB")
            }
        }
    }
}

@Composable
fun InfoItem(icon: ImageVector, label: String, value: String, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Icon(imageVector = icon, contentDescription = label, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Column {
            Text(text = label, style = MaterialTheme.typography.bodySmall)
            Text(text = value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
    HorizontalDivider()
}


