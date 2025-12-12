package com.pirorin215.fastrecmob.viewModel

import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface GoogleTasksIntegration {
    val account: StateFlow<GoogleSignInAccount?>
    val isLoadingGoogleTasks: StateFlow<Boolean>
    val googleSignInClient: SharedFlow<GoogleSignInClient>

    suspend fun syncTranscriptionResultsWithGoogleTasks(audioDirName: String)
    fun handleSignInResult(intent: Intent, onSuccess: () -> Unit, onFailure: (Exception) -> Unit)
    fun signOut()
}
