package com.pirorin215.fastrecmob.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh // Add this import
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Stop // Add this import
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pirorin215.fastrecmob.data.FileUtil
import com.pirorin215.fastrecmob.data.TranscriptionResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptionDetailBottomSheet(
    result: TranscriptionResult,
    fontSize: Int,
    audioFileExists: Boolean,
    audioDirName: String, // New parameter
    isPlaying: Boolean, // New parameter for playback status
    onPlay: (TranscriptionResult) -> Unit,
    onStop: () -> Unit, // New lambda for stopping playback
    onDelete: (TranscriptionResult) -> Unit,
    onSave: (TranscriptionResult, String, String?) -> Unit, // Modified to include note
    onRetranscribe: (TranscriptionResult) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var editableText by remember { mutableStateOf(TextFieldValue(result.transcription)) }
    var editableNote by remember { mutableStateOf(TextFieldValue(result.googleTaskNotes ?: "")) } // New state for note
    val isEdited = remember(editableText.text, editableNote.text) { // Updated isEdited logic
        editableText.text != result.transcription || editableNote.text != (result.googleTaskNotes ?: "")
    }
    val scrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
    )
    var isExpanded by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .verticalScroll(scrollState)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { isExpanded = !isExpanded } // Make the column clickable
                ) {
                    Text(
                        text = "作成日時: " + FileUtil.extractRecordingDateTime(result.fileName),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "編集日時: " + FileUtil.formatTimestampToDateTimeString(result.lastEditedTimestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {

                    // Retranscribe Button
                    if (audioFileExists) { // Conditionally display the button
                        IconButton(
                            onClick = { onRetranscribe(result) }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh, // Using Refresh icon for re-transcribe
                                contentDescription = "Retranscribe"
                            )
                        }
                    }


                    // Copy Button
                    IconButton(
                        onClick = {
                            val selectedText = editableText.text.substring(editableText.selection.min, editableText.selection.max)
                            val textToCopy = if (selectedText.isNotEmpty()) {
                                selectedText
                            } else {
                                editableText.text // Copy full text if no selection
                            }

                            if (textToCopy.isNotEmpty()) {
                                val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Transcription", textToCopy)
                                clipboardManager.setPrimaryClip(clip)
                                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "No text to copy", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Text"
                        )
                    }

                    // Delete Button
                    IconButton(
                        onClick = { onDelete(result) }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Transcription",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            if (isExpanded) { // Use isExpanded here
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    Text("Google Task Information", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    result.googleTaskId?.let {
                        Text("Task ID: $it", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Completed: ${result.isCompleted}", style = MaterialTheme.typography.bodySmall)
                    result.googleTaskNotes?.let {
                        Text("Notes: $it", style = MaterialTheme.typography.bodySmall)
                    }
                    result.googleTaskUpdated?.let {
                        Text("Updated: $it", style = MaterialTheme.typography.bodySmall)
                    }
                    result.googleTaskPosition?.let {
                        Text("Position: $it", style = MaterialTheme.typography.bodySmall)
                    }
                    result.googleTaskDue?.let {
                        Text("Due: $it", style = MaterialTheme.typography.bodySmall)
                    }
                    result.googleTaskWebViewLink?.let {
                        Text("Web Link: $it", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Synced: ${result.isSyncedWithGoogleTasks}", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(16.dp))
            }


            // Title Text Field (originally "Editable Text Field")
            OutlinedTextField(
                value = editableText,
                onValueChange = { editableText = it },
                label = { Text("タイトル") },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = fontSize.sp),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .focusRequester(focusRequester)
            )

            // Note Text Field
            OutlinedTextField(
                value = editableNote,
                onValueChange = { editableNote = it },
                label = { Text("詳細") }, // Added label
                textStyle = MaterialTheme.typography.bodyMedium, // Slightly smaller text for note
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 400.dp) // Adjusted height for note
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Play/Stop Button (Icon)
                if (audioFileExists) {
                    IconButton(
                        onClick = {
                            if (isPlaying) {
                                onStop()
                            } else {
                                onPlay(result)
                            }
                        },
                        modifier = Modifier.heightIn(min = 48.dp).padding(end = 8.dp)
                    ) {
                        if (isPlaying) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop Audio")
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Play Audio")
                        }
                    }
                }

                // Map Button (Icon)
                result.locationData?.let { location ->
                    IconButton(
                        onClick = {
                            val gmmIntentUri = Uri.parse("geo:${location.latitude},${location.longitude}?q=${location.latitude},${location.longitude}(Recorded Location)")
                            val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
                            mapIntent.setPackage("com.google.android.apps.maps")
                            if (mapIntent.resolveActivity(context.packageManager) != null) {
                                context.startActivity(mapIntent)
                            } else {
                                val webIntentUri = Uri.parse("https://www.google.com/maps/search/?api=1&query=${location.latitude},${location.longitude}")
                                val webMapIntent = Intent(Intent.ACTION_VIEW, webIntentUri)
                                context.startActivity(webMapIntent)
                            }
                        },
                        modifier = Modifier.heightIn(min = 48.dp).padding(end = 8.dp)
                    ) {
                        Icon(Icons.Default.LocationOn, contentDescription = "Show Location")
                    }
                }
                Spacer(Modifier.weight(1f)) // Pushes the Save button to the end
                // Save Button
                Button(
                    onClick = { onSave(result, editableText.text, editableNote.text.ifBlank { null }) }, // Pass editableNote.text
                    enabled = isEdited,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.Save, contentDescription = "Save Changes")
                    Spacer(Modifier.width(4.dp))
                    Text("保存")
                }
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
        }
    }
}
