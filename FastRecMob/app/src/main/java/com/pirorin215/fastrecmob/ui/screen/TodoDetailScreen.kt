package com.pirorin215.fastrecmob.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pirorin215.fastrecmob.data.TodoItem
import com.pirorin215.fastrecmob.ui.theme.FastRecMobTheme
import com.pirorin215.fastrecmob.viewModel.TodoViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoDetailScreen(
    todoItem: TodoItem?,
    onSave: (TodoItem) -> Unit,
    onDelete: (TodoItem) -> Unit,
    onBack: () -> Unit
) {
    var currentText by remember(todoItem) { mutableStateOf(todoItem?.text ?: "") }
    var currentNotes by remember(todoItem) { mutableStateOf(todoItem?.notes ?: "") }
    var currentIsCompleted by remember(todoItem) { mutableStateOf(todoItem?.isCompleted ?: false) }

    val isNewTodo = todoItem == null || todoItem.id.isEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.Close, contentDescription = "Close")
            }
            Text(
                text = if (isNewTodo) "New Todo" else "Edit Todo",
                style = MaterialTheme.typography.headlineSmall
            )
            IconButton(onClick = {
                val itemToSave = todoItem?.copy(
                    text = currentText,
                    notes = currentNotes,
                    isCompleted = currentIsCompleted
                ) ?: TodoItem(text = currentText, notes = currentNotes, isCompleted = currentIsCompleted)
                onSave(itemToSave)
            }) {
                Icon(Icons.Default.Check, contentDescription = "Save")
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = currentText,
            onValueChange = { currentText = it },
            label = { Text("Todo Text") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = currentNotes,
            onValueChange = { currentNotes = it },
            label = { Text("Notes") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3
        )
        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = currentIsCompleted,
                onCheckedChange = { currentIsCompleted = it }
            )
            Text("Completed")
        }
        Spacer(modifier = Modifier.height(16.dp))

        if (!isNewTodo) {
            todoItem?.id?.let {
                Text("ID: $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(4.dp))
            }
            todoItem?.updated?.let {
                Text("Updated: ${formatRfc3339Timestamp(it)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(4.dp))
            }
            todoItem?.position?.let {
                Text("Position: $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(4.dp))
            }
            todoItem?.due?.let {
                Text("Due: ${formatRfc3339Timestamp(it)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(4.dp))
            }
            todoItem?.webViewLink?.let {
                Text("Web Link: $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(16.dp))
            }

            Button(
                onClick = { todoItem?.let { onDelete(it) } },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Delete Todo")
            }
        }
    }
}

private fun formatRfc3339Timestamp(timestamp: String): String {
    return try {
        // Example format: 2024-03-04T10:30:00.000Z
        val parser = java.time.format.DateTimeFormatter.ISO_DATE_TIME
        val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        java.time.OffsetDateTime.parse(timestamp, parser).format(formatter)
    } catch (e: Exception) {
        timestamp // Return original if parsing fails
    }
}
