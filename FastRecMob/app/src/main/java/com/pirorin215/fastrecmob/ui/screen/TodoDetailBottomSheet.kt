package com.pirorin215.fastrecmob.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pirorin215.fastrecmob.viewModel.TodoViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoDetailBottomSheet(
    todoId: String,
    onDismiss: () -> Unit,
    todoViewModel: TodoViewModel
) {
    val todoItem by todoViewModel.getTodoItemById(todoId).collectAsState(initial = null)
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Top
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Todo Details", style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            if (todoItem == null) {
                Text("Todo item not found.", style = MaterialTheme.typography.bodyLarge)
            } else {
                Text("ID: ${todoItem!!.id}", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Text: ${todoItem!!.text}", style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(8.dp))
                if (!todoItem!!.notes.isNullOrBlank()) {
                    Text("Notes: ${todoItem!!.notes}", style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (!todoItem!!.due.isNullOrBlank()) {
                    Text("Due Date: ${todoItem!!.due}", style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (!todoItem!!.webViewLink.isNullOrBlank()) {
                    Text("Link: ${todoItem!!.webViewLink}", style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Text("Completed: ${if (todoItem!!.isCompleted.value) "Yes" else "No"}", style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(8.dp))
                if (!todoItem!!.updated.isNullOrBlank()) {
                    Text("Last Updated: ${todoItem!!.updated}", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (!todoItem!!.position.isNullOrBlank()) {
                    Text("Position: ${todoItem!!.position}", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
            Spacer(modifier = Modifier.height(32.dp)) // Extra space for bottom padding
        }
    }
}
