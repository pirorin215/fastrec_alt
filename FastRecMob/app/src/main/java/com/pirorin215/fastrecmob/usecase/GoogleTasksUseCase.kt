package com.pirorin215.fastrecmob.usecase

import android.app.Application
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.pirorin215.fastrecmob.data.AppSettingsRepository
import com.pirorin215.fastrecmob.data.FileUtil
import com.pirorin215.fastrecmob.data.Task
import com.pirorin215.fastrecmob.data.TaskList
import com.pirorin215.fastrecmob.data.TranscriptionResult
import com.pirorin215.fastrecmob.data.TranscriptionResultRepository
import com.pirorin215.fastrecmob.network.GoogleTasksApiService
import com.pirorin215.fastrecmob.network.RetrofitClient
import com.pirorin215.fastrecmob.viewModel.LogManager
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class GoogleTasksUseCase(
    private val application: Application,
    private val appSettingsRepository: AppSettingsRepository,
    private val transcriptionResultRepository: TranscriptionResultRepository,
    private val context: Context,
    private val scope: CoroutineScope, // Added CoroutineScope
    private val logManager: LogManager // Added LogManager
) {
    private val _account = MutableStateFlow<GoogleSignInAccount?>(null)
    val account: StateFlow<GoogleSignInAccount?> = _account.asStateFlow()

    private val _isLoadingGoogleTasks = MutableStateFlow(false)
    val isLoadingGoogleTasks: StateFlow<Boolean> = _isLoadingGoogleTasks.asStateFlow()

    val googleSignInClient: GoogleSignInClient

    private var taskListId: String? = null
    private val tasksScope = "https://www.googleapis.com/auth/tasks"

    private val apiService: GoogleTasksApiService by lazy {
        RetrofitClient.create {
            val account = _account.value ?: throw IllegalStateException("User not signed in for Google Tasks API.")
            GoogleAuthUtil.getToken(application, account.account!!, "oauth2:$tasksScope")
        }
    }

    init {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(tasksScope))
            .build()
        googleSignInClient = GoogleSignIn.getClient(application, gso)

        _account.value = GoogleSignIn.getLastSignedInAccount(application)
        if (_account.value != null) {
            logManager.addLog("Signed in to Google Tasks: ${_account.value?.displayName}")
        }

        // Observe changes to the Google Todo list name and clear the cached taskListId
        appSettingsRepository.googleTodoListNameFlow.onEach {
            taskListId = null // Clear cached taskListId so it's re-fetched
            logManager.addLog("Google Todo list name changed. Cleared cached taskListId.")
        }.launchIn(scope)
    }

    private suspend fun createGoogleTaskList(listName: String): String? {
        if (_account.value == null || listName.isBlank()) return null
        val taskList = TaskList(id = "", title = listName) // ID is ignored for creation
        return try {
            val newTaskList = apiService.createTaskList(taskList)
            logManager.addLog("Created new Google Task List: '${newTaskList.title}' (ID: ${newTaskList.id})")
            newTaskList.id
        } catch (e: Exception) {
            logManager.addLog("Error creating Google Task List '$listName': ${e.message}")
            null
        }
    }

    private suspend fun getTaskListId(): String? {
        if (taskListId != null) return taskListId

        val listName = appSettingsRepository.googleTodoListNameFlow.first()
        if (listName.isBlank()) {
            logManager.addLog("Google Todo List Name is blank. Using '@default'.")
            taskListId = "@default" // Cache the default
            return "@default"
        }
        return try {
            val taskListsResponse = apiService.getTaskLists()
            var foundList = taskListsResponse.items.find { it.title == listName }

            if (foundList == null && listName != "@default") {
                logManager.addLog("Google Task List '$listName' not found. Attempting to create it.")
                val newTaskListId = createGoogleTaskList(listName)
                if (newTaskListId != null) {
                    taskListId = newTaskListId
                    return newTaskListId
                } else {
                    logManager.addLog("Failed to create Google Task List '$listName'. Falling back to default or first available.")
                }
            }

            taskListId = foundList?.id ?: taskListsResponse.items.firstOrNull()?.id // Fallback to first list if user-defined not found/created
            taskListId
        } catch (e: Exception) {
            logManager.addLog("Error getting or creating task lists: ${e.message}")
            null
        }
    }

    private suspend fun addGoogleTask(title: String, notes: String?, isCompleted: Boolean, due: String?): Task? {
        if (_account.value == null || title.isBlank()) return null
        val currentTaskListId = taskListId ?: getTaskListId() ?: return null
        val status = if (isCompleted) "completed" else "needsAction"
        val task = Task(title = title, notes = notes, status = status, due = due)
        return try {
            val createdTask = apiService.createTask(currentTaskListId, task)
            logManager.addLog("Added new Google Task: $title")
            createdTask
        } catch (e: Exception) {
            logManager.addLog("Error adding Google Task '$title': ${e.message}")
            null
        }
    }

    private suspend fun updateGoogleTask(localResult: TranscriptionResult): Task? {
        val taskId = localResult.googleTaskId ?: return null
        if (_account.value == null) return null
        val currentTaskListId = taskListId ?: getTaskListId() ?: return null

        try {
            // Fetch the current state of the task from Google Tasks
            val remoteTask = apiService.getTask(currentTaskListId, taskId)
            val remoteTimestamp = FileUtil.parseRfc3339Timestamp(remoteTask.updated)
            val localTimestamp = localResult.lastEditedTimestamp

            if (localTimestamp < remoteTimestamp) {
                logManager.addLog("Skipping update for task '${localResult.transcription}' (ID: $taskId). Remote version is newer.")
                return remoteTask // Return remote task to signal that remote is newer
            }

            // Proceed with the update if the local version is newer
            val status = if (localResult.isCompleted) "completed" else "needsAction"
            val taskToUpdate = Task(
                id = taskId,
                title = localResult.transcription,
                notes = localResult.googleTaskNotes,
                status = status,
                due = remoteTask.due
            )

            val updatedTask = apiService.updateTask(currentTaskListId, taskId, taskToUpdate)
            logManager.addLog("Updated Google Task: ${localResult.transcription} (ID: $taskId)")
            return updatedTask

        } catch (e: Exception) {
            logManager.addLog("Error updating or checking Google Task '${localResult.transcription}' (ID: $taskId): ${e.message}")
            return null
        }
    }

    suspend fun syncTranscriptionResultsWithGoogleTasks(audioDirName: String) {
        if (_account.value == null) {
            logManager.addLog("Not signed in to Google. Cannot sync tasks.")
            return
        }
        _isLoadingGoogleTasks.value = true
        logManager.addLog("Starting Google Tasks synchronization...")

        try {
            val localResults = transcriptionResultRepository.transcriptionResultsFlow.first()
            val updatedResults = mutableListOf<TranscriptionResult>()

            for (localResult in localResults) {
                // Skip locally deleted items, do not sync deletion to Google Tasks
                if (localResult.isDeletedLocally) {
                    updatedResults.add(localResult)
                    continue
                }

                if (localResult.googleTaskId == null) {
                    // This is a new item, add it to Google Tasks
                    logManager.addLog("Local result '${localResult.fileName}' has no Google Task ID. Adding to Google Tasks.")

                    // JSTの今日の日付をUTCの00:00:00のRFC3339タイムスタンプとして生成
                    val dueTime = LocalDate.now(ZoneId.of("Asia/Tokyo"))
                        .atStartOfDay()
                        .atOffset(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

                    val addedTask = addGoogleTask(
                        title = localResult.transcription,
                        notes = localResult.googleTaskNotes,
                        isCompleted = localResult.isCompleted,
                        due = dueTime
                    )
                    if (addedTask != null && addedTask.id != null) {
                        updatedResults.add(
                            localResult.copy(
                                googleTaskId = addedTask.id,
                                isSyncedWithGoogleTasks = true,
                                googleTaskUpdated = addedTask.updated,
                                googleTaskDue = addedTask.due, // dueを追加
                                lastEditedTimestamp = FileUtil.parseRfc3339Timestamp(addedTask.updated)
                            )
                        )
                        logManager.addLog("Added local result '${localResult.fileName}' to Google Tasks. New Google ID: ${addedTask.id}")
                    } else {
                        logManager.addLog("Failed to add local result '${localResult.fileName}' to Google Tasks. Will retry on next sync.")
                        updatedResults.add(localResult.copy(isSyncedWithGoogleTasks = false))
                    }
                } else {
                    // This item already exists on Google Tasks, check if it needs an update.
                    val googleTaskUpdatedTimestamp = FileUtil.parseRfc3339Timestamp(localResult.googleTaskUpdated)
                    val needsUpdate = !localResult.isSyncedWithGoogleTasks || localResult.lastEditedTimestamp > googleTaskUpdatedTimestamp

                    if (needsUpdate) {
                        val updatedTask = updateGoogleTask(localResult)

                        if (updatedTask != null) {
                            val remoteTimestamp = FileUtil.parseRfc3339Timestamp(updatedTask.updated)
                            // Check if the remote was newer
                            if (localResult.lastEditedTimestamp < remoteTimestamp) {
                                logManager.addLog("Updating local task '${localResult.fileName}' with newer remote data.")
                                updatedResults.add(
                                    localResult.copy(
                                        transcription = updatedTask.title ?: "",
                                        isCompleted = updatedTask.status == "completed",
                                        googleTaskNotes = updatedTask.notes,
                                        isSyncedWithGoogleTasks = true,
                                        googleTaskUpdated = updatedTask.updated,
                                        googleTaskDue = updatedTask.due, // dueを追加
                                        lastEditedTimestamp = remoteTimestamp
                                    )
                                )
                            } else {
                                // Local was newer, and we successfully updated the remote
                                logManager.addLog("Successfully pushed local changes to Google Task for '${localResult.fileName}'.")
                                updatedResults.add(
                                    localResult.copy(
                                        isSyncedWithGoogleTasks = true,
                                        googleTaskUpdated = updatedTask.updated,
                                        googleTaskDue = updatedTask.due, // dueを追加
                                        lastEditedTimestamp = remoteTimestamp
                                    )
                                )
                            }
                        } else {
                            logManager.addLog("Failed to sync Google Task for '${localResult.fileName}'. Will retry on next sync.")
                            updatedResults.add(localResult.copy(isSyncedWithGoogleTasks = false))
                        }
                    } else {
                        // No changes needed for this item
                        updatedResults.add(localResult)
                    }
                }
            }

            // Update the local database with the new state
            transcriptionResultRepository.updateResults(updatedResults)

            logManager.addLog("Google Tasks synchronization completed.")

        } catch (e: Exception) {
            logManager.addLog("Error during Google Tasks synchronization: ${e.message}")
        } finally {
            _isLoadingGoogleTasks.value = false
        }
    }

    fun handleSignInResult(intent: Intent, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(intent)
            _account.value = task.getResult(ApiException::class.java)
            logManager.addLog("Google Sign-In successful for: ${_account.value?.displayName}")
            onSuccess()
        } catch (e: ApiException) {
            logManager.addLog("Google Sign-In failed: ${e.statusCode} - ${e.message}")
            onFailure(e)
        }
    }

    fun signOut() {
        googleSignInClient.signOut().addOnCompleteListener {
            _account.value = null
            taskListId = null
            logManager.addLog("Signed out from Google Tasks.")
        }
    }
}
