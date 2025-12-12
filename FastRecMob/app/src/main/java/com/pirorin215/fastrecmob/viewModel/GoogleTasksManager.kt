package com.pirorin215.fastrecmob.viewModel

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.pirorin215.fastrecmob.data.AppSettingsRepository
import com.pirorin215.fastrecmob.data.TranscriptionResultRepository
import com.pirorin215.fastrecmob.usecase.GoogleTasksUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow

class GoogleTasksManager(
    private val application: Application,
    private val appSettingsRepository: AppSettingsRepository,
    private val transcriptionResultRepository: TranscriptionResultRepository,
    private val context: Context,
    private val scope: CoroutineScope, // Scope provided by ViewModel
    private val logManager: LogManager
) : GoogleTasksIntegration {
    private val googleTasksUseCase: GoogleTasksUseCase

    private val _account = MutableStateFlow<GoogleSignInAccount?>(null)
    override val account: StateFlow<GoogleSignInAccount?> = _account.asStateFlow()

    private val _isLoadingGoogleTasks = MutableStateFlow(false)
    override val isLoadingGoogleTasks: StateFlow<Boolean> = _isLoadingGoogleTasks.asStateFlow()

    private val _googleSignInClient = MutableSharedFlow<GoogleSignInClient>(replay = 1) // Using SharedFlow to provide client
    override val googleSignInClient: SharedFlow<GoogleSignInClient> = _googleSignInClient.asSharedFlow()

    init {
        googleTasksUseCase = GoogleTasksUseCase(
            application,
            appSettingsRepository,
            transcriptionResultRepository,
            context,
            scope, // Pass the provided scope
            logManager // Pass the logManager here
        )
        // Observe UseCase's states and expose them through Manager's states
        scope.launch {
            googleTasksUseCase.account.collect {
                _account.value = it
            }
        }
        scope.launch {
            googleTasksUseCase.isLoadingGoogleTasks.collect {
                _isLoadingGoogleTasks.value = it
            }
        }
        scope.launch {
            _googleSignInClient.emit(googleTasksUseCase.googleSignInClient)
        }
    }

    override suspend fun syncTranscriptionResultsWithGoogleTasks(audioDirName: String) {
        googleTasksUseCase.syncTranscriptionResultsWithGoogleTasks(audioDirName)
        logManager.addLog("Google Tasks sync requested.")
    }

    override fun handleSignInResult(intent: Intent, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        googleTasksUseCase.handleSignInResult(intent, {
            scope.launch {
                onSuccess()
            }
        }, onFailure)
        logManager.addLog("Google Sign-In result handled.")
    }

    override fun signOut() {
        googleTasksUseCase.signOut()
        logManager.addLog("Signed out from Google Tasks.")
    }
}
