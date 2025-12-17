package com.pirorin215.fastrecmob

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.pirorin215.fastrecmob.service.BleScanService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootCompletedReceiver : BroadcastReceiver() {

    private val TAG = "BootCompletedReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Received BOOT_COMPLETED intent.")
            val pendingResult: PendingResult = goAsync()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

            scope.launch {
                try {
                    val app = context.applicationContext as? MainApplication
                    if (app == null) {
                        Log.e(TAG, "Application is not MainApplication.")
                        return@launch
                    }

                    val appSettingsRepository = app.appSettingsRepository
                    val autoStartOnBoot = appSettingsRepository.autoStartOnBootFlow.first()

                    if (autoStartOnBoot) {
                        Log.d(TAG, "Auto-start on boot is enabled. Starting BleScanService.")
                        val serviceIntent = Intent(context, BleScanService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }
                    } else {
                        Log.d(TAG, "Auto-start on boot is disabled. Not starting BleScanService.")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in BootCompletedReceiver: ${e.message}", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
