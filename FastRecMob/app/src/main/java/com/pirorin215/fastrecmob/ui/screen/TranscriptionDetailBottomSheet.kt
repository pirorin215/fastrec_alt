package com.pirorin215.fastrecmob.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pirorin215.fastrecmob.data.FileUtil
import com.pirorin215.fastrecmob.data.TranscriptionResult
import com.pirorin215.fastrecmob.viewModel.BleViewModel
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Edit
import android.content.Intent
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptionDetailBottomSheet(
    result: TranscriptionResult,
    fontSize: Int,
    audioFileExists: Boolean,
    audioDirName: String, // New parameter
    onPlay: (TranscriptionResult) -> Unit,
    onDelete: (TranscriptionResult) -> Unit,
    onSave: (TranscriptionResult, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var editableText by remember { mutableStateOf(result.transcription) }
    val isEdited = remember(editableText) { editableText != result.transcription }
    val scrollState = rememberScrollState()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .verticalScroll(scrollState)
        ) {
            // Header: FileName
            Text(
                text = FileUtil.extractRecordingDateTime(result.fileName),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Editable Text Field
            OutlinedTextField(
                value = editableText,
                onValueChange = { editableText = it },
                label = { Text("Transcription Content") },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = fontSize.sp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 400.dp)
                    .padding(vertical = 8.dp)
            )

            // Indication of unsaved changes
            if (isEdited) {
                Text(
                    "Unsaved changes",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.End)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Play Button
                if (audioFileExists) {
                    Button(
                        onClick = { onPlay(result) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play Audio")
                        Spacer(Modifier.width(4.dp))
                        Text("Play")
                    }
                    Spacer(Modifier.width(8.dp))
                }

                // Save Button
                Button(
                    onClick = { onSave(result, editableText) },
                    enabled = isEdited,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.Save, contentDescription = "Save Changes")
                    Spacer(Modifier.width(4.dp))
                    Text("Save")
                }
                Spacer(Modifier.width(8.dp))

                // Delete Button
                Button(
                    onClick = { onDelete(result) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete Transcription")
                    Spacer(Modifier.width(4.dp))
                    Text("Delete")
                }
            }

            Spacer(modifier = Modifier.height(16.dp)) // Add some spacing

            // Show Location Button
            result.locationData?.let { location ->
                Button(
                    onClick = {
                        val gmmIntentUri = Uri.parse("geo:${location.latitude},${location.longitude}?q=${location.latitude},${location.longitude}(Recorded Location)")
                        val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
                        mapIntent.setPackage("com.google.android.apps.maps") // Try to open in Google Maps
                        if (mapIntent.resolveActivity(context.packageManager) != null) {
                            context.startActivity(mapIntent)
                        } else {
                            // Fallback to web browser if Google Maps app is not installed
                            val webIntentUri = Uri.parse("https://www.google.com/maps/search/?api=1&query=${location.latitude},${location.longitude}")
                            val webMapIntent = Intent(Intent.ACTION_VIEW, webIntentUri)
                            context.startActivity(webMapIntent)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.LocationOn, contentDescription = "Show Location") // Assuming Icons.Default.LocationOn exists
                    Spacer(Modifier.width(4.dp))
                    Text("Show Location on Map")
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Close Button
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.outlinedButtonColors()
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close")
                Spacer(Modifier.width(4.dp))
                Text("Close")
            }
            Spacer(modifier = Modifier.height(30.dp)) // Padding for bottom sheet swipe handle
        }
    }
}
