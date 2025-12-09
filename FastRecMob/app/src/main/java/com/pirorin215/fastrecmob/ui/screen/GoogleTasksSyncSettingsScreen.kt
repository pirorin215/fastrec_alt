package com.pirorin215.fastrecmob.ui.screen

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pirorin215.fastrecmob.viewModel.MainViewModel
import com.pirorin215.fastrecmob.viewModel.AppSettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoogleTasksSyncSettingsScreen(
    viewModel: MainViewModel,
    appSettingsViewModel: AppSettingsViewModel,
    onBack: () -> Unit,
    onSignInClick: (Intent) -> Unit
) {
    BackHandler(onBack = onBack)
    val googleAccount by viewModel.account.collectAsState()
    val isLoadingGoogleTasks by viewModel.isLoadingGoogleTasks.collectAsState()
    val googleSignInClient by viewModel.googleSignInClient.collectAsState(initial = null)
    val currentGoogleTodoListName by appSettingsViewModel.googleTodoListName.collectAsState()
    var googleTodoListNameText by remember(currentGoogleTodoListName) { mutableStateOf(currentGoogleTodoListName) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Google Tasks 同期設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Google Sign-In/Out Button
            if (googleAccount == null) {
                Button(
                    onClick = { googleSignInClient?.signInIntent?.let { onSignInClick(it) } },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Google Tasks にサインイン")
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "現在ログイン中のアカウント:",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "${googleAccount?.displayName}",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { viewModel.signOut() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Logout, contentDescription = "Sign Out")
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("サインアウト")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = { viewModel.syncTranscriptionResultsWithGoogleTasks() },
                                enabled = !isLoadingGoogleTasks,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Sync, contentDescription = "Sync with Google Tasks")
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("同期")
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = googleTodoListNameText,
                onValueChange = { googleTodoListNameText = it },
                label = { Text("Google ToDo List Name") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    appSettingsViewModel.saveGoogleTodoListName(googleTodoListNameText)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Google ToDo List 名を保存")
            }

            if (isLoadingGoogleTasks) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(8.dp))
                Text("Google Tasks と同期中...", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
