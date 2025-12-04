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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
    onPlay: (TranscriptionResult) -> Unit,
    onDelete: (TranscriptionResult) -> Unit,
    onSave: (TranscriptionResult, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var editableText by remember { mutableStateOf(TextFieldValue(result.transcription)) }
    val isEdited = remember(editableText.text) { editableText.text != result.transcription }
    val scrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
    )

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
                // Header: FileName
                Text(
                    text = FileUtil.extractRecordingDateTime(result.fileName),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Select All Button
                    IconButton(
                        onClick = {
                            editableText = editableText.copy(selection = androidx.compose.ui.text.TextRange(0, editableText.text.length))
                            focusRequester.requestFocus()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.SelectAll,
                            contentDescription = "Select All"
                        )
                    }

                    // Copy Button
                    IconButton(
                        onClick = {
                            val selectedText = editableText.text.substring(editableText.selection.min, editableText.selection.max)
                            if (selectedText.isNotEmpty()) {
                                val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Transcription", selectedText)
                                clipboardManager.setPrimaryClip(clip)
                                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "No text selected", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Selected Text"
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

            // Editable Text Field
            OutlinedTextField(
                value = editableText,
                onValueChange = { editableText = it },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = fontSize.sp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 600.dp)
                    .padding(vertical = 8.dp)
                    .focusRequester(focusRequester)
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
                        Text("再生")
                    }
                    Spacer(Modifier.width(8.dp))
                }

                // Map Button
                result.locationData?.let { location ->
                    Button(
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
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.LocationOn, contentDescription = "Show Location")
                        Spacer(Modifier.width(4.dp))
                        Text("地図")
                    }
                    Spacer(Modifier.width(8.dp))
                }
                // Save Button
                Button(
                    onClick = { onSave(result, editableText.text) },
                    enabled = isEdited,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
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
